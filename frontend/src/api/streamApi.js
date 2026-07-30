import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

function parseEventData(data) {
  if (!data) return {}
  try { return JSON.parse(data) } catch { return { text: data } }
}

export function streamChat({ conversationId, mode, message, onEvent }) {
  const controller = new AbortController()

  void fetch('/api/workbench/chat/stream', {
    method: 'POST',
    credentials: 'include',
    headers: authHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
    body: JSON.stringify({ conversationId, mode, workspaceId: getActiveWorkspaceId(), message }),
    signal: controller.signal
  }).then(async response => {
    if (!response.ok || !response.body) throw new Error(`SSE request failed: ${response.status}`)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() || ''
      for (const block of blocks) dispatchBlock(block, onEvent)
      if (done) {
        if (buffer.trim()) dispatchBlock(buffer, onEvent)
        break
      }
    }
  }).catch(error => {
    if (error.name !== 'AbortError') onEvent('error', { message: '请求失败，请稍后重试' })
  })

  return () => controller.abort()
}

function dispatchBlock(block, onEvent) {
  let type = 'message'
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) type = line.substring(6).trim()
    else if (line.startsWith('data:')) data.push(line.substring(5).trimStart())
  }
  onEvent(type, parseEventData(data.join('\n')))
}
