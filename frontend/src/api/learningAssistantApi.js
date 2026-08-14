import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

async function request(path, options = {}, fallback = '学习助手请求失败。') {
  try {
    const response = await fetch(path, {
      ...options,
      credentials: 'include',
      headers: authHeaders(options.body === undefined ? options.headers : { 'Content-Type': 'application/json', ...options.headers })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, fallback)
    return response.status === 204 ? null : response.json()
  } catch (error) {
    throw apiErrorFromException(error, fallback)
  }
}

function workspaceQuery() {
  return `?workspaceId=${encodeURIComponent(getActiveWorkspaceId())}`
}

export function fetchLearningSessions() {
  return request(`/api/learning-assistant/sessions${workspaceQuery()}`, {}, '学习会话加载失败。')
}

export function createLearningSession({ mode = 'AUTO', userLevel = 'BEGINNER' } = {}) {
  return request('/api/learning-assistant/sessions', {
    method: 'POST',
    body: JSON.stringify({ workspaceId: getActiveWorkspaceId(), mode, userLevel })
  }, '创建学习会话失败。')
}

export function fetchLearningSession(sessionId) {
  return request(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}${workspaceQuery()}`, {}, '学习会话恢复失败。')
}

export function deleteLearningSession(sessionId) {
  return request(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}${workspaceQuery()}`, {
    method: 'DELETE'
  }, '删除学习会话失败。')
}

export function streamLearningMessage(sessionId, payload, onEvent) {
  const controller = new AbortController()
  let sawTerminalEvent = false
  void fetch(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}/messages/stream`, {
    method: 'POST',
    credentials: 'include',
    headers: authHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
    body: JSON.stringify({ workspaceId: getActiveWorkspaceId(), ...payload }),
    signal: controller.signal
  }).then(async response => {
    if (!response.ok) throw await apiErrorFromResponse(response, '学习助手回答失败。')
    if (!response.body) throw apiErrorFromException(new Error('后端没有返回回答流。'), '后端没有返回回答流。')
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8', { fatal: true })
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() || ''
      for (const block of blocks) dispatchEventBlock(block, dispatch)
      if (done) {
        if (buffer.trim()) dispatchEventBlock(buffer, dispatch)
        if (!sawTerminalEvent) {
          const apiError = apiErrorFromException(new Error('回答流意外结束，请重试。'), '回答流意外结束，请重试。')
          dispatch('error', { apiError, message: apiError.message, retryable: true })
        }
        break
      }
    }
  }).catch(error => {
    if (error.name !== 'AbortError') {
      const apiError = apiErrorFromException(error, '请求失败，请稍后重试。')
      dispatch('error', {
        apiError,
        message: apiError.message,
        status: apiError.status,
        requestId: apiError.requestId,
        retryable: apiError.retryable
      })
    }
  })

  function dispatch(type, data) {
    if (type === 'done' || type === 'error') sawTerminalEvent = true
    onEvent(type, data)
  }
  return () => controller.abort()
}

function dispatchEventBlock(block, onEvent) {
  let type = 'message'
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) type = line.substring(6).trim()
    else if (line.startsWith('data:')) data.push(line.substring(5).trimStart())
  }
  const raw = data.join('\n')
  let parsed = {}
  try { parsed = raw ? JSON.parse(raw) : {} } catch { parsed = { text: raw } }
  onEvent(type, parsed)
}

export function submitLearningCheck(sessionId, payload) {
  return request(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}/check`, {
    method: 'POST',
    body: JSON.stringify({ workspaceId: getActiveWorkspaceId(), ...payload })
  }, '理解检查提交失败。')
}

export function submitLearningPractice(sessionId, payload) {
  return request(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}/practice`, {
    method: 'POST',
    body: JSON.stringify({ workspaceId: getActiveWorkspaceId(), ...payload })
  }, '实践提交失败。')
}

export function stopLearningSession(sessionId) {
  return request(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}/stop${workspaceQuery()}`, {
    method: 'POST'
  }, '停止学习请求失败。')
}
