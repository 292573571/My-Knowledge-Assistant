import { apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

async function request(path, options = {}, fallbackMessage) {
  const response = await fetch(path, {
    ...options,
    credentials: 'include',
    headers: authHeaders(options.headers)
  })
  if (!response.ok) throw await apiErrorFromResponse(response, fallbackMessage)
  return response.status === 204 ? null : response.json()
}

export function fetchAdminUsers() {
  return request('/api/admin/users', {}, '用户列表加载失败。')
}

export function updateUserSystemRole(publicId, systemRole) {
  return request(`/api/admin/users/${encodeURIComponent(publicId)}/role`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ systemRole })
  }, '系统角色更新失败。')
}
