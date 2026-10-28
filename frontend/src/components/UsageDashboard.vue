<template>
  <div class="usage-shell">
    <header class="usage-hero" data-reveal>
      <div class="usage-hero-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M4 20V10" /><path d="M10 20V4" /><path d="M16 20v-7" /><path d="M22 20H2" />
        </svg>
      </div>
      <div class="usage-hero-body">
        <h1>用量仪表盘</h1>
        <p>你的模型调用与 token 消耗统计。数据按天汇总，仅自己可见。</p>
      </div>
      <div class="usage-hero-aside">
        <div class="usage-range" role="group" aria-label="统计时间范围">
          <button
            v-for="option in rangeOptions"
            :key="option"
            type="button"
            :class="{ active: days === option }"
            @click="setDays(option)"
          >{{ option }} 天</button>
        </div>
      </div>
    </header>

    <p v-if="error" class="usage-alert error">{{ error }}</p>
    <p v-else-if="loading" class="usage-alert loading">正在加载用量数据…</p>

    <template v-else>
      <section class="usage-cards" aria-label="用量汇总">
        <div class="usage-card">
          <span class="usage-card-label">模型调用</span>
          <strong class="usage-card-value">{{ fmtInt(summary?.totals?.calls) }}</strong>
          <span class="usage-card-sub">成功 {{ fmtInt(summary?.totals?.successCalls) }} · 失败 {{ fmtInt(summary?.totals?.failedCalls) }}</span>
        </div>
        <div class="usage-card">
          <span class="usage-card-label">总 Tokens</span>
          <strong class="usage-card-value">{{ fmtTokens(summary?.totals?.totalTokens) }}</strong>
          <span class="usage-card-sub">输入 {{ fmtTokens(summary?.totals?.promptTokens) }} · 输出 {{ fmtTokens(summary?.totals?.completionTokens) }}</span>
        </div>
        <div class="usage-card">
          <span class="usage-card-label">平均耗时</span>
          <strong class="usage-card-value">{{ fmtDuration(summary?.totals?.avgDurationMs) }}</strong>
          <span class="usage-card-sub">累计 {{ fmtDuration(summary?.totals?.totalDurationMs) }}</span>
        </div>
        <div class="usage-card">
          <span class="usage-card-label">检索命中率</span>
          <strong class="usage-card-value">{{ fmtPercent(summary?.retrieval?.hitRate) }}</strong>
          <span class="usage-card-sub">回答 {{ fmtInt(summary?.retrieval?.assistantMessages) }} 次 · 带来源 {{ fmtInt(summary?.retrieval?.answersWithSources) }} 次</span>
        </div>
      </section>

      <section class="usage-panel" aria-label="每日消耗">
        <div class="usage-panel-head">
          <h2>每日 Token 消耗</h2>
          <div class="usage-legend">
            <span><i class="dot prompt"></i>输入</span>
            <span><i class="dot completion"></i>输出</span>
          </div>
        </div>
        <div v-if="!dailyPoints.length" class="usage-empty">
          <p>这个时间段还没有模型调用记录。</p>
          <span>去 AI 助手问几个问题，第二天就能在这里看到曲线。</span>
        </div>
        <div v-else class="usage-chart" role="img" aria-label="每日 token 消耗柱状图">
          <div v-for="point in dailyPoints" :key="point.day" class="usage-bar-slot">
            <div class="usage-bar-track">
              <div class="usage-bar-stack" :style="{ height: barHeight(point) }">
                <div class="usage-bar prompt" :style="{ height: promptShare(point) }"></div>
                <div class="usage-bar completion"></div>
              </div>
            </div>
            <span class="usage-bar-label">{{ dayLabel(point.day) }}</span>
          </div>
        </div>
      </section>

      <section class="usage-panel" aria-label="模型分布">
        <div class="usage-panel-head">
          <h2>模型分布</h2>
        </div>
        <div v-if="!modelRows.length" class="usage-empty"><p>暂无模型调用数据。</p></div>
        <table v-else class="usage-table">
          <thead>
            <tr><th>模型</th><th class="num">调用</th><th class="num">输入</th><th class="num">输出</th><th class="num">平均耗时</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in modelRows" :key="row.model">
              <td class="usage-model-name" :title="row.model">{{ shortModel(row.model) }}</td>
              <td class="num">{{ fmtInt(row.calls) }}</td>
              <td class="num">{{ fmtTokens(row.promptTokens) }}</td>
              <td class="num">{{ fmtTokens(row.completionTokens) }}</td>
              <td class="num">{{ fmtDuration(row.avgDurationMs) }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { fetchUsageSummary } from '../api/usageApi'

const rangeOptions = [7, 30, 90]
const days = ref(30)
const summary = ref(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    summary.value = await fetchUsageSummary(days.value)
  } catch (exception) {
    error.value = formatApiError(exception, '用量数据加载失败。')
  } finally {
    loading.value = false
  }
}

