<script setup>
import { ref } from 'vue'

defineProps({
  toolCalls: {
    type: Array,
    default: () => []
  }
})

const hoveredCall = ref(null)
const tooltipPosition = ref({ x: 0, y: 0 })

function getToolCallKey(call) {
  return `${call.id || call.toolCallId || call.toolName || call.name || call.tool || 'tool'}-${call.durationMs ?? ''}`
}

function formatArguments(args) {
  return JSON.stringify(args || {}, null, 2)
}

function getStatus(call) {
  return call.status || (call.success === false ? 'error' : call.success ? 'success' : 'running')
}

function showTooltip(call, event) {
  hoveredCall.value = call
  moveTooltip(event)
}

function moveTooltip(event) {
  const width = 360
  const height = 340
  const gap = 14
  const rect = event.currentTarget?.getBoundingClientRect?.()
  const clientX = Number.isFinite(event.clientX) ? event.clientX : rect?.right || gap
  const clientY = Number.isFinite(event.clientY) ? event.clientY : rect?.top || gap
  const x = Math.min(clientX + gap, window.innerWidth - width - gap)
  const y = Math.min(clientY + gap, window.innerHeight - height - gap)

  tooltipPosition.value = { x: Math.max(gap, x), y: Math.max(gap, y) }
}

function hideTooltip() {
  hoveredCall.value = null
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
         @mouseenter="showTooltip(call, $event)"
         @mousemove="moveTooltip"
         @mouseleave="hideTooltip"
       >
        <div class="tool-call-header">
          <strong>{{ call.toolName || call.name || call.tool || '工具' }}</strong>
          <span :class="getStatus(call)">
            {{ getStatus(call) }}
          </span>
        </div>
      </article>

      <Teleport to="body">
        <aside
          v-if="hoveredCall"
          class="tool-call-tooltip"
          :style="{ left: `${tooltipPosition.x}px`, top: `${tooltipPosition.y}px` }"
        >
          <div class="tool-call-header">
            <strong>{{ hoveredCall.toolName || hoveredCall.name || hoveredCall.tool || '工具' }}</strong>
            <span :class="getStatus(hoveredCall)">{{ getStatus(hoveredCall) }}</span>
          </div>
          <label>arguments</label>
          <pre>{{ formatArguments(hoveredCall.arguments || hoveredCall.input) }}</pre>
          <label>resultPreview</label>
          <p>{{ hoveredCall.resultPreview || hoveredCall.result || hoveredCall.output || '暂无结果预览' }}</p>
          <dl><div><dt>durationMs</dt><dd>{{ hoveredCall.durationMs ?? '-' }}</dd></div></dl>
        </aside>
      </Teleport>
    </template>
  </div>
</template>
