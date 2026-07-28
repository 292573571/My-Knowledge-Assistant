import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

async function request(path, options = {}) {
  const method = options.method || 'GET'
  const startedAt = performance.now()

  console.debug('[api] request', {
    method,
    path,
    body: options.body ? safeJsonParse(options.body) : null
  })

  let response

  try {
    response = await fetch(path, { ...options, headers: authHeaders(options.headers) })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务，请确认 Spring Boot 已启动。')
  }

  if (!response.ok) {
    const apiError = await apiErrorFromResponse(response, `请求失败: ${response.status}`)
    console.error('[api] response error', {
      method,
      path,
      status: response.status,
      requestId: apiError.requestId,
      durationMs: Math.round(performance.now() - startedAt)
    })
    throw apiError
  }

  const requestId = response.headers.get('X-Request-Id') || ''

  const responseText = await response.text()
  if (response.status === 204 || !responseText.trim()) {
    console.debug('[api] response', {
      method,
      path,
      status: response.status,
      requestId,
      durationMs: Math.round(performance.now() - startedAt),
      data: null
    })
    return null
  }

  const data = JSON.parse(responseText)
  console.debug('[api] response', {
    method,
    path,
    status: response.status,
    requestId,
    durationMs: Math.round(performance.now() - startedAt),
    data
  })
  if (Array.isArray(data)) {
    return data
  }

  return data && typeof data === 'object' ? { ...data, requestId } : data
}

function safeJsonParse(value) {
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

export function fetchDocuments() {
  return request('/api/documents')
}

export function fetchDocumentContent(documentId) {
  return request(`/api/documents/${encodeURIComponent(documentId)}/content`)
}

export function ingestDocuments(path = '', force = false) {
  return request('/api/documents/ingest-directory', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, force })
  })
}

export function ingestDocument(path, force = false) {
  return request('/api/documents/ingest', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, force })
  })
}

export function rebuildDocuments() {
  return request('/api/documents/rebuild', {
    method: 'POST'
  })
}

export function syncDocuments() {
  return request('/api/documents/sync', {
    method: 'POST'
  })
}

export function deleteDocument(documentId) {
  return request(`/api/documents/${encodeURIComponent(documentId)}`, {
    method: 'DELETE'
  })
}
