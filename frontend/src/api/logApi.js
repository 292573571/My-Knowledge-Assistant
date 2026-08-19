import { authHeaders } from './authApi'

async function request(url, options, errorMessage) {
  const response = await fetch(url, { ...options, headers: { ...authHeaders(), 'Accept': 'application/json' } })
  if (!response.ok) throw new Error(errorMessage || `请求失败 (${response.status})`)
  return response.json()
}

export function fetchLogs({ page = 0, size = 100, level, keyword, hours = 2 } = {}) {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(size))
  params.set('hours', String(hours))
  if (level) params.set('level', level)
  if (keyword) params.set('keyword', keyword)
  return request(`/api/logs?${params}`, {}, '加载日志失败。')
}
