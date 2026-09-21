import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

export async function chatWithSystemAgent(message, workspaceId = '') {
  try {
    const response = await fetch('/api/agent/system/chat', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ workspaceId, message })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, '系统管家请求失败。')
    return response.json()
  } catch (error) {
    throw apiErrorFromException(error, '无法连接系统管家，请检查后端服务。')
  }
}

export async function confirmSystemAgentAction(confirmationToken, workspaceId = '') {
  try {
    const response = await fetch('/api/agent/system/confirm', {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ confirmationToken, workspaceId })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, '系统操作执行失败。')
    return response.json()
  } catch (error) {
    throw apiErrorFromException(error, '无法执行系统操作，请稍后重试。')
  }
}

export async function fetchSystemAgentTaskProgress(tasks) {
  try {
    const batches = []
    for (let index = 0; index < tasks.length; index += 100) batches.push(tasks.slice(index, index + 100))
    const responses = await Promise.all(batches.map(async (batch) => {
      const response = await fetch('/api/agent/system/tasks/progress', {
        method: 'POST',
        credentials: 'include',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ tasks: batch })
      })
      if (!response.ok) throw await apiErrorFromResponse(response, '任务进度查询失败。')
      return response.json()
    }))
    return responses.flat()
  } catch (error) {
    throw apiErrorFromException(error, '无法查询任务进度，请稍后重试。')
  }
}
