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

export async function submitTeachingCheck({ workspaceId, sessionId, checkId, answer }) {
  try {
    const response = await fetch('/api/agent/teaching/check', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ workspaceId, sessionId, checkId, answer })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, '教学检查提交失败。')
    return response.json()
  } catch (error) {
    throw apiErrorFromException(error, '无法提交教学检查，请稍后重试。')
  }
}
