<script setup>
import { onMounted, ref } from 'vue'
import { domainsApi } from '../api/client.js'

const domains = ref([])
const check = ref(null)
const provisionResult = ref(null)
const busy = ref('')
const error = ref('')

async function refresh() {
  busy.value = 'check'
  error.value = ''
  try {
    const [domainData, checkData] = await Promise.all([domainsApi.list(), domainsApi.selfCheck()])
    domains.value = domainData.domains
    check.value = checkData
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function provision() {
  busy.value = 'provision'
  error.value = ''
  try {
    provisionResult.value = await domainsApi.provision()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
    await refresh()
  }
}

// 知识域管理（P1，均限 admin）：新建 / 编辑 / 启停 / 初始化工作区 / 删除
const notice = ref('')
const editing = ref(null)
const formBusy = ref('')
const formError = ref('')

function openCreate() {
  editing.value = { mode: 'create', code: '', name: '', description: '', workspaceSlug: '' }
  formError.value = ''
}

function openEdit(domain) {
  editing.value = {
    mode: 'edit',
    code: domain.code,
    name: domain.name,
    description: domain.description || '',
    workspaceSlug: domain.workspaceSlug,
    enabled: domain.enabled
  }
  formError.value = ''
}

async function submitForm() {
  const form = editing.value
  formBusy.value = 'form'
  formError.value = ''
  try {
    if (form.mode === 'create') {
      await domainsApi.create({
        code: form.code,
        name: form.name,
        description: form.description,
        workspaceSlug: form.workspaceSlug
      })
      notice.value = `已新建知识域 ${form.code}`
    } else {
      await domainsApi.update(form.code, {
        name: form.name,
        description: form.description,
        enabled: form.enabled
      })
      notice.value = `已更新 ${form.code}`
    }
    editing.value = null
    await refresh()
  } catch (e) {
    formError.value = e.message
  } finally {
    formBusy.value = ''
  }
}

async function toggleEnabled(domain) {
  busy.value = `toggle-${domain.code}`
  error.value = ''
  try {
    await domainsApi.update(domain.code, {
      name: domain.name,
      description: domain.description,
      enabled: !domain.enabled
    })
    notice.value = `${domain.code} 已${domain.enabled ? '停用' : '启用'}`
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function provisionOne(domain) {
  busy.value = `prov-${domain.code}`
  error.value = ''
  try {
    const result = await domainsApi.provisionOne(domain.code)
    notice.value = `${domain.code} 工作区：${actionText(result.action)} —— ${result.message}`
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function removeDomain(domain) {
  if (!window.confirm(`确认删除知识域 ${domain.code}？该域下还有文档时会被拒绝。`)) return
  busy.value = `del-${domain.code}`
  error.value = ''
  try {
    await domainsApi.remove(domain.code)
    notice.value = `已删除知识域 ${domain.code}`
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

function statusClass(status) {
  if (status === 'OK') return 'ok'
  if (status === 'WARN') return 'warn'
  return 'err'
}

function actionText(action) {
  return { CREATED: '已创建', EXISTS: '已存在', SKIPPED: '已跳过', FAILED: '失败' }[action] || action
}

onMounted(refresh)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>知识域与内核</h1>
        <p class="desc">
          内核（AnythingLLM）负责文档仓与向量检索；知识域与内核工作区一一对应，权限与引用由本系统自己管。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn" :disabled="busy === 'check'" @click="refresh">
          {{ busy === 'check' ? '检查中…' : '重新自检' }}
        </button>
        <button class="btn" @click="openCreate">新建知识域</button>
        <button class="btn primary" :disabled="busy === 'provision'" @click="provision">
          {{ busy === 'provision' ? '初始化中…' : '初始化知识域' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="banner err">请求失败：{{ error }}</div>
    <div v-if="notice" class="banner info">{{ notice }}</div>

    <section v-if="check" class="card">
      <header class="card-head">
        <div>
          <h2>启动自检</h2>
          <p class="desc">服务启动时自动执行一次，失败项只告警不阻断启动。</p>
        </div>
        <span :class="['pill', check.ok ? 'ok' : 'err']">{{ check.ok ? '通过' : '未通过' }}</span>
      </header>

      <ul class="checks">
        <li v-for="item in check.items" :key="item.key">
          <span :class="['pill', statusClass(item.status)]">{{ item.status }}</span>
          <div>
            <strong>{{ item.title }}</strong>
            <p>{{ item.detail }}</p>
          </div>
        </li>
      </ul>

      <div v-if="check.kernel" class="snapshot">
        <div><span>向量化引擎</span><b>{{ check.kernel.embeddingEngine || '—' }}</b></div>
        <div><span>向量化模型</span><b>{{ check.kernel.embeddingModel || '—' }}</b></div>
        <div><span>生成模型</span><b>{{ check.kernel.llmModel || '—' }}</b></div>
        <div><span>向量总数</span><b>{{ check.vectorCount ?? '—' }}</b></div>
      </div>
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>知识域</h2>
          <p class="desc">每个知识域对应内核里一个工作区；改这里的启用状态会在下次自检生效。</p>
        </div>
        <code class="code">{{ domains.length }} 个</code>
      </header>
      <table class="grid">
        <thead>
          <tr>
            <th>代码</th><th>名称</th><th>内核工作区</th><th>可见文档</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="domain in domains" :key="domain.code">
            <td><code>{{ domain.code }}</code></td>
            <td>{{ domain.name }}</td>
            <td><code>{{ domain.workspaceSlug }}</code></td>
            <td :title="'当前账号可见的文档数（只算你看得到的）'">{{ domain.visibleDocuments }}</td>
            <td>{{ domain.enabled ? '启用' : '停用' }}</td>
            <td class="row-actions">
              <button class="btn tiny" @click="openEdit(domain)">编辑</button>
              <button
                class="btn tiny"
                :disabled="!!busy"
                @click="toggleEnabled(domain)"
              >{{ busy === `toggle-${domain.code}` ? '…' : (domain.enabled ? '停用' : '启用') }}</button>
              <button
                class="btn tiny"
                :disabled="!!busy"
                @click="provisionOne(domain)"
              >{{ busy === `prov-${domain.code}` ? '初始化中…' : '初始化工作区' }}</button>
              <button
                class="btn tiny danger"
                :disabled="!!busy"
                @click="removeDomain(domain)"
              >{{ busy === `del-${domain.code}` ? '删除中…' : '删除' }}</button>
            </td>
          </tr>
        </tbody>
      </table>
      <small class="hint">新建/修改/删除限管理员；删除对"有文档的知识域"会被拒绝，请先清理或改为停用。</small>
    </section>

    <section v-if="provisionResult" class="card">
      <header class="card-head">
        <div>
          <h2>初始化结果</h2>
          <p class="desc">幂等操作：已存在的工作区不会重复创建。</p>
        </div>
        <span :class="['pill', provisionResult.ok ? 'ok' : 'err']">
          {{ provisionResult.ok ? '成功' : '有失败项' }}
        </span>
      </header>
      <ul class="checks">
        <li v-for="item in provisionResult.results" :key="item.domainCode">
          <span :class="['pill', item.action === 'FAILED' ? 'err' : 'ok']">{{ actionText(item.action) }}</span>
          <div>
            <strong>{{ item.domainCode }} → {{ item.workspaceSlug }}</strong>
            <p>{{ item.message }}</p>
          </div>
        </li>
      </ul>
    </section>

    <div v-if="editing" class="overlay" @click.self="editing = null">
      <section class="modal">
        <header class="modal-head">
          <h2>{{ editing.mode === 'create' ? '新建知识域' : `编辑 ${editing.code}` }}</h2>
          <button class="btn tiny" @click="editing = null">关闭</button>
        </header>
        <div class="fields">
          <label class="field">
            <span>代码 <em>小写字母/数字/-/_，字母开头</em></span>
            <input v-model="editing.code" :disabled="editing.mode === 'edit'" placeholder="如 quality" />
          </label>
          <label class="field">
            <span>名称</span>
            <input v-model="editing.name" placeholder="如 质量体系" />
          </label>
          <label class="field">
            <span>描述</span>
            <input v-model="editing.description" placeholder="可选" />
          </label>
          <label v-if="editing.mode === 'create'" class="field">
            <span>内核工作区 slug <em>留空默认 knowledge-&lt;代码&gt;</em></span>
            <input v-model="editing.workspaceSlug" placeholder="knowledge-quality" />
          </label>
          <label v-else class="field inline">
            <input type="checkbox" v-model="editing.enabled" /> 启用
          </label>
        </div>
        <div v-if="formError" class="banner err">{{ formError }}</div>
        <footer class="modal-foot">
          <button class="btn" @click="editing = null">取消</button>
          <button class="btn primary" :disabled="formBusy === 'form'" @click="submitForm">
            {{ formBusy === 'form' ? '保存中…' : '保存' }}
          </button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 自检/初始化结果列表：这一页独有（其余按钮/卡片/表格/弹窗都用全局样式） */
.checks {
  list-style: none;
  margin: 0;
  padding: 0;
}

.checks li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 12px 0;
  border-bottom: 1px solid var(--line-2);
}

.checks li:last-child {
  border-bottom: none;
}

.checks strong {
  font-size: 13.5px;
}

.checks p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12.5px;
  word-break: break-word;
}

.checks + .snapshot {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
</style>
