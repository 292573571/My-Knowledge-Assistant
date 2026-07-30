import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

const REQUEST_ERROR_MESSAGE = '会话请求失败，请检查后端服务是否启动。'

function workspacePath(path) {
  const separator = path.includes('?') ? '&' : '?'
  return `${path}${separator}workspaceId=${encodeURIComponent(getActiveWorkspaceId())}`
}

async function request(path, options = {}) {
  try {
    const response = await fetch(path, {
      ...options,
      credentials: 'include',
      headers: authHeaders(options.headers)
    })

    if (!response.ok) {
      throw await apiErrorFromResponse(response, REQUEST_ERROR_MESSAGE)
    }

    return response.status === 204 ? null : response.json()
  } catch (error) {
    throw apiErrorFromException(error, REQUEST_ERROR_MESSAGE)
  }
}

export function fetchConversations() {
  return request(workspacePath('/api/conversations'))
}

export function fetchConversationMessages(conversationId) {
  return request(workspacePath(`/api/conversations/${encodeURIComponent(conversationId)}/messages`))
}

export function createConversation(conversation) {
  return request(workspacePath('/api/conversations'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(conversation)
  })
}

export function deleteConversation(conversationId) {
  return request(workspacePath(`/api/conversations/${encodeURIComponent(conversationId)}`), { method: 'DELETE' })
}

export function stopConversation(conversationId) {
  return request(workspacePath(`/api/conversations/${encodeURIComponent(conversationId)}/stop`), { method: 'POST' })
}
