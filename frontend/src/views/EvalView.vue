<script setup>
// 评测页（P3）：跑题集、看趋势、看失败用例。
//
// 三条口径：
//   1. retrieval 模式只跑检索（秒级、不花生成费），full 模式全链路（几分钟、真调模型）——
//      按钮上要写清楚，别让人随手点出一个花几百块的测试。
//   2. 指标只在同一题集内可比：跨题集的通过率没有可比性，所以 Δ 只跟"上一次同题集的 run"比。
//   3. 泄露数不为 0 就是硬回归，界面上必须比其它指标更显眼。
import { computed, onMounted, ref } from 'vue'
import { evalApi } from '../api/client.js'

const runs = ref([])
const sets = ref([])
const busy = ref('')
const error = ref('')
const note = ref('')
const selectedSet = ref('')
const mode = ref('retrieval')
const detail = ref(null)
const detailBusy = ref(false)

const latest = computed(() => runs.value[0] || null)

/** 上一次**同题集**的 run（下标 0 是自己）。跨题集比通过率等于拿两次不同的考试比分数。 */
const baseline = computed(() => {
  if (!latest.value) return null
  return runs.value.slice(1).find((r) => r.questionSet === latest.value.questionSet) || null
})

function pct(value) {
  return value === null || value === undefined ? '—' : `${(value * 100).toFixed(1)}%`
}

function delta(current, previous) {
  if (current === null || current === undefined || previous === null || previous === undefined) return null
  return current - previous
}

function deltaText(deltaValue, digits = 1) {
  if (deltaValue === null) return ''
  const points = (deltaValue * 100).toFixed(digits)
  return deltaValue >= 0 ? `▲${points}` : `▼${points}`
}

function deltaClass(deltaValue) {
  if (deltaValue === null) return 'flat'
  if (deltaValue > 0.0001) return 'up'
  if (deltaValue < -0.0001) return 'down'
  return 'flat'
}

function fmtTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function fmtCost(value) {
  return value === null || value === undefined ? '未计费' : `${Number(value).toFixed(4)} 元`
}

function fmtScore(value) {
  return value === null || value === undefined ? '—' : Number(value).toFixed(3)
}

function statusPill(row) {
  if (row.status === 'FAILED') return 'err'
  if (row.leakCount > 0) return 'err'
  if (row.failed > 0) return 'warn'
  return 'ok'
}

