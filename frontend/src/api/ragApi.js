import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

const REQUEST_ERROR_MESSAGE = '检索调试请求失败，请检查后端服务是否启动。'

export async function debugRetrieval(message) {
  const body = { message, workspaceId: getActiveWorkspaceId() }

  try {
    const response = await fetch('/api/rag/debug', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body)
    })

    if (!response.ok) {
      throw await apiErrorFromResponse(response, REQUEST_ERROR_MESSAGE)
    }

    const data = await response.json()
    return {
      ...data,
      requestId: response.headers.get('X-Request-Id') || ''
    }
  } catch (error) {
    throw apiErrorFromException(error, REQUEST_ERROR_MESSAGE)
  }
}

async function evalRequest(path, options = {}, fallbackMessage) {
  try {
    const response = await fetch(path, {
      ...options,
      credentials: 'include',
      headers: authHeaders(options.headers)
    })

    if (!response.ok) {
      throw await apiErrorFromResponse(response, fallbackMessage)
    }

    if (response.status === 204) return null
    const text = await response.text()
    return text.trim() ? JSON.parse(text) : null
  } catch (error) {
    throw apiErrorFromException(error, fallbackMessage)
  }
}

export function fetchEvalCases() {
  return evalRequest('/api/eval/cases', {}, '加载评测题失败，请检查后端服务。')
}

export function createEvalCase(caseDto) {
  return evalRequest('/api/eval/cases', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(caseDto)
  }, '新增评测题失败。')
}

export function updateEvalCase(id, caseDto) {
  return evalRequest(`/api/eval/cases/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(caseDto)
  }, '更新评测题失败。')
}

export function deleteEvalCase(id) {
  return evalRequest(`/api/eval/cases/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }, '删除评测题失败。')
}

export function importEvalCases(file) {
  const body = new FormData()
  body.append('file', file)
  return evalRequest('/api/eval/cases/import', { method: 'POST', body }, '导入评测题失败。')
}

export function fetchEvalImports() {
  return evalRequest('/api/eval/imports', {}, '加载导入记录失败。')
}

export async function downloadEvalImport(id) {
  const response = await fetch(`/api/eval/imports/${encodeURIComponent(id)}/download`, { credentials: 'include', headers: authHeaders() })
  if (!response.ok) throw await apiErrorFromResponse(response, '下载导入文件失败。')
  const disposition = response.headers.get('Content-Disposition') || ''
  const matched = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  return { name: matched ? decodeURIComponent(matched[1]) : 'eval-import', content: await response.blob() }
}

export function runEvals(caseIds, enhanced, suite = null, layer = null) {
  return evalRequest('/api/eval/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ caseIds, enhanced, suite, layer })
  }, '评测执行失败，请检查后端服务和模型配置。')
}

export function fetchEvalRuns() {
  return evalRequest('/api/eval/runs', {}, '加载检索历史失败，请检查后端服务。')
}

export function fetchEvalRun(runId) {
  return evalRequest(`/api/eval/runs/${encodeURIComponent(runId)}`, {}, '加载检索结果失败。')
}
