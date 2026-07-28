function parseEventData(data) {
  if (!data) return {}

  try {
    return JSON.parse(data)
  } catch {
    return { text: data }
  }
}

export function streamChat({ conversationId, mode, message, onEvent }) {
  const url = `/api/workbench/chat/stream?conversationId=${encodeURIComponent(conversationId)}&mode=${encodeURIComponent(mode)}&message=${encodeURIComponent(message)}&access_token=${encodeURIComponent(getAccessToken())}`
  const startedAt = performance.now()

  console.debug('[sse] connect', {
    url,
    conversationId,
    mode,
    message
  })

  const es = new EventSource(url)

  es.addEventListener('start', (e) => handleEvent('start', e, onEvent))
  es.addEventListener('token', (e) => handleEvent('token', e, onEvent, false))
  es.addEventListener('source', (e) => handleEvent('source', e, onEvent))
  es.addEventListener('tool_call_start', (e) => handleEvent('tool_call_start', e, onEvent))
  es.addEventListener('tool_call_result', (e) => handleEvent('tool_call_result', e, onEvent))
  es.addEventListener('done', (e) => {
    handleEvent('done', e, onEvent)
    console.debug('[sse] done', {
      url,
      durationMs: Math.round(performance.now() - startedAt)
    })
    es.close()
  })
  es.addEventListener('error', (e) => {
    console.error('[sse] error', {
      url,
      durationMs: Math.round(performance.now() - startedAt),
      error: e
    })
    onEvent('error', e)
    es.close()
  })

  return () => {
    console.debug('[sse] close', {
      url,
      durationMs: Math.round(performance.now() - startedAt)
    })
    es.close()
  }
}

function handleEvent(type, event, onEvent, shouldLog = true) {
  const data = parseEventData(event.data)

  if (shouldLog) {
    console.debug('[sse] event', { type, data })
  }

  onEvent(type, data)
}
import { getAccessToken } from './authApi'
