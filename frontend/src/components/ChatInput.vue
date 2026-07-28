<script setup>
import { ref } from 'vue'

defineProps({
  disabled: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['send', 'stop'])
const text = ref('')

function submit() {
  const value = text.value.trim()
  if (!value) return
  emit('send', value)
  text.value = ''
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submit()
  }
}
</script>

<template>
  <form class="chat-input" @submit.prevent="submit">
    <textarea
      v-model="text"
      :disabled="disabled"
      rows="1"
      placeholder="问点什么，或从知识库中检索答案..."
      @keydown="onKeydown"
    ></textarea>
    <div class="send-row">
      <span>Enter 发送 · Shift + Enter 换行</span>
      <div>
        <button v-if="disabled" type="button" class="secondary" @click="emit('stop')">停</button>
        <button v-else type="submit" class="send-button">➤</button>
      </div>
    </div>
  </form>
</template>
