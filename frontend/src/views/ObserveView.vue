<script setup>
// 运行记录（T10）：账本已经从检索层与生成层落库，这一页只做"看得见、查得到"。
// 两条铁律：费用没有就是"—"而不是 0；汇总要同时说清有多少条没算钱。
import { computed, onMounted, ref } from 'vue'
import { domainsApi, observeApi } from '../api/client.js'
import { pageLabel } from '../format.js'

const traces = ref([])
const summary = ref(null)
const price = ref(null)
const domains = ref([])
const domainCode = ref('')
const refusedFilter = ref('')
const busy = ref(false)
const error = ref('')
const detail = ref(null)
const detailLoading = ref(false)
const feedback = ref(null)

const totalTokens = computed(() => {
  if (!summary.value) return 0
  return summary.value.promptTokens + summary.value.completionTokens
})

const detailTitle = computed(() => (detail.value ? `#${detail.value.row.id}` : ''))

function domainName(code) {
  return domains.value.find((d) => d.code === code)?.name || code
}

async function load() {
  busy.value = true
  error.value = ''
  try {
    const [data, priceData, feedbackData] = await Promise.all([
      observeApi.traces({ limit: 50, domainCode: domainCode.value, refused: refusedFilter.value }),
      observeApi.price(),
      // 反馈汇总单独取：它是全量统计，不该跟着"筛了哪个域"变
      observeApi.feedbackSummary().catch(() => null)
    ])
    traces.value = data.traces || []
    summary.value = data.summary
    price.value = priceData
    feedback.value = feedbackData
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function openDetail(row) {
  detailLoading.value = true
  error.value = ''
  try {
    detail.value = await observeApi.detail(row.id)
  } catch (e) {
    error.value = e.message
  } finally {
    detailLoading.value = false
  }
}

/** 后端给的是带时区的 ISO 串，这里换成浏览器所在时区——排查问题时"几点"必须是本地时间。 */
function fmtTime(iso) {
  if (!iso) return '—'
  const at = new Date(iso)
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString('zh-CN', { hour12: false })
}

function fmtCost(value) {
  return value === null || value === undefined ? '—' : `${Number(value).toFixed(4)} 元`
}

function fmtMs(value) {
  return value === null || value === undefined ? '—' : `${value} ms`
}

function fmtScore(value) {
  return value === null || value === undefined ? '—' : Number(value).toFixed(3)
}

// 账本里的页码可能是 null（内核通道或 DOCX 没有页码语义）——显示 "—"，不显示 "第 0 页"。
function fmtPage(fragment) {
  return pageLabel(fragment) || '—'
}

// 两路名次：融合**之前**各自排第几（0 = 那一路根本没召回它，显示 "—" 而不是"第 0 名"）。
// 账本只记融合后的最终名次时，看的人分不清"向量没召回"和"融合把它排下去了"，这两件事的处置完全不同。
function fmtRanks(fragment) {
  const parts = []
  if (fragment.vectorRank > 0) parts.push(向量 )
  if (fragment.keywordRank > 0) parts.push(关键词 )
  return parts.length ? parts.join(' / ') : '—'
}

onMounted(async () => {
  try {
    domains.value = (await domainsApi.list()).domains || []
  } catch {
    // 知识域取不到不该挡住账本：筛选框退化成"全部"就好
  }
  await load()
})
</script>

<template>
  <div class="page wide">
    <div class="page-head">
      <div>
        <h1>运行记录</h1>
        <p class="desc">
          每次问答一条账本：查询词、可见范围、命中片段、耗时与 token 都在里面。
          日志回答"出过什么事"，这一页回答"一共问了多少次、拒答多少、花了多少钱"。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn" :disabled="busy" @click="load">{{ busy ? '刷新中…' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="error" class="banner err">请求失败：{{ error }}</div>

    <section v-if="price && !price.promptPer1k" class="banner warn">
      未配置模型单价，因此只记 token 不记费用（在「系统配置 → 生成模型出口」填输入/输出单价后才开始计费）。
      单价会随每条账本快照，事后改价不会改写已有记录。
    </section>

    <section v-if="summary" class="stats">
      <div><span>问答次数</span><b>{{ summary.total }}</b></div>
      <div><span>拒答率</span><b>{{ summary.refuseRate }}%</b></div>
      <div><span>平均耗时</span><b>{{ summary.avgElapsedMs }}ms</b></div>
      <div><span>最长耗时</span><b>{{ summary.maxElapsedMs }}ms</b></div>
      <div><span>token 合计</span><b>{{ totalTokens }}</b></div>
      <div>
        <span>费用合计</span>
        <b>{{ fmtCost(summary.costTotal) }}</b>
        <em v-if="summary.unpricedCount > 0">另有 {{ summary.unpricedCount }} 条未计费</em>
      </div>
      <div v-if="feedback">
        <span>满意率</span>
        <b>{{ feedback.rated ? `${feedback.likeRate}%` : '—' }}</b>
        <em v-if="feedback.rated">👍 {{ feedback.likes }} / 👎 {{ feedback.dislikes }}（共 {{ feedback.rated }} 条评价）</em>
        <em v-else>还没有用户评价</em>
      </div>
    </section>

    <!-- 差评原因分布：这是最直接的"该改哪里"的信号 —— 答非所问多半是检索，内容不全多半是切分 -->
    <section v-if="feedback && feedback.dislikes > 0" class="card">
      <header class="card-head">
        <div>
          <h2>差评原因</h2>
          <p class="desc">用户点踩时选的标签（全量统计，不受上面的筛选影响）。</p>
        </div>
      </header>
      <div class="reasons">
        <span v-for="(count, reason) in feedback.reasons" :key="reason" class="reason">
          {{ reason }} <b>{{ count }}</b>
        </span>
      </div>
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>账本</h2>
          <p class="desc">越界引用与"被截断"是拒答的两种原因，它们都在账上，可以直接数出来。</p>
        </div>
        <div class="filters">
          <select v-model="domainCode" @change="load">
            <option value="">全部知识域</option>
            <option v-for="d in domains" :key="d.code" :value="d.code">{{ d.name }}</option>
          </select>
          <select v-model="refusedFilter" @change="load">
            <option value="">全部结论</option>
            <option value="false">仅放行</option>
            <option value="true">仅拒答</option>
          </select>
        </div>
      </header>

      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>时间</th>
              <th>用户</th>
              <th>知识域</th>
              <th>问题</th>
              <th>结论</th>
              <th>证据</th>
              <th>引用</th>
              <th>最高分</th>
              <th>评价</th>
              <th>token</th>
              <th>耗时</th>
              <th>费用</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in traces" :key="row.id">
              <td class="nowrap">{{ fmtTime(row.createdAt) }}</td>
              <td class="nowrap">{{ row.userName }}</td>
              <td class="nowrap">{{ domainName(row.domainCode) }}</td>
              <td class="question">{{ row.question }}</td>
              <td>
                <span :class="['pill', row.refused ? 'err' : 'ok']">{{ row.refused ? '拒答' : '放行' }}</span>
                <span v-if="row.refusalRule" class="rule">{{ row.refusalRule }}</span>
              </td>
              <td class="nowrap">{{ row.evidenceCount }}</td>
              <td class="nowrap">{{ row.citedCount }}</td>
              <td class="nowrap">{{ fmtScore(row.bestScore) }}</td>
              <td class="nowrap">
                <span v-if="row.rating === 1" title="有用">👍</span>
                <span v-else-if="row.rating === -1" :title="row.feedbackReason || '没用'">👎 {{ row.feedbackReason }}</span>
                <span v-else class="muted">—</span>
              </td>
              <td class="nowrap">{{ row.promptTokens }}/{{ row.completionTokens }}</td>
              <td class="nowrap">{{ row.totalElapsedMs }}ms</td>
              <td class="nowrap">{{ fmtCost(row.costTotal) }}</td>
              <td><button class="btn tiny" :disabled="detailLoading" @click="openDetail(row)">明细</button></td>
            </tr>
            <tr v-if="!traces.length">
              <td colspan="12" class="empty">还没有记录。去「知识问答」问一句就会落一条。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="detail" class="card">
      <header class="card-head">
        <div>
          <h2>运行明细 {{ detailTitle }}</h2>
          <p class="desc">{{ fmtTime(detail.row.createdAt) }} · {{ detail.row.userName }} · {{ domainName(detail.row.domainCode) }}</p>
        </div>
        <button class="btn tiny" @click="detail = null">关闭</button>
      </header>

      <div class="snapshot">
        <div><span>问题</span><b class="break">{{ detail.row.question }}</b></div>
        <div><span>用户评价</span>
          <b v-if="detail.row.rating === 1">👍 有用</b>
          <b v-else-if="detail.row.rating === -1">👎 没用（{{ detail.row.feedbackReason || '未填原因' }}）</b>
          <b v-else class="muted">未评价</b>
        </div>
        <div><span>检索 topN / 内核 topN</span><b>{{ detail.requestedTopN }} / {{ detail.kernelTopN }}</b></div>
        <div><span>召回下限 / 作答下限</span><b>{{ detail.minScore }} / {{ detail.answerMinScore }}</b></div>
        <div><span>内核命中</span><b>{{ detail.kernelHits }}</b></div>
        <div><span>过滤掉：无权限</span><b>{{ detail.droppedUnauthorized }}</b></div>
        <div><span>过滤掉：未登记 / 未索引 / 重复</span>
          <b>{{ detail.droppedUnknown }} / {{ detail.droppedNotIndexed }} / {{ detail.droppedDuplicate }}</b></div>
        <div><span>耗时：检索 / 模型 / 合计</span>
          <b>{{ detail.retrievalElapsedMs }} / {{ detail.generationElapsedMs }} / {{ detail.row.totalElapsedMs }} ms</b></div>
        <div><span>模型</span><b>{{ detail.model || '未调用（证据不足）' }}</b></div>
        <div><span>越界引用</span>
          <b>{{ detail.outOfRangeCitations.length ? detail.outOfRangeCitations.join(', ') : '无' }}</b></div>
        <div><span>输出被截断</span><b>{{ detail.truncated ? '是（已拒答）' : '否' }}</b></div>
        <div><span>计费单价（元/千 token）</span>
          <b>{{ detail.promptPricePer1k === null ? '未配置' : `${detail.promptPricePer1k} / ${detail.completionPricePer1k}` }}</b></div>
      </div>

      <p class="label">可见范围（当时真正下推的过滤条件）</p>
      <pre class="filter">{{ detail.accessFilter }}</pre>

      <p class="label">命中片段（{{ detail.evidences.length }} 条，<b>已引用</b>的是模型真正用到的）</p>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr><th>证据号</th><th>引用</th><th>文档</th><th>版本</th><th>页码</th><th>分数</th><th>两路名次</th><th>片段</th></tr>
          </thead>
          <tbody>
            <tr v-for="(fragment, index) in detail.evidences" :key="`${fragment.rank}-${fragment.chunkId}-${index}`">
              <td class="nowrap">[{{ fragment.rank }}]</td>
              <td class="nowrap">
                <span :class="['pill', fragment.cited ? 'ok' : '']">{{ fragment.cited ? '已引用' : '未引用' }}</span>
              </td>
              <td class="question">{{ fragment.title }}<br /><code>{{ fragment.docNo }}</code></td>
              <td class="nowrap">{{ fragment.version }}</td>
              <td class="nowrap">{{ fmtPage(fragment) }}</td>
              <td class="nowrap">{{ fmtScore(fragment.score) }}</td>
              <td class="nowrap">{{ fmtRanks(fragment) }}</td>
              <td class="nowrap"><code>{{ fragment.chunkId }}</code></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.filters { display: flex; gap: 8px; flex: none; flex-wrap: wrap; }

/* 问题列限宽 + 换行，长问题不把整行撑爆 */
.question { max-width: 260px; word-break: break-word; }

.rule { display: block; margin-top: 3px; font-size: 11px; color: var(--muted); }

.break { word-break: break-word; }

.label { margin: 20px 0 6px; font-size: 12.5px; font-weight: 600; color: var(--ink-2); }

/* 原样展示当时下推的过滤条件（一段 SQL 片段） */
.filter {
  margin: 0 0 6px;
  padding: 10px 12px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--ink-2);
}

.reasons { display: flex; gap: 10px; flex-wrap: wrap; padding: 4px 18px 16px; }

.reason {
  font-size: 13px;
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 3px 12px;
}

.reason b { color: var(--brand); margin-left: 4px; }

.muted { color: var(--muted); font-weight: 400; }
</style>
