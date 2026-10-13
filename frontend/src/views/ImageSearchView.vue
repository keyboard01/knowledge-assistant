<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { domainsApi, mediaApi } from '../api/client.js'
import DocumentViewer from './DocumentViewer.vue'

// 找图：按一句描述检索图片素材。图片字节必须走 Blob + Authorization（不能直接 <img src>）。
const domains = ref([])
const domainCode = ref('')
const query = ref('')
const images = ref([])
const thumbs = ref({})
const busy = ref(false)
const error = ref('')
const notice = ref('')

const viewer = ref(null)
const lightbox = ref(null)

async function loadDomains() {
  try {
    domains.value = (await domainsApi.list()).domains
    if (!domainCode.value && domains.value.length) {
      domainCode.value = domains.value[0].code
    }
  } catch {
    domains.value = []
  }
}

function revokeThumbs() {
  for (const url of Object.values(thumbs.value)) {
    URL.revokeObjectURL(url)
  }
  thumbs.value = {}
}

async function loadThumbs() {
  revokeThumbs()
  for (const item of images.value) {
    try {
      const blob = await mediaApi.thumbnail(item.assetId)
      thumbs.value[item.assetId] = URL.createObjectURL(blob)
    } catch {
      // 缩略图取不到不影响列表本身
    }
  }
}

async function search() {
  if (!domainCode.value) {
    error.value = '请选择知识域'
    return
  }
  if (!query.value.trim()) {
    error.value = '请输入要找的图片描述'
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await mediaApi.search({ domainCode: domainCode.value, query: query.value.trim() })
    images.value = result.images || []
    notice.value = result.notice || ''
    await loadThumbs()
  } catch (e) {
    error.value = e.message
    images.value = []
    revokeThumbs()
  } finally {
    busy.value = false
  }
}

async function openRaw(item) {
  try {
    const blob = await mediaApi.raw(item.assetId)
    lightbox.value = { url: URL.createObjectURL(blob), caption: item.caption }
  } catch (e) {
    error.value = e.message
  }
}

function closeLightbox() {
  if (lightbox.value) {
    URL.revokeObjectURL(lightbox.value.url)
    lightbox.value = null
  }
}

function openDocument(item) {
  viewer.value = { id: item.documentId, title: item.title }
}

onMounted(loadDomains)
onBeforeUnmount(() => {
  revokeThumbs()
  closeLightbox()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>找图</h1>
        <p class="desc">
          用一句描述找知识域里的图片素材（来自文档内嵌图与直接上传的图片）。只显示你有权限、且已生效的文档里的图。
        </p>
      </div>
    </div>

    <section class="card">
      <div class="search-bar">
        <label class="field">
          <span>知识域</span>
          <select v-model="domainCode">
            <option v-for="d in domains" :key="d.code" :value="d.code">{{ d.name }}（{{ d.code }}）</option>
          </select>
        </label>
        <label class="field grow">
          <span>找什么样的图</span>
          <input v-model="query" placeholder="如：液压站结构示意图 / 带公司 logo 的封面" @keyup.enter="search" />
        </label>
        <button class="btn primary" :disabled="busy" @click="search">{{ busy ? '检索中…' : '找图' }}</button>
      </div>
    </section>

    <div v-if="error" class="banner err">{{ error }}</div>
    <div v-if="notice" class="banner info">{{ notice }}</div>

    <section v-if="images.length" class="grid-images">
      <figure v-for="item in images" :key="item.assetId" class="image-card">
        <img
          v-if="thumbs[item.assetId]"
          :src="thumbs[item.assetId]"
          :alt="item.caption || item.title"
          @click="openRaw(item)"
        />
        <div v-else class="thumb-placeholder">无缩略图</div>
        <figcaption>
          <p class="caption">{{ item.caption || '（无描述）' }}</p>
          <p class="meta">
            《{{ item.title }}》
            <span v-if="item.page">· 第 {{ item.page }} 页</span>
            <span>· {{ item.docNo }} {{ item.version }}</span>
          </p>
          <div class="actions">
            <button class="btn tiny" @click="openRaw(item)">看大图</button>
            <button class="btn tiny" @click="openDocument(item)">来源文档</button>
          </div>
        </figcaption>
      </figure>
    </section>

    <DocumentViewer
      v-if="viewer"
      :document-id="viewer.id"
      :doc-title="viewer.title"
      @close="viewer = null"
    />

    <div v-if="lightbox" class="overlay" @click.self="closeLightbox">
      <section class="modal wide">
        <header class="modal-head">
          <div><h2>原图</h2></div>
          <button class="btn tiny" @click="closeLightbox">关闭</button>
        </header>
        <img :src="lightbox.url" class="raw-image" alt="原图" />
        <p v-if="lightbox.caption" class="desc">{{ lightbox.caption }}</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.search-bar {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  flex-wrap: wrap;
}

.search-bar .field {
  margin: 0;
}

.search-bar .grow {
  flex: 1;
  min-width: 240px;
}

.grid-images {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 14px;
  margin-top: 16px;
}

.image-card {
  margin: 0;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-md, 10px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.image-card img {
  width: 100%;
  height: 150px;
  object-fit: contain;
  background: #f4f6fb;
  cursor: zoom-in;
}

.thumb-placeholder {
  height: 150px;
  display: grid;
  place-items: center;
  color: var(--muted-2);
  background: #f4f6fb;
  font-size: 12px;
}

figcaption {
  padding: 10px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.caption {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink);
}

.meta {
  margin: 0;
  font-size: 11.5px;
  color: var(--muted);
}

.actions {
  display: flex;
  gap: 6px;
}

.raw-image {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
  display: block;
  margin: 0 auto;
}
</style>
