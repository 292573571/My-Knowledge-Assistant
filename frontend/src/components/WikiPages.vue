<script setup>
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { formatApiError } from '../api/apiError'
import {
  createWikiPage,
  deleteWikiPage,
  fetchWikiPage,
  fetchWikiPages,
  importWikiPageFile,
  fetchWikiImportTask,
  updateWikiPage
} from '../api/wikiPageApi'
import { renderMarkdown } from '../utils/markdown'
import ConfirmDialog from './ConfirmDialog.vue'

// 图谱按需加载：只在切到「图谱」视图时才拉这 500 行与布局仿真代码。
const GraphView = defineAsyncComponent(() => import('./GraphView.vue'))

const props = defineProps({
  workspace: { type: Object, default: null },
  openPageId: { type: String, default: null },
  // 由 URL 状态传入，支持 ?tab=wiki&view=graph 直接落到图谱视图
  initialView: { type: String, default: 'pages' }
})
const emit = defineEmits(['wikiPageOpened', 'viewChange'])

// 视图：'pages'=页面（目录 + 文档），'graph'=图谱
const view = ref(props.initialView === 'graph' ? 'graph' : 'pages')
const keyword = ref('')
const deleteOpen = ref(false)

function setView(next) {
  const target = next === 'graph' ? 'graph' : 'pages'
  if (view.value === target) return
  view.value = target
  emit('viewChange', target)
}

function openFromGraph(pageId) {
  setView('pages')
  if (pageId) openById(pageId)
}

const pages = ref([])
const selectedId = ref(null)
const selected = ref(null)
const loading = ref(true)
const reading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
// 区分「未选择任何页面」与「点击新建进入空白编辑态」
const isNew = ref(false)
const importing = ref(false)
const importInput = ref(null)

const draft = ref({ title: '', slug: '', parentId: '', contentMd: '' })
const previewMode = ref(false)
// 保存基线：用于判断是否存在未保存修改，避免「以为存了其实没存」
const baseline = ref('')

const workspaceId = computed(() => props.workspace?.id || '')

const snapshot = () => JSON.stringify({
  title: draft.value.title,
  slug: draft.value.slug,
  parentId: draft.value.parentId,
  contentMd: draft.value.contentMd
})

const isDirty = computed(() => snapshot() !== baseline.value)

function resetBaseline() {
  baseline.value = snapshot()
}

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

// 搜索：命中标题/短链即保留，同时保留其祖先，保证层级路径可读
const filteredTree = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  if (!term) return tree.value
  const hit = (node) => node.title.toLowerCase().includes(term) || (node.slug || '').toLowerCase().includes(term)
  const prune = (nodes) => nodes
    .map((node) => {
      const children = prune(node.children || [])
      return hit(node) || children.length ? { ...node, children } : null
    })
    .filter(Boolean)
  return prune(tree.value)
})

const flatTree = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    nodes.forEach((node) => {
      out.push({ node, depth })
      walk(node.children, depth + 1)
    })
  }
  walk(filteredTree.value, 0)
  return out
})

// 面包屑：从当前页沿 parentId 回溯，带环路保护
const breadcrumb = computed(() => {
  if (!selectedId.value) return []
  const map = new Map(pages.value.map((page) => [page.id, page]))
  const chain = []
  const seen = new Set()
  let node = map.get(selectedId.value)
  while (node && !seen.has(node.id)) {
    seen.add(node.id)
    chain.unshift(node)
    node = node.parentId ? map.get(node.parentId) : null
  }
  return chain
})

const previewHtml = computed(() => renderMarkdown(draft.value.contentMd || '', { wikiLinks: true }))
const charCount = computed(() => (draft.value.contentMd || '').length)

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

const parentOptions = computed(() => pages.value.filter((page) => page.id !== selectedId.value))

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function load() {
  loading.value = true
  error.value = ''
  selectedId.value = null
  selected.value = null
  isNew.value = false
  draft.value = { title: '', slug: '', parentId: '', contentMd: '' }
  resetBaseline()
  if (!workspaceId.value) {
    pages.value = []
    loading.value = false
    return
  }
  try {
    pages.value = await fetchWikiPages(workspaceId.value)
  } catch (exception) {
    error.value = formatApiError(exception, '知识笔记加载失败。')
  } finally {
    loading.value = false
  }
}

