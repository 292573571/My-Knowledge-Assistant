<script setup>
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  document: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
const emit = defineEmits(['close', 'retry'])

const isMarkdown = computed(() => props.document.fileName?.toLowerCase().endsWith('.md'))
const renderedContent = computed(() => isMarkdown.value ? renderMarkdown(props.document.content || '') : '')

function handleKeydown(event) {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <Teleport to="body">
    <div class="document-reader-backdrop" @click.self="$emit('close')">
      <section class="document-reader" role="dialog" aria-modal="true" aria-labelledby="document-reader-title">
        <header>
          <div><p>DOCUMENT PREVIEW</p><h2 id="document-reader-title">{{ document.fileName }}</h2><span>{{ document.path }}</span></div>
          <button type="button" aria-label="关闭文件内容" @click="$emit('close')">×</button>
        </header>
        <div v-if="loading" class="document-reader-state">正在读取文件内容...</div>
        <div v-else-if="error" class="document-reader-state error"><span>{{ error }}</span><button type="button" @click="$emit('retry')">重新加载</button></div>
        <article v-else-if="isMarkdown" class="document-reader-content markdown-body" v-html="renderedContent"></article>
        <pre v-else class="document-reader-text">{{ document.content }}</pre>
      </section>
    </div>
  </Teleport>
</template>
