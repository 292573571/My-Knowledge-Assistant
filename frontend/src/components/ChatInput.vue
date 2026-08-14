<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  disabled: {
    type: Boolean,
    default: false
  },
  stoppable: {
    type: Boolean,
    default: false
  },
  mode: {
    type: String,
    default: 'AUTO'
  },
  modeLabels: {
    type: Object,
    default: () => ({})
  }
})

const emit = defineEmits(['send', 'stop', 'update:mode'])
const text = ref('')
const isComposing = ref(false)
const modeMenuOpen = ref(false)
const modeButton = ref(null)
const modeLabel = computed(() => props.modeLabels[props.mode] || '选择模式')

const modeIcons = {
  AUTO: 'M7 5h10M5 9h14M7 13h10M9 17h6',
  CHAT: 'M5 6.5A2.5 2.5 0 0 1 7.5 4h9A2.5 2.5 0 0 1 19 6.5v5a2.5 2.5 0 0 1-2.5 2.5H11l-4 3v-3.4a2.5 2.5 0 0 1-2-2.4z',
  GUIDED: 'M12 4l1.8 5.2L19 11l-5.2 1.8L12 18l-1.8-5.2L5 11l5.2-1.8z',
  REVIEW: 'M6 5.5h12v13H6z M9 3.5v4M15 3.5v4M9 12h6M9 15h4',
  PRACTICE: 'M8 4h8v4H8z M6 8h12v11H6z M9 12h6M9 15h4'
}

function submit() {
  const value = text.value.trim()
  if (!value) return
  emit('send', value)
  text.value = ''
}

function onKeydown(event) {
  // 中文等输入法会用 Enter 确认候选内容，不能将该事件当作发送指令。
  if (isComposing.value || event.isComposing || event.keyCode === 229) return

  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submit()
  }
}

function chooseMode(value) {
  emit('update:mode', value)
  modeMenuOpen.value = false
}

function closeMenus(event) {
  if (!event.target.closest('.chat-mode-menu')) {
    modeMenuOpen.value = false
  }
}

function onWindowKeydown(event) {
  if (event.key !== 'Escape') return
  modeMenuOpen.value = false
  modeButton.value?.focus()
}

onMounted(() => {
  document.addEventListener('click', closeMenus)
  document.addEventListener('keydown', onWindowKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', closeMenus)
  document.removeEventListener('keydown', onWindowKeydown)
})
</script>

<template>
  <form class="chat-input" @submit.prevent="submit">
    <textarea
      v-model="text"
      :disabled="disabled"
      rows="1"
      placeholder="问点什么，或从知识库中检索答案..."
      @keydown="onKeydown"
      @compositionstart="isComposing = true"
      @compositionend="isComposing = false"
    ></textarea>
    <div class="send-row">
      <div class="chat-input-controls">
        <div class="chat-mode-menu">
          <button ref="modeButton" type="button" class="chat-action-button" :disabled="disabled" :aria-expanded="modeMenuOpen" aria-haspopup="menu" aria-label="选择学习模式" @click.stop="modeMenuOpen = !modeMenuOpen">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="modeIcons[mode] || modeIcons.AUTO" /></svg>
            <span>{{ modeLabel }}</span>
            <svg class="chat-action-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 9 5 5 5-5" /></svg>
          </button>
          <div v-if="modeMenuOpen" class="chat-mode-popover" role="menu" aria-label="学习模式">
            <button v-for="(label, value) in modeLabels" :key="value" type="button" role="menuitemradio" :aria-checked="mode === value" :class="{ active: mode === value }" @click="chooseMode(value)">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="modeIcons[value] || modeIcons.AUTO" /></svg><span>{{ label }}</span><b v-if="mode === value" aria-hidden="true">✓</b>
            </button>
          </div>
        </div>
        <span>Enter 发送 · Shift + Enter 换行</span>
      </div>
      <div>
        <button v-if="disabled && stoppable" type="button" class="secondary" aria-label="停止生成" title="停止生成" @click="emit('stop')"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1.5" /></svg></button>
        <button v-else type="submit" class="send-button" :disabled="disabled" aria-label="发送问题" title="发送问题"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 13-7-3.5 7L18 17zM14.5 12H5" /></svg></button>
      </div>
    </div>
  </form>
</template>