function setDays(option) {
  if (days.value === option) return
  days.value = option
  load()
}

onMounted(load)

const dailyPoints = computed(() => summary.value?.daily ?? [])
const modelRows = computed(() => summary.value?.models ?? [])

const maxDayTokens = computed(() => Math.max(
  1,
  ...dailyPoints.value.map(point => (point.promptTokens || 0) + (point.completionTokens || 0))
))

function barHeight(point) {
  const total = (point.promptTokens || 0) + (point.completionTokens || 0)
  if (!total) return '2px'
  return `${Math.max(4, Math.round((total / maxDayTokens.value) * 150))}px`
}

function promptShare(point) {
  const total = (point.promptTokens || 0) + (point.completionTokens || 0)
  if (!total) return '100%'
  return `${Math.round(((point.promptTokens || 0) / total) * 100)}%`
}

function dayLabel(day) {
  if (!day) return ''
  const date = new Date(`${day}T00:00:00`)
  return `${date.getMonth() + 1}/${date.getDate()}`
}

function shortModel(model) {
  if (!model) return '未知'
  const slashIndex = model.lastIndexOf('/')
  return slashIndex >= 0 && slashIndex < model.length - 1 ? model.slice(slashIndex + 1) : model
}

function fmtInt(value) {
  return typeof value === 'number' ? value.toLocaleString('zh-CN') : '0'
}

function fmtTokens(value) {
  const num = typeof value === 'number' ? value : 0
  if (num >= 100000000) return `${(num / 100000000).toFixed(1)} 亿`
  if (num >= 10000) return `${(num / 10000).toFixed(1)} 万`
  return num.toLocaleString('zh-CN')
}

function fmtDuration(value) {
  const num = typeof value === 'number' ? value : 0
  if (num >= 60000) return `${Math.round(num / 60000)} 分 ${Math.round((num % 60000) / 1000)} 秒`
  if (num >= 1000) return `${(num / 1000).toFixed(1)} 秒`
  return `${num} ms`
}

function fmtPercent(value) {
  const num = typeof value === 'number' ? value : 0
  return `${Math.round(num * 100)}%`
}
</script>

<style scoped>
/* ============ 设计令牌：与知识库共用绿系语义色 ============ */
.usage-shell {
  --wk-green: #2f6b49;
  --wk-green-soft: #eaf3e7;
  --wk-green-line: #cfe3d2;
  --wk-line: #dce5f2;
  --wk-line-soft: #e9eef6;
  --wk-ink: #1d2940;
  --wk-ink-soft: #52607a;
  --wk-muted: #7c879b;
  --wk-surface: #ffffff;
  --wk-radius: 14px;

  display: flex;
  flex-direction: column;
  gap: 12px;
}

.usage-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 4px 10px;
  flex-wrap: wrap;
}

.usage-hero-icon {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  color: var(--wk-green);
  background: var(--wk-green-soft);
}

.usage-hero-icon svg { width: 22px; height: 22px; }

