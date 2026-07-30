import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

// 一次 RAG 问答可能包含召回质量评估、回答生成和答案评估，需与 SSE 请求预算保持一致。
const REQUEST_TIMEOUT_MS = 120000
const REQUEST_ERROR_MESSAGE = '请求失败，请检查后端服务是否启动。'

export async function sendChatMessage({ conversationId, mode, message, onAbortReady }) {
  const controller = new AbortController()
  onAbortReady?.(() => controller.abort())
  const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  const body = { conversationId, mode, workspaceId: getActiveWorkspaceId(), message }
  const startedAt = performance.now()

  try {
    const res = await fetch('/api/workbench/chat', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
      signal: controller.signal
    })

    if (!res.ok) {
      const apiError = await apiErrorFromResponse(res, REQUEST_ERROR_MESSAGE)
      throw apiError
    }

    const data = await res.json()
    const requestId = res.headers.get('X-Request-Id') || ''
    return { ...data, requestId }
  } catch (error) {
    const apiError = apiErrorFromException(error, REQUEST_ERROR_MESSAGE)
    throw apiError
  } finally {
    window.clearTimeout(timeoutId)
  }
}
