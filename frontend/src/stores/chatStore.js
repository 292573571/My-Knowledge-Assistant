import { reactive, readonly } from 'vue'
import { apiErrorFromException, formatApiError } from '../api/apiError'
import { sendChatMessage } from '../api/chatApi'
import { streamChat } from '../api/streamApi'
import { deleteConversation as deleteRemoteConversation, fetchConversationMessages, fetchConversations, stopConversation } from '../api/conversationApi'

const REQUEST_ERROR_MESSAGE = '请求失败，请检查后端服务是否启动。'

function createConversation(title = '新的对话') {
  return {
    id: crypto.randomUUID(),
    title,
    mode: 'rag',
    messages: [],
    sources: [],
    toolCalls: [],
    persisted: false,
    updatedAt: new Date().toISOString()
  }
}

function normalizeConversation(conversation) {
  // 后端只返回会话元数据；前端补齐 UI 专用字段，消息在选中会话后按需加载。
  return {
    id: conversation.id || crypto.randomUUID(),
    title: conversation.title || '新的对话',
    mode: conversation.mode || 'rag',
    messages: (conversation.messages || []).map((message) => ({
      error: null,
      ...message,
      streaming: false
    })),
    sources: conversation.sources || [],
    toolCalls: conversation.toolCalls || [],
    persisted: true,
    updatedAt: conversation.updatedAt || new Date().toISOString()
  }
}

function normalizeMessage(message) {
  // 将数据库恢复的消息转换为与实时消息一致的 UI 形状。
  return {
    ...message,
    sources: message.sources || [],
    toolCalls: message.toolCalls || [],
    streaming: false,
    error: null
  }
}

const state = reactive({
  // conversations 保存会话列表；messages 始终指向当前活动会话的消息，便于组件直接渲染。
  conversations: [],
  activeConversationId: '',
  messages: [],
  mode: 'rag',
  isLoadingConversations: false,
  isStreaming: false,
  error: '',
  lastFailedMessage: '',
  abortStream: null
})

let activeAccount = 'anonymous'
let loadingAccount = ''

function getActiveConversation() {
  return state.conversations.find((conversation) => conversation.id === state.activeConversationId)
}

function syncActiveMessages() {
  // 切换、创建或删除会话后统一同步右侧聊天面板的数据源。
  const conversation = getActiveConversation()
  state.messages = conversation?.messages || []
}

async function setUser(account) {
  if (!account || account === loadingAccount) return
  // 用户切换前先终止旧账号的请求，避免异步结果写入新账号界面。
  stop()
  loadingAccount = account
  activeAccount = account
  state.isLoadingConversations = true
  state.error = ''
  state.lastFailedMessage = ''
  state.conversations = []
  state.activeConversationId = ''
  state.messages = []

  try {
    // 服务端历史是唯一持久数据源；localStorage 不保存聊天内容。
    const conversations = (await fetchConversations())
      .map(normalizeConversation)
      .sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt))
    // 异步加载期间账号可能已切换，旧请求结果必须丢弃。
    if (loadingAccount !== account) return

    if (!conversations.length) {
      const conversation = createConversation()
      state.conversations = [conversation]
      state.activeConversationId = conversation.id
      state.messages = conversation.messages
      state.mode = conversation.mode
      return
    }

    // 登录后优先恢复最近会话，避免先显示临时新会话再跳转导致闪烁。
    const mostRecentConversation = conversations[0]
    try {
      const messages = await fetchConversationMessages(mostRecentConversation.id)
      if (loadingAccount !== account) return
      mostRecentConversation.messages = messages.map(normalizeMessage)
    } catch {}

    if (loadingAccount !== account) return
    state.conversations = conversations
    state.activeConversationId = mostRecentConversation.id
    state.messages = mostRecentConversation.messages
    state.mode = 'rag'
  } catch (error) {
    const apiError = apiErrorFromException(error, '加载聊天记录失败。')
    state.error = formatApiError(apiError, '加载聊天记录失败。')
  } finally {
    if (loadingAccount === account) {
      loadingAccount = ''
      state.isLoadingConversations = false
    }
  }

}

function touchActiveConversation() {
  const conversation = getActiveConversation()
  if (conversation) {
    conversation.updatedAt = new Date().toISOString()
  }
}

