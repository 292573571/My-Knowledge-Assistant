export class ApiError extends Error {
  constructor({ message, status = null, requestId = '', details = '', retryable = false, cause = null }) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.requestId = requestId
    this.details = details
    this.retryable = retryable
    this.cause = cause
  }
}

export async function apiErrorFromResponse(response, fallbackMessage) {
  const requestId = response.headers.get('X-Request-Id') || ''
  const text = await response.text().catch(() => '')
  const body = safeJsonParse(text)
  const message = body?.message || body?.error || fallbackMessage || messageForStatus(response.status)
  const details = body?.details || body?.path || text || ''

  return new ApiError({
    message,
    status: response.status,
    requestId,
    details,
    retryable: response.status === 408 || response.status === 429 || response.status >= 500
  })
}

export function apiErrorFromException(error, fallbackMessage = '请求失败，请检查后端服务是否启动。') {
  if (error instanceof ApiError) {
    return error
  }

  if (error?.name === 'AbortError') {
    return new ApiError({
      message: '模型服务响应超时，请稍后重试。',
      details: '前端在限定时间内未收到后端的完整回答。',
      retryable: true,
      cause: error
    })
  }

  return new ApiError({
    message: fallbackMessage,
    details: error?.message || '无法连接后端服务，可能是后端未启动、端口不通或代理配置异常。',
    retryable: true,
    cause: error
  })
}

export function formatApiError(error, fallbackMessage) {
  const apiError = apiErrorFromException(error, fallbackMessage)
  return apiError.message
}

function messageForStatus(status) {
  if (status === 400) return '请求参数不正确，请检查输入内容。'
  if (status === 401 || status === 403) return '后端拒绝了请求，请检查鉴权或模型 API Key 配置。'
  if (status === 404) return '接口不存在，请确认前后端版本一致。'
  if (status === 408) return '请求超时，请稍后重试。'
  if (status === 429) return '请求过于频繁或模型服务限流，请稍后重试。'
  if (status >= 500) return '后端处理失败，可能是模型调用、知识库或服务异常。'
  return '请求失败，请稍后重试。'
}

function safeJsonParse(value) {
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}
