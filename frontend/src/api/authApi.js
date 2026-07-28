import { apiErrorFromException, apiErrorFromResponse } from './apiError'

const TOKEN_KEY = 'personal-ai-workbench-token'

export function getAccessToken() {
  return window.localStorage.getItem(TOKEN_KEY) || ''
}

export function saveAccessToken(token) {
  window.localStorage.setItem(TOKEN_KEY, token)
}

export function clearAccessToken() {
  window.localStorage.removeItem(TOKEN_KEY)
}

export function authHeaders(headers = {}) {
  const token = getAccessToken()
  return token ? { ...headers, Authorization: `Bearer ${token}` } : headers
}

export async function authenticate(path, credentials) {
  let response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials)
    })
  } catch (error) {
    throw apiErrorFromException(error, '无法连接后端服务，请确认 Spring Boot 已启动。')
  }

  if (!response.ok) {
    throw await apiErrorFromResponse(response, '认证失败，请检查输入。')
  }

  return response.json()
}

export function register(credentials) {
  return authenticate('/api/auth/register', credentials)
}

export function login(credentials) {
  return authenticate('/api/auth/login', credentials)
}

export async function fetchCurrentUser() {
  const response = await fetch('/api/auth/me', { headers: authHeaders() })
  if (!response.ok) {
    throw await apiErrorFromResponse(response, '登录状态已失效。')
  }
  return response.json()
}

export async function logout() {
  const response = await fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() })
  clearAccessToken()
  if (!response.ok && response.status !== 401) {
    throw await apiErrorFromResponse(response, '退出登录失败。')
  }
}

export async function changePassword(passwords) {
  const response = await fetch('/api/auth/change-password', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(passwords)
  })
  if (!response.ok) {
    throw await apiErrorFromResponse(response, '修改密码失败。')
  }
}

export async function updateProfile(profile) {
  const response = await fetch('/api/auth/profile', {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(profile)
  })
  if (!response.ok) throw await apiErrorFromResponse(response, '个人资料更新失败。')
  return response.json()
}

export async function uploadAvatar(file) {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch('/api/auth/avatar', {
    method: 'POST',
    headers: authHeaders(),
    body: form
  })
  if (!response.ok) throw await apiErrorFromResponse(response, '头像上传失败。')
  return response.json()
}

export async function fetchAvatarUrl() {
  const response = await fetch('/api/auth/avatar', { headers: authHeaders(), cache: 'no-store' })
  if (!response.ok) throw await apiErrorFromResponse(response, '头像加载失败。')
  return URL.createObjectURL(await response.blob())
}