// 连续点击不同页面时，旧请求后到会覆盖新内容，这里用令牌丢弃过期响应
let readToken = 0

async function selectPage(page) {
  const token = ++readToken
  reading.value = true
  error.value = ''
  isNew.value = false
  try {
    const detail = await fetchWikiPage(page.id, workspaceId.value)
    if (token !== readToken) return
    selectedId.value = detail.id
    selected.value = detail
    draft.value = {
      title: detail.title,
      slug: detail.slug,
      parentId: detail.parentId || '',
      contentMd: detail.contentMd || ''
    }
    resetBaseline()
    previewMode.value = false
    notice.value = ''
  } catch (exception) {
    if (token !== readToken) return
    error.value = formatApiError(exception, '知识笔记加载失败。')
  } finally {
    if (token === readToken) reading.value = false
  }
}

function newPage() {
  readToken += 1
  selectedId.value = null
  selected.value = null
  isNew.value = true
  draft.value = { title: '', slug: '', parentId: '', contentMd: '' }
  resetBaseline()
  previewMode.value = false
  error.value = ''
  notice.value = ''
}

function triggerImport() {
  if (importInput.value) importInput.value.click()
}

async function onImportFile(event) {
  const file = event.target.files && event.target.files[0]
  if (!file) return
  importing.value = true
  error.value = ''
  notice.value = ''
  try {
    // 受理异步任务：大文件（扫描 PDF / 图片 PPT）解析与 OCR 可能耗时数分钟，轮询直到出结果
    const accepted = await importWikiPageFile(workspaceId.value, file)
    notice.value = `已开始解析「${file.name}」，扫描件与大文档可能需要几分钟…`
    const result = await pollImportTask(workspaceId.value, accepted.taskId)
    readToken += 1
    isNew.value = true
    selectedId.value = null
    selected.value = null
    draft.value = {
      title: result.title || file.name.replace(/\.[^.]+$/, ''),
      slug: '',
      parentId: '',
      contentMd: result.contentMd || ''
    }
    // 导入是草稿态：不设基线，让「未保存」标记如实出现
    baseline.value = ''
    previewMode.value = false
    notice.value = '文件已解析为草稿，确认无误后点击「保存」生成知识笔记。'
  } catch (exception) {
    error.value = formatApiError(exception, '文件导入失败。')
  } finally {
    importing.value = false
    event.target.value = ''
  }
}

const IMPORT_POLL_INTERVAL_MS = 2000
const IMPORT_POLL_MAX_MS = 10 * 60 * 1000

async function pollImportTask(workspace, taskId) {
  const deadline = Date.now() + IMPORT_POLL_MAX_MS
  for (;;) {
    await new Promise(resolve => setTimeout(resolve, IMPORT_POLL_INTERVAL_MS))
    const task = await fetchWikiImportTask(workspace, taskId)
    if (task.status === 'SUCCEEDED') return task
    if (task.status === 'FAILED') throw Object.assign(new Error(task.error || '文件解析失败，请确认格式受支持后重试。'), { retryable: false })
    if (Date.now() > deadline) throw new Error('解析超时，请缩小文件后重试或改用文本粘贴。')
  }
}

async function save() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  const body = {
    title: draft.value.title,
    slug: draft.value.slug || undefined,
    parentId: draft.value.parentId || undefined,
    contentMd: draft.value.contentMd
  }
  const savedContent = draft.value.contentMd
  try {
    if (selectedId.value) {
      await updateWikiPage(selectedId.value, workspaceId.value, body)
    } else {
      const created = await createWikiPage(workspaceId.value, body)
      selectedId.value = created.id
    }
    const savedId = selectedId.value
    resetBaseline()
    notice.value = '已保存，笔记内容已进入知识库检索。'
    // 刷新目录（标题/层级可能变化），但保持当前编辑对象不丢
    await load()
    if (savedId) {
      const refreshed = pages.value.find((page) => page.id === savedId)
      if (refreshed) await selectPage(refreshed)
      else if (selectedId.value === null) {
        // load 已清空选中，把刚保存的内容放回编辑器
        selectedId.value = savedId
        baseEditState(savedContent)
      }
    }
  } catch (exception) {
    error.value = formatApiError(exception, '知识笔记保存失败。')
  } finally {
    saving.value = false
  }
}

