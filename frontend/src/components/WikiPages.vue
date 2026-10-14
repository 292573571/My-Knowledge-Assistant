<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { formatApiError } from '../api/apiError'
import {
  createWikiPage,
  deleteWikiPage,
  fetchWikiPage,
  fetchWikiPages,
  updateWikiPage
} from '../api/wikiPageApi'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  workspace: { type: Object, default: null },
  openPageId: { type: String, default: null }
})
const emit = defineEmits(['wikiPageOpened'])

const pages = ref([])
const selectedId = ref(null)
const selected = ref(null)
const loading = ref(true)
const reading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')

// 编辑表单
const draft = ref({ title: '', slug: '', parentId: '', contentMd: '' })
const previewMode = ref(false)

const workspaceId = computed(() => props.workspace?.id || '')

const tree = computed(() => {
  const map = new Map()
  pages.value.forEach((page) => map.set(page.id, { ...page, children: [] }))
  const roots = []
  map.forEach((node) => {
    if (node.parentId && map.has(node.parentId)) map.get(node.parentId).children.push(node)
    else roots.push(node)
  })
  const sortRec = (nodes) => {
    nodes.sort((a, b) => a.title.localeCompare(b.title, 'zh-Hans-CN'))
    nodes.forEach((node) => sortRec(node.children))
  }
  sortRec(roots)
  return roots
})

const flatTree = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    nodes.forEach((node) => {
      out.push({ node, depth })
      walk(node.children, depth + 1)
    })
  }
  walk(tree.value, 0)
  return out
})

const previewHtml = computed(() => renderMarkdown(draft.value.contentMd || '', { wikiLinks: true }))

// 预览区点击 wiki 链接：按标题/短链在当前空间匹配页面并打开，未命中给出提示。
function onPreviewClick(event) {
  const link = event.target.closest('a.wiki-link')
  if (!link) return
  event.preventDefault()
  const title = link.getAttribute('data-wiki-title') || ''
  if (!title) return
  const page = pages.value.find((p) => p.title === title || p.slug === title)
  if (page) {
    notice.value = ''
    selectPage(page)
  } else {
    notice.value = `未找到名为「${title}」的页面，可新建后用 [[${title}]] 关联。`
  }
}

const parentOptions = computed(() => {
  // 可选上级：除自己以外的所有页面（简单处理，P1 不做后代闭环校验）
  return pages.value.filter((page) => page.id !== selectedId.value)
})

async function load() {
  loading.value = true
  error.value = ''
  selectedId.value = null
  selected.value = null
  draft.value = { title: '', slug: '', parentId: '', contentMd: '' }
  if (!workspaceId.value) {
    pages.value = []
    loading.value = false
    return
  }
  try {
    pages.value = await fetchWikiPages(workspaceId.value)
  } catch (exception) {
    error.value = formatApiError(exception, 'wiki 页面加载失败。')
  } finally {
    loading.value = false
  }
}

async function selectPage(page) {
  reading.value = true
  error.value = ''
  try {
    const detail = await fetchWikiPage(page.id, workspaceId.value)
    selectedId.value = detail.id
    selected.value = detail
    draft.value = {
      title: detail.title,
      slug: detail.slug,
      parentId: detail.parentId || '',
      contentMd: detail.contentMd || ''
    }
    previewMode.value = false
    notice.value = ''
  } catch (exception) {
    error.value = formatApiError(exception, 'wiki 页面加载失败。')
  } finally {
    reading.value = false
  }
}

function newPage() {
  selectedId.value = null
  selected.value = null
  draft.value = { title: '', slug: '', parentId: '', contentMd: '' }
  previewMode.value = false
  error.value = ''
  notice.value = ''
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  const body = {
    title: draft.value.title,
    slug: draft.value.slug || undefined,
    parentId: draft.value.parentId || undefined,
    contentMd: draft.value.contentMd
  }
  try {
    if (selectedId.value) {
      await updateWikiPage(selectedId.value, workspaceId.value, body)
    } else {
      const created = await createWikiPage(workspaceId.value, body)
      selectedId.value = created.id
    }
    notice.value = '已保存，页面已进入知识库检索。'
    await load()
    if (selectedId.value) {
      const refreshed = pages.value.find((page) => page.id === selectedId.value)
      if (refreshed) await selectPage(refreshed)
    }
  } catch (exception) {
    error.value = formatApiError(exception, 'wiki 页面保存失败。')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!selectedId.value) return
  saving.value = true
  error.value = ''
  try {
    await deleteWikiPage(selectedId.value, workspaceId.value)
    notice.value = '页面已删除。'
    await load()
  } catch (exception) {
    error.value = formatApiError(exception, 'wiki 页面删除失败。')
  } finally {
    saving.value = false
  }
}

