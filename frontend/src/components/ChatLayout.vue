<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import ChatInput from './ChatInput.vue'
import ChatMessage from './ChatMessage.vue'
import ConversationSidebar from './ConversationSidebar.vue'
import InfoPanel from './InfoPanel.vue'
import { useChatStore } from '../stores/chatStore'

const props = defineProps({
  userName: {
    type: String,
    default: ''
  }
})
const emit = defineEmits(['logout'])

const { state, send, stop, retryLast, newConversation, selectConversation, deleteConversation } = useChatStore()
const messagesEl = ref(null)
const shouldAutoScroll = ref(true)
const infoPanelOpen = ref(false)
const BOTTOM_THRESHOLD = 80
const starterPrompts = [
  '总结当前知识库的主要内容',
  'Spring AI 如何实现 RAG？',
  '帮我梳理文档导入和重建流程'
]

const latestInfo = computed(() => {
  const conversation = state.conversations.find((item) => item.id === state.activeConversationId)
  const latestAssistant = [...state.messages].reverse().find((message) => message.role === 'assistant') || {}

  return {
    ...latestAssistant,
    sources: conversation?.sources?.length ? conversation.sources : latestAssistant.sources || [],
    toolCalls: conversation?.toolCalls?.length ? conversation.toolCalls : latestAssistant.toolCalls || []
  }
})

const activeConversation = computed(() => {
  return state.conversations.find((item) => item.id === state.activeConversationId)
})

watch(
  () => state.messages.map((message) => `${message.content}:${message.sources?.length || 0}:${message.toolCalls?.length || 0}`).join('|'),
  async () => {
    await nextTick()
    if (messagesEl.value && shouldAutoScroll.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  }
)

function handleMessagesScroll() {
  if (!messagesEl.value) return

  const { scrollTop, scrollHeight, clientHeight } = messagesEl.value
  shouldAutoScroll.value = scrollHeight - scrollTop - clientHeight < BOTTOM_THRESHOLD
}
</script>

<template>
    <div class="app-shell assistant-shell">
    <div class="workspace-grid">
      <ConversationSidebar
        :conversations="state.conversations"
        :active-id="state.activeConversationId"
        :deleting-disabled="state.isStreaming"
        @new="newConversation"
        @select="selectConversation"
        @delete="deleteConversation"
      />

      <main class="chat-panel">
        <div class="chat-panel-header">
          <div>
            <h2>{{ activeConversation?.title || '新的对话' }}</h2>
            <p>和你的 AI 学习助理一起梳理问题与知识</p>
          </div>
          <div class="chat-header-actions">
            <button type="button" class="info-panel-toggle" @click="infoPanelOpen = true">信息</button>
          </div>
        </div>

        <section ref="messagesEl" class="messages" aria-label="聊天消息" @scroll="handleMessagesScroll">
          <div v-if="!state.messages.length" class="empty-state">
            <div class="assistant-orbit">✦</div>
            <h2>您好，我是您的AI学习助理</h2>
            <p>直接问你正在卡住的问题。我会优先参考已导入资料，并清楚标注回答依据。</p>
            <div class="starter-prompts">
              <button v-for="prompt in starterPrompts" :key="prompt" type="button" @click="send(prompt)">
                {{ prompt }}
              </button>
            </div>
          </div>

          <ChatMessage
            v-for="message in state.messages"
            :key="message.id"
            :message="message"
            :streaming="message.streaming && !message.content"
          />
        </section>

        <div v-if="state.error" class="error-text">
          <strong>请求失败</strong>
          <span>{{ state.error }}</span>
          <button v-if="state.lastFailedMessage && !state.isStreaming" type="button" @click="retryLast">重试上一条</button>
        </div>

        <footer class="input-bar">
          <ChatInput :disabled="state.isStreaming" @send="send" @stop="stop" />
        </footer>
      </main>

      <InfoPanel
        :class="{ 'is-open': infoPanelOpen }"
        :sources="latestInfo.sources"
        :tool-calls="latestInfo.toolCalls"
        :search-results="latestInfo.searchResults"
        :file-results="latestInfo.fileResults"
        @close="infoPanelOpen = false"
      />
      <button
        v-if="infoPanelOpen"
        type="button"
        class="info-panel-backdrop"
        aria-label="关闭信息面板"
        @click="infoPanelOpen = false"
      />
    </div>

  </div>
</template>
