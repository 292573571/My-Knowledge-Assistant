<script setup>
import { computed, ref } from 'vue'
import { deduplicateDisplayedSources } from '../utils/sources'

const props = defineProps({
  sources: {
    type: Array,
    default: () => []
  },
  emptyMessage: {
    type: String,
    default: '如果当前是 RAG 模式，说明本次回答没有返回 source；可能是知识库未命中、后端降级回答，或接口没有携带 sources。'
  }
})

const hoveredSource = ref(null)
const tooltipPosition = ref({ x: 0, y: 0 })
const displayedSources = computed(() => deduplicateDisplayedSources(props.sources))

function getSourceKey(source) {
  return `${source.file || source.title || source.name || 'source'}-${source.pageNumber ?? ''}-${source.chunkIndex ?? source.id ?? ''}`
}

function getFileName(source) {
  return source.fileName || source.file || source.title || source.name || '未知文件'
}

function getHeadingPath(source) {
  const value = source.headingPath

  if (Array.isArray(value)) {
    return value.filter(Boolean).join(' > ')
  }

  return value || ''
}

function getPageLabel(source) {
  return source.pageNumber ? `第 ${source.pageNumber} 页` : ''
}

function showSourceTooltip(source, event) {
  hoveredSource.value = source
  moveSourceTooltip(event)
}

function moveSourceTooltip(event) {
  const tooltipWidth = 330
  const tooltipHeight = 260
  const gap = 14
  const x = Math.min(event.clientX + gap, window.innerWidth - tooltipWidth - gap)
  const y = Math.min(event.clientY + gap, window.innerHeight - tooltipHeight - gap)

  tooltipPosition.value = { x: Math.max(gap, x), y: Math.max(gap, y) }
}

function hideSourceTooltip() {
  hoveredSource.value = null
}

</script>

<template>
  <div class="source-panel">
    <div v-if="!displayedSources.length" class="muted-card source-empty">
      <strong>暂无引用来源</strong>
      <span>{{ emptyMessage }}</span>
    </div>
    <template v-else>
      <article
        v-for="source in displayedSources"
        :key="getSourceKey(source)"
        class="source-card"
        @mouseenter="showSourceTooltip(source, $event)"
        @mousemove="moveSourceTooltip"
        @mouseleave="hideSourceTooltip"
      >
        <div class="source-card-toggle">
          <div class="source-card-header">
            <strong>{{ getFileName(source) }}</strong>
          </div>
          <small v-if="getPageLabel(source)" class="source-heading-path">{{ getPageLabel(source) }}</small>
          <small v-else-if="getHeadingPath(source)" class="source-heading-path">{{ getHeadingPath(source) }}</small>
          <small v-else class="source-heading-path">未返回标题路径</small>
        </div>
      </article>

      <Teleport to="body">
        <aside
          v-if="hoveredSource"
          class="source-hover-tooltip"
          :style="{ left: `${tooltipPosition.x}px`, top: `${tooltipPosition.y}px` }"
        >
          <strong>{{ getFileName(hoveredSource) }}</strong>
          <dl>
            <div>
               <dt>位置</dt>
               <dd>{{ getHeadingPath(hoveredSource) || '-' }}</dd>
            </div>
            <div>
               <dt>页码</dt>
               <dd>{{ getPageLabel(hoveredSource) || '-' }}</dd>
            </div>
          </dl>
           <p>{{ hoveredSource.snippet || hoveredSource.excerpt || hoveredSource.content || '暂无内容预览' }}</p>
        </aside>
      </Teleport>

    </template>
  </div>
</template>
