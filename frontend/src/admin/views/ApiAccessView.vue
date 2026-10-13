<script setup>
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '../../api/client.js'

// 对外接入：API Key 管理 + MCP 端点信息。
// Key 绑定的是已有账号，权限由该账号的角色/部门决定（fail-closed 复用检索权限）。
const keys = ref([])
const users = ref([])
const error = ref('')
const notice = ref('')
const busy = ref('')

const form = ref(null)
const formError = ref('')
const formBusy = ref(false)
const revealed = ref(null)

const mcpEndpoint = computed(() => `${window.location.origin}/api/mcp`)

const TOOLS = [
  { name: 'search_documents', desc: '在当前身份可见范围内检索，返回带出处的证据片段' },
  { name: 'list_domains', desc: '列出当前身份可见的知识域' },
  { name: 'read_document', desc: '按文档 id 读取原文（域级/文档级 ACL + 现行状态都会校验）' }
]

async function load() {
  error.value = ''
  try {
    const [k, u] = await Promise.all([adminApi.apiKeys(), adminApi.users()])
    keys.value = k.keys
    users.value = u.users
  } catch (e) {
    error.value = e.message
  }
}

function openCreate() {
  form.value = { name: '', userId: users.value[0]?.id ?? '' }
  formError.value = ''
  revealed.value = null
}

async function submit() {
  const f = form.value
  formBusy.value = true
  formError.value = ''
  try {
    const res = await adminApi.createApiKey({ name: f.name, userId: Number(f.userId) })
    revealed.value = { name: f.name, key: res.key, client: res.client }
    form.value = null
    await load()
  } catch (e) {
    formError.value = e.message
  } finally {
    formBusy.value = false
  }
}

async function toggle(key) {
  busy.value = `toggle-${key.id}`
  error.value = ''
  try {
    await adminApi.setApiKeyEnabled(key.id, !key.enabled)
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function revoke(key) {
  if (!window.confirm(`吊销「${key.name}」（${key.keyPrefix}…）？该密钥将立即失效。`)) return
  busy.value = `revoke-${key.id}`
  error.value = ''
  try {
    await adminApi.revokeApiKey(key.id)
    notice.value = `已吊销 ${key.name}`
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

function formatTime(value) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>对外接入</h1>
        <p class="desc">
          外部系统用 API Key 调用本服务：密钥绑定一个账号，可见范围完全由该账号的角色与 ACL 决定（fail-closed）。
          也可通过 MCP 端点接入支持 MCP 的客户端。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn primary" @click="openCreate">新建 API Key</button>
      </div>
    </div>

    <div v-if="error" class="banner err">请求失败：{{ error }}</div>
    <div v-if="notice" class="banner info">{{ notice }}</div>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>API Key</h2>
          <p class="desc">明文只在创建时显示一次；列表只保留前缀。停用绑定账号会连带使其密钥失效。</p>
        </div>
        <code class="code">{{ keys.length }} 枚</code>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr><th>名称</th><th>前缀</th><th>绑定账号</th><th>状态</th><th>最近使用</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="key in keys" :key="key.id">
              <td>{{ key.name }}</td>
              <td><code>{{ key.keyPrefix }}…</code></td>
              <td>{{ key.username || key.userId }}<span v-if="key.displayName" class="muted-soft">{{ key.displayName }}</span></td>
              <td><span :class="['pill', key.enabled ? 'ok' : 'err']">{{ key.enabled ? '启用' : '停用' }}</span></td>
              <td>{{ formatTime(key.lastUsedAt) }}</td>
              <td class="row-actions">
                <button class="btn tiny" :disabled="busy === `toggle-${key.id}`" @click="toggle(key)">
                  {{ key.enabled ? '停用' : '启用' }}
                </button>
                <button class="btn tiny" :disabled="busy === `revoke-${key.id}`" @click="revoke(key)">吊销</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>MCP 端点</h2>
          <p class="desc">Streamable HTTP（JSON-RPC 2.0）。用 API Key 作 Bearer 调用；工具返回范围同样受该账号权限约束。</p>
        </div>
      </header>
      <div class="mcp">
        <div><span>端点</span><code>{{ mcpEndpoint }}</code></div>
        <div><span>鉴权</span><code>Authorization: Bearer &lt;API Key&gt;</code></div>
        <div class="tools">
          <span>工具</span>
          <ul>
            <li v-for="t in TOOLS" :key="t.name"><code>{{ t.name }}</code> —— {{ t.desc }}</li>
          </ul>
        </div>
      </div>
    </section>

    <div v-if="form" class="overlay" @click.self="form = null">
      <section class="modal">
        <header class="modal-head">
          <div><h2>新建 API Key</h2></div>
          <button class="btn tiny" @click="form = null">关闭</button>
        </header>
        <div class="fields">
          <label class="field"><span>名称</span><input v-model="form.name" placeholder="如 运维平台对接" /></label>
          <label class="field">
            <span>绑定账号</span>
            <select v-model="form.userId">
              <option v-for="u in users" :key="u.id" :value="u.id">
                {{ u.displayName || u.username }}（{{ u.username }} · {{ u.roleCode }}）
              </option>
            </select>
          </label>
        </div>
        <div v-if="formError" class="banner err">{{ formError }}</div>
        <footer class="modal-foot">
          <button class="btn" @click="form = null">取消</button>
          <button class="btn primary" :disabled="formBusy" @click="submit">
            {{ formBusy ? '创建中…' : '创建' }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="revealed" class="overlay" @click.self="revealed = null">
      <section class="modal">
        <header class="modal-head">
          <div>
            <h2>密钥（仅显示这一次）</h2>
            <p class="desc">关闭后无法再次查看，只能吊销重建。请立即复制并妥善保存。</p>
          </div>
          <button class="btn tiny" @click="revealed = null">关闭</button>
        </header>
        <p><code class="reveal">{{ revealed.key }}</code></p>
        <p class="desc">绑定账号：{{ revealed.client.username }}（{{ revealed.client.roleCode || '—' }}）</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.muted-soft {
  margin-left: 6px;
  color: var(--muted);
  font-size: 12px;
}

.mcp {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 2px;
}

.mcp > div {
  display: flex;
  gap: 12px;
  align-items: baseline;
}

.mcp span {
  min-width: 44px;
  color: var(--muted);
  font-size: 12.5px;
}

.mcp .tools {
  align-items: flex-start;
}

.mcp ul {
  margin: 0;
  padding-left: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}

.reveal {
  display: block;
  padding: 12px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: var(--r-sm);
  word-break: break-all;
  font-size: 13px;
}
</style>
