import { apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, { ...options, credentials: 'include', headers: authHeaders(options.headers) })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务。')
  }
  if (!response.ok) throw await apiErrorFromResponse(response, '知识图谱加载失败。')
  if (response.status === 204 || response.headers.get('content-length') === '0') return null
  return response.json()
}

/** 获取某知识空间的知识图谱快照（节点 + 边）。 */
export function fetchKgGraph(workspaceId) {
  return request(`/api/kg/graph?workspaceId=${encodeURIComponent(workspaceId || '')}`)
}
