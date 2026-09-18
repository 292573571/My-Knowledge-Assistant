import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

export async function chatWithSupportAgent(message, workspaceId = '') {
  try {
    const response = await fetch('/api/agent/support/chat', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ workspaceId, message })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, '使用帮助请求失败。')
    return response.json()
  } catch (error) {
    throw apiErrorFromException(error, '无法连接使用帮助，请检查后端服务。')
  }
}
