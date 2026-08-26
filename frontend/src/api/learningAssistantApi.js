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

export function streamLearningMessage(sessionId, payload, onEvent, options = {}) {
  const controller = new AbortController()
  let sawTerminalEvent = false
  let lastEventId = Number.isFinite(options.lastEventId) ? options.lastEventId : 0
  let streamId = options.streamId || null
  const query = streamId
    ? `?streamId=${encodeURIComponent(streamId)}&resumeFrom=${encodeURIComponent(String(lastEventId))}`
    : ''
  const headers = authHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' })
  if (lastEventId) headers['Last-Event-ID'] = String(lastEventId)
  void fetch(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}/messages/stream${query}`, {
    method: 'POST',
    credentials: 'include',
    headers,
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
      for (const block of blocks) handleBlock(block)
      if (done) {
        if (buffer.trim()) handleBlock(buffer)
        if (!sawTerminalEvent) {
          // 流意外结束但未收到终态事件:可能是连接中断。标记为 synthetic,
          // 上层据此区分「连接中断(可断点续传)」与「服务端真实错误(需重建重生成)」。
          const apiError = apiErrorFromException(new Error('回答流意外结束，请重试。'), '回答流意外结束，请重试。')
          dispatch('error', { apiError, message: apiError.message, retryable: true, synthetic: true })
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
        retryable: apiError.retryable,
        synthetic: true
      })
    }
  })

  function dispatch(type, data) {
    if (type === 'done' || type === 'error') sawTerminalEvent = true
    onEvent(type, data, { seq: lastEventId, streamId })
  }

  function handleBlock(block) {
    let type = 'message'
    let id = null
    const dataLines = []
    let sawContent = false
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith(':')) continue // SSE 注释(心跳保活), 忽略
      if (line.startsWith('id:')) {
        id = line.substring(3).trim()
        continue
      }
      if (line.startsWith('event:')) {
        type = line.substring(6).trim()
        sawContent = true
        continue
      }
      if (line.startsWith('data:')) {
        dataLines.push(line.substring(5).trimStart())
        sawContent = true
      }
    }
    if (!sawContent) return // 纯注释或空块, 不派发
    if (id != null && /^\d+$/.test(id)) lastEventId = Number(id)
    const raw = dataLines.join('\n')
    let parsed = {}
    try {
      parsed = raw ? JSON.parse(raw) : {}
    } catch {
      parsed = { text: raw }
    }
    if (type === 'stream_init' && parsed.streamId) streamId = parsed.streamId
    dispatch(type, parsed)
  }
  return () => controller.abort()
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

export function stopLearningSession(sessionId, workspaceId = getActiveWorkspaceId()) {
  return request(`/api/learning-assistant/sessions/${encodeURIComponent(sessionId)}/stop?workspaceId=${encodeURIComponent(workspaceId || '')}`, {
    method: 'POST'
  }, '停止学习请求失败。')
}
