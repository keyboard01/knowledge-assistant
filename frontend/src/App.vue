<script setup>
import { computed, onMounted, ref } from 'vue'
import SettingsView from './views/SettingsView.vue'
import KernelView from './views/KernelView.vue'
import IngestView from './views/IngestView.vue'
import AskView from './views/AskView.vue'
import ObserveView from './views/ObserveView.vue'
import LoginView from './views/LoginView.vue'
import { authApi, getToken, setToken } from './api/client.js'

const view = ref('ask')
const user = ref(null)
const ready = ref(false)

// 运行记录接口限管理员（账本里存着全部人的提问原话），非管理员就别给一个点了必然报错的入口
const isAdmin = computed(() => user.value?.roleCode === 'admin')

async function loadUser() {
  if (!getToken()) {
    ready.value = true
    return
  }
  try {
    user.value = (await authApi.me()).user
  } catch {
    // 会话过期就回登录页，不弹错——这不是用户操作失误
    user.value = null
  } finally {
    ready.value = true
  }
}

function onSignedIn(signedIn) {
  user.value = signedIn
  view.value = 'ask'
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

onMounted(loadUser)
</script>

<template>
  <div v-if="!ready" class="boot">加载中…</div>
  <LoginView v-else-if="!user" @signed-in="onSignedIn" />
  <div v-else class="shell">
    <header class="topbar">
      <div class="brand">企业知识助手</div>
      <nav>
        <span :class="{ active: view === 'ask' }" @click="view = 'ask'">知识问答</span>
        <span :class="{ active: view === 'kernel' }" @click="view = 'kernel'">知识域与内核</span>
        <span :class="{ active: view === 'ingest' }" @click="view = 'ingest'">文档与同步</span>
        <span v-if="isAdmin" :class="{ active: view === 'observe' }" @click="view = 'observe'">运行记录</span>
        <span :class="{ active: view === 'settings' }" @click="view = 'settings'">系统配置</span>
      </nav>
      <div class="who">
        <span>{{ user.displayName || user.username }}（{{ user.roleCode }}）</span>
        <button type="button" @click="logout">退出</button>
      </div>
    </header>
    <main>
      <AskView v-if="view === 'ask'" />
      <KernelView v-else-if="view === 'kernel'" />
      <IngestView v-else-if="view === 'ingest'" />
      <ObserveView v-else-if="view === 'observe'" />
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
  background: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.28);
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
