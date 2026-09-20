<script setup>
import { ref } from 'vue'
import AgentChatPanel from './AgentChatPanel.vue'

const props = defineProps({
  // 透传给 AgentChatPanel
  kicker: { type: String, default: 'AI AGENT' },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  badge: { type: String, default: '只读' },
  icon: { type: String, default: 'bot' },
  suggestions: { type: Array, default: () => [] },
  placeholder: { type: String, default: '描述你的问题…' },
  sendLabel: { type: String, default: '发送' },
  loadingLabel: { type: String, default: '思考中…' },
  emptyTitle: { type: String, default: '有什么可以帮你？' },
  emptyHint: { type: String, default: '试试下面的常见问题，或直接描述你的问题。' },
  send: { type: Function, required: true },
  confirm: { type: Function, default: null },
  confirmHint: { type: Function, default: null },
  // 悬浮入口专属
  label: { type: String, default: '打开智能客服' }
})

const isOpen = ref(false)
const titleId = `floating-agent-title-${Math.random().toString(36).slice(2, 8)}`

function toggle() {
  isOpen.value = !isOpen.value
}

function close() {
  isOpen.value = false
}
</script>

<template>
  <div class="floating-agent-launcher">
    <button
      v-if="!isOpen"
      type="button"
      class="floating-agent-button"
      :aria-label="label"
      :aria-expanded="isOpen"
      @click="toggle"
    >
      <span class="floating-agent-icon" aria-hidden="true">
        <svg viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
          <!-- 耳机头梁 -->
          <path d="M19 27c0-9 6-16 13-16s13 7 13 16" stroke="#d4c4a8" stroke-width="4" stroke-linecap="round" fill="none"/>
          <!-- 耳罩 -->
          <rect x="13" y="27" width="11" height="18" rx="5.5" fill="#d4c4a8"/>
          <rect x="40" y="27" width="11" height="18" rx="5.5" fill="#d4c4a8"/>
          <!-- 猫耳 -->
          <path d="M18 23l-5-12 10 7-5 5z" fill="#2a2a35"/>
          <path d="M46 23l5-12-10 7 5 5z" fill="#2a2a35"/>
          <!-- 脸 -->
          <circle cx="32" cy="36" r="17" fill="#2a2a35"/>
          <!-- 眼睛 -->
          <path d="M24 36c2 2 4 2 6 0" stroke="#f7e6a3" stroke-width="2.5" stroke-linecap="round" fill="none"/>
          <path d="M34 36c2 2 4 2 6 0" stroke="#f7e6a3" stroke-width="2.5" stroke-linecap="round" fill="none"/>
          <!-- 嘴 -->
          <path d="M26 43c4 3 8 3 12 0" stroke="#f7e6a3" stroke-width="2.5" stroke-linecap="round" fill="none"/>
        </svg>
      </span>
    </button>

    <div
      v-if="isOpen"
      class="floating-agent-dialog"
      role="dialog"
      :aria-labelledby="titleId"
      aria-modal="true"
    >
      <header class="floating-agent-dialog-header">
        <div class="floating-agent-dialog-title">
          <span class="agent-chat-mark" aria-hidden="true">
            <svg v-if="icon === 'help'" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.25-.9.75-.9 1.4v.3"/><path d="M12 17h.01"/></svg>
            <svg v-else-if="icon === 'wrench'" viewBox="0 0 24 24"><path d="M14.7 6.3a4 4 0 0 0-5 5L4 17l3 3 5.7-5.7a4 4 0 0 0 5-5l-2.2 2.2-3-3 2.2-2.2Z"/></svg>
            <svg v-else viewBox="0 0 24 24"><path d="M12 3.5a8.5 8.5 0 0 0-8.5 8.5c0 1.8.56 3.47 1.52 4.84L4 20.5l4.05-1.02A8.5 8.5 0 1 0 12 3.5Z"/><path d="M8 12h.01M12 12h.01M16 12h.01"/></svg>
          </span>
          <div>
            <p class="agent-chat-kicker">{{ kicker }}</p>
            <h2 :id="titleId">{{ title }}</h2>
          </div>
        </div>
        <div class="floating-agent-dialog-actions">
          <span class="agent-chat-badge">{{ badge }}</span>
          <button type="button" class="floating-agent-close" aria-label="关闭" @click="close">×</button>
        </div>
      </header>
      <AgentChatPanel
        :show-header="false"
        :kicker="kicker"
        :title="title"
        :description="description"
        :badge="badge"
        :icon="icon"
        :suggestions="suggestions"
        :placeholder="placeholder"
        :send-label="sendLabel"
        :loading-label="loadingLabel"
        :empty-title="emptyTitle"
        :empty-hint="emptyHint"
        :send="send"
        :confirm="confirm"
        :confirm-hint="confirmHint"
      />
    </div>
  </div>
</template>
