<script setup>
// 文档查看器：原文（PDF）与 Markdown 两个页签，两个入口（文档表 / 问答引用）共用。
//
// 三条来自需求的口径：
//   1. 水印由服务端烧进 PDF 字节 —— 页面这里不再叠一层，避免"界面有、文件没有"的假象；
//      Markdown 视图没有文件可烧（它是由 chunk 现场拼的），所以只在页面上叠一层 CSS 水印。
//   2. 预览与下载都要带令牌，不能用直链，统一走 fetch + Blob。
//   3. 403 不区分"不存在"与"无权限"，界面照抄服务端文案，不自己编。
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { documentViewApi } from '../api/client.js'
import { pageLabel } from '../format.js'

const props = defineProps({
  documentId: { type: Number, required: true },
  docTitle: { type: String, default: '' },
  highlightChunkId: { type: String, default: '' }
})
const emit = defineEmits(['close'])

const tab = ref('pdf')
const content = ref(null)
const pdfUrl = ref('')
const error = ref('')
const notice = ref('')
const busy = ref('')
const showRaw = ref(false)

const markdownRows = computed(() => {
  const blocks = content.value?.blocks || []
  return blocks.map((block) => ({
    ...block,
    page: pageLabel(block),
    kind: (block.kind || 'PARAGRAPH').toUpperCase()
  }))
})

const headingSize = (level) => Math.max(1, Math.min(Number(level) || 1, 5))

/** SVG 平铺水印：Markdown 视图没有"可烧的字节"，只能在页面上叠。 */
const watermarkStyle = computed(() => {
  const text = content.value?.watermarkText || ''
  if (!text) return {}
  const svg = "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='180'>"
    + "<text x='20' y='110' fill='rgba(90,102,120,0.14)' font-size='15' font-family='sans-serif'"
    + " transform='rotate(-30 20 110)'>" + escapeXml(text) + '</text></svg>'
  return { backgroundImage: `url("data:image/svg+xml;utf8,${encodeURIComponent(svg)}")` }
})

function escapeXml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

async function loadContent() {
  try {
    content.value = await documentViewApi.content(props.documentId)
  } catch (e) {
    error.value = e.message
  }
}

