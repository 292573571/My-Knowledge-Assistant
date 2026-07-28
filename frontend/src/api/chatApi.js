import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

// 一次 RAG 问答可能包含召回质量评估、回答生成和答案评估，需与 SSE 请求预算保持一致。
const REQUEST_TIMEOUT_MS = 120000
const REQUEST_ERROR_MESSAGE = '请求失败，请检查后端服务是否启动。'

export async function sendChatMessage({ conversationId, mode, message, onAbortReady }) {
  const controller = new AbortController()
  onAbortReady?.(() => controller.abort())
  const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  const body = { conversationId, mode, message }
  const startedAt = performance.now()

  try {
    console.debug('[api] request', {
      method: 'POST',
      path: '/api/workbench/chat',
      body
    })

    const res = await fetch('/api/workbench/chat', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
      signal: controller.signal
    })

    if (!res.ok) {
      const apiError = await apiErrorFromResponse(res, REQUEST_ERROR_MESSAGE)
      console.error('[api] response error', {
        method: 'POST',
        path: '/api/workbench/chat',
        status: res.status,
        requestId: apiError.requestId,
        durationMs: Math.round(performance.now() - startedAt)
      })
      throw apiError
    }

    const data = await res.json()
    const requestId = res.headers.get('X-Request-Id') || ''
    console.debug('[api] response', {
      method: 'POST',
      path: '/api/workbench/chat',
      status: res.status,
      requestId,
      durationMs: Math.round(performance.now() - startedAt),
      data
    })
    return { ...data, requestId }
  } catch (error) {
    const apiError = apiErrorFromException(error, REQUEST_ERROR_MESSAGE)
    console.error('[api] request failed', {
      method: 'POST',
      path: '/api/workbench/chat',
      durationMs: Math.round(performance.now() - startedAt),
      requestId: apiError.requestId,
      error: apiError
    })
    throw apiError
  } finally {
    window.clearTimeout(timeoutId)
  }
}
