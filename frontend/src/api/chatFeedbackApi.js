import { ApiError, apiErrorFromException, apiErrorFromResponse } from './apiError'
import { authHeaders } from './authApi'
import { getActiveWorkspaceId } from './workspaceApi'

async function request(path, options = {}, fallback = '反馈提交失败。') {
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

/** 对一条 AI 回答提交 👍/👎。rating: 1 或 -1 */
export function submitMessageFeedback(messageId, conversationId, rating, reason = '') {
  return request(`/api/chat-messages/${encodeURIComponent(String(messageId))}/feedback`
    + `?workspaceId=${encodeURIComponent(getActiveWorkspaceId())}`
    + `&conversationId=${encodeURIComponent(conversationId || '')}`, {
    method: 'POST',
    body: JSON.stringify({ rating, reason: reason || null })
  }, '反馈提交失败。')
}

/** 管理员反馈池（rating=-1 只看差评） */
export function fetchAdminFeedback(workspaceId, rating = null) {
  const suffix = rating == null ? '' : `&rating=${rating}`
  return request(`/api/feedback?workspaceId=${encodeURIComponent(workspaceId)}${suffix}`, {}, '反馈列表加载失败。')
}

/** 把一条反馈沉淀为评测草稿（suite=DRAFT，caseId 前缀 fb-） */
export function createEvalCaseFromFeedback(feedbackId) {
  return request('/api/eval/cases/from-feedback', {
    method: 'POST',
    body: JSON.stringify(feedbackId)
  }, '沉淀评测题失败。')
}
