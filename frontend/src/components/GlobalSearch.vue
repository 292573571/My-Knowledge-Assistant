<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { globalSearch } from '../api/searchApi'
import { formatApiError } from '../api/apiError'

const emit = defineEmits(['navigate'])

const query = ref('')
const open = ref(false)
const loading = ref(false)
const error = ref('')
const hits = ref([])
const boxRef = ref(null)
const inputRef = ref(null)

let debounceTimer = null
let searchSeq = 0

const grouped = computed(() => ({
  docs: hits.value.filter(hit => hit.type === 'DOCUMENT'),
  wiki: hits.value.filter(hit => hit.type === 'WIKI')
}))

const hasResults = computed(() => hits.value.length > 0)

function onInput() {
  if (debounceTimer) window.clearTimeout(debounceTimer)
  const value = query.value.trim()
  if (value.length < 2) {
    hits.value = []
    error.value = ''
    loading.value = false
    return
  }
  loading.value = true
  debounceTimer = window.setTimeout(() => runSearch(value), 350)
}

async function runSearch(value) {
  const seq = ++searchSeq
  try {
    const result = await globalSearch(value, 12)
    if (seq !== searchSeq) return
    hits.value = Array.isArray(result) ? result : []
    error.value = ''
  } catch (exception) {
    if (seq !== searchSeq) return
    hits.value = []
    error.value = formatApiError(exception, '搜索失败，请稍后重试。')
  } finally {
    if (seq === searchSeq) loading.value = false
  }
}

function select(hit) {
  open.value = false
  query.value = ''
  hits.value = []
  emit('navigate', hit)
}

function onDocumentKeydown(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open.value = true
    inputRef.value?.focus()
  } else if (event.key === 'Escape' && open.value) {
    open.value = false
    inputRef.value?.blur()
  }
}

function onClickOutside(event) {
  if (boxRef.value && !boxRef.value.contains(event.target)) open.value = false
}

onMounted(() => {
  document.addEventListener('keydown', onDocumentKeydown)
  document.addEventListener('mousedown', onClickOutside)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onDocumentKeydown)
  document.removeEventListener('mousedown', onClickOutside)
  if (debounceTimer) window.clearTimeout(debounceTimer)
})
</script>

<template>
  <div ref="boxRef" class="global-search">
    <div class="global-search-field" :class="{ open }">
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6"/><path d="m16 16 4.5 4.5"/></svg>
      <input
        ref="inputRef"
        v-model="query"
        type="search"
        placeholder="搜索知识库…"
        aria-label="全局搜索"
        @focus="open = true"
        @input="onInput"
      >
      <kbd>Ctrl K</kbd>
    </div>
    <div v-if="open && query.trim().length >= 2" class="global-search-dropdown" role="listbox" aria-label="搜索结果">
      <div v-if="loading" class="global-search-status">搜索中…</div>
      <div v-else-if="error" class="global-search-status error">{{ error }}</div>
      <div v-else-if="!hasResults" class="global-search-status">没有匹配的文档或笔记。</div>
      <template v-else>
        <p v-if="grouped.docs.length" class="global-search-group-label">文档</p>
        <button v-for="hit in grouped.docs" :key="`d-${hit.documentId}`" type="button" class="global-search-hit" @click="select(hit)">
          <strong>{{ hit.title }}</strong>
          <span>{{ hit.snippet }}</span>
          <em v-if="hit.pageNumber">第 {{ hit.pageNumber }} 页</em>
        </button>
        <p v-if="grouped.wiki.length" class="global-search-group-label">知识笔记</p>
        <button v-for="hit in grouped.wiki" :key="`w-${hit.documentId}`" type="button" class="global-search-hit" @click="select(hit)">
          <strong>{{ hit.title }}</strong>
          <span>{{ hit.snippet }}</span>
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.global-search {
  position: relative;
}

.global-search-field {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--site-border, #d8ded4);
  border-radius: 999px;
  padding: 5px 12px;
  background: var(--site-surface, #fff);
  transition: border-color 0.15s ease;
}

.global-search-field.open,
.global-search-field:focus-within {
  border-color: var(--site-accent, #3a7d5c);
}

.global-search-field svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: var(--site-muted, #718074);
  stroke-width: 1.8;
  stroke-linecap: round;
}

.global-search-field input {
  width: 150px;
  border: 0;
  outline: 0;
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: var(--site-ink, #17221b);
}

.global-search-field kbd {
  border: 1px solid var(--site-border, #d8ded4);
  border-radius: 4px;
  padding: 0 4px;
  font-size: 10px;
  color: var(--site-muted, #718074);
}

.global-search-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  width: min(420px, 90vw);
  max-height: 420px;
  overflow-y: auto;
  background: var(--site-surface, #fff);
  border: 1px solid var(--site-border, #d8ded4);
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  padding: 6px;
  z-index: 60;
}

.global-search-status {
  padding: 12px;
  font-size: 13px;
  color: var(--site-muted, #718074);
}

.global-search-status.error {
  color: #b4372f;
}

.global-search-group-label {
  margin: 8px 8px 2px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--site-muted, #718074);
}

.global-search-hit {
  display: grid;
  gap: 2px;
  width: 100%;
  border: 0;
  background: transparent;
  text-align: left;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
}

.global-search-hit:hover {
  background: var(--site-hover, rgba(0, 0, 0, 0.04));
}

.global-search-hit strong {
  font-size: 13px;
  color: var(--site-ink, #17221b);
}

.global-search-hit span {
  font-size: 12px;
  color: var(--site-muted, #718074);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.global-search-hit em {
  font-size: 11px;
  font-style: normal;
  color: var(--site-accent, #3a7d5c);
}

@media (max-width: 720px) {
  .global-search-field input {
    width: 90px;
  }

  .global-search-field kbd {
    display: none;
  }
}
</style>