async function loadPdf() {
  if (pdfUrl.value) return
  busy.value = 'pdf'
  try {
    const { blob } = await documentViewApi.pdf(props.documentId)
    // #view=FitH：让内置查看器按宽度自适应。否则默认缩放会宽过面板，右侧正文被裁掉
    pdfUrl.value = `${URL.createObjectURL(blob)}#view=FitH`
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function download() {
  busy.value = 'download'
  error.value = ''
  try {
    const { blob, fileName } = await documentViewApi.pdf(props.documentId, { download: true })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 5000)
    notice.value = `已下载 ${fileName}`
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

/** 引用跳转：切到 Markdown 页签并滚到那一段。原文（PDF）是重排页面，无法按 chunk 定位。 */
async function scrollToHighlight() {
  if (!props.highlightChunkId) return
  tab.value = 'markdown'
  await nextTick()
  const target = document.getElementById(`view-chunk-${props.highlightChunkId}`)
  if (target) target.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

watch(() => props.highlightChunkId, scrollToHighlight)
watch(tab, (value) => {
  if (value === 'pdf') loadPdf()
})

loadContent().then(() => {
  if (props.highlightChunkId) scrollToHighlight()
  else loadPdf()
})

onBeforeUnmount(() => {
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value)
})
</script>

<template>
  <div class="overlay" @click.self="emit('close')">
    <div class="modal">
      <header class="head">
        <div class="meta">
          <h2>{{ content?.title || props.docTitle || '文档查看' }}</h2>
          <p class="sub">
            <span v-if="content" class="tag">{{ content.docNo }}</span>
            <span v-if="content" class="tag">{{ content.version }}</span>
            <span v-if="content" class="tag">{{ content.secrecy }}</span>
            <span v-if="content" class="tag">{{ content.domainCode }}</span>
            <span v-if="content" class="tag">{{ content.chunkCount }} 个片段</span>
            <span v-if="content" class="tag plain">水印：{{ content.watermarkText }}</span>
          </p>
        </div>
        <div class="head-actions">
          <button class="btn" :disabled="!!busy" @click="download">
            {{ busy === 'download' ? '准备中…' : '下载 PDF' }}
          </button>
          <button class="btn ghost" @click="emit('close')">关闭</button>
        </div>
      </header>

      <nav class="tabs">
        <span :class="{ active: tab === 'pdf' }" @click="tab = 'pdf'">原文</span>
        <span :class="{ active: tab === 'markdown' }" @click="tab = 'markdown'">Markdown</span>
      </nav>

      <div v-if="error" class="banner err">{{ error }}</div>
      <div v-if="notice" class="banner info">{{ notice }}</div>

      <section v-if="tab === 'pdf'" class="pane">
        <div v-if="!pdfUrl && !error" class="hint">正在生成带水印的 PDF…</div>
        <iframe v-else-if="pdfUrl" class="pdf" :src="pdfUrl" title="文档原文"></iframe>
      </section>

      <section v-else class="pane markdown-pane">
        <div class="md-tools">
          <span class="hint-inline">Markdown 视图由库内片段现场拼出，不含原始排版；页码来自解析结果。</span>
          <button class="btn tiny" @click="showRaw = !showRaw">{{ showRaw ? '收起源文' : '查看 Markdown 源文' }}</button>
        </div>
        <pre v-if="showRaw" class="raw">{{ content?.markdown }}</pre>
        <article v-else class="md" :style="watermarkStyle">
          <template v-for="row in markdownRows" :key="row.chunkId">
            <h3
              v-if="row.sectionHead"
              class="section-head"
              :id="`view-section-${row.chunkId}`"
            >{{ row.sectionPath }}</h3>
            <component
              :is="row.kind === 'HEADING' ? `h${headingSize(row.level) + 1}` : 'p'"
              :id="`view-chunk-${row.chunkId}`"
              :class="[
                row.kind === 'BULLET' ? 'bullet' : '',
                row.kind === 'TABLE_ROW' ? 'table-row' : '',
                String(row.chunkId) === props.highlightChunkId ? 'hit' : ''
              ]"
            >
              <span v-if="row.page" class="page">{{ row.page }}</span>{{ row.text }}
            </component>
          </template>
          <p v-if="!markdownRows.length" class="hint">这份文档还没有可展示的片段。</p>
        </article>
      </section>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(17, 26, 46, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  z-index: 50;
}

.modal {
  background: #fff;
  border-radius: 10px;
  width: min(1000px, 100%);
  height: min(88vh, 100%);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 12px 40px rgba(15, 25, 45, 0.28);
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px 12px;
  border-bottom: 1px solid var(--line);
}

.head h2 { margin: 0 0 6px; font-size: 15px; color: var(--brand); }
.meta .sub { margin: 0; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.head-actions { display: flex; gap: 8px; flex: none; }

.tag {
  font-size: 11.5px;
  color: var(--muted);
  background: #f1f4f9;
  border-radius: 4px;
  padding: 1px 6px;
}

.tag.plain { background: #eefaf2; color: var(--ok); }

.tabs { display: flex; gap: 20px; padding: 10px 18px 0; border-bottom: 1px solid var(--line); }

.tabs span {
  font-size: 13px;
  color: var(--muted);
  padding-bottom: 8px;
  cursor: pointer;
}

.tabs span.active { color: var(--brand); border-bottom: 2px solid var(--brand); font-weight: 600; }

.banner { margin: 12px 18px 0; padding: 9px 12px; border-radius: 6px; font-size: 13px; }
.banner.err { background: #fdf3f3; border: 1px solid #f0c9c9; color: var(--err); }
.banner.info { background: #eefaf2; border: 1px solid #bfe6ce; color: var(--ok); }

.pane { flex: 1; overflow: auto; padding: 14px 18px 20px; }

.pdf { width: 100%; height: 100%; border: 1px solid var(--line); border-radius: 6px; }

.hint { color: var(--muted); font-size: 13px; padding: 24px 4px; }
.hint-inline { color: var(--muted); font-size: 12px; }

.md-tools { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }

.raw {
  margin: 0;
  padding: 12px 14px;
  background: #f7f9fc;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.md { font-size: 13.5px; line-height: 1.85; color: #222; }

.md h2, .md h3, .md h4, .md h5, .md h6 {
  margin: 18px 0 8px;
  font-size: 14.5px;
  color: var(--brand);
}

.md .section-head { margin: 20px 0 8px; padding-bottom: 4px; border-bottom: 1px solid #eef2f9; }

.md p { margin: 0 0 10px; white-space: pre-wrap; word-break: break-word; }
.md p.bullet { padding-left: 14px; }
.md p.bullet::before { content: '· '; color: var(--brand); }
.md p.table-row { padding-left: 14px; color: #4a5464; }

.md .hit { background: #fff6d9; border: 1px solid #f0dcae; border-radius: 6px; padding: 6px 8px; }

.page {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  font-size: 11px;
  color: var(--brand);
  background: #eef2fb;
  border-radius: 4px;
}

</style>
