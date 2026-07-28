function parseSseEvent(block) {
  const lines = block.split('\n')
  let eventName = 'message'
  const data = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart())
    }
  }

  const rawData = data.join('\n')
  if (!rawData) return null

  try {
    return {
      type: eventName,
      ...JSON.parse(rawData)
    }
  } catch {
    return {
      type: eventName,
      content: rawData
    }
  }
}

export async function readSseStream(body, handlers = {}) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() || ''

    for (const block of blocks) {
      const event = parseSseEvent(block.trim())
      if (event) handlers.onMessage?.(event)
    }
  }

  if (buffer.trim()) {
    const event = parseSseEvent(buffer.trim())
    if (event) handlers.onMessage?.(event)
  }

  handlers.onMessage?.({ type: 'done' })
}