// 保存后详情拉取失败时的兜底：沿用刚提交的内容，不把编辑器清空
function baseEditState(contentMd) {
  selected.value = { id: selectedId.value, contentMd }
  draft.value = { ...draft.value, contentMd }
  resetBaseline()
}

async function confirmRemove() {
  if (!selectedId.value) return
  saving.value = true
  error.value = ''
  try {
    await deleteWikiPage(selectedId.value, workspaceId.value)
    deleteOpen.value = false
    notice.value = '笔记已删除。'
    await load()
  } catch (exception) {
    error.value = formatApiError(exception, '知识笔记删除失败。')
  } finally {
    saving.value = false
  }
}

watch(() => props.workspace?.id, load)

// 由知识图谱点击页面节点触发：按 id 直接打开该页
async function openById(id) {
  const token = ++readToken
  reading.value = true
  error.value = ''
  isNew.value = false
  try {
    const detail = await fetchWikiPage(id, workspaceId.value)
    if (token !== readToken) return
    selectedId.value = detail.id
    selected.value = detail
    draft.value = {
      title: detail.title,
      slug: detail.slug,
      parentId: detail.parentId || '',
      contentMd: detail.contentMd || ''
    }
    resetBaseline()
    previewMode.value = false
    notice.value = ''
  } catch (exception) {
    if (token !== readToken) return
    error.value = formatApiError(exception, '知识笔记打开失败。')
  } finally {
    if (token === readToken) {
      reading.value = false
      emit('wikiPageOpened')
    }
  }
}

watch(() => props.openPageId, (id) => {
  if (id) openById(id)
})
watch(() => props.initialView, (next) => {
  view.value = next === 'graph' ? 'graph' : 'pages'
})
onMounted(load)
</script>

