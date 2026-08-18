import { apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'

export function fetchModelPool() {
  return request('/api/model-config/pool', {}, '模型池加载失败。')
}

export function createPoolModel(data) {
  return request('/api/model-config/pool', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  }, '模型添加失败。')
}

export function updatePoolModel(id, data) {
  return request(`/api/model-config/pool/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  }, '模型更新失败。')
}

export function deletePoolModel(id) {
  return request(`/api/model-config/pool/${id}`, { method: 'DELETE' }, '模型删除失败。')
}

export function setDefaultPoolModel(id) {
  return request(`/api/model-config/pool/${id}/default`, { method: 'PUT' }, '默认模型设置失败。')
}

export function testPoolModel(id) {
  return request(`/api/model-config/pool/${id}/test`, { method: 'POST' }, '模型测试失败。')
}

export function fetchMyConfig() {
  return request('/api/model-config/me', {}, '模型配置加载失败。')
}

export function saveMyConfig(data) {
  return request('/api/model-config/me', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  }, '模型配置保存失败。')
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