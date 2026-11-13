import { ApiError, apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

async function request(path, options = {}, fallback = '搜索失败。') {
  try {
    const response = await fetch(path, {
      ...options,
      credentials: 'include',
      headers: authHeaders(options.body === undefined ? options.headers : { 'Content-Type': 'application/json', ...options.headers })
    })
    if (!response.ok) throw await apiErrorFromResponse(response, fallback)
    return response.status === 204 ? null : response.json()
  } catch (error) {
    throw apiErrorFromException(error, fallback)
  }
}

/** 全局搜索：同时命中知识库文档与 wiki 笔记（排除学习记录） */
export function globalSearch(query, limit = 12) {
  return request(`/api/search?q=${encodeURIComponent(query)}&workspaceId=${encodeURIComponent(getActiveWorkspaceId())}&limit=${limit}`, {}, '搜索失败。')
}
