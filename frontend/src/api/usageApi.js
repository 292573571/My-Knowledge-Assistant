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
