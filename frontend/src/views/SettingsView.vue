<script setup>
import { onMounted, reactive, ref } from 'vue'
import { settingsApi } from '../api/client.js'

const groups = ref([])
const edit = reactive({})
const busy = reactive({})
const feedback = reactive({})
const loadError = ref('')

function initEdit(group) {
  edit[group.code] = {}
  for (const field of group.fields) {
    edit[group.code][field.key] = field.secret ? '' : field.value
  }
}

function replaceGroup(updated) {
  const index = groups.value.findIndex((g) => g.code === updated.code)
  if (index >= 0) {
    groups.value[index] = updated
  }
  initEdit(updated)
}

async function load() {
  loadError.value = ''
  try {
    const data = await settingsApi.list()
    groups.value = data.groups
    groups.value.forEach(initEdit)
  } catch (error) {
    loadError.value = error.message
  }
}

async function save(group) {
  busy[group.code] = 'save'
  feedback[group.code] = null
  try {
    const updated = await settingsApi.save(group.code, { ...edit[group.code] })
    replaceGroup(updated)
    feedback[group.code] = { ok: true, message: '已保存到服务端' }
  } catch (error) {
    feedback[group.code] = { ok: false, message: error.message }
  } finally {
    busy[group.code] = null
  }
}

async function test(group) {
  busy[group.code] = 'test'
  feedback[group.code] = null
  try {
    const result = await settingsApi.test(group.code)
    feedback[group.code] = {
      ok: result.ok,
      message: result.message,
      detail: result.detail
    }
  } catch (error) {
    feedback[group.code] = { ok: false, message: error.message }
  } finally {
    busy[group.code] = null
  }
}

async function clearField(group, field) {
  busy[group.code] = 'clear'
  try {
    const updated = await settingsApi.clear(group.code, field.key)
    replaceGroup(updated)
    feedback[group.code] = { ok: true, message: `${field.label} 已清空` }
  } catch (error) {
    feedback[group.code] = { ok: false, message: error.message }
  } finally {
    busy[group.code] = null
  }
}

function placeholderFor(field) {
  if (!field.secret) return field.hint || ''
  return field.hasValue ? `已配置：${field.mask}（留空表示不修改）` : '未配置，请填写'
}

onMounted(load)
</script>

<template>
  <div class="page narrow">
    <div class="page-head">
      <div>
        <h1>系统配置</h1>
        <p class="desc">密钥只保存到服务端数据库，页面仅回显掩码；密钥字段留空表示不修改。</p>
      </div>
    </div>

    <div v-if="loadError" class="banner err">加载失败：{{ loadError }}</div>

    <section v-for="group in groups" :key="group.code" class="card">
      <header class="card-head">
        <div>
          <h2>{{ group.title }}</h2>
          <p class="desc">{{ group.description }}</p>
        </div>
        <code class="code">{{ group.code }}</code>
      </header>

      <div class="fields">
        <div v-for="field in group.fields" :key="field.key" class="field">
          <label :for="`${group.code}-${field.key}`">
            {{ field.label }}
            <span v-if="field.secret" class="tag">密钥</span>
          </label>
          <div class="input-row">
            <input
              :id="`${group.code}-${field.key}`"
              v-model="edit[group.code][field.key]"
              :type="field.secret ? 'password' : 'text'"
              :placeholder="placeholderFor(field)"
              autocomplete="off"
              spellcheck="false"
            />
            <button
              v-if="field.secret && field.hasValue"
              class="btn ghost"
              :disabled="busy[group.code] === 'clear'"
              @click="clearField(group, field)"
            >
              清空
            </button>
          </div>
          <small v-if="field.hint">{{ field.hint }}</small>
        </div>
      </div>

      <footer class="card-foot">
        <button class="btn primary" :disabled="busy[group.code] === 'save'" @click="save(group)">
          {{ busy[group.code] === 'save' ? '保存中…' : '保存' }}
        </button>
        <button class="btn" :disabled="busy[group.code] === 'test'" @click="test(group)">
          {{ busy[group.code] === 'test' ? '测试中…' : '测试连接' }}
        </button>
      </footer>

      <div v-if="feedback[group.code]" :class="['banner', feedback[group.code].ok ? 'ok' : 'err']">
        {{ feedback[group.code].message }}
        <pre v-if="feedback[group.code].detail" class="detail">{{ feedback[group.code].detail }}</pre>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 这一页独有：输入行（输入框 + 清空按钮）、密钥标记、测试详情 */
.input-row {
  display: flex;
  gap: 8px;
}

.input-row input {
  flex: 1;
}

.input-row .btn {
  flex: none;
}

.field small {
  display: block;
  margin-top: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

/* "密钥" 标记：红色区别于普通标签 */
.tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: var(--err);
  border: 1px solid var(--err-line);
  background: var(--err-bg);
  border-radius: 4px;
  padding: 0 6px;
}

.detail {
  margin: 8px 0 0;
  padding: 10px 12px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
