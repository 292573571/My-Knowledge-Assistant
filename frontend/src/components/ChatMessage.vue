<script setup>
import { computed, ref } from 'vue'
import LoadingDots from './LoadingDots.vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  message: {
    type: Object,
    required: true
  },
  streaming: {
    type: Boolean,
    default: false
  }
})

const copiedCode = ref('')
const displayContent = computed(() => (props.message.content || '')
  .replace(/^\s*以上回答基于通用大模型知识，不是当前知识库内容。\s*$/gm, '')
  .trim())
const html = computed(() => renderMarkdown(displayContent.value))
const roleLabel = computed(() => (props.message.role === 'user' ? '你' : '助手'))
const isAssistant = computed(() => props.message.role === 'assistant')
const isModelSupplement = computed(() => isAssistant.value && !props.message.error && !props.message.sources?.length
  && props.message.content?.includes('以上回答基于通用大模型知识'))
function sourceLabel(source) {
  const file = source.fileName || source.file || source.title || source.name || '来源'
  const page = source.pageNumber ? ` / 第 ${source.pageNumber} 页` : ''
  const heading = source.headingPath ? ` / ${source.headingPath}` : ''

  return `${file}${page}${heading}`
}
const timeLabel = computed(() => {
  if (!props.message.createdAt) return ''

  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(props.message.createdAt))
})

async function copyCode(event) {
  const button = event.target.closest('.code-copy-button')
  if (!button) return
  const code = button.closest('.code-block')?.querySelector('code')?.textContent || ''
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    copiedCode.value = code
    button.textContent = '已复制'
    window.setTimeout(() => {
      if (button.isConnected) button.textContent = '复制'
      if (copiedCode.value === code) copiedCode.value = ''
    }, 1500)
  } catch {
    button.textContent = '复制失败'
  }
}
</script>

<template>
  <article v-if="message.role === 'user' || (message.role === 'assistant' && (message.streaming || message.content || message.error))" class="message" :class="`message-${message.role}`">
    <div class="avatar">{{ roleLabel }}</div>
    <div class="message-body">
      <div class="message-meta">
        <strong>{{ roleLabel }}</strong>
        <time v-if="timeLabel" :datetime="message.createdAt">{{ timeLabel }}</time>
      </div>
      <LoadingDots v-if="streaming && !message.content" />
      <div v-if="message.content" class="markdown-body" @click="copyCode" v-html="html"></div>
      <div v-if="isAssistant && message.error" class="message-alert error">
        <strong>模型或后端调用失败</strong>
        <span>{{ message.error }}</span>
      </div>
      <div v-else-if="isAssistant && message.noRagMatch" class="message-alert warning">
        <strong>RAG 未命中</strong>
        <span>当前知识库没有找到足够依据，已尝试使用通用大模型知识补充回答。</span>
      </div>
      <div v-if="!streaming && isModelSupplement" class="model-supplement-label"><strong>模型补充</strong><span>不含本地资料依据，请对关键事实进行核实。</span></div>
      <div v-if="isAssistant && message.sources?.length" class="source-chips">
        <span v-for="source in message.sources" :key="`${sourceLabel(source)}-${source.chunkIndex ?? ''}`">
          {{ sourceLabel(source) }}
        </span>
      </div>
    </div>
  </article>
</template>
