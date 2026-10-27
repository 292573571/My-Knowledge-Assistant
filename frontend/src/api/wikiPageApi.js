import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, { ...options, credentials: 'include', headers: authHeaders(options.headers) })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务。')
  }
  if (!response.ok) throw await apiErrorFromResponse(response, 'wiki 页面操作失败。')
  if (response.status === 204 || response.headers.get('content-length') === '0') return null
  return response.json()
}

export function fetchWikiPages(workspaceId) {
  return request(`/api/wiki-pages?workspaceId=${encodeURIComponent(workspaceId || '')}`)
}

export function fetchWikiPage(id, workspaceId) {
  return request(`/api/wiki-pages/${encodeURIComponent(id)}?workspaceId=${encodeURIComponent(workspaceId || '')}`)
}

export function createWikiPage(workspaceId, body) {
  return request(`/api/wiki-pages?workspaceId=${encodeURIComponent(workspaceId || '')}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
}

export function updateWikiPage(id, workspaceId, body) {
  return request(`/api/wiki-pages/${encodeURIComponent(id)}?workspaceId=${encodeURIComponent(workspaceId || '')}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
}

export function deleteWikiPage(id, workspaceId) {
  return request(`/api/wiki-pages/${encodeURIComponent(id)}?workspaceId=${encodeURIComponent(workspaceId || '')}`, { method: 'DELETE' })
}

// 受理 wiki 导入任务：立即返回 { taskId }，后台异步解析（大文件 OCR 可能耗时数分钟）。
export function importWikiPageFile(workspaceId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request(`/api/wiki-pages/import?workspaceId=${encodeURIComponent(workspaceId || '')}`, {
    method: 'POST',
    body: formData
  })
}

// 轮询导入任务：RUNNING / SUCCEEDED（含草稿）/ FAILED；任务不存在返回 404。
export function fetchWikiImportTask(workspaceId, taskId) {
  return request(`/api/wiki-pages/import/${encodeURIComponent(taskId)}?workspaceId=${encodeURIComponent(workspaceId || '')}`)
}
