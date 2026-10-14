<script setup>
import { onMounted, ref } from 'vue'
import { domainsApi } from '../api/client.js'

// tenantAdmin=true 时（多项目接入 S3）：建域只能落在自己管理的租户，后端兜底校验。
const props = defineProps({
  tenantAdmin: { type: Boolean, default: false }
})

const domains = ref([])
const check = ref(null)
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

// 知识域管理（均限 admin）：新建 / 编辑 / 启停 / 删除
const notice = ref('')
const editing = ref(null)
const formBusy = ref('')
const formError = ref('')

function openCreate() {
  editing.value = { mode: 'create', code: '', name: '', description: '', tenantCode: '' }
  formError.value = ''
}

function openEdit(domain) {
  editing.value = {
    mode: 'edit',
    code: domain.code,
    name: domain.name,
    description: domain.description || '',
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
        tenantCode: form.tenantCode
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

onMounted(refresh)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>知识域管理</h1>
        <p class="desc">
          知识域是检索与授权的顶层分组：权限按域下发，文档归属在域里。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn" :disabled="busy === 'check'" @click="refresh">
          {{ busy === 'check' ? '检查中…' : '重新自检' }}
        </button>
        <button class="btn" @click="openCreate">新建知识域</button>
      </div>
    </div>

    <div v-if="error" class="banner err">请求失败：{{ error }}</div>
    <div v-if="notice" class="banner info">{{ notice }}</div>

    <section v-if="check" class="card">
      <header class="card-head">
        <div>
          <h2>本地自检</h2>
          <p class="desc">检查文档目录、图片资产目录、向量化配置与本地检索链路。</p>
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
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>知识域</h2>
          <p class="desc">改这里的启用状态会即时生效；权限在「工作区授权」里按域下发。</p>
        </div>
        <code class="code">{{ domains.length }} 个</code>
      </header>
      <table class="grid">
        <thead>
          <tr>
            <th>代码</th><th>名称</th><th>租户</th><th>可见文档</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="domain in domains" :key="domain.code">
            <td><code>{{ domain.code }}</code></td>
            <td>{{ domain.name }}</td>
            <td><span class="tag">{{ domain.tenantCode }}</span></td>
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
          <!-- 租户只在新建时可指定：建好之后再换租户等于搬家，会把可见性作用域整块挪走 -->
          <label v-if="editing.mode === 'create'" class="field">
            <span>租户 <em>{{ props.tenantAdmin ? '留空 = 你管理的租户' : '留空 = 默认租户' }}</em></span>
            <input v-model="editing.tenantCode" :placeholder="props.tenantAdmin ? '你管理的租户' : 'default'" />
          </label>
          <label v-if="editing.mode === 'edit'" class="field inline">
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
/* 自检结果列表：这一页独有（其余按钮/卡片/表格/弹窗都用全局样式） */
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
</style>