<template>
  <div class="wiki-shell">
    <header class="wiki-hero" data-reveal>
      <div class="wiki-hero-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H10l2 2.5h6.5A1.5 1.5 0 0 1 20 8v10.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-13Z" />
          <path d="M8 12h8M8 15.5h5" />
        </svg>
      </div>
      <div class="wiki-hero-body">
        <h1>知识笔记</h1>
        <p>用 Markdown 组织内容，用 [[笔记名]] 互相引用。保存后自动进入知识库检索，并同步更新知识图谱。</p>
      </div>
      <div class="wiki-hero-aside">
        <span class="wiki-chip">
          <i class="wiki-chip-dot" aria-hidden="true" />
          {{ pages.length }} 篇笔记
        </span>
        <div class="wiki-seg" role="tablist" aria-label="知识笔记视图">
          <button
            type="button"
            role="tab"
            :aria-selected="view === 'pages'"
            :class="{ active: view === 'pages' }"
            @click="setView('pages')"
          >笔记</button>
          <button
            type="button"
            role="tab"
            :aria-selected="view === 'graph'"
            :class="{ active: view === 'graph' }"
            @click="setView('graph')"
          >知识图谱</button>
        </div>
      </div>
    </header>

    <div v-if="view === 'pages'" class="wiki-workspace" data-reveal>
      <aside class="wiki-nav">
        <div class="wiki-nav-head">
          <label class="wiki-search">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <circle cx="11" cy="11" r="6.5" />
              <path d="m16 16 4 4" stroke-linecap="round" />
            </svg>
            <input v-model="keyword" type="search" placeholder="搜索笔记" aria-label="搜索笔记" />
            <button v-if="keyword" type="button" class="wiki-search-clear" aria-label="清除搜索" @click="keyword = ''">×</button>
          </label>
        </div>

        <div class="wiki-nav-list">
          <div v-if="loading" class="wiki-nav-skeleton">
            <span v-for="n in 6" :key="n" :style="{ width: `${86 - n * 6}%` }" />
          </div>
          <div v-else-if="!flatTree.length" class="wiki-nav-empty">
            <template v-if="keyword">
              <p>没有匹配「{{ keyword }}」的笔记</p>
              <button type="button" @click="keyword = ''">清除搜索</button>
            </template>
            <template v-else>
              <p>目录还是空的</p>
              <span>先新建一篇，或用导入把已有文档变成笔记。</span>
            </template>
          </div>
          <ul v-else class="wiki-tree">
            <li v-for="item in flatTree" :key="item.node.id">
              <button
                type="button"
                :class="{ active: selectedId === item.node.id }"
                :style="{ paddingLeft: `${10 + item.depth * 14}px` }"
                @click="selectPage(item.node)"
              >
                <span class="wiki-tree-title">{{ item.node.title }}</span>
                <span v-if="item.node.children.length" class="wiki-tree-count">{{ item.node.children.length }}</span>
              </button>
            </li>
          </ul>
        </div>

        <div class="wiki-nav-foot">
          <button type="button" class="wiki-btn primary" :disabled="!workspaceId" @click="newPage">
            <span aria-hidden="true">＋</span>新建笔记
          </button>
          <button type="button" class="wiki-btn ghost" :disabled="!workspaceId || importing" @click="triggerImport">
            {{ importing ? '导入中…' : '导入' }}
          </button>
          <input
            ref="importInput"
            type="file"
            class="wiki-import-input"
            accept=".md,.txt,.html,.htm,.pdf,.docx,.doc,.xlsx,.xls,.pptx,.ppt,.csv,.json,.xml,.rtf,.odt,.png,.jpg,.jpeg"
            @change="onImportFile"
          />
        </div>
      </aside>

      <section class="wiki-doc">
        <div v-if="error" class="wiki-banner is-error" role="alert">
          <span>{{ error }}</span>
          <button type="button" @click="error = ''">关闭</button>
        </div>
        <div v-if="notice" class="wiki-banner is-ok">
          <span>{{ notice }}</span>
          <button type="button" @click="notice = ''">关闭</button>
        </div>

        <div v-if="reading" class="wiki-blank">
          <span class="wiki-spinner" aria-hidden="true" />
          <p>正在打开笔记…</p>
        </div>

        <div v-else-if="!workspaceId" class="wiki-blank">
          <p>请先在右上角选择一个知识空间。</p>
        </div>

        <div v-else-if="selectedId === null && !isNew" class="wiki-blank is-welcome">
          <div class="wiki-welcome-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H10l2 2.5h6.5A1.5 1.5 0 0 1 20 8v10.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-13Z" />
              <path d="M12 11.2v5M9.5 13.7h5" />
            </svg>
          </div>
          <h2>{{ pages.length ? '选一篇笔记继续' : '写下第一篇笔记' }}</h2>
          <p>
            {{ pages.length
              ? '从左侧目录选择笔记阅读或编辑，也可以新建一篇。'
              : '把零散的想法整理成结构化笔记，保存后立刻可被 AI 检索引用。' }}
          </p>
          <div class="wiki-welcome-actions">
            <button type="button" class="wiki-btn primary" @click="newPage">新建笔记</button>
            <button type="button" class="wiki-btn ghost" @click="triggerImport">导入文档</button>
          </div>
        </div>

        <template v-else>
          <div class="wiki-doc-bar">
            <nav class="wiki-crumbs" aria-label="笔记层级">
              <span class="wiki-crumb-space">{{ workspace?.name || '当前空间' }}</span>
              <template v-for="crumb in breadcrumb" :key="crumb.id">
                <span class="wiki-crumb-sep" aria-hidden="true">/</span>
                <button type="button" class="wiki-crumb" :title="crumb.title" @click="selectPage(crumb)">{{ crumb.title }}</button>
              </template>
              <span v-if="isNew" class="wiki-crumb-sep" aria-hidden="true">/</span>
              <span v-if="isNew" class="wiki-crumb current">未保存的新笔记</span>
            </nav>

            <div class="wiki-doc-actions">
              <div class="wiki-seg is-compact">
                <button type="button" :class="{ active: !previewMode }" @click="previewMode = false">编辑</button>
                <button type="button" :class="{ active: previewMode }" @click="previewMode = true">预览</button>
              </div>
              <button v-if="selectedId" type="button" class="wiki-btn ghost is-danger" :disabled="saving" @click="deleteOpen = true">删除</button>
              <button
                type="button"
                class="wiki-btn primary"
                :disabled="saving || (!isDirty && !!selectedId)"
                :aria-busy="saving"
                @click="save"
              >
                {{ saving ? '保存中…' : (isDirty || !selectedId ? '保存' : '已保存') }}
              </button>
            </div>
          </div>

          <div class="wiki-doc-body">
            <input
              v-model="draft.title"
              class="wiki-title"
              placeholder="无标题笔记"
              aria-label="笔记标题"
              @keydown.enter.prevent="previewMode = false"
            />

            <div class="wiki-doc-meta">
              <span :class="['wiki-state', { dirty: isDirty }]">
                <i aria-hidden="true" />{{ isDirty ? '有未保存的修改' : '已保存' }}
              </span>
              <span v-if="selected && selected.updatedAt">更新于 {{ formatTime(selected.updatedAt) }}</span>
              <span>{{ charCount }} 字符</span>
              <span class="wiki-meta-hint">⌘/Ctrl + S 保存</span>
            </div>

            <textarea
              v-if="!previewMode"
              v-model="draft.contentMd"
              class="wiki-editor"
              placeholder="从这里开始写。支持标题、列表、表格、代码块，以及 [[笔记名]] 形式的内部引用。"
              aria-label="笔记正文"
              @keydown.ctrl.s.prevent="save"
              @keydown.meta.s.prevent="save"
            ></textarea>
            <article v-else class="wiki-preview markdown-body" @click="onPreviewClick" v-html="previewHtml"></article>

            <details class="wiki-settings">
              <summary>
                <span>笔记设置</span>
                <span class="wiki-settings-hint">上级笔记 · 短链</span>
              </summary>
              <div class="wiki-settings-grid">
                <label class="wiki-field">
                  <span>上级笔记</span>
                  <select v-model="draft.parentId" aria-label="上级笔记">
                    <option value="">无（作为根笔记）</option>
                    <option v-for="option in parentOptions" :key="option.id" :value="option.id">{{ option.title }}</option>
                  </select>
                </label>
                <label class="wiki-field">
                  <span>短链</span>
                  <input v-model="draft.slug" placeholder="留空自动生成" aria-label="页面短链" />
                </label>
              </div>
            </details>
          </div>
        </template>
      </section>
    </div>

    <GraphView
      v-else
      class="wiki-graph"
      :workspace="workspace"
      @open-wiki-page="openFromGraph"
    />

    <ConfirmDialog
      v-if="deleteOpen"
      title="删除这篇笔记？"
      :message="`「${draft.title || '未命名笔记'}」及其正文会被移除，笔记里的 [[引用]] 也会失效。此操作不可撤销。`"
      confirm-text="删除笔记"
      :busy="saving"
      danger
      @confirm="confirmRemove"
      @cancel="deleteOpen = false"
    />
  </div>
