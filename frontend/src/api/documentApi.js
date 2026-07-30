import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

function workspaceQuery() {
  const workspaceId = getActiveWorkspaceId()
  return workspaceId ? `?workspaceId=${encodeURIComponent(workspaceId)}` : ''
}

async function request(path, options = {}) {
  let response

  try {
    response = await fetch(path, { ...options, credentials: 'include', headers: authHeaders(options.headers) })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务，请确认 Spring Boot 已启动。')
  }

  if (!response.ok) {
    const apiError = await apiErrorFromResponse(response, `请求失败: ${response.status}`)
    throw apiError
  }

  const requestId = response.headers.get('X-Request-Id') || ''

  const responseText = await response.text()
  if (response.status === 204 || !responseText.trim()) {
    return null
  }

  const data = JSON.parse(responseText)
  if (Array.isArray(data)) {
    return data
  }

  return data && typeof data === 'object' ? { ...data, requestId } : data
}

export function fetchDocuments() {
  return request(`/api/documents${workspaceQuery()}`)
}

export function fetchDocumentContent(documentId) {
  return request(`/api/documents/${encodeURIComponent(documentId)}/content${workspaceQuery()}`)
}

export function uploadWorkspaceDocument(file) {
  const form = new FormData()
  form.append('file', file)
  return request(`/api/documents/upload${workspaceQuery()}`, {
    method: 'POST',
    body: form
  })
}

export function ingestDocuments(path = '', force = false) {
  return request(`/api/documents/ingest-directory${workspaceQuery()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, force })
  })
}

export function ingestDocument(path, force = false) {
  return request(`/api/documents/ingest${workspaceQuery()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, force })
  })
}

export function rebuildDocuments() {
  return request(`/api/documents/rebuild${workspaceQuery()}`, {
    method: 'POST'
  })
}

export function syncDocuments() {
  return request(`/api/documents/sync${workspaceQuery()}`, {
    method: 'POST'
  })
}

export function deleteDocument(documentId) {
  return request(`/api/documents/${encodeURIComponent(documentId)}${workspaceQuery()}`, {
    method: 'DELETE'
  })
}
