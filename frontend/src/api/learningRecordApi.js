import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, { ...options, credentials: 'include', headers: authHeaders(options.headers) })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务。')
  }
  if (!response.ok) throw await apiErrorFromResponse(response, '学习记录操作失败。')
  if (response.status === 204 || response.headers.get('content-length') === '0') return null
  return response.json()
}

export function fetchLearningRecords() {
  return request('/api/learning-records')
}

export function fetchLearningRecord(date) {
  return request(`/api/learning-records/${encodeURIComponent(date)}`)
}

export function updateLearningRecord(date, content) {
  return request(`/api/learning-records/${encodeURIComponent(date)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content })
  })
}

export function deleteLearningRecord(date) {
  return request(`/api/learning-records/${encodeURIComponent(date)}`, { method: 'DELETE' })
}

export function promoteLearningRecord(date, content) {
  return request(`/api/learning-records/${encodeURIComponent(date)}/promote`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content })
  })
}
