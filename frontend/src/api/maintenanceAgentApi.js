import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

export async function chatWithMaintenanceAgent(message, workspaceId = '') {
  try {
    const response = await fetch('/api/agent/maintenance/chat', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ workspaceId, message })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, '维护助手请求失败。')
    return response.json()
  } catch (error) {
    throw apiErrorFromException(error, '无法连接维护助手，请检查后端服务。')
  }
}

export async function confirmMaintenanceAgentAction(confirmationToken, workspaceId = '') {
  try {
    const response = await fetch('/api/agent/maintenance/confirm', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ confirmationToken, workspaceId })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, '维护操作执行失败。')
    return response.json()
  } catch (error) {
    throw apiErrorFromException(error, '无法执行维护操作，请稍后重试。')
  }
}
