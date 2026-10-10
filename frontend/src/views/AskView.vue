<script setup>
// 问答页：选知识域 → 提问 → 流式看答案 → 点引用跳原文。
// 两条铁律：只有 verdict.refused=false 才把正文当结论；notice 必须显示出来。
import { computed, nextTick, onMounted, ref } from 'vue'
import { deepApi, domainsApi, generationApi, newRunId } from '../api/client.js'
import { pageLabel } from '../format.js'
import DocumentViewer from './DocumentViewer.vue'

const domains = ref([])
const domainCode = ref('')
const question = ref('')
const phase = ref('idle') // idle | retrieving | streaming | done | refused | error
const streamedText = ref('')
const result = ref(null)
const evidences = ref([])
const error = ref('')
const showEvidence = ref(false)
const activeRank = ref(null)

// 深度分析（P3a）：多轮检索 + 分步作答，探索过程也显示出来。
// 结论仍然只认 verdict 事件——分步场景下，中途流出来的正文可能来自"某一段后来被拒"的那次生成。
const deep = ref(false)
const plan = ref([])
const steps = ref([])
const budget = ref('')
const runId = ref('')
const cancelNote = ref('')
let abortController = null

const busy = computed(() => phase.value === 'retrieving' || phase.value === 'streaming')
const refused = computed(() => phase.value === 'refused')
// 校验结论里的 answer 才是"被放行"的正文；流出来的只是草稿
const displayText = computed(() => (result.value ? result.value.answer : streamedText.value))
const citedRanks = computed(() => new Set((result.value?.citations || []).map((c) => c.rank)))

const segments = computed(() => {
  const text = displayText.value
  if (!text) return []
  if (refused.value) return splitBold(text)
  const parts = []
  const pattern = /(?:\[|【)\s*(\d{1,3})\s*(?:\]|】)/g
  let last = 0
  let match
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) parts.push(...splitBold(text.slice(last, match.index)))
    parts.push({ marker: Number(match[1]) })
    last = match.index + match[0].length
  }
  if (last < text.length) parts.push(...splitBold(text.slice(last)))
  return parts
})

/**
 * 模型偶尔会带 Markdown 加粗（系统提示只要求"简明直接"，没禁标记）。
 * 这里只认 **加粗** 这一种：其余标记（标题、列表、表格）原样显示——
 * 宁可让用户看到符号，也不要猜错结构把正文排乱。
 */
function splitBold(text) {
  const parts = []
  const pattern = /\*\*([^*]+)\*\*/g
  let last = 0
  let match
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) parts.push({ text: text.slice(last, match.index) })
    parts.push({ text: match[1], bold: true })
    last = match.index + match[0].length
  }
  if (last < text.length) parts.push({ text: text.slice(last) })
  return parts
}

async function loadDomains() {
  try {
    const data = await domainsApi.list()
    domains.value = data.domains || []
    // 默认选中第一个"能问出东西"的域：可见文档为 0 的域即使启用也问不出结果，
    // 把它选成默认值，用户按第一次提问就会撞上"证据不足"。
    const firstUsable =
      domains.value.find((d) => d.enabled && hasVisibleDocs(d)) ||
      domains.value.find((d) => d.enabled) ||
      domains.value[0]
    if (firstUsable) domainCode.value = firstUsable.code
  } catch (e) {
    error.value = `知识域加载失败：${e.message}`
  }
}

// 可见文档数由后端按登录身份算好（AC-P1-009）。为 0 说明"这个域里没有当前账号能看的文档"，
// 但**不能**由此断定"这个域是空的"——那正是我们不能泄露的信息，所以文案只说"没有你可访问的文档"。
function hasVisibleDocs(domain) {
  return domain.visibleDocuments !== 0
}

const blockedDomains = computed(() => domains.value.filter((d) => !hasVisibleDocs(d)))
function reset() {
  // 上一轮还在流就把连接断掉：模型那边由服务端按预算跑完，但我们不再接收它的输出
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  streamedText.value = ''
  result.value = null
  evidences.value = []
  activeRank.value = null
  showEvidence.value = false
  error.value = ''
  plan.value = []
  steps.value = []
  budget.value = ''
  cancelNote.value = ''
}

