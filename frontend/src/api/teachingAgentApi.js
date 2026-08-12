import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

export async function chatWithTeachingAgent({ workspaceId = '', sessionId = '', topic, userLevel = 'BEGINNER', message }) {
  try {
    const response = await fetch('/api/agent/teaching/chat', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ workspaceId, sessionId, topic, userLevel, message })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, '教学助手请求失败。')
    return response.json()
  } catch (error) {
    throw apiErrorFromException(error, '无法连接教学助手，请检查后端服务。')
  }
}