function createUserMessage(content) {
  return {
    id: crypto.randomUUID(),
    role: 'user',
    content,
    createdAt: Date.now(),
    streaming: false,
    error: null
  }
}

function createAssistantMessage() {
  return {
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    streaming: true,
    error: null,
    sources: [],
    toolCalls: [],
    searchResults: [],
    fileResults: [],
    requestId: '',
    retryable: false,
    createdAt: Date.now()
  }
}

function upsertConversationTitle(content) {
  const conversation = getActiveConversation()
  if (conversation && conversation.title === '新的对话') {
    conversation.title = content.slice(0, 24) || '新的对话'
  }
}

function getToolCallKey(toolCall) {
  return toolCall.id || toolCall.toolCallId || toolCall.toolName || toolCall.name || toolCall.tool
}

function upsertToolCall(toolCalls, toolCall) {
  // 同一工具可能依次发出 running 和 success/error 事件，按稳定 key 原地更新而非重复追加。
  const key = getToolCallKey(toolCall)
  const index = toolCalls.findIndex((item) => getToolCallKey(item) === key)

  if (key && index >= 0) {
    toolCalls[index] = {
      ...toolCalls[index],
      ...toolCall,
      status: toolCall.status || (toolCall.success === false ? 'error' : toolCall.success ? 'success' : toolCalls[index].status)
    }
    return
  }

  toolCalls.push({
    id: toolCall.id || toolCall.toolCallId || crypto.randomUUID(),
    toolName: toolCall.toolName || toolCall.name || toolCall.tool || 'tool',
    arguments: toolCall.arguments || toolCall.input || {},
    resultPreview: toolCall.resultPreview || toolCall.result || toolCall.output || '',
    success: toolCall.success ?? null,
    durationMs: toolCall.durationMs ?? null,
    status: toolCall.status || (toolCall.success === false ? 'error' : toolCall.success ? 'success' : 'running'),
    ...toolCall
  })
}

function finishStreaming() {
  // 所有成功、失败和停止路径最终都要复位流式状态与取消句柄。
  state.isStreaming = false
  state.abortStream = null
  touchActiveConversation()
}

function failStreaming(assistantMessage, error = null) {
  const apiError = apiErrorFromException(error, REQUEST_ERROR_MESSAGE)
  const message = formatApiError(apiError, REQUEST_ERROR_MESSAGE)
  state.error = message
  state.lastFailedMessage = state.lastFailedMessage || ''
  assistantMessage.error = message
  assistantMessage.requestId = apiError.requestId
  assistantMessage.retryable = apiError.retryable
  assistantMessage.streaming = false
  finishStreaming()
}

function applyRagEmptyState(assistantMessage, mode) {
  if (mode !== 'rag') return
  if (assistantMessage.sources?.length) return
  if (assistantMessage.error) return

  const answer = assistantMessage.content || ''
  if (answer.includes('当前知识库中没有找到足够信息') || answer.includes('当前还没有摄取知识文档')) {
    assistantMessage.noRagMatch = true
    return
  }

  // 无引用但并非明确“无匹配”时，通常是模型补充回答，供 UI 展示来源提示。
  assistantMessage.noSources = true
}