</template>

<style scoped>
/* ============ 设计令牌：与知识库 tab 共用一套绿系语义色 ============ */
.wiki-shell {
  --wk-green: #2f6b49;
  --wk-green-hover: #255a3d;
  --wk-green-soft: #eaf3e7;
  --wk-green-line: #cfe3d2;
  --wk-line: #dce5f2;
  --wk-line-soft: #e9eef6;
  --wk-ink: #1d2940;
  --wk-ink-soft: #52607a;
  --wk-muted: #7c879b;
  --wk-surface: #ffffff;
  --wk-radius: 14px;

  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

/* ============ 头部：与 KnowledgeBase 的 hero 同构 ============ */
.wiki-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 4px 10px;
  flex-wrap: wrap;
}

.wiki-hero-icon {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  color: var(--wk-green);
  background: var(--wk-green-soft);
}

.wiki-hero-icon svg { width: 22px; height: 22px; }

.wiki-hero-body { flex: 1 1 320px; min-width: 0; }
.wiki-hero-body h1 { margin: 0; color: var(--wk-ink); font-size: 22px; letter-spacing: -0.01em; }
.wiki-hero-body p { margin: 6px 0 0; color: var(--wk-muted); font-size: 13px; line-height: 1.6; }

.wiki-hero-aside {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.wiki-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px;
  border: 1px solid var(--wk-line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.75);
  color: var(--wk-ink-soft);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.wiki-chip-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wk-green);
}