watch(() => props.workspace?.id, load)

// 由知识图谱点击页面节点触发：按 id 直接打开该页
async function openById(id) {
  reading.value = true
  error.value = ''
  try {
    const detail = await fetchWikiPage(id, workspaceId.value)
    selectedId.value = detail.id
    selected.value = detail
    draft.value = {
      title: detail.title,
      slug: detail.slug,
      parentId: detail.parentId || '',
      contentMd: detail.contentMd || ''
    }
    previewMode.value = false
    notice.value = ''
  } catch (exception) {
    error.value = formatApiError(exception, 'wiki 页面打开失败。')
  } finally {
    reading.value = false
    emit('wikiPageOpened')
  }
}

watch(() => props.openPageId, (id) => {
  if (id) openById(id)
})
onMounted(load)
</script>

<template>
  <main class="wiki-layout">
    <aside class="wiki-sidebar" data-reveal>
      <header class="wiki-sidebar-header">
        <div>
          <p class="wiki-badge">WIKI · 知识库</p>
          <h2>页面</h2>
        </div>
        <button type="button" class="wiki-new-button" :disabled="!workspaceId" @click="newPage">＋ 新建</button>
      </header>
      <div v-if="loading" class="wiki-list-skeleton"><span></span><span></span><span></span></div>
      <div v-else-if="!flatTree.length" class="wiki-empty">
        <strong>还没有页面</strong>
        <span>新建一个页面，用 Markdown 组织你的知识，保存后自动进入检索。</span>
      </div>
      <ul v-else class="wiki-tree">
        <li v-for="item in flatTree" :key="item.node.id" :style="{ paddingLeft: `${12 + item.depth * 16}px` }">
          <button type="button" :class="{ active: selectedId === item.node.id }" @click="selectPage(item.node)">
            <span class="wiki-tree-title">{{ item.node.title }}</span>
            <span class="wiki-tree-slug">/{{ item.node.slug }}</span>
          </button>
        </li>
      </ul>
    </aside>

    <section class="wiki-main" data-reveal>
      <div v-if="error" class="wiki-error">
        <span>{{ error }}</span>
        <button type="button" @click="load">重试</button>
      </div>
      <p v-if="notice" class="wiki-notice">{{ notice }}</p>

      <div v-if="reading" class="wiki-placeholder">正在打开页面...</div>
      <div v-else-if="!workspaceId" class="wiki-placeholder">请先选择知识空间。</div>
      <div v-else-if="selectedId === null && !selected" class="wiki-placeholder">
        从左侧选择页面查看或编辑，或点击「新建」开始记录知识。
      </div>

      <article v-else class="wiki-editor">
        <header class="wiki-editor-header">
          <input v-model="draft.title" class="wiki-title-input" placeholder="页面标题" aria-label="页面标题" />
          <div class="wiki-editor-actions">
            <button v-if="selectedId" type="button" class="wiki-delete" :disabled="saving" @click="remove">删除</button>
            <button type="button" class="wiki-save primary" :disabled="saving" :aria-busy="saving" @click="save">
              {{ saving ? '保存中...' : '保存' }}
            </button>
          </div>
        </header>

        <div class="wiki-meta-row">
          <label class="wiki-field">
            <span>上级页面</span>
            <select v-model="draft.parentId" aria-label="上级页面">
              <option value="">（无，作为根页面）</option>
              <option v-for="option in parentOptions" :key="option.id" :value="option.id">{{ option.title }}</option>
            </select>
          </label>
          <label class="wiki-field">
            <span>短链（留空自动生成）</span>
            <input v-model="draft.slug" placeholder="auto" aria-label="页面短链" />
          </label>
        </div>

        <div class="wiki-toolbar">
          <button type="button" :class="{ active: !previewMode }" @click="previewMode = false">编辑</button>
          <button type="button" :class="{ active: previewMode }" @click="previewMode = true">预览</button>
        </div>

        <textarea
          v-if="!previewMode"
          v-model="draft.contentMd"
          class="wiki-content-input"
          placeholder="用 Markdown 书写页面内容..."
          aria-label="页面正文"
        ></textarea>
        <div v-else class="wiki-preview markdown-body" v-html="previewHtml" @click="onPreviewClick"></div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.wiki-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 24px;
  align-items: start;
}

