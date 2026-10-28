import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, { ...options, credentials: 'include', headers: authHeaders(options.headers) })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务。')
  }
  if (!response.ok) throw await apiErrorFromResponse(response, '用量数据加载失败。')
  return response.json()
}

export function fetchUsageSummary(days = 30) {
  return request(`/api/usage/summary?days=${encodeURIComponent(days)}`)
}

export function fetchUsageModelStats(days = 30, scope = 'self') {
  return request(`/api/usage/model-stats?days=${encodeURIComponent(days)}&scope=${encodeURIComponent(scope)}`)
}

function fmtTokens(value) {
  const num = typeof value === 'number' ? value : 0
  if (num >= 100000000) return `${(num / 100000000).toFixed(1)} 亿`
  if (num >= 10000) return `${(num / 10000).toFixed(1)} 万`
  return num.toLocaleString('zh-CN')
}

function fmtDuration(value) {
  const num = typeof value === 'number' ? value : 0
  if (num >= 1000) return `${(num / 1000).toFixed(1)} 秒`
  return `${num} ms`
}

export function formatModelUsage(stats) {
  if (!stats || !stats.calls) return '近 30 天暂无调用'
  return `近 30 天：调用 ${stats.calls.toLocaleString('zh-CN')} 次 · 输入 ${fmtTokens(stats.promptTokens)} · 输出 ${fmtTokens(stats.completionTokens)} · 平均 ${fmtDuration(stats.avgDurationMs)}`
}
