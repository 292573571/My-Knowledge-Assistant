<script setup>
import { ref } from 'vue'
import AgentChatPanel from './AgentChatPanel.vue'

const props = defineProps({
  title: { type: String, required: true },
  suggestions: { type: Array, default: () => [] },
  welcome: { type: String, default: '' },
  placeholder: { type: String, default: '描述你的问题…' },
  sendLabel: { type: String, default: '发送' },
  loadingLabel: { type: String, default: '思考中…' },
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
        <h2 :id="titleId">{{ title }}</h2>
        <button type="button" class="floating-agent-close" aria-label="关闭" @click="close">×</button>
      </header>
      <AgentChatPanel
        :show-header="false"
        :title="title"
        :suggestions="suggestions"
        :welcome="welcome"
        :placeholder="placeholder"
        :send-label="sendLabel"
        :loading-label="loadingLabel"
        :confirm="confirm"
        :confirm-hint="confirmHint"
        :send="send"
      />
    </div>
  </div>
</template>
