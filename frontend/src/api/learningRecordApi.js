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

export function fetchLearningRecords(workspaceId, pagination = null) {
  const suffix = pagination ? `&page=${pagination.page ?? 0}&size=${pagination.size ?? 100}` : ''
  return request(`/api/learning-records?workspaceId=${encodeURIComponent(workspaceId || '')}${suffix}`)
}

export function fetchTeachingProgress(workspaceId) {
  return request(`/api/learning-records/teaching-progress?workspaceId=${encodeURIComponent(workspaceId || '')}`)
}

export function fetchLearningRecord(date, workspaceId) {
  return request(`/api/learning-records/${encodeURIComponent(date)}?workspaceId=${encodeURIComponent(workspaceId || '')}`)
}

export function updateLearningRecord(date, workspaceId, content) {
  return request(`/api/learning-records/${encodeURIComponent(date)}?workspaceId=${encodeURIComponent(workspaceId || '')}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content })
  })
}

export function deleteLearningRecord(date, workspaceId) {
  return request(`/api/learning-records/${encodeURIComponent(date)}?workspaceId=${encodeURIComponent(workspaceId || '')}`, { method: 'DELETE' })
}

export function promoteLearningRecord(date, workspaceId, content) {
  return request(`/api/learning-records/${encodeURIComponent(date)}/promote?workspaceId=${encodeURIComponent(workspaceId || '')}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content })
  })
}
