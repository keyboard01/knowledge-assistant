<script setup>
// 登录页：本地账号 + 令牌落 localStorage，刷新后由 App 调 /auth/me 复核。
// 知识域选择不在这里——它属于提问动作，放在 AskView 里跟着问题走。
import { ref } from 'vue'
import { authApi, setToken } from '../api/client.js'

const emit = defineEmits(['signed-in'])

const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请填写用户名和密码'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const result = await authApi.login(username.value.trim(), password.value)
    setToken(result.token)
    emit('signed-in', result.user)
  } catch (e) {
    error.value = e.message
    password.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <form class="login" @submit.prevent="submit">
      <div>
        <div class="brand">企业知识助手</div>
        <p class="sub">登录后按知识库内容作答，每条结论都可回溯到原文。</p>
      </div>
      <label class="field">
        <span>用户名</span>
        <input v-model="username" autocomplete="username" autofocus placeholder="admin / demo" />
      </label>
      <label class="field">
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" />
      </label>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" class="btn primary submit" :disabled="busy">{{ busy ? '登录中…' : '登录' }}</button>
      <p class="tip">
        账号不存在时：在 <code>deploy\.env</code> 设 <code>ASSISTANT_ADMIN_PASSWORD</code>（演示账号用
        <code>ASSISTANT_DEMO_PASSWORD</code>）后重启后端会自动创建。
      </p>
    </form>
  </div>
</template>

<style scoped>
.wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: radial-gradient(1100px 520px at 50% -8%, #e6edfb 0%, var(--bg) 60%);
}

.login {
  width: 100%;
  max-width: 390px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 32px 30px 26px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-2);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 19px;
  font-weight: 700;
  color: var(--ink);
}

.brand::before {
  content: "";
  width: 12px;
  height: 12px;
  border-radius: 3px;
  background: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.22);
}

.sub {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.7;
}

.error {
  margin: 0;
  font-size: 12.5px;
  color: var(--err);
}

.submit {
  width: 100%;
  height: 40px;
  margin-top: 2px;
}

.tip {
  margin: 0;
  color: var(--muted-2);
  font-size: 11.5px;
  line-height: 1.7;
}
</style>
