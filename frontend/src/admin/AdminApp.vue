<script setup>
import { computed, onMounted, ref } from 'vue'
import SettingsView from '../views/SettingsView.vue'
import KernelView from '../views/KernelView.vue'
import IngestView from '../views/IngestView.vue'
import ObserveView from '../views/ObserveView.vue'
import EvalView from '../views/EvalView.vue'
import LoginView from '../views/LoginView.vue'
import UsersView from './views/UsersView.vue'
import DomainAccessView from './views/DomainAccessView.vue'
import ApiAccessView from './views/ApiAccessView.vue'
import { authApi, getToken, setToken } from '../api/client.js'

// 管理控制台：独立入口（/admin.html），只服务 admin。
// 与用户门户共用同一套登录令牌与接口，只是把管理面聚到这里。
const view = ref('kernel')
const user = ref(null)
const ready = ref(false)

// 平台管理员：全局。租户管理员：只能进管理台、只能管自己租户的域/授权/审核。
const isPlatformAdmin = computed(() => user.value?.roleCode === 'admin')
const tenantAdmins = computed(() => user.value?.adminTenants || [])
const isTenantAdmin = computed(() => tenantAdmins.value.length > 0)
const canEnter = computed(() => isPlatformAdmin.value || isTenantAdmin.value)

async function loadUser() {
  if (!getToken()) {
    ready.value = true
    return
  }
  try {
    user.value = (await authApi.me()).user
  } catch {
    user.value = null
  } finally {
    ready.value = true
  }
}

function onSignedIn(signedIn) {
  user.value = signedIn
  view.value = 'kernel'
}

async function logout() {
  try {
    await authApi.logout()
  } catch {
    // 令牌可能已过期，退出动作照样要完成
  }
  setToken('')
  user.value = null
}

function goPortal() {
  window.location.href = '/'
}

onMounted(loadUser)
</script>

<template>
  <div v-if="!ready" class="boot">加载中…</div>
  <LoginView v-else-if="!user" @signed-in="onSignedIn" />
  <div v-else-if="!canEnter" class="shell">
    <div class="denied">
      <h1>无法进入管理控制台</h1>
      <p>当前账号（{{ user.displayName || user.username }} · {{ user.roleCode }}）不是管理员。</p>
      <button class="btn primary" type="button" @click="goPortal">前往用户门户</button>
    </div>
  </div>
  <div v-else class="shell">
    <header class="topbar">
      <div class="brand">知识助手 · 管理控制台</div>
      <nav>
        <span :class="{ active: view === 'kernel' }" @click="view = 'kernel'">知识域管理</span>
        <span :class="{ active: view === 'ingest' }" @click="view = 'ingest'">文档与同步</span>
        <span v-if="isPlatformAdmin" :class="{ active: view === 'users' }" @click="view = 'users'">用户与角色</span>
        <span :class="{ active: view === 'access' }" @click="view = 'access'">工作区授权</span>
        <span v-if="isPlatformAdmin" :class="{ active: view === 'apiaccess' }" @click="view = 'apiaccess'">对外接入</span>
        <span v-if="isPlatformAdmin" :class="{ active: view === 'observe' }" @click="view = 'observe'">运行记录</span>
        <span v-if="isPlatformAdmin" :class="{ active: view === 'eval' }" @click="view = 'eval'">评测</span>
        <span v-if="isPlatformAdmin" :class="{ active: view === 'settings' }" @click="view = 'settings'">系统配置</span>
      </nav>
      <div class="who">
        <span>{{ user.displayName || user.username }}（{{ isPlatformAdmin ? user.roleCode : '租户管理员' }}）</span>
        <button type="button" @click="goPortal">用户门户</button>
        <button type="button" @click="logout">退出</button>
      </div>
    </header>
    <main>
      <KernelView v-if="view === 'kernel'" :tenant-admin="isTenantAdmin" />
      <IngestView v-else-if="view === 'ingest'" :admin="isPlatformAdmin" :tenant-admin="isTenantAdmin" />
      <UsersView v-else-if="view === 'users'" />
      <DomainAccessView v-else-if="view === 'access'" />
      <ApiAccessView v-else-if="view === 'apiaccess'" />
      <ObserveView v-else-if="view === 'observe'" />
      <EvalView v-else-if="view === 'eval'" />
      <SettingsView v-else />
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 26px;
  height: 58px;
  padding: 0 24px;
  background: var(--nav);
  color: var(--nav-ink);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.06), 0 10px 30px rgba(15, 23, 42, 0.22);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  font-weight: 700;
  font-size: 15px;
  letter-spacing: 0.6px;
  color: #fff;
  white-space: nowrap;
}

.brand::before {
  content: "";
  width: 11px;
  height: 11px;
  border-radius: 3px;
  background: var(--warn, #f59e0b);
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.28);
}

nav {
  display: flex;
  gap: 4px;
}

nav span {
  font-size: 13.5px;
  color: var(--nav-ink);
  padding: 7px 13px;
  border-radius: var(--r-sm);
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--tr), color var(--tr);
}

nav span:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

nav span.active {
  color: #fff;
  background: var(--primary);
}

.boot {
  padding: 48px;
  color: var(--muted);
}

.denied {
  margin: auto;
  max-width: 460px;
  text-align: center;
  padding: 48px 24px;
}

.denied h1 {
  font-size: 20px;
  margin-bottom: 12px;
}

.denied p {
  color: var(--muted);
  margin-bottom: 20px;
}

.who {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12.5px;
  color: var(--nav-ink);
  white-space: nowrap;
}

.who button {
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: transparent;
  color: #fff;
  border-radius: var(--r-sm);
  padding: 5px 13px;
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: background var(--tr);
}

.who button:hover {
  background: rgba(255, 255, 255, 0.14);
}

main {
  flex: 1;
  padding: 26px 24px 56px;
}

@media (max-width: 760px) {
  .topbar {
    gap: 12px;
    padding: 0 14px;
    overflow-x: auto;
  }
  .brand {
    font-size: 14px;
  }
  .who span {
    display: none;
  }
  main {
    padding: 18px 14px 40px;
  }
}
</style>
