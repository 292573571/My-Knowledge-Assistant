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

export function fetchDocuments(workspaceId = '', pagination = null) {
  const query = workspaceQuery(workspaceId)
  const suffix = pagination ? `${query ? '&' : '?'}page=${pagination.page ?? 0}&size=${pagination.size ?? 100}` : ''
  return request(`/api/documents${query}${suffix}`)
}

export function fetchDocumentContent(documentId, workspaceId = '') {
  return request(`/api/documents/${encodeURIComponent(documentId)}/content${workspaceQuery(workspaceId)}`)
}

export function uploadWorkspaceDocument(file, workspaceId = '', clientRequestId = '') {
  const form = new FormData()
  form.append('file', file)
  const workspace = workspaceQuery(workspaceId)
  const separator = workspace ? '&' : '?'
  return request(`/api/documents/upload${workspace}${separator}clientRequestId=${encodeURIComponent(clientRequestId)}`, {
    method: 'POST',
    body: form
  })
}

export function fetchDocumentTasks(workspaceId = '', pagination = null) {
  const query = workspaceQuery(workspaceId)
  const suffix = pagination ? `${query ? '&' : '?'}page=${pagination.page ?? 0}&size=${pagination.size ?? 100}` : ''
  return request(`/api/document-tasks${query}${suffix}`)
}

export function fetchDocumentTaskBatches(taskId, workspaceId = '') {
  return request(`/api/document-tasks/${encodeURIComponent(taskId)}/batches${workspaceQuery(workspaceId)}`)
}

export function retryDocumentTask(taskId, workspaceId = '') {
  return request(`/api/document-tasks/${encodeURIComponent(taskId)}/retry${workspaceQuery(workspaceId)}`, {
    method: 'POST'
  })
}

export async function fetchDocumentTaskSource(taskId, workspaceId = '') {
  let response
  try {
    response = await fetch(`/api/document-tasks/${encodeURIComponent(taskId)}/source${workspaceQuery(workspaceId)}`, {
      credentials: 'include',
      headers: authHeaders()
    })
  } catch (error) {
    throw apiErrorFromException(error, '无法读取文档源文件。')
  }
  if (!response.ok) throw await apiErrorFromResponse(response, '文档源文件不可用。')
  return response.blob()
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