async function ask() {
  if (!domainCode.value) {
    error.value = '请选择知识域'
    return
  }
  if (!question.value.trim()) {
    error.value = '请输入问题'
    return
  }
  if (deep.value) {
    await askDeep()
    return
  }
  reset()
  phase.value = 'retrieving'
  try {
    await generationApi.answerStream(
      { domainCode: domainCode.value, question: question.value.trim(), topN: 5 },
      {
        evidences: (payload) => {
          evidences.value = payload.evidences || []
          if (!payload.insufficient) phase.value = 'streaming'
        },
        delta: (payload) => {
          streamedText.value += payload.text
        },
        verdict: (payload) => {
          result.value = payload
          if (payload.evidences) evidences.value = payload.evidences
          phase.value = payload.refused ? 'refused' : 'done'
        }
      }
    )
    if (!result.value) {
      phase.value = 'error'
      error.value = '连接提前结束，没有收到校验结论——这次的内容不作为答案。'
    }
  } catch (e) {
    phase.value = 'error'
    error.value = e.message
  }
}

async function askDeep() {
  reset()
  runId.value = newRunId()
  phase.value = 'retrieving'
  const controller = new AbortController()
  abortController = controller
  try {
    await deepApi.answerStream(
      { domainCode: domainCode.value, question: question.value.trim(), runId: runId.value },
      {
        plan: (payload) => {
          runId.value = payload.runId || runId.value
          plan.value = payload.plan || []
          budget.value = payload.budget || ''
          phase.value = 'streaming'
        },
        step: (payload) => {
          if (payload.step) steps.value.push(payload.step)
        },
        evidences: (payload) => {
          evidences.value = payload.evidences || []
        },
        delta: (payload) => {
          streamedText.value += payload.text
        },
        verdict: (payload) => {
          result.value = payload
          if (payload.evidences) evidences.value = payload.evidences
          phase.value = payload.refused ? 'refused' : 'done'
        }
      },
      controller.signal
    )
    if (!result.value) {
      phase.value = 'error'
      error.value = '连接提前结束，没有收到校验结论——这次的内容不作为答案。'
    }
  } catch (e) {
    phase.value = 'error'
    error.value = e.message
  } finally {
    abortController = null
  }
}

/**
 * 取消是发给**服务端**的：它在下个步骤边界停下，并把已经查到的部分回给我们。
 * 只掐断浏览器连接不算取消——那样只是我们不看了，模型那边照样在烧 token。
 */
async function cancelDeep() {
  try {
    const payload = await deepApi.cancel(runId.value)
    cancelNote.value = payload.note || ''
  } catch (e) {
    error.value = e.message
  }
}

/** 流式挂了还能用非流式确认一次：是链路（代理/长连接）的问题，还是模型的问题。 */
async function askWithoutStream() {
  reset()
  phase.value = 'retrieving'
  try {
    const payload = await generationApi.answer({
      domainCode: domainCode.value,
      question: question.value.trim(),
      topN: 5
    })
    result.value = payload
    evidences.value = payload.evidences || []
    phase.value = payload.refused ? 'refused' : 'done'
  } catch (e) {
    phase.value = 'error'
    error.value = e.message
  }
}

