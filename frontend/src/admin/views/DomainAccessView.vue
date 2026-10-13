<script setup>
import { onMounted, ref } from 'vue'
import { adminApi, domainsApi } from '../../api/client.js'

// 工作区(知识域)级授权：谁能看哪个域。整份替换语义——保存时提交的就是全部规则。
const domains = ref([])
const error = ref('')
const notice = ref('')

const editor = ref(null)
const editorError = ref('')
const editorBusy = ref(false)

const SUBJECT_TYPES = [
  { value: 'ALL', label: '所有人' },
  { value: 'ROLE', label: '角色' },
  { value: 'DEPT', label: '部门' },
  { value: 'USER', label: '用户' }
]

async function load() {
  error.value = ''
  try {
    domains.value = (await domainsApi.list()).domains
  } catch (e) {
    error.value = e.message
  }
}

async function openEditor(domain) {
  editor.value = { domain, rules: [], loading: true }
  editorError.value = ''
  try {
    const res = await adminApi.domainAcl(domain.code)
    editor.value = { domain, rules: res.rules || [], loading: false }
  } catch (e) {
    editor.value = { domain, rules: [], loading: false }
    editorError.value = e.message
  }
}

function addRule() {
  editor.value.rules.push({ subjectType: 'ROLE', subjectValue: '' })
}

function removeRule(index) {
  editor.value.rules.splice(index, 1)
}

async function save() {
  const ed = editor.value
  editorBusy.value = true
  editorError.value = ''
  try {
    const res = await adminApi.saveDomainAcl(ed.domain.code, ed.rules)
    notice.value = `已更新「${ed.domain.name}」的授权（${res.rules.length} 条规则）`
    editor.value = null
    await load()
  } catch (e) {
    editorError.value = e.message
  } finally {
    editorBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>工作区授权</h1>
        <p class="desc">
          设置谁能看到哪个知识域。域级门先过（这里是白名单，fail-closed：一条规则都没有 = 只有管理员可见），
          再按文档级 ACL 判细粒度可见。新建域默认公开（ALL:*）。
        </p>
      </div>
    </div>

    <div v-if="error" class="banner err">请求失败：{{ error }}</div>
    <div v-if="notice" class="banner info">{{ notice }}</div>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>知识域</h2>
          <p class="desc">点「编辑授权」维护该域的可见范围。</p>
        </div>
        <code class="code">{{ domains.length }} 个</code>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr><th>代码</th><th>名称</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="domain in domains" :key="domain.code">
              <td><code>{{ domain.code }}</code></td>
              <td>{{ domain.name }}</td>
              <td>
                <span :class="['pill', domain.enabled ? 'ok' : 'warn']">{{ domain.enabled ? '启用' : '停用' }}</span>
              </td>
              <td class="row-actions">
                <button class="btn tiny" @click="openEditor(domain)">编辑授权</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="editor" class="overlay" @click.self="editor = null">
      <section class="modal">
        <header class="modal-head">
          <div>
            <h2>授权 · {{ editor.domain.name }}（{{ editor.domain.code }}）</h2>
            <p class="desc">保存会整份替换该域现有规则。留空 = 只有管理员可见。</p>
          </div>
          <button class="btn tiny" @click="editor = null">关闭</button>
        </header>

        <div v-if="editor.loading" class="desc">加载中…</div>
        <div v-else class="rules">
          <div v-for="(rule, index) in editor.rules" :key="index" class="rule-row">
            <select v-model="rule.subjectType">
              <option v-for="t in SUBJECT_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
            <input
              v-model="rule.subjectValue"
              :disabled="rule.subjectType === 'ALL'"
              :placeholder="rule.subjectType === 'ALL' ? '所有人（*）' : '角色码 / 部门码 / 用户名'"
            />
            <button class="btn tiny" @click="removeRule(index)">删除</button>
          </div>
          <p v-if="!editor.rules.length" class="desc">暂无规则（只有管理员可见）。</p>
        </div>

        <div v-if="editorError" class="banner err">{{ editorError }}</div>
        <footer class="modal-foot">
          <button class="btn" @click="addRule">添加规则</button>
          <span class="spacer"></span>
          <button class="btn" @click="editor = null">取消</button>
          <button class="btn primary" :disabled="editorBusy || editor.loading" @click="save">
            {{ editorBusy ? '保存中…' : '保存' }}
          </button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.rules {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rule-row {
  display: grid;
  grid-template-columns: 120px 1fr auto;
  gap: 8px;
  align-items: center;
}

.spacer {
  flex: 1;
}
</style>
