/**
 * 前端运行时错误上报。
 *
 * 目标：白屏 / 渲染异常 / 未捕获 Promise 不再只能靠用户截图。
 * 约束：
 * - 任何失败都静默吞掉，绝不影响页面本身；
 * - 同一条错误只报一次（按 message + stack + 页面去重），避免循环渲染刷爆日志；
 * - 每个页面会话最多上报 20 条，后端还有按用户每分钟限流；
 * - 不采集表单内容，只发 message / stack / pathname / userAgent。
 */

const MAX_REPORTS = 20
const MAX_SEEN = 100

let sent = 0
const seen = new Set()

function normalize(error, info) {
  if (!error) return null
  if (typeof error === 'string') {
    return { message: error, stack: info || '' }
  }
  const message = error.message || error.name || String(error)
  const stack = error.stack || info || ''
  return { message: String(message), stack: String(stack) }
}

function dedupeKey(detail) {
  return `${detail.message.slice(0, 160)}|${detail.stack.slice(0, 240)}|${location.pathname}`
}

export function reportClientError(error, info = '') {
  try {
    const detail = normalize(error, info)
    if (!detail || !detail.message) return

    const key = dedupeKey(detail)
    if (seen.has(key) || sent >= MAX_REPORTS) return
    if (seen.size >= MAX_SEEN) seen.clear()
    seen.add(key)
    sent += 1

    const body = {
      kind: String(info || 'error').slice(0, 32),
      message: detail.message.slice(0, 1000),
      stack: detail.stack.slice(0, 2000),
      page: location.pathname.slice(0, 200),
      userAgent: navigator.userAgent.slice(0, 200)
    }

    void fetch('/api/client-errors', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      keepalive: true
    }).catch(() => {})
  } catch {
    // 上报本身不能抛错
  }
}

export function installGlobalErrorHandlers(app) {
  app.config.errorHandler = (error, instance, info) => {
    reportClientError(error, `vue:${info}`)
  }

  window.addEventListener('error', (event) => {
    // 资源加载失败没有 error 对象，也没有可读 message，跳过以免噪声。
    if (!event.error && !event.message) return
    reportClientError(event.error || event.message, 'window:error')
  })

  window.addEventListener('unhandledrejection', (event) => {
    reportClientError(event.reason, 'unhandledrejection')
  })
}