async function load() {
  busy.value = 'load'
  error.value = ''
  try {
    const data = await evalApi.runs({ limit: 30, set: selectedSet.value })
    runs.value = data.runs || []
    sets.value = data.sets || []
    if (!selectedSet.value && sets.value.length) selectedSet.value = sets.value[0]
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function runEval() {
  if (!selectedSet.value) {
    error.value = '请先选择题集'
    return
  }
  if (mode.value === 'full' && !window.confirm(
    'full 模式会真调生成模型：30 题大约几分钟，并产生模型费用。确定继续？')) {
    return
  }
  busy.value = 'run'
  error.value = ''
  note.value = mode.value === 'full' ? '正在跑全链路（每题一次问答），请勿关闭页面…' : ''
  try {
    const result = await evalApi.run(selectedSet.value, mode.value)
    note.value = `完成：通过 ${result.run.passed}/${result.run.total}，hit@k ${pct(result.run.hitAtK)}`
    await load()
    detail.value = result
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function openDetail(row) {
  detailBusy.value = true
  error.value = ''
  try {
    detail.value = await evalApi.detail(row.id)
  } catch (e) {
    error.value = e.message
  } finally {
    detailBusy.value = false
  }
}

/** 失败用例按原因归类：同类原因堆在一起，才看得出"这次是哪一环掉了"。 */
const failures = computed(() => {
  const cases = detail.value?.cases || []
  return cases.filter((item) => !item.ok)
})

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>评测</h1>
        <p class="desc">
          用固定题集跑一遍并记录指标。<b>retrieval</b> 只跑检索（秒级、不花钱），
          <b>full</b> 走完整问答（会真调模型）。指标落库，可跨次对比；泄露数不为 0 即权限回归。
        </p>
      </div>
      <div class="controls">
        <select v-model="selectedSet" :disabled="!!busy">
          <option v-for="name in sets" :key="name" :value="name">{{ name }}</option>
        </select>
        <select v-model="mode" :disabled="!!busy">
          <option value="retrieval">retrieval（只检索）</option>
          <option value="full">full（全链路）</option>
        </select>
        <button class="btn primary" :disabled="!!busy || !selectedSet" @click="runEval">
          {{ busy === 'run' ? '运行中…' : '运行评测' }}
        </button>
        <button class="btn tiny" :disabled="!!busy" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="error" class="banner err">{{ error }}</div>
    <div v-if="note" class="banner info">{{ note }}</div>

    <section v-if="latest" class="stats">
      <div><span>通过</span><b>{{ latest.passed }}/{{ latest.total }}</b></div>
      <div>
        <span>hit@k</span><b>{{ pct(latest.hitAtK) }}</b>
        <em v-if="baseline" :class="deltaClass(delta(latest.hitAtK, baseline.hitAtK))">
          {{ deltaText(delta(latest.hitAtK, baseline.hitAtK)) }}
        </em>
      </div>
      <div>
        <span>MRR</span><b>{{ fmtScore(latest.mrr) }}</b>
        <em v-if="baseline" :class="deltaClass(delta(latest.mrr, baseline.mrr))">
          {{ delta(latest.mrr, baseline.mrr) === null ? '' : delta(latest.mrr, baseline.mrr).toFixed(3) }}
        </em>
      </div>
      <div>
        <span>引用正确率</span><b>{{ pct(latest.citationAccuracy) }}</b>
      </div>
      <div>
        <span>拒答准确率</span><b>{{ pct(latest.refusalAccuracy) }}</b>
      </div>
      <div>
        <span>泄露</span>
        <b :class="latest.leakCount > 0 ? 'bad' : ''">{{ latest.leakCount }}</b>
      </div>
      <div><span>平均耗时</span><b>{{ latest.avgElapsedMs }}ms</b></div>
      <div><span>费用</span><b>{{ fmtCost(latest.costTotal) }}</b></div>
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>历史</h2>
          <p class="desc">
            趋势条按同题集内的 hit@k 画（0~100%）；点某一行看用例明细。
            <template v-if="baseline">Δ 对比的是同题集的上一次：{{ fmtTime(baseline.createdAt) }}。</template>
          </p>
        </div>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>时间</th>
              <th>题集</th>
              <th>模式</th>
              <th>状态</th>
              <th>通过</th>
              <th>hit@k</th>
              <th>趋势</th>
              <th>MRR</th>
              <th>引用</th>
              <th>拒答</th>
              <th>泄露</th>
              <th>耗时</th>
              <th>费用</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in runs" :key="row.id">
              <td class="nowrap">{{ fmtTime(row.createdAt) }}</td>
              <td class="nowrap">{{ row.questionSet }}</td>
              <td class="nowrap">{{ row.mode }}</td>
              <td><span :class="['pill', statusPill(row)]">{{ row.status }}</span></td>
              <td class="nowrap">{{ row.passed }}/{{ row.total }}</td>
              <td class="nowrap">{{ pct(row.hitAtK) }}</td>
              <td class="bar-cell">
                <div class="bar"><span :style="{ width: `${Math.round((row.hitAtK || 0) * 100)}%` }"></span></div>
              </td>
              <td class="nowrap">{{ fmtScore(row.mrr) }}</td>
              <td class="nowrap">{{ pct(row.citationAccuracy) }}</td>
              <td class="nowrap">{{ pct(row.refusalAccuracy) }}</td>
              <td class="nowrap" :class="{ bad: row.leakCount > 0 }">{{ row.leakCount }}</td>
              <td class="nowrap">{{ row.avgElapsedMs }}ms</td>
              <td class="nowrap">{{ fmtCost(row.costTotal) }}</td>
              <td><button class="btn tiny" :disabled="detailBusy" @click="openDetail(row)">明细</button></td>
            </tr>
            <tr v-if="!runs.length">
              <td colspan="14" class="empty">还没有评测记录。选好题集与模式，点「运行评测」。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="detail" class="card">
      <header class="card-head">
        <div>
          <h2>用例明细 #{{ detail.run.id }}</h2>
          <p class="desc">
            {{ detail.run.questionSet }} · {{ detail.run.mode }} · 失败 {{ failures.length }} 题
            <template v-if="failures.length">（下方只列失败用例）</template>
          </p>
        </div>
        <button class="btn tiny" @click="detail = null">关闭</button>
      </header>
      <div class="table-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>题号</th>
              <th>题型</th>
              <th>提问人</th>
              <th>问题</th>
              <th>期望</th>
              <th>实际</th>
              <th>命中位次</th>
              <th>最高分</th>
              <th>失败原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in failures" :key="item.caseId" :class="{ leaked: item.leaked }">
              <td class="nowrap">{{ item.caseId }}</td>
              <td class="nowrap">{{ item.reasoning || '—' }}</td>
              <td class="nowrap">{{ item.asker }}</td>
              <td class="question">{{ item.question }}</td>
              <td class="nowrap">{{ item.expectedOutcome }}</td>
              <td class="nowrap">{{ item.actualOutcome }}</td>
              <td class="nowrap">{{ item.docRank || '—' }}</td>
              <td class="nowrap">{{ fmtScore(item.bestScore) }}</td>
              <td class="reason">{{ item.failureReason }}</td>
            </tr>
            <tr v-if="!failures.length">
              <td colspan="9" class="empty">这次没有失败用例。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.controls { display: flex; gap: 8px; align-items: center; flex: none; }

.controls select {
  padding: 6px 8px;
  font-size: 13px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
}

.stats { display: flex; flex-wrap: wrap; gap: 18px; margin: 4px 0 16px; }

.stats div {
  min-width: 108px;
  padding: 10px 14px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.stats span { display: block; font-size: 12px; color: var(--muted); }
.stats b { font-size: 16px; color: var(--ink); }
.stats em { display: block; font-size: 11.5px; font-style: normal; }
.stats em.up { color: var(--ok); }
.stats em.down { color: var(--err); }
.stats em.flat { color: var(--muted); }
.stats b.bad { color: var(--err); }

.bar-cell { width: 120px; }
.bar { height: 8px; background: #eef2f9; border-radius: 4px; overflow: hidden; }
.bar span { display: block; height: 100%; background: var(--brand); }

.bad { color: var(--err); font-weight: 600; }
.empty { color: var(--muted); text-align: center; padding: 18px 0; }
.question { max-width: 320px; }
.reason { color: var(--err); font-size: 12.5px; }
tr.leaked td { background: #fdf3f3; }

.grid { width: 100%; border-collapse: collapse; font-size: 13px; }
.grid th, .grid td { padding: 7px 10px; border-bottom: 1px solid var(--line); text-align: left; }
.grid th { color: var(--muted); font-weight: 600; background: var(--surface-2); }
.nowrap { white-space: nowrap; }
</style>