export function useChatStore() {
  async function send(content) {
    const text = content.trim()
    if (state.isStreaming) return
    if (!text) {
      state.error = REQUEST_ERROR_MESSAGE
      return
    }

    syncActiveMessages()
    state.error = ''
    state.lastFailedMessage = text
    // 先插入助手占位消息；首个 token 到达前仅显示生成指示器。
    state.messages.push(createUserMessage(text))
    upsertConversationTitle(text)

    const assistantMessage = createAssistantMessage()
    state.messages.push(assistantMessage)
    touchActiveConversation()
    state.isStreaming = true

    try {
      await new Promise((resolve, reject) => {
        let settled = false
        const close = streamChat({
          conversationId: state.activeConversationId,
          message: text,
          mode: 'rag',
          onEvent: (type, data) => {
            if (settled) return
            if (type === 'token') {
              assistantMessage.content += data.text || ''
              return
            }
            if (type === 'source') {
              assistantMessage.sources.push(data)
              return
            }
            if (type === 'tool_call_start' || type === 'tool_call_result') {
              upsertToolCall(assistantMessage.toolCalls, data)
              return
            }
            if (type === 'done') {
              settled = true
              close()
              resolve()
              return
            }
            if (type === 'error') {
              settled = true
              close()
              reject(new Error(data.message || '模型流式回答中断，请稍后重试。'))
            }
          }
        })
        state.abortStream = close
      })

      assistantMessage.streaming = false
      applyRagEmptyState(assistantMessage, 'rag')

      const conversation = getActiveConversation()
      if (conversation) {
        // 首次成功回答后会话已在服务端创建，后续切换时应从服务端重新加载。
        conversation.persisted = true
        conversation.sources = assistantMessage.sources
        conversation.toolCalls = assistantMessage.toolCalls
      }

      finishStreaming()
      // 重试成功后必须清除上一轮失败状态，避免正确回答下方仍显示过期错误提示。
      state.error = ''
      state.lastFailedMessage = ''
    } catch (error) {
      failStreaming(assistantMessage, error)
    }
  }

  function retryLast() {
    if (!state.lastFailedMessage || state.isStreaming) return
    return send(state.lastFailedMessage)
  }

  async function stop() {
    const conversationId = state.activeConversationId
    const conversation = getActiveConversation()
    // 客户端中止请求后仍通知服务端取消生成，避免模型继续运行并产生迟到写入。
    state.abortStream?.()
    state.abortStream = null
    state.isStreaming = false
    const streamingMessage = [...state.messages].reverse().find((message) => message.streaming)
    if (streamingMessage) {
      streamingMessage.streaming = false
      // 用户在首个 token 前停止时，这只是本地占位，不应留下空白回答框。
      if (!streamingMessage.content && !streamingMessage.error) {
        const index = state.messages.indexOf(streamingMessage)
        if (index >= 0) state.messages.splice(index, 1)
      }
    }
    touchActiveConversation()
    if (conversationId && conversation?.persisted) {
      try {
        await stopConversation(conversationId)
      } catch (error) {
        state.error = formatApiError(apiErrorFromException(error, '停止会话失败。'), '停止会话失败。')
      }
    }
  }

  async function newConversation() {
    stop()
    const conversation = createConversation()
    state.conversations.unshift(conversation)
    state.activeConversationId = conversation.id
    syncActiveMessages()
    state.error = ''
    state.lastFailedMessage = ''
  }

  async function selectConversation(id) {
    stop()
    const conversation = state.conversations.find((item) => item.id === id)
    if (!conversation) return
    state.activeConversationId = id
    // 未发送过消息的临时会话尚不存在于服务端，直接显示其本地消息即可。
    if (!conversation.persisted) {
      syncActiveMessages()
      state.mode = conversation.mode
      state.error = ''
      return
    }
    state.messages = []
    try {
      const messages = await fetchConversationMessages(id)
      conversation.messages = messages.map(normalizeMessage)
      syncActiveMessages()
      state.mode = conversation.mode
      state.error = ''
    } catch (error) {
      state.error = formatApiError(apiErrorFromException(error, '加载该会话消息失败。'), '加载该会话消息失败。')
    }
  }

  async function deleteConversation(id) {
    if (state.isStreaming) {
      state.error = '请先停止当前回答，再删除会话。'
      return
    }
    const index = state.conversations.findIndex((conversation) => conversation.id === id)
    if (index < 0) return
    try {
      // 已持久化会话必须先由服务端删除，前端成功后再移除本地列表。
      if (state.conversations[index].persisted) {
        await deleteRemoteConversation(id)
      }
      state.conversations.splice(index, 1)
      if (!state.conversations.length) {
        await newConversation()
      } else if (state.activeConversationId === id) {
        await selectConversation(state.conversations[Math.max(0, index - 1)].id)
      }
      state.error = ''
    } catch (error) {
      state.error = formatApiError(apiErrorFromException(error, REQUEST_ERROR_MESSAGE), REQUEST_ERROR_MESSAGE)
    }
  }

  return {
    state: readonly(state),
    send,
    stop,
    newConversation,
    selectConversation,
    deleteConversation,
    retryLast,
    setUser
  }
}
