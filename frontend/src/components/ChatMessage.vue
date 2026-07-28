<script setup>
import { computed } from 'vue'
import LoadingDots from './LoadingDots.vue'
import ToolCallPanel from './ToolCallPanel.vue'
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

const html = computed(() => renderMarkdown(props.message.content))
const roleLabel = computed(() => (props.message.role === 'user' ? '你' : '助手'))
const isAssistant = computed(() => props.message.role === 'assistant')
const isModelSupplement = computed(() => isAssistant.value && !props.message.error && !props.message.sources?.length
  && props.message.content?.includes('以上回答基于通用大模型知识'))
function sourceLabel(source) {
  const file = source.fileName || source.file || source.title || source.name || '来源'
  const heading = source.headingPath ? ` / ${source.headingPath}` : ''

  return `${file}${heading}`
}
const timeLabel = computed(() => {
  if (!props.message.createdAt) return ''

  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(props.message.createdAt))
})
</script>

<template>
  <article v-if="message.role === 'user' || message.role === 'assistant'" class="message" :class="`message-${message.role}`">
    <div class="avatar">{{ roleLabel }}</div>
    <div class="message-body">
      <div class="message-meta">
        <strong>{{ roleLabel }}</strong>
        <time v-if="timeLabel" :datetime="message.createdAt">{{ timeLabel }}</time>
      </div>
      <LoadingDots v-if="streaming" />
      <div v-if="!streaming" class="markdown-body" v-html="html"></div>
      <div v-if="isAssistant && message.error" class="message-alert error">
        <strong>模型或后端调用失败</strong>
        <span>{{ message.error }}</span>
      </div>
      <div v-else-if="isAssistant && message.noRagMatch" class="message-alert warning">
        <strong>RAG 未命中</strong>
        <span>当前知识库没有找到足够依据，已尝试使用通用大模型知识补充回答。</span>
      </div>
      <div v-else-if="isAssistant && message.noSources && !isModelSupplement" class="message-alert info">
        <strong>未返回引用来源</strong>
        <span>本次回答没有使用本地知识库来源，可能基于通用大模型知识生成。</span>
      </div>
      <div v-if="!streaming && isModelSupplement" class="model-supplement-label"><strong>模型补充</strong><span>不含本地资料依据，请对关键事实进行核实。</span></div>
      <ToolCallPanel v-if="isAssistant && message.toolCalls?.length" :tool-calls="message.toolCalls" />
      <div v-if="isAssistant && message.sources?.length" class="source-chips">
        <span v-for="source in message.sources" :key="`${sourceLabel(source)}-${source.chunkIndex ?? ''}`">
          {{ sourceLabel(source) }}
        </span>
      </div>
    </div>
  </article>
</template>
