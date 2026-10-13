<script setup>
import { onMounted, ref } from 'vue'
import { adminApi, dingtalkApi } from '../../api/client.js'

// 用户与角色管理（admin）。角色下拉取 app_role 字典，不写死。
const users = ref([])
const roles = ref([])
const error = ref('')
const notice = ref('')
const busy = ref('')
const syncing = ref(false)

const form = ref(null)
const formError = ref('')
const formBusy = ref(false)

const pwdForm = ref(null)
const pwdError = ref('')
const pwdBusy = ref(false)

function roleName(code) {
  return roles.value.find((r) => r.code === code)?.name || code
}

async function load() {
  error.value = ''
  try {
    const [u, r] = await Promise.all([adminApi.users(), adminApi.roles()])
    users.value = u.users
    roles.value = r.roles
  } catch (e) {
    error.value = e.message
  }
}

// 从钉钉组织通讯录同步（需先在「系统配置 → 钉钉」填好凭据并启用）。
async function syncDingTalk() {
  syncing.value = true
  error.value = ''
  notice.value = ''
  try {
    const r = await dingtalkApi.sync()
    notice.value = `同步完成：部门 ${r.departments}，用户 ${r.users}，新增 ${r.created}，更新 ${r.updated}` +
      `，跳过 ${r.skipped}${r.truncated ? '（已达上限，已截断）' : ''}`
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    syncing.value = false
  }
}

function openCreate() {
  form.value = {
    mode: 'create',
    id: null,
    username: '',
    displayName: '',
    roleCode: roles.value[0]?.code || 'viewer',
    deptCode: '',
    password: '',
    enabled: true
  }
  formError.value = ''
}

function openEdit(user) {
  form.value = {
    mode: 'edit',
    id: user.id,
    username: user.username,
    displayName: user.displayName || '',
    roleCode: user.roleCode,
    deptCode: user.deptCode || '',
    password: '',
    enabled: user.enabled
  }
  formError.value = ''
}

async function submitForm() {
  const f = form.value
  formBusy.value = true
  formError.value = ''
  try {
    if (f.mode === 'create') {
      await adminApi.createUser({
        username: f.username,
        displayName: f.displayName,
        roleCode: f.roleCode,
        deptCode: f.deptCode,
        password: f.password
      })
      notice.value = `已创建用户 ${f.username}`
    } else {
      await adminApi.updateUser(f.id, {
        displayName: f.displayName,
        roleCode: f.roleCode,
        deptCode: f.deptCode,
        enabled: f.enabled
      })
      notice.value = `已更新用户 ${f.username}`
    }
    form.value = null
    await load()
  } catch (e) {
    formError.value = e.message
  } finally {
    formBusy.value = false
  }
}

async function toggleEnabled(user) {
  busy.value = `toggle-${user.id}`
  error.value = ''
  try {
    await adminApi.updateUser(user.id, {
      displayName: user.displayName,
      roleCode: user.roleCode,
      deptCode: user.deptCode,
      enabled: !user.enabled
    })
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

function openReset(user) {
  pwdForm.value = { user, newPassword: '' }
  pwdError.value = ''
}

async function submitReset() {
  const p = pwdForm.value
  pwdBusy.value = true
  pwdError.value = ''
  try {
    await adminApi.resetPassword(p.user.id, p.newPassword)
    notice.value = `已重置 ${p.user.username} 的密码（其全部会话已失效）`
    pwdForm.value = null
  } catch (e) {
    pwdError.value = e.message
  } finally {
    pwdBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>用户与角色</h1>
        <p class="desc">
          本地账号：建号、改角色/部门、停用、重置密码。角色字典来自 app_role；系统不允许停用或降级最后一个管理员。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn" :disabled="syncing" @click="syncDingTalk">
          {{ syncing ? '同步中…' : '从钉钉同步' }}
        </button>
        <button class="btn primary" @click="openCreate">新建用户</button>
      </div>
    </div>

    <div v-if="error" class="banner err">请求失败：{{ error }}</div>
    <div v-if="notice" class="banner info">{{ notice }}</div>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>账号</h2>
          <p class="desc">停用账号或重置密码会立即作废该账号的所有会话。</p>
        </div>
        <code class="code">{{ users.length }} 个</code>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>用户名</th><th>显示名</th><th>角色</th><th>部门</th><th>状态</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td><code>{{ user.username }}</code></td>
              <td>{{ user.displayName || '—' }}</td>
              <td>{{ user.roleCode }} <span class="muted-soft">{{ roleName(user.roleCode) }}</span></td>
              <td>{{ user.deptCode || '—' }}</td>
              <td>
                <span :class="['pill', user.enabled ? 'ok' : 'err']">{{ user.enabled ? '启用' : '停用' }}</span>
              </td>
              <td class="row-actions">
                <button class="btn tiny" @click="openEdit(user)">编辑</button>
                <button class="btn tiny" @click="openReset(user)">重置密码</button>
                <button class="btn tiny" :disabled="busy === `toggle-${user.id}`" @click="toggleEnabled(user)">
                  {{ user.enabled ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="form" class="overlay" @click.self="form = null">
      <section class="modal">
        <header class="modal-head">
          <div>
            <h2>{{ form.mode === 'create' ? '新建用户' : `编辑 ${form.username}` }}</h2>
          </div>
          <button class="btn tiny" @click="form = null">关闭</button>
        </header>
        <div class="fields">
          <label v-if="form.mode === 'create'" class="field">
            <span>用户名</span>
            <input v-model="form.username" placeholder="登录名，如 zhangsan" />
          </label>
          <label class="field"><span>显示名</span><input v-model="form.displayName" /></label>
          <label class="field">
            <span>角色</span>
            <select v-model="form.roleCode">
              <option v-for="r in roles" :key="r.code" :value="r.code">{{ r.name }}（{{ r.code }}）</option>
            </select>
          </label>
          <label class="field"><span>部门 <em>可选</em></span><input v-model="form.deptCode" /></label>
          <label v-if="form.mode === 'create'" class="field">
            <span>初始密码</span>
            <input v-model="form.password" type="password" placeholder="至少 8 位" />
          </label>
          <label v-else class="field">
            <span>状态</span>
            <select v-model="form.enabled">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </label>
        </div>
        <div v-if="formError" class="banner err">{{ formError }}</div>
        <footer class="modal-foot">
          <button class="btn" @click="form = null">取消</button>
          <button class="btn primary" :disabled="formBusy" @click="submitForm">
            {{ formBusy ? '保存中…' : '保存' }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="pwdForm" class="overlay" @click.self="pwdForm = null">
      <section class="modal">
        <header class="modal-head">
          <div>
            <h2>重置密码 · {{ pwdForm.user.username }}</h2>
            <p class="desc">重置后该账号的全部会话立即失效，需用新密码重新登录。</p>
          </div>
          <button class="btn tiny" @click="pwdForm = null">关闭</button>
        </header>
        <div class="fields">
          <label class="field">
            <span>新密码</span>
            <input v-model="pwdForm.newPassword" type="password" placeholder="至少 8 位" />
          </label>
        </div>
        <div v-if="pwdError" class="banner err">{{ pwdError }}</div>
        <footer class="modal-foot">
          <button class="btn" @click="pwdForm = null">取消</button>
          <button class="btn primary" :disabled="pwdBusy" @click="submitReset">
            {{ pwdBusy ? '重置中…' : '重置密码' }}
          </button>
        </footer>
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
</style>
