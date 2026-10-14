<script setup>
import { computed, onMounted, ref } from 'vue'
import { domainsApi, ingestApi, mediaApi } from '../api/client.js'
import DocumentViewer from './DocumentViewer.vue'

// admin=false 时是用户门户的只读视图：隐藏上传/扫描/重试/状态切换等管理动作。
// 后端对这些接口本就限 admin，这里只是别给用户一个点了必然 403 的入口。
const props = defineProps({
  admin: { type: Boolean, default: false }
})

// 知识域列表（上传时选域）；viewer 拿不到就留空，上传按钮随后提示
const domains = ref([])

async function loadDomains() {
  try {
    domains.value = (await domainsApi.list()).domains
  } catch {
    domains.value = []
  }
}

const documents = ref([])
const tasks = ref([])
const summary = ref(null)
const busy = ref('')
const error = ref('')
const notice = ref('')

// 查看器：{ id, title }。打开时后端会按文档级 ACL 重新判一次权限——
// 列表里看得到不代表现在还能看，权限行可能刚被改掉。
const viewer = ref(null)
const viewerHighlight = ref('')

function openViewer(doc, chunkId = '') {
  viewerHighlight.value = chunkId
  viewer.value = { id: doc.id, title: doc.title }
}

// 解析预览（P1）：把解析出的块列出来，让人先确认"解析对不对"，而不是等检索答错才发现。
const preview = ref(null)
const previewBusy = ref('')
const previewError = ref('')

async function openPreview(doc) {
  previewBusy.value = `preview-${doc.id}`
  previewError.value = ''
  preview.value = null
  try {
    preview.value = await ingestApi.parsePreview(doc.id)
  } catch (e) {
    previewError.value = e.message
  } finally {
    previewBusy.value = ''
  }
}

function closePreview() {
  preview.value = null
  previewError.value = ''
}

// 上传（P1）：文件 + 元数据 → 服务端写盘 + 登记 manifest + 入库。传"新版本"就是填同一 docNo、新 version。
const upload = ref(null)
const uploadBusy = ref(false)
const uploadError = ref('')
const uploadResult = ref(null)

function openUpload(base = null) {
  upload.value = {
    domainCode: base?.domainCode || domains.value[0]?.code || '',
    docNo: base?.docNo || '',
    title: base?.title || '',
    version: '',
    docStatus: props.admin ? '现行' : '草案·未生效',
    secrecy: base?.secrecy || '内部公开',
    ownerDept: base?.ownerDept || '',
    file: null
  }
  uploadError.value = ''
  uploadResult.value = null
}

function onFileChosen(event) {
  upload.value.file = event.target.files?.[0] || null
}

async function submitUpload() {
  const form = upload.value
  if (!form.file) {
    uploadError.value = '请选择要上传的文件'
    return
  }
  uploadBusy.value = true
  uploadError.value = ''
  try {
    const data = new FormData()
    data.append('file', form.file)
    data.append('domainCode', form.domainCode)
    data.append('docNo', form.docNo)
    data.append('title', form.title)
    data.append('version', form.version)
    if (form.docStatus) data.append('docStatus', form.docStatus)
    if (form.secrecy) data.append('secrecy', form.secrecy)
    if (form.ownerDept) data.append('ownerDept', form.ownerDept)
    uploadResult.value = await ingestApi.upload(data)
    upload.value = null
    await load()
  } catch (e) {
    uploadError.value = e.message
  } finally {
    uploadBusy.value = false
  }
}