/* 分段控件：页面 / 图谱、编辑 / 预览 共用 */
.wiki-seg {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--wk-line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.8);
}

.wiki-seg button {
  appearance: none;
  border: 0;
  background: transparent;
  padding: 6px 15px;
  border-radius: 999px;
  color: var(--wk-ink-soft);
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}

.wiki-seg button:hover { color: var(--wk-green); background: var(--wk-green-soft); }
.wiki-seg button.active { color: #fff; background: var(--wk-green); }
.wiki-seg.is-compact button { padding: 5px 12px; font-size: 12.5px; }

/* ============ 主工作区：一张卡，左目录 + 右文档 ============ */
.wiki-workspace {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  height: clamp(540px, calc(100vh - 268px), 1180px);
  overflow: hidden;
  border: 1px solid var(--wk-line);
  border-radius: 16px;
  background: var(--wk-surface);
  box-shadow: 0 10px 28px rgba(48, 67, 105, 0.055);
}

.wiki-nav {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--wk-line-soft);
  background: linear-gradient(180deg, #fbfdfa 0%, #f6faf5 100%);
}

.wiki-nav-head { padding: 12px 12px 8px; flex: 0 0 auto; }

.wiki-search {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 9px;
  height: 34px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: rgba(38, 53, 82, 0.05);
  transition: background 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}

.wiki-search:focus-within {
  border-color: var(--wk-green-line);
  background: #fff;
  box-shadow: 0 0 0 3px rgba(47, 107, 73, 0.08);
}

.wiki-search svg { width: 14px; height: 14px; color: var(--wk-muted); flex: 0 0 auto; }

.wiki-search input {
  flex: 1 1 auto;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--wk-ink);
  font-size: 13px;
  padding: 0;
}

.wiki-search input:focus { outline: none; }
.wiki-search input::-webkit-search-cancel-button { display: none; }

.wiki-search-clear {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: 50%;
  background: rgba(38, 53, 82, 0.1);
  color: var(--wk-ink-soft);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.wiki-nav-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 2px 8px 10px;
}

.wiki-tree { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 1px; }

.wiki-tree button {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wk-ink-soft);
  font-size: 13.5px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: background 0.13s ease, color 0.13s ease;
}

.wiki-tree button:hover { background: rgba(47, 107, 73, 0.07); color: var(--wk-ink); }

.wiki-tree button.active {
  background: var(--wk-green-soft);
  color: var(--wk-green);
  font-weight: 650;
  box-shadow: inset 3px 0 0 var(--wk-green);
}

.wiki-tree-title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.wiki-tree-count {
  flex: 0 0 auto;
  min-width: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(38, 53, 82, 0.07);
  color: var(--wk-muted);
  font-size: 11px;
  font-weight: 700;
  line-height: 16px;
  text-align: center;
}

.wiki-tree button.active .wiki-tree-count { background: rgba(47, 107, 73, 0.14); color: var(--wk-green); }

.wiki-nav-skeleton { padding: 8px 4px; display: flex; flex-direction: column; gap: 8px; }

.wiki-nav-skeleton span {
  height: 13px;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef2ec, #f8fbf7, #eef2ec);
  background-size: 200% 100%;
  animation: wk-shimmer 1.4s ease-in-out infinite;
}

@keyframes wk-shimmer {
  0% { background-position: 120% 0; }
  100% { background-position: -20% 0; }
}

.wiki-nav-empty {
  padding: 26px 12px;
  text-align: center;
  color: var(--wk-muted);
}

.wiki-nav-empty p { margin: 0 0 6px; color: var(--wk-ink-soft); font-size: 13px; font-weight: 600; word-break: break-word; }
.wiki-nav-empty span { font-size: 12px; line-height: 1.6; }

