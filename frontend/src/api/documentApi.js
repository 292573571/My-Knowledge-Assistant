import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

function workspaceQuery(explicitWorkspaceId = '') {
  const workspaceId = explicitWorkspaceId || getActiveWorkspaceId()
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

export function fetchDocuments(workspaceId = '') {
  return request(`/api/documents${workspaceQuery(workspaceId)}`)
}

export function fetchDocumentContent(documentId, workspaceId = '') {
  return request(`/api/documents/${encodeURIComponent(documentId)}/content${workspaceQuery(workspaceId)}`)
}

export function uploadWorkspaceDocument(file, workspaceId = '') {
  const form = new FormData()
  form.append('file', file)
  return request(`/api/documents/upload${workspaceQuery(workspaceId)}`, {
    method: 'POST',
    body: form
  })
}

export function ingestDocuments(path = '', force = false, workspaceId = '') {
  return request(`/api/documents/ingest-directory${workspaceQuery(workspaceId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, force })
  })
}

export function ingestDocument(path, force = false, workspaceId = '') {
  return request(`/api/documents/ingest${workspaceQuery(workspaceId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, force })
  })
}

export function rebuildDocuments(workspaceId = '') {
  return request(`/api/documents/rebuild${workspaceQuery(workspaceId)}`, {
    method: 'POST'
  })
}

export function syncDocuments(workspaceId = '') {
  return request(`/api/documents/sync${workspaceQuery(workspaceId)}`, {
    method: 'POST'
  })
}

export function deleteDocument(documentId, workspaceId = '') {
  return request(`/api/documents/${encodeURIComponent(documentId)}${workspaceQuery(workspaceId)}`, {
    method: 'DELETE'
  })
}
