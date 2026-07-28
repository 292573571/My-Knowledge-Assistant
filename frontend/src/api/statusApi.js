import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

export async function fetchWorkbenchStatus() {
  let response

  try {
    response = await fetch('/api/health', { headers: authHeaders() })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务，请确认 Spring Boot 已启动。')
  }

  if (!response.ok) {
    throw await apiErrorFromResponse(response, `状态加载失败: ${response.status}`)
  }

  return response.json()
}