.wiki-nav-empty button {
  margin-top: 4px;
  border: 1px solid var(--wk-line);
  border-radius: 8px;
  background: #fff;
  padding: 5px 12px;
  color: var(--wk-ink-soft);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.wiki-nav-foot {
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
  padding: 10px 12px 12px;
  border-top: 1px solid var(--wk-line-soft);
}

.wiki-nav-foot .primary { flex: 1 1 auto; }

/* ============ 通用按钮 ============ */
.wiki-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 34px;
  padding: 0 14px;
  border-radius: 9px;
  border: 1px solid var(--wk-line);
  background: #fff;
  color: var(--wk-ink-soft);
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.wiki-btn:hover:not(:disabled) { border-color: #b9cbe8; color: var(--wk-ink); background: #fbfcfe; }

.wiki-btn.primary {
  border-color: var(--wk-green);
  background: var(--wk-green);
  color: #fff;
  box-shadow: 0 1px 2px rgba(47, 107, 73, 0.18);
}

.wiki-btn.primary:hover:not(:disabled) {
  background: var(--wk-green-hover);
  border-color: var(--wk-green-hover);
  color: #fff;
  box-shadow: 0 2px 8px rgba(47, 107, 73, 0.24);
}

.wiki-btn.ghost { background: transparent; }

.wiki-btn.is-danger { color: #b4392f; border-color: #edd3d0; }
.wiki-btn.is-danger:hover:not(:disabled) { background: #fdf3f2; border-color: #e0b3ae; color: #9d2f26; }

.wiki-btn:disabled { opacity: 0.55; cursor: not-allowed; box-shadow: none; }

.wiki-import-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ============ 文档区 ============ */
.wiki-doc {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #fff;
}

.wiki-banner {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 18px 0;
  padding: 9px 13px;
  border-radius: 9px;
  font-size: 13px;
  line-height: 1.5;
}

.wiki-banner.is-error { background: #fdf3f2; color: #a5372d; border: 1px solid #f0d6d2; }
.wiki-banner.is-ok { background: #eef7ef; color: #2b6a49; border: 1px solid #d3e8d8; }

.wiki-banner button {
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 12px;
  font-weight: 650;
  opacity: 0.75;
  cursor: pointer;
}

.wiki-blank {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 24px;
  color: var(--wk-muted);
  font-size: 13.5px;
}

.wiki-blank.is-welcome { gap: 10px; }
.wiki-blank h2 { margin: 4px 0 0; color: var(--wk-ink); font-size: 18px; letter-spacing: -0.01em; }
.wiki-blank p { margin: 0; max-width: 380px; text-align: center; line-height: 1.7; }

.wiki-welcome-icon {
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  border-radius: 18px;
  color: var(--wk-green);
  background: var(--wk-green-soft);
  margin-bottom: 2px;
}

.wiki-welcome-icon svg { width: 30px; height: 30px; }

.wiki-welcome-actions { display: flex; gap: 10px; margin-top: 12px; }

.wiki-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(47, 107, 73, 0.2);
  border-top-color: var(--wk-green);
  border-radius: 50%;
  animation: wk-spin 0.7s linear infinite;
}

@keyframes wk-spin { to { transform: rotate(360deg); } }

.wiki-doc-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--wk-line-soft);
  background: rgba(255, 255, 255, 0.94);
}

.wiki-crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  color: var(--wk-muted);
}

.wiki-crumb-space { flex: 0 1 auto; max-width: 140px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.wiki-crumb-sep { flex: 0 0 auto; color: #c2cbd9; }

.wiki-crumb {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 170px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--wk-ink-soft);
  font-size: 12.5px;
  cursor: pointer;
}

.wiki-crumb:hover { color: var(--wk-green); text-decoration: underline; }
.wiki-crumb.current { color: var(--wk-ink); font-weight: 650; }

.wiki-doc-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }

.wiki-doc-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 26px 34px 30px;
  display: flex;
  flex-direction: column;
}

.wiki-title {
  width: 100%;
  border: 0;
  border-bottom: 1px solid transparent;
  padding: 0 0 10px;
  background: transparent;
  color: var(--wk-ink);
  font-size: 27px;
  font-weight: 700;
  letter-spacing: -0.022em;
  line-height: 1.3;
}

.wiki-title::placeholder { color: #c3ccd9; }
.wiki-title:hover { border-bottom-color: var(--wk-line-soft); }
.wiki-title:focus { outline: none; border-bottom-color: var(--wk-green); }

.wiki-doc-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 10px 0 16px;
  color: var(--wk-muted);
  font-size: 12px;
}

.wiki-state { display: inline-flex; align-items: center; gap: 5px; color: #5c7f66; }
.wiki-state i { width: 6px; height: 6px; border-radius: 50%; background: #7fb08c; }
.wiki-state.dirty { color: #a76a1c; }
.wiki-state.dirty i { background: #d99a3c; }

.wiki-meta-hint { margin-left: auto; color: #a9b3c2; }

.wiki-editor {
  flex: 1 1 auto;
  width: 100%;
  min-height: 340px;
  padding: 16px 18px;
  border: 1px solid var(--wk-line-soft);
  border-radius: 12px;
  background: #fcfdfe;
  color: #2a3444;
  font-family: inherit;
  font-size: 14.5px;
  line-height: 1.85;
  resize: vertical;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.wiki-editor:hover { border-color: #cfdcea; }
.wiki-editor:focus {
  outline: none;
  background: #fff;
  border-color: #9ec2a9;
  box-shadow: 0 0 0 3px rgba(47, 107, 73, 0.08);
}

.wiki-preview {
  flex: 1 1 auto;
  min-height: 340px;
  padding: 2px 2px 8px;
  color: #2a3444;
  font-size: 15px;
  line-height: 1.85;
}

.wiki-preview :deep(.wiki-link) {
  color: var(--wk-green);
  text-decoration: underline;
  text-decoration-color: rgba(47, 107, 73, 0.4);
  text-underline-offset: 2px;
  cursor: pointer;
}

.wiki-preview :deep(.wiki-link:hover) { text-decoration-color: var(--wk-green); background: var(--wk-green-soft); }

/* 元数据收进折叠区，不抢占正文首屏 */
.wiki-settings {
  flex: 0 0 auto;
  margin-top: 18px;
  border-top: 1px solid var(--wk-line-soft);
  padding-top: 12px;
}

.wiki-settings summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  color: var(--wk-ink-soft);
  font-size: 12.5px;
  font-weight: 650;
  cursor: pointer;
  list-style: none;
}

.wiki-settings summary::-webkit-details-marker { display: none; }

.wiki-settings summary::before {
  content: '';
  width: 0;
  height: 0;
  border-left: 5px solid #98a4b5;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  transition: transform 0.15s ease;
}

.wiki-settings[open] summary::before { transform: rotate(90deg); }
.wiki-settings-hint { color: var(--wk-muted); font-weight: 500; }

.wiki-settings-grid {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  padding: 12px 0 2px;
}

.wiki-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  flex: 1 1 200px;
  min-width: 180px;
  font-size: 12px;
  color: var(--wk-muted);
}

.wiki-field select,
.wiki-field input {
  height: 34px;
  border: 1px solid var(--wk-line);
  border-radius: 9px;
  padding: 0 10px;
  background: #fff;
  color: var(--wk-ink);
  font-size: 13px;
}

.wiki-field select:focus,
.wiki-field input:focus {
  outline: none;
  border-color: #9ec2a9;
  box-shadow: 0 0 0 3px rgba(47, 107, 73, 0.08);
}

.wiki-graph { min-height: 660px; }

/* ============ 响应式：窄屏把目录折到上方 ============ */
@media (max-width: 1024px) {
  .wiki-workspace {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }

  .wiki-nav {
    border-right: 0;
    border-bottom: 1px solid var(--wk-line-soft);
  }

  .wiki-nav-list { max-height: 240px; }
  .wiki-doc-body { padding: 20px 20px 26px; }
  .wiki-title { font-size: 23px; }
  .wiki-doc-bar { flex-wrap: wrap; }
  .wiki-doc-actions { width: 100%; justify-content: flex-end; }
}

@media (max-width: 640px) {
  .wiki-hero-aside { width: 100%; justify-content: space-between; }
  .wiki-doc-meta .wiki-meta-hint { display: none; }
}
</style>
