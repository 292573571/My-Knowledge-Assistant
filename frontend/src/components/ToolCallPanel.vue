<script setup>
import { ref } from 'vue'

defineProps({
  toolCalls: {
    type: Array,
    default: () => []
  }
})

const expandedKeys = ref(new Set())

function getToolCallKey(call) {
  return `${call.id || call.toolCallId || call.toolName || call.name || call.tool || 'tool'}-${call.durationMs ?? ''}`
}

function toggleToolCall(call) {
  const key = getToolCallKey(call)
  const next = new Set(expandedKeys.value)

  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }

  expandedKeys.value = next
}

function formatArguments(args) {
  return JSON.stringify(args || {}, null, 2)
}

function getStatus(call) {
  return call.status || (call.success === false ? 'error' : call.success ? 'success' : 'running')
}
</script>

<template>
  <div class="tool-panel">
    <div v-if="!toolCalls.length" class="muted-card">暂无工具调用</div>
    <template v-else>
      <article
        v-for="call in toolCalls"
        :key="getToolCallKey(call)"
        class="tool-call"
      >
        <button type="button" class="tool-call-toggle" @click="toggleToolCall(call)">
          <div class="tool-call-header">
            <strong>{{ call.toolName || call.name || call.tool || '工具' }}</strong>
            <span :class="getStatus(call)">
              {{ getStatus(call) }}
            </span>
          </div>
          <small>{{ expandedKeys.has(getToolCallKey(call)) ? '收起' : '展开' }}</small>
        </button>

        <div v-if="expandedKeys.has(getToolCallKey(call))" class="tool-call-detail">
          <label>arguments</label>
          <pre>{{ formatArguments(call.arguments || call.input) }}</pre>
          <label>resultPreview</label>
          <pre>{{ call.resultPreview || call.result || call.output || '暂无结果预览' }}</pre>
          <dl>
            <div>
              <dt>durationMs</dt>
              <dd>{{ call.durationMs ?? '-' }}</dd>
            </div>
          </dl>
        </div>
      </article>
    </template>
  </div>
</template>
