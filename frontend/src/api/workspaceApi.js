import { apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

let activeWorkspaceId = ''

export function getActiveWorkspaceId() {
  return activeWorkspaceId
}

export function setActiveWorkspaceId(workspaceId) {
  activeWorkspaceId = workspaceId || ''
}

export async function fetchWorkspaces() {
  return request('/api/workspaces', {}, '知识空间加载失败。')
}

export async function initializePersonalWorkspace() {
  const workspaces = await fetchWorkspaces()
  const personal = workspaces.find(workspace => workspace.type === 'PERSONAL')
  setActiveWorkspaceId(personal?.id || '')
  return workspaces
}

export function createTeamWorkspace(name) {
  return request('/api/workspaces/team', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  }, '团队空间创建失败。')
}

export function createPublicWorkspace(name) {
  return request('/api/workspaces/public', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  }, '公共知识源创建失败。')
}

export function fetchWorkspaceMembers(workspaceId) {
  return request(`/api/workspaces/${encodeURIComponent(workspaceId)}/members`, {}, '空间成员加载失败。')
}

export function addWorkspaceMember(workspaceId, account, role) {
  return request(`/api/workspaces/${encodeURIComponent(workspaceId)}/members`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account, role })
  }, '成员添加失败。')
}

export function updateWorkspaceMemberRole(workspaceId, publicId, role) {
  return request(`/api/workspaces/${encodeURIComponent(workspaceId)}/members/${encodeURIComponent(publicId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role })
  }, '成员角色更新失败。')
}

export function removeWorkspaceMember(workspaceId, publicId) {
  return request(`/api/workspaces/${encodeURIComponent(workspaceId)}/members/${encodeURIComponent(publicId)}`, {
    method: 'DELETE'
  }, '成员移除失败。')
}

export function fetchWorkspaceAuditEvents(workspaceId) {
  return request(`/api/workspaces/${encodeURIComponent(workspaceId)}/audit-events`, {}, '审计记录加载失败。')
}

async function request(path, options = {}, fallbackMessage) {
  const response = await fetch(path, {
    ...options,
    credentials: 'include',
    headers: authHeaders(options.headers)
  })
  if (!response.ok) throw await apiErrorFromResponse(response, fallbackMessage)
  return response.status === 204 ? null : response.json()
}
