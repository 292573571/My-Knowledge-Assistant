import { authHeaders } from './authApi'

async function request(url, options, errorMessage) {
  const response = await fetch(url, { ...options, headers: { ...authHeaders(), 'Accept': 'application/json' } })
  if (!response.ok) throw new Error(errorMessage || `请求失败 (${response.status})`)
  return response.json()
}

export function fetchLogs({ page = 0, size = 100, level, keyword, hours = 0 } = {}) {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(size))
  params.set('hours', String(hours))
  if (level) params.set('level', level)
  if (keyword) params.set('keyword', keyword)
  return request(`/api/logs?${params}`, {}, '加载日志失败。')
}

export function fetchAuditEvents(pagination = null) {
  const suffix = pagination ? `?page=${pagination.page ?? 0}&size=${pagination.size ?? 100}` : ''
  return request(`/api/audit-events${suffix}`, {}, '加载审计日志失败。')
}

export async function purgeAuditEvents() {
  const response = await fetch('/api/audit-events', {
    method: 'DELETE',
    headers: { ...authHeaders(), 'Accept': 'application/json' }
  })
  if (!response.ok) throw new Error('删除审计日志失败。')
  return response.json()
}

export async function clearLogs() {
  const response = await fetch('/api/logs', { method: 'DELETE', headers: { ...authHeaders(), 'Accept': 'application/json' } })
  if (!response.ok) throw new Error('清除日志失败。')
  return response.json()
}
