<script setup>
import { computed, ref } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import { useDialogFocus } from '../composables/useDialogFocus'

const props = defineProps({
  document: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
const emit = defineEmits(['close', 'retry'])
const dialogRef = ref(null)

const isMarkdown = computed(() => props.document.fileName?.toLowerCase().endsWith('.md'))
const isPdf = computed(() => props.document.fileName?.toLowerCase().endsWith('.pdf'))
const renderedContent = computed(() => isMarkdown.value ? renderMarkdown(props.document.content || '') : '')

useDialogFocus(dialogRef, () => emit('close'))
</script>

<template>
  <Teleport to="body">
    <div class="document-reader-backdrop" @click.self="$emit('close')">
       <section ref="dialogRef" class="document-reader" role="dialog" aria-modal="true" aria-labelledby="document-reader-title">
        <header>
          <div><p>解析文本预览</p><h2 id="document-reader-title">{{ document.fileName }}</h2><small v-if="document.sourceAvailable === false" class="source-missing">源文件已缺失，当前展示从知识库索引恢复的解析文本。</small><small v-else-if="isPdf">这里展示用于知识库检索的解析文本，不是 PDF 原始版面。</small></div>
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
