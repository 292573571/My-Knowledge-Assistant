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

// 将上传文件解析为 wiki 页面草稿（不落库），返回 { fileName, title, contentMd } 供编辑器填充。
export function importWikiPageFile(workspaceId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request(`/api/wiki-pages/import?workspaceId=${encodeURIComponent(workspaceId || '')}`, {
    method: 'POST',
    body: formData
  })
}