.wiki-sidebar {
  background: var(--surface, #fff);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 16px;
  padding: 18px;
  position: sticky;
  top: 24px;
}

.wiki-sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.wiki-badge {
  margin: 0 0 4px;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--accent, #4f46e5);
  font-weight: 600;
}

.wiki-sidebar-header h2 {
  margin: 0;
  font-size: 20px;
}

.wiki-new-button {
  border: 1px solid var(--accent, #4f46e5);
  color: var(--accent, #4f46e5);
  background: transparent;
  border-radius: 10px;
  padding: 6px 12px;
  cursor: pointer;
  font-weight: 600;
}

.wiki-new-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.wiki-tree {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 60vh;
  overflow: auto;
}

.wiki-tree button {
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--text, #1f2937);
}

.wiki-tree button:hover {
  background: var(--surface-hover, #f3f4f6);
}

.wiki-tree button.active {
  background: var(--accent-soft, #eef2ff);
  color: var(--accent, #4f46e5);
}

.wiki-tree-title {
  font-weight: 600;
  font-size: 14px;
}

.wiki-tree-slug {
  font-size: 11px;
  opacity: 0.6;
}

.wiki-empty,
.wiki-placeholder {
  color: var(--text-muted, #6b7280);
  font-size: 14px;
}

.wiki-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 4px;
}

.wiki-list-skeleton span {
  display: block;
  height: 14px;
  margin: 10px 0;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef0f3, #f7f8fa, #eef0f3);
}

.wiki-main {
  background: var(--surface, #fff);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 16px;
  padding: 22px;
  min-height: 60vh;
}

.wiki-placeholder {
  padding: 40px 8px;
  text-align: center;
}

.wiki-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fef2f2;
  color: #b91c1c;
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 14px;
  font-size: 14px;
}

.wiki-error button {
  border: 1px solid #fca5a5;
  background: #fff;
  color: #b91c1c;
  border-radius: 8px;
  padding: 4px 10px;
  cursor: pointer;
}

.wiki-notice {
  background: #ecfdf5;
  color: #047857;
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 14px;
  font-size: 14px;
}

.wiki-editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.wiki-title-input {
  flex: 1;
  font-size: 22px;
  font-weight: 700;
  border: none;
  border-bottom: 2px solid transparent;
  padding: 6px 0;
  background: transparent;
  color: var(--text, #1f2937);
}

.wiki-title-input:focus {
  outline: none;
  border-bottom-color: var(--accent, #4f46e5);
}

.wiki-editor-actions {
  display: flex;
  gap: 8px;
}

.wiki-editor-actions button {
  border-radius: 10px;
  padding: 8px 16px;
  cursor: pointer;
  font-weight: 600;
  border: 1px solid var(--border, #e5e7eb);
  background: var(--surface, #fff);
  color: var(--text, #1f2937);
}

.wiki-save.primary {
  border-color: var(--accent, #4f46e5);
  background: var(--accent, #4f46e5);
  color: #fff;
}

.wiki-delete {
  color: #b91c1c;
  border-color: #fca5a5;
}

.wiki-meta-row {
  display: flex;
  gap: 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.wiki-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted, #6b7280);
  flex: 1;
  min-width: 180px;
}

.wiki-field select,
.wiki-field input {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--surface, #fff);
  color: var(--text, #1f2937);
}

.wiki-toolbar {
  display: inline-flex;
  gap: 4px;
  margin-bottom: 10px;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  padding: 3px;
}

.wiki-toolbar button {
  border: none;
  background: transparent;
  padding: 6px 16px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-muted, #6b7280);
  font-weight: 600;
}

.wiki-toolbar button.active {
  background: var(--accent-soft, #eef2ff);
  color: var(--accent, #4f46e5);
}

.wiki-content-input {
  width: 100%;
  min-height: 50vh;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 12px;
  padding: 16px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 14px;
  line-height: 1.6;
  resize: vertical;
  color: var(--text, #1f2937);
  background: var(--surface, #fff);
}

.wiki-preview {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 12px;
  padding: 16px 20px;
  min-height: 50vh;
}

.wiki-preview :deep(.wiki-link) {
  color: var(--accent, #4f46e5);
  text-decoration: underline;
  cursor: pointer;
}
</style>