.usage-hero-body { flex: 1 1 320px; min-width: 0; }
.usage-hero-body h1 { margin: 0; color: var(--wk-ink); font-size: 22px; letter-spacing: -0.01em; }
.usage-hero-body p { margin: 6px 0 0; color: var(--wk-muted); font-size: 13px; line-height: 1.6; }

.usage-range {
  display: inline-flex;
  padding: 3px;
  gap: 2px;
  border: 1px solid var(--wk-line);
  border-radius: 10px;
  background: var(--wk-surface);
}

.usage-range button {
  border: 0;
  background: transparent;
  padding: 6px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--wk-ink-soft);
  cursor: pointer;
}

.usage-range button.active {
  color: #fff;
  background: var(--wk-green);
}

.usage-alert {
  margin: 0;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13px;
}

.usage-alert.error { color: #a13a3a; background: #fbeeee; border: 1px solid #eccccc; }
.usage-alert.loading { color: var(--wk-ink-soft); background: var(--wk-surface); border: 1px solid var(--wk-line); }

.usage-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.usage-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 16px 18px;
  border: 1px solid var(--wk-line);
  border-radius: var(--wk-radius);
  background: var(--wk-surface);
}

.usage-card-label { font-size: 12px; font-weight: 700; color: var(--wk-muted); letter-spacing: 0.02em; }
.usage-card-value { font-size: 26px; font-weight: 700; color: var(--wk-ink); letter-spacing: -0.01em; }
.usage-card-sub { font-size: 12px; color: var(--wk-muted); }

.usage-panel {
  border: 1px solid var(--wk-line);
  border-radius: var(--wk-radius);
  background: var(--wk-surface);
  padding: 16px 18px;
}

.usage-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.usage-panel-head h2 { margin: 0; font-size: 15px; color: var(--wk-ink); }

.usage-legend {
  display: inline-flex;
  gap: 14px;
  font-size: 12px;
  color: var(--wk-muted);
}

.usage-legend span { display: inline-flex; align-items: center; gap: 5px; }
.usage-legend .dot { width: 9px; height: 9px; border-radius: 3px; display: inline-block; }
.usage-legend .dot.prompt { background: #bcd9c2; }
.usage-legend .dot.completion { background: var(--wk-green); }

.usage-empty {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 18px 0;
  text-align: center;
}

.usage-empty p { margin: 0; font-size: 14px; font-weight: 600; color: var(--wk-ink-soft); }
.usage-empty span { font-size: 12px; color: var(--wk-muted); }

.usage-chart {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 190px;
  overflow-x: auto;
  padding-top: 8px;
}

.usage-bar-slot {
  flex: 1 0 26px;
  max-width: 44px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.usage-bar-track {
  display: flex;
  align-items: flex-end;
  height: 150px;
  width: 100%;
  justify-content: center;
}

.usage-bar-stack {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  width: 60%;
  max-width: 26px;
  min-height: 2px;
  border-radius: 5px 5px 2px 2px;
  overflow: hidden;
}

.usage-bar { width: 100%; }
.usage-bar.prompt { background: #bcd9c2; }
.usage-bar.completion { background: var(--wk-green); flex: 1; }

.usage-bar-label {
  font-size: 10px;
  color: var(--wk-muted);
  white-space: nowrap;
}

.usage-table { width: 100%; border-collapse: collapse; font-size: 13px; }

.usage-table th {
  text-align: left;
  padding: 8px 10px;
  color: var(--wk-muted);
  font-size: 12px;
  font-weight: 700;
  border-bottom: 1px solid var(--wk-line);
}

.usage-table td {
  padding: 10px;
  border-bottom: 1px solid var(--wk-line-soft);
  color: var(--wk-ink-soft);
}

.usage-table tr:last-child td { border-bottom: 0; }
.usage-table .num { text-align: right; font-variant-numeric: tabular-nums; }
.usage-model-name { font-weight: 600; color: var(--wk-ink); max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 640px) {
  .usage-cards { grid-template-columns: repeat(2, 1fr); }
  .usage-card-value { font-size: 22px; }
}
</style>