async function changeStatus(doc, status) {
  busy.value = `status-${doc.id}`
  error.value = ''
  try {
    await ingestApi.setDocumentStatus(doc.id, status)
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

// 同编号的版本数：让"还有别的版本"一眼可见（检索侧仍会照常提示版本冲突）
const versionCount = computed(() => {
  const counts = {}
  for (const doc of documents.value) {
    counts[doc.docNo] = (counts[doc.docNo] || 0) + 1
  }
  return counts
})

const readyCount = computed(() => documents.value.filter((d) => d.chunkStatus === 'INDEXED').length)
const failedCount = computed(() => documents.value.filter((d) => d.chunkStatus === 'FAILED').length)
// 待审草案：管理员在列表里审核（通过→现行，驳回→已废止）。草案不参与检索。
const pendingDocs = computed(() => documents.value.filter((d) => d.docStatus && d.docStatus.includes('草案')))

async function load() {
  error.value = ''
  try {
    const docs = await ingestApi.documents()
    documents.value = docs.documents
  } catch (e) {
    error.value = e.message
    return
  }
  // 同步任务是管理员接口。viewer 打开这一页是为了**看文档**（还要用查看器），
  // 不能因为这个管理接口 403 就把整页变成"请求失败"——文档列表本身对 viewer 是开放的。
  try {
    tasks.value = (await ingestApi.tasks(50)).tasks
  } catch {
    tasks.value = []
  }
}

async function scan() {
  busy.value = 'scan'
  error.value = ''
  notice.value = '正在扫描并入库：每份文档都要解析并向量化，请耐心等待…'
  try {
    summary.value = await ingestApi.scan()
    notice.value = ''
  } catch (e) {
    error.value = e.message
    notice.value = ''
  } finally {
    busy.value = ''
    await load()
  }
}

async function retry(task) {
  busy.value = `retry-${task.id}`
  error.value = ''
  try {
    const result = await ingestApi.retry(task.id)
    notice.value = `重试结果：${formatStatus(result.status)} —— ${result.detail}`
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
    await load()
  }
}

async function retryAll() {
  busy.value = 'retryAll'
  error.value = ''
  try {
    const result = await ingestApi.retryFailed()
    notice.value = `已重试 ${result.results.length} 项，${result.ok ? '全部成功' : '仍有失败项'}`
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
    await load()
  }
}

function formatStatus(status) {
  return { SUCCEEDED: '成功', FAILED: '失败', SKIPPED: '跳过', RUNNING: '进行中' }[status] || status
}

function formatAction(action) {
  return { CREATED: '新建', REUPLOADED: '重新入库', RETRIED: '重试', SKIPPED: '跳过', FAILED: '失败' }[action] || action
}

function statusClass(status) {
  if (status === 'SUCCEEDED') return 'ok'
  if (status === 'FAILED') return 'err'
  return 'warn'
}

function formatTime(value) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

// INDEXED=好，FAILED=坏，其余（SKIPPED/PENDING/NONE）用中性色——避免"没做"被当成"做坏了"
function statusClassOf(status) {
  if (status === 'INDEXED') return 'ok'
  if (status === 'FAILED') return 'err'
  return 'warn'
}

async function reindexImages() {
  busy.value = 'images'
  error.value = ''
  try {
    const result = await mediaApi.reindex(false)
    notice.value = `图片索引重建：共 ${result.total}，重建 ${result.indexed}，跳过 ${result.skipped}，失败 ${result.failed}`
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
    await load()
  }
}

onMounted(() => {
  load()
  loadDomains()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>文档与同步</h1>
        <p class="desc">
          扫描配置目录 → 算内容哈希 → 内容没变就跳过 → 解析并向量化。每次尝试都留痕，失败可直接重试。
        </p>
      </div>
      <div class="head-actions">
        <button v-if="admin" class="btn" :disabled="!!busy" @click="retryAll">重试全部失败项</button>
        <button class="btn" @click="openUpload()">上传文档</button>
        <button v-if="admin" class="btn" :disabled="!!busy" @click="reindexImages">
          {{ busy === 'images' ? '重建中…' : '重建图片索引' }}
        </button>
        <button v-if="admin" class="btn primary" :disabled="!!busy" @click="scan">
          {{ busy === 'scan' ? '同步中…' : '立即同步' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="banner err">请求失败：{{ error }}</div>
    <div v-if="notice" class="banner info">{{ notice }}</div>

    <section class="stats">
      <div><span>文档总数</span><b>{{ documents.length }}</b></div>
      <div><span>片段就绪</span><b>{{ readyCount }}</b></div>
      <div><span>片段失败</span><b>{{ failedCount }}</b></div>
      <div v-if="admin"><span>图片资产</span><b>{{ documents.reduce((n, d) => n + (d.assetCount || 0), 0) }}</b></div>
    </section>

    <section v-if="summary" class="card">
      <header class="card-head">
        <div>
          <h2>本次同步</h2>
          <p class="desc">{{ summary.sourceRoot }}</p>
        </div>
        <span :class="['pill', summary.failed === 0 ? 'ok' : 'err']">
          {{ summary.failed === 0 ? '无失败项' : `${summary.failed} 项失败` }}
        </span>
      </header>
      <div class="snapshot">
        <div><span>扫描</span><b>{{ summary.scanned }}</b></div>
        <div><span>入库</span><b>{{ summary.uploaded }}</b></div>
        <div><span>跳过</span><b>{{ summary.skipped }}</b></div>
        <div><span>失败</span><b>{{ summary.failed }}</b></div>
      </div>
    </section>

    <section v-if="admin && pendingDocs.length" class="card">
      <header class="card-head">
        <div>
          <h2>待审核</h2>
          <p class="desc">用户上传的草案。通过后转「现行」才进检索；驳回转「已废止」。</p>
        </div>
        <code class="code">{{ pendingDocs.length }} 份</code>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr><th>文档编号</th><th>标题</th><th>版本</th><th>知识域</th><th>上传者</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="doc in pendingDocs" :key="doc.id">
              <td><code>{{ doc.docNo }}</code></td>
              <td>{{ doc.title }}</td>
              <td>{{ doc.version }}</td>
              <td>{{ doc.domainCode }}</td>
              <td>{{ doc.uploadedBy || '—' }}</td>
              <td class="row-actions">
                <button class="btn tiny primary" :disabled="busy === `status-${doc.id}`" @click="changeStatus(doc, '现行')">通过</button>
                <button class="btn tiny" :disabled="busy === `status-${doc.id}`" @click="changeStatus(doc, '已废止')">驳回</button>
                <button class="btn tiny" @click="openViewer(doc)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>文档</h2>
          <p class="desc">文档级 ACL 在入库时按密级写入，检索侧是 fail-closed：没有授权行就查不到。「解析」是片段就绪状态——只有它就绪的文档才会被检索。</p>
        </div>
        <code class="code">{{ documents.length }} 份</code>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>文档编号</th><th>标题</th><th>版本</th><th>状态</th>
              <th>密级</th><th>上传者</th><th>知识域</th><th>解析</th><th>图片</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="doc in documents" :key="doc.id">
              <td><code>{{ doc.docNo }}</code></td>
              <td>{{ doc.title }}</td>
              <td>
                {{ doc.version }}
                <span v-if="versionCount[doc.docNo] > 1" class="chunk-count" title="同编号还有其它版本">
                  {{ versionCount[doc.docNo] }} 版
                </span>
              </td>
              <td>
                <select
                  v-if="admin"
                  class="status-select"
                  :value="doc.docStatus"
                  :disabled="busy === `status-${doc.id}`"
                  @change="changeStatus(doc, $event.target.value)"
                >
                  <option>现行</option>
                  <option>已废止</option>
                  <option>草案·未生效</option>
                </select>
                <span v-else>{{ doc.docStatus }}</span>
              </td>
              <td>{{ doc.secrecy }}</td>
              <td>{{ doc.uploadedBy || '—' }}</td>
              <td>{{ doc.domainCode }}</td>
              <td>
                <span :class="['pill', statusClassOf(doc.chunkStatus)]">{{ doc.chunkStatus }}</span>
                <span class="chunk-count">{{ doc.chunkCount }} 段</span>
              </td>
              <td>
                <span :class="['pill', statusClassOf(doc.assetStatus)]">{{ doc.assetStatus }}</span>
                <span class="chunk-count">{{ doc.assetCount }} 张</span>
              </td>
              <td class="row-actions">
                <button v-if="admin" class="btn tiny" @click="openUpload(doc)">传新版本</button>
                <button
                  v-if="admin"
                  class="btn tiny"
                  :disabled="!!previewBusy"
                  @click="openPreview(doc)"
                >{{ previewBusy === `preview-${doc.id}` ? '解析中…' : '解析预览' }}</button>
                <button class="btn tiny" @click="openViewer(doc)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="admin" class="card">
      <header class="card-head">
        <div>
          <h2>同步任务</h2>
          <p class="desc">同一个文件的每次尝试都单独一行，失败原因直接可读。</p>
        </div>
        <code class="code">最近 {{ tasks.length }} 条</code>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr><th>时间</th><th>文件</th><th>次数</th><th>动作</th><th>结果</th><th>说明</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.id">
              <td class="nowrap">{{ formatTime(task.finishedAt || task.createdAt) }}</td>
              <td>{{ task.sourcePath }}</td>
              <td>{{ task.attempts }}</td>
              <td>{{ formatAction(task.action) }}</td>
              <td><span :class="['pill', statusClass(task.status)]">{{ formatStatus(task.status) }}</span></td>
              <td class="detail-cell">{{ task.lastError || task.detail }}</td>
              <td>
                <button
                  v-if="task.status === 'FAILED'"
                  class="btn tiny"
                  :disabled="!!busy"
                  @click="retry(task)"
                >{{ busy === `retry-${task.id}` ? '重试中…' : '重试' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <DocumentViewer
      v-if="viewer"
      :document-id="viewer.id"
      :doc-title="viewer.title"
      :highlight-chunk-id="viewerHighlight"
      @close="viewer = null"
    />

    <div v-if="preview || previewError" class="overlay" @click.self="closePreview">
      <section class="modal wide">
        <header class="modal-head">
          <div>
            <h2>解析预览</h2>
            <p v-if="preview" class="desc">
              {{ preview.docNo }} · {{ preview.title }} · 解析器：{{ preview.parser }}
              · 块 {{ preview.blockCount }} / 片段 {{ preview.chunkCount }}
              <span v-if="preview.truncated">（仅显示前 {{ preview.blocks.length }} 块）</span>
            </p>
          </div>
          <button class="btn tiny" @click="closePreview">关闭</button>
        </header>
        <div v-if="previewError" class="banner err">解析预览失败：{{ previewError }}</div>
        <div v-else class="table-wrap">
          <table class="grid">
            <thead>
              <tr><th>#</th><th>类型</th><th>页码</th><th>层级</th><th>文本</th></tr>
            </thead>
            <tbody>
              <tr v-for="(block, index) in preview.blocks" :key="index">
                <td>{{ index + 1 }}</td>
                <td>{{ block.kind }}</td>
                <td>{{ block.page || '—' }}</td>
                <td>{{ block.level }}</td>
                <td class="detail-cell">{{ block.text }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <div v-if="upload" class="overlay" @click.self="upload = null">
      <section class="modal">
        <header class="modal-head">
          <div>
            <h2>上传文档</h2>
            <p class="desc">
              支持 PDF / DOCX / PPTX / XLSX / 图片（png/jpg/jpeg/gif/bmp）。上传会写盘并登记 manifest.json，
              然后解析并向量化；图片单独建图片索引（供「找图」）。同一「文档编号 + 版本」重复上传视为覆盖。
            </p>
          </div>
          <button class="btn tiny" @click="upload = null">关闭</button>
        </header>
        <div class="fields">
          <label class="field">
            <span>知识域</span>
            <select v-model="upload.domainCode">
              <option v-for="d in domains" :key="d.code" :value="d.code">{{ d.name }}（{{ d.code }}）</option>
            </select>
          </label>
          <label class="field"><span>文档编号</span><input v-model="upload.docNo" placeholder="如 QM-ZD-2026-030" /></label>
          <label class="field"><span>标题</span><input v-model="upload.title" /></label>
          <label class="field"><span>版本</span><input v-model="upload.version" placeholder="如 V1.0" /></label>
          <label v-if="admin" class="field">
            <span>状态</span>
            <select v-model="upload.docStatus">
              <option>现行</option><option>已废止</option><option>草案·未生效</option>
            </select>
          </label>
          <label v-if="admin" class="field">
            <span>密级</span>
            <select v-model="upload.secrecy">
              <option>内部公开</option><option>秘密</option>
            </select>
          </label>
          <label v-if="admin" class="field"><span>责任部门 <em>可选</em></span><input v-model="upload.ownerDept" /></label>
          <label class="field"><span>文件</span><input type="file" accept=".pdf,.docx,.pptx,.xlsx,.png,.jpg,.jpeg,.gif,.bmp" @change="onFileChosen" /></label>
        </div>
        <p v-if="!admin" class="desc upload-hint">
          提交后进入「待审核」（草案·未生效），管理员通过后才进检索。状态与密级由管理员审核时确定。
        </p>
        <div v-if="uploadError" class="banner err">{{ uploadError }}</div>
        <footer class="modal-foot">
          <button class="btn" @click="upload = null">取消</button>
          <button class="btn primary" :disabled="uploadBusy" @click="submitUpload">
            {{ uploadBusy ? '上传中…' : '上传并入库' }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="uploadResult" class="overlay" @click.self="uploadResult = null">
      <section class="modal">
        <header class="modal-head">
          <div><h2>上传结果</h2></div>
          <button class="btn tiny" @click="uploadResult = null">关闭</button>
        </header>
        <p>
          <span :class="['pill', uploadResult.status === 'SUCCEEDED' ? 'ok' : (uploadResult.status === 'SKIPPED' ? 'warn' : 'err')]">
            {{ uploadResult.status }}
          </span>
          <code>{{ uploadResult.sourcePath }}</code>
        </p>
        <p class="desc">{{ uploadResult.detail }}</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 这一页独有：限宽单元格 / 段数标记（其余共用件用全局样式） */
.detail-cell {
  max-width: 320px;
  color: var(--muted);
  font-size: 12.5px;
  word-break: break-word;
}

.chunk-count {
  margin-left: 6px;
  color: var(--muted);
  font-size: 12px;
}

/* 解析预览用更宽的弹窗（上传弹窗仍用全局默认宽度） */
.modal.wide {
  max-width: 880px;
}

.field input[type='file'] {
  height: auto;
  padding: 7px 8px;
}

.status-select {
  width: auto;
  height: 28px;
  padding: 0 6px;
  font-size: 12px;
}

.upload-hint {
  margin-top: 10px;
}
</style>
