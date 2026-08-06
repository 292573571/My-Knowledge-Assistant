<script setup>
import { onMounted, ref } from 'vue'
import SourcePanel from './SourcePanel.vue'
import ToolCallPanel from './ToolCallPanel.vue'
import { fetchWorkbenchStatus } from '../api/statusApi'

defineProps({
  sources: {
    type: Array,
    default: () => []
  },
  toolCalls: {
    type: Array,
    default: () => []
  },
})

defineEmits(['close'])

const status = ref(null)
const statusError = ref('')
const statusLoading = ref(false)

async function loadStatus() {
  statusLoading.value = true
  statusError.value = ''

  try {
    status.value = await fetchWorkbenchStatus()
  } catch (error) {
    statusError.value = error.message || '状态加载失败'
  } finally {
    statusLoading.value = false
  }
}

onMounted(loadStatus)
</script>

<template>
  <aside class="info-panel">
    <div class="info-panel-title">
      <span>工作台信息</span>
      <button type="button" class="info-panel-close" aria-label="关闭信息面板" @click="$emit('close')">关闭</button>
    </div>

    <section class="info-section status-section">
      <div class="status-heading">
        <h2>Runtime</h2>
        <button type="button" :disabled="statusLoading" @click="loadStatus">刷新</button>
      </div>
      <div v-if="statusLoading" class="muted-card">正在加载运行状态...</div>
      <div v-else-if="statusError" class="status-error">{{ statusError }}</div>
      <dl v-else-if="status" class="runtime-status">
        <div><dt>模型</dt><dd>{{ status.provider || '-' }} / {{ status.model || '-' }}</dd></div>
        <div><dt>聊天</dt><dd :class="status.chatClientAvailable ? 'status-ok' : 'status-warning'">{{ status.chatClientAvailable ? 'ChatClient 已装配' : 'ChatClient 不可用' }}</dd></div>
        <div><dt>向量库</dt><dd :class="status.chromaConfigured ? 'status-ok' : 'status-warning'">{{ status.vectorStore }}{{ status.chromaConfigured ? ' 已装配' : '' }}</dd></div>
        <div><dt>索引</dt><dd>{{ status.documentCount }} 个文档 / {{ status.chunkCount }} chunks</dd></div>
      </dl>
    </section>

    <section class="info-section">
      <h2>Sources</h2>
      <SourcePanel :sources="sources" />
    </section>

    <section class="info-section">
      <h2>Tool Calls</h2>
      <ToolCallPanel :tool-calls="toolCalls" />
    </section>

  </aside>
</template>