async function openCitation(rank) {
  activeRank.value = rank
  showEvidence.value = true
  await nextTick()
  const chunkId = (result.value?.citations || []).find((c) => c.rank === rank)?.chunkId
  const target = chunkId ? document.getElementById(`chunk-${chunkId}`) : null
  if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

// 「看原文」是另一件事：上面那个是页面内定位，这个是打开整份文档（带水印、走文档级 ACL）。
// 被引用的片段 id 一起带过去——DOCX 的原文是按片段重排的，PDF 无法按片段定位，
// 所以查看器会落在 Markdown 页签并高亮那一段。
const viewerDoc = ref(null)
const viewerChunk = ref('')

function openOriginal(citation) {
  viewerChunk.value = citation.chunkId || ''
  viewerDoc.value = { id: citation.documentId, title: citation.title }
}

function isCited(rank) {
  return citedRanks.value.has(rank)
}

function isCitedChunk(chunkId) {
  return (result.value?.citations || []).some((c) => c.chunkId === chunkId)
}

onMounted(loadDomains)
</script>

<template>
  <div class="page narrow">
    <div class="page-head">
      <div>
        <h1>知识问答</h1>
        <p class="desc">
          只依据知识库里检索到的内容作答，每条结论都带引用编号；证据不足时明确说无法回答，不猜。
        </p>
      </div>
    </div>

    <section class="card ask">
      <div class="row">
        <label class="field domain">
          <span>知识域</span>
          <select v-model="domainCode" :disabled="busy">
            <option
              v-for="d in domains"
              :key="d.code"
              :value="d.code"
              :disabled="!d.enabled || !hasVisibleDocs(d)"
            >
              {{ d.name }}{{ d.enabled ? '' : '（已停用）' }}{{ hasVisibleDocs(d) ? '' : '（无可见文档）' }}
            </option>
          </select>
          <small v-if="blockedDomains.length" class="no-visible">
            {{ blockedDomains.map((d) => d.name).join('、') }}：当前账号没有可访问的文档（可能未授权，
            也可能尚未入库）。若确认应该有权限，请联系管理员开通。
          </small>
        </label>
        <label class="field grow">
          <span>问题</span>
          <input
            v-model="question"
            :disabled="busy"
            placeholder="例如：出差住宿费标准是多少"
            @keyup.enter="ask"
          />
        </label>
        <label class="field mode">
          <span>模式</span>
          <select v-model="deep" :disabled="busy" title="深度分析会拆解问题、多轮检索，并用更长时间换更完整的答案">
            <option :value="false">快速问答</option>
            <option :value="true">深度分析（多轮）</option>
          </select>
        </label>
        <button class="btn primary send" :disabled="busy" @click="ask">
          {{ busy ? (deep ? '分析中…' : '检索中…') : '提问' }}
        </button>
        <button v-if="busy && deep" class="btn" @click="cancelDeep">取消</button>
      </div>

      <div v-if="error" class="banner err">
        {{ error }}
        <button v-if="phase === 'error'" class="btn tiny" @click="askWithoutStream">改用非流式重试</button>
      </div>
    </section>

    <section v-if="deep && (plan.length || steps.length)" class="card">
      <header class="card-head">
        <h2>深度分析过程</h2>
        <span class="meta">{{ budget }}</span>
      </header>
      <div v-if="plan.length" class="plan">
        <div v-for="(item, index) in plan" :key="item.id" class="plan-item">
          <b>{{ index + 1 }}</b> {{ item.question }}
        </div>
      </div>
      <ol v-if="steps.length" class="steps">
        <li v-for="s in steps" :key="s.seq">
          <span class="tag">{{ s.action }}</span>
          {{ s.detail }}
          <span class="tag">新增 {{ s.newFragments }} 段</span>
        </li>
      </ol>
      <p v-if="cancelNote" class="meta">{{ cancelNote }}</p>
    </section>

    <section v-if="result || streamedText || evidences.length" class="card">
      <header class="card-head">
        <h2>
          <template v-if="busy">正在作答…</template>
          <template v-else-if="refused">无法回答</template>
          <template v-else>答案</template>
        </h2>
        <div class="facts">
          <span class="pill" :class="busy ? 'warn' : refused ? 'err' : 'ok'">
            {{ busy ? '引用校验中' : refused ? '已拒答' : '引用已核对' }}
          </span>
          <span v-if="result" class="meta">
            证据 {{ result.diagnostics.evidenceCount }} 份 · 引用 {{ result.citations.length }} 条 ·
            耗时 {{ result.diagnostics.elapsedMs }}ms
            <template v-if="result.diagnostics.modelCalled">
              · token {{ result.diagnostics.promptTokens }}/{{ result.diagnostics.completionTokens }}
            </template>
            <template v-if="deep && result.stopReason">
              · 停止原因 {{ result.stopReason }}
            </template>
          </span>
        </div>
      </header>

      <p class="answer" :class="{ refused }">
        <template v-for="(seg, index) in segments" :key="index">
          <span v-if="seg.text !== undefined" :class="{ bold: seg.bold }">{{ seg.text }}</span>
          <button
            v-else
            class="cite"
            :class="{ active: activeRank === seg.marker, ghost: !citedRanks.has(seg.marker) }"
            @click="openCitation(seg.marker)"
          >[{{ seg.marker }}]</button>
        </template>
        <span v-if="busy && !streamedText" class="thinking">正在检索知识库…</span>
      </p>

      <p v-if="result?.notice" class="banner warn">{{ result.notice }}</p>

      <div v-if="result && !refused && result.citations.length" class="citations">
        <div
          v-for="c in result.citations"
          :key="c.rank"
          class="citation-row"
        >
          <button
            class="citation"
            :class="{ active: activeRank === c.rank }"
            @click="openCitation(c.rank)"
          >
            <b>[{{ c.rank }}]</b>
            <span class="title">{{ c.title }}</span>
            <span v-if="pageLabel(c)" class="tag" title="页码来自解析结果，不是模型写的">{{ pageLabel(c) }}</span>
            <span class="tag">{{ c.version }}</span>
            <span class="tag">{{ c.docNo }}</span>
          </button>
          <button class="btn tiny" @click="openOriginal(c)">看原文</button>
        </div>
      </div>
    </section>

    <section v-if="evidences.length" class="card">
      <header class="card-head head-click" @click="showEvidence = !showEvidence">
        <h2>检索到的证据（{{ evidences.length }} 份）</h2>
        <span class="meta">{{ showEvidence ? '收起' : '展开' }} —— 点引用可直接定位到片段</span>
      </header>
      <div v-if="showEvidence" class="evidence">
        <article v-for="e in evidences" :key="e.rank" class="evidence-item" :class="{ cited: isCited(e.rank) }">
          <header>
            <b>[{{ e.rank }}]</b> 《{{ e.title }}》
            <span class="tag">{{ e.version }}</span>
            <span class="tag">{{ e.docNo }}</span>
            <span class="tag">{{ e.docStatus }}</span>
            <span class="tag">{{ e.ownerDept }}</span>
            <span v-if="isCited(e.rank)" class="mark">已引用</span>
          </header>
          <p
            v-for="f in e.fragments"
            :key="f.chunkId"
            :id="`chunk-${f.chunkId}`"
            class="frag"
            :class="{ hit: isCitedChunk(f.chunkId) }"
          ><span v-if="pageLabel(f)" class="frag-page">{{ pageLabel(f) }}</span>{{ f.text }}</p>
        </article>
      </div>
    </section>

    <DocumentViewer
      v-if="viewerDoc"
      :document-id="viewerDoc.id"
      :doc-title="viewerDoc.title"
      :highlight-chunk-id="viewerChunk"
      @close="viewerDoc = null"
    />
  </div>
</template>

<style scoped>
.head-click { cursor: pointer; }

.row { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
.field.domain { width: 200px; flex: none; }
.field.mode { width: 165px; flex: none; }
.no-visible { font-size: 11px; line-height: 1.4; }
.field.grow { flex: 1 1 320px; }

/* 深度分析的探索过程：让用户看得见"它在做什么"，而不是以为卡死了 */
.plan { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }

.plan-item {
  font-size: 13px;
  color: var(--ink);
  background: #f7f9fe;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 7px 10px;
}

.plan-item b { color: var(--brand); margin-right: 6px; }

.steps {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
  color: #333;
}

.send { flex: none; }

.facts { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.answer {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.85;
}

.answer.refused { color: var(--err); }
.answer .bold { font-weight: 700; }

.cite {
  border: none;
  background: #eef2fb;
  color: var(--brand);
  border-radius: 4px;
  padding: 0 5px;
  margin: 0 2px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  vertical-align: 1px;
}

.cite:hover, .cite.active { background: var(--brand); color: #fff; }
.cite.ghost { background: #fdf3f3; color: var(--err); }
.thinking { color: var(--muted); font-size: 13px; }

.citations { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }

.citation-row { display: flex; align-items: center; gap: 8px; }

.citation-row .citation { flex: 1; }

.citation {
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  border: 1px solid var(--line);
  border-left: 3px solid var(--brand);
  border-radius: 6px;
  background: #fbfcfe;
  padding: 8px 12px;
  font-size: 13px;
  cursor: pointer;
}

.citation:hover, .citation.active { background: #eef2fb; }
.citation b { color: var(--brand); }
.citation .title { font-weight: 600; }

.evidence { display: flex; flex-direction: column; gap: 14px; }

.evidence-item {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px 14px;
  background: #fbfcfe;
}

.evidence-item.cited { border-color: #b9c9ea; background: #f7f9fe; }
.evidence-item header { font-size: 13px; margin-bottom: 8px; }

.mark {
  font-size: 11.5px;
  color: var(--ok);
  background: #eefaf2;
  border: 1px solid #bfe6ce;
  border-radius: 4px;
  padding: 1px 6px;
  margin-left: 4px;
}

.frag {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.8;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: 6px;
  padding: 6px 8px;
}

.frag-page {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 6px;
  font-size: 11.5px;
  color: var(--brand);
  background: #eef2fb;
  border-radius: 4px;
}

.frag:last-child { margin-bottom: 0; }
.frag.hit { background: #fff6d9; border: 1px solid #f0dcae; }
</style>
