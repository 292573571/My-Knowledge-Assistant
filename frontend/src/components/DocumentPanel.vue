<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { deleteDocument, fetchDocumentContent, fetchDocuments, ingestDocument, ingestDocuments, rebuildDocuments, syncDocuments } from '../api/documentApi'
import ConfirmDialog from './ConfirmDialog.vue'
import DocumentContentDialog from './DocumentContentDialog.vue'

const documents = ref([])
const loading = ref(false)
const error = ref('')
const errorType = ref('error')
const notice = ref('')
const inputPath = ref('')
const lastFailedAction = ref(null)
const rebuildStatus = ref(null)
const syncStatus = ref(null)
const searchQuery = ref('')
const fileType = ref('all')
const category = ref('all')
const pendingDeleteDocument = ref(null)
const deleting = ref(false)
const contentDocument = ref(null)
const contentLoading = ref(false)
const contentError = ref('')
let noticeTimer = null
let errorTimer = null

const categoryLabels = {
  SOURCE: '原始资料',
  FORMAL_NOTE: '正式笔记'
}

const totalChunks = computed(() => {
  return documents.value.reduce((sum, document) => sum + (document.chunkCount || 0), 0)
})

const availableFileTypes = computed(() => [...new Set(documents.value.map((document) => fileExtension(document.fileName)))].filter(Boolean).sort())

const filteredDocuments = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()

  return documents.value.filter((document) => {
    const matchesType = fileType.value === 'all' || fileExtension(document.fileName) === fileType.value
    const matchesCategory = category.value === 'all' || (document.category || 'SOURCE') === category.value
    const text = `${document.fileName || ''} ${document.path || ''}`.toLowerCase()
    return matchesType && matchesCategory && (!query || text.includes(query))
  })
})

function fileExtension(fileName = '') {
  const index = fileName.lastIndexOf('.')
  return index < 0 ? '' : fileName.slice(index + 1).toLowerCase()
}

function formatTime(value) {
  if (!value) return '-'

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

async function loadDocuments() {
  loading.value = true
  clearError()

  try {
    documents.value = await fetchDocuments()
  } catch (exception) {
    showError(`文档列表加载失败：${formatApiError(exception)}`)
  } finally {
    loading.value = false
  }
}

function summarizeResult(result, fallback) {
  if (!result) return fallback

  return `${fallback}：导入 ${result.imported || 0}，跳过 ${result.skipped || 0}，失败 ${result.failed || 0}，chunks ${result.chunks || 0}`
}

function showNotice(message) {
  notice.value = message
  if (noticeTimer) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => {
    notice.value = ''
    noticeTimer = null
  }, 3000)
}

function showError(message, type = 'error') {
  error.value = message
  errorType.value = type
  if (errorTimer) window.clearTimeout(errorTimer)
  errorTimer = window.setTimeout(() => {
    error.value = ''
    errorTimer = null
  }, type === 'warning' ? 3500 : 6000)
}

function clearError() {
  error.value = ''
  if (errorTimer) window.clearTimeout(errorTimer)
  errorTimer = null
}

function clearNotice() {
  notice.value = ''
  if (noticeTimer) window.clearTimeout(noticeTimer)
  noticeTimer = null
}

async function runIngest(action, successText) {
  loading.value = true
  clearError()
  notice.value = ''
  lastFailedAction.value = null
  rebuildStatus.value = null
  syncStatus.value = null

  try {
    const result = await action()
    await loadDocuments()
    showNotice(summarizeResult(result, successText))
  } catch (exception) {
    lastFailedAction.value = () => runIngest(action, successText)
    showError(`${successText}失败：${formatApiError(exception)}`)
  } finally {
    loading.value = false
  }
}

async function handleIngestFile() {
  const path = inputPath.value.trim()
  if (!path) {
    lastFailedAction.value = null
    showError('请输入文件路径，例如 docs/mcp-notes.md', 'warning')
    return
  }

  await runIngest(() => ingestDocument(path), '文件导入完成')
}

async function handleIngestDirectory() {
  await runIngest(() => ingestDocuments(inputPath.value.trim()), '目录导入完成')
}

async function handleRebuild() {
  loading.value = true
  clearError()
  notice.value = ''
  lastFailedAction.value = null
  rebuildStatus.value = {
    stage: 'rebuilding',
    message: '正在清空旧索引并重新切分、写入向量库，请不要关闭页面。'
  }
  syncStatus.value = null

  try {
    const result = await rebuildDocuments()
    await loadDocuments()
    rebuildStatus.value = {
      stage: 'success',
      ...result,
      message: `重建成功：已清理 ${result.clearedDocuments || 0} 个旧文档、${result.clearedChunks || 0} 个旧 chunks；当前写入 ${result.files || 0} 个文件、${result.chunks || 0} 个 chunks。`
    }
    showNotice(`知识库重建完成，耗时 ${result.durationMs || 0}ms`)
  } catch (exception) {
    rebuildStatus.value = {
      stage: 'failed',
      message: `重建失败：${formatApiError(exception)}`,
      requestId: exception.requestId || ''
    }
    lastFailedAction.value = handleRebuild
    showError(rebuildStatus.value.message)
  } finally {
    loading.value = false
  }
}

async function handleSync() {
  loading.value = true
  clearError()
  notice.value = ''
  lastFailedAction.value = null
  rebuildStatus.value = null
  syncStatus.value = {
    stage: 'syncing',
    message: '正在比对 docs 目录与当前索引，只处理新增、变化和已删除的文档。'
  }

  try {
    const result = await syncDocuments()
    await loadDocuments()
    syncStatus.value = {
      stage: 'success',
      ...result,
      message: `同步完成：新增 ${result.addedFiles || 0}，更新 ${result.updatedFiles || 0}，未变化 ${result.unchangedFiles || 0}，删除 ${result.deletedFiles || 0} 个文件。`
    }
    showNotice(`已增写 ${result.addedChunks || 0} 个 chunks，已删除 ${result.deletedChunks || 0} 个 chunks，耗时 ${result.durationMs || 0}ms`)
  } catch (exception) {
    syncStatus.value = {
      stage: 'failed',
      message: `同步失败：${formatApiError(exception)}`,
      requestId: exception.requestId || ''
    }
    lastFailedAction.value = handleSync
    showError(syncStatus.value.message)
  } finally {
    loading.value = false
  }
}

async function handleReingest(document) {
  await runIngest(() => ingestDocument(document.path, true), '文件重新导入完成')
}

async function openDocument(document) {
  contentDocument.value = { ...document, content: '' }
  contentLoading.value = true
  contentError.value = ''
  try {
    contentDocument.value = await fetchDocumentContent(document.documentId)
  } catch (exception) {
    contentError.value = formatApiError(exception, '文件内容加载失败。')
  } finally {
    contentLoading.value = false
  }
}

async function handleDelete(documentId) {
  const document = documents.value.find((item) => item.documentId === documentId)
  pendingDeleteDocument.value = document || { documentId, fileName: '这个文档' }
}

async function confirmDelete() {
  const documentId = pendingDeleteDocument.value?.documentId
  if (!documentId) return

  loading.value = true
  deleting.value = true
  clearError()
  notice.value = ''

  try {
    await deleteDocument(documentId)
    documents.value = documents.value.filter((document) => document.documentId !== documentId)
    pendingDeleteDocument.value = null
    showNotice('文档索引已删除，源文件保留在 docs 目录')
  } catch (exception) {
    showError(`文档删除失败：${formatApiError(exception)}`)
  } finally {
    loading.value = false
    deleting.value = false
  }
}

onMounted(loadDocuments)
onBeforeUnmount(() => {
  if (noticeTimer) window.clearTimeout(noticeTimer)
  if (errorTimer) window.clearTimeout(errorTimer)
})
</script>

<template>
  <section class="document-panel">
    <div class="document-panel-header">
      <div>
        <h2>Knowledge Base</h2>
        <p>{{ documents.length }} 个文档，{{ totalChunks }} 个 chunks</p>
      </div>
      <div class="document-actions">
        <button type="button" :disabled="loading" @click="loadDocuments">刷新</button>
      </div>
    </div>

    <div class="document-ingest-form">
      <input
        v-model="inputPath"
        type="text"
        placeholder="输入文件或目录路径，例如 docs/mcp-notes.md 或 docs"
        :disabled="loading"
      >
      <div class="document-actions document-actions-wide">
        <button type="button" :disabled="loading" @click="handleIngestFile">导入文件</button>
        <button type="button" :disabled="loading" @click="handleIngestDirectory">导入目录</button>
        <button type="button" :disabled="loading" @click="handleSync">同步知识库</button>
        <button type="button" class="secondary-action" :disabled="loading" @click="handleRebuild">全量重建</button>
      </div>
    </div>

    <div v-if="documents.length" class="document-filters">
      <input v-model="searchQuery" type="search" placeholder="搜索文件名或路径">
      <select v-model="fileType" aria-label="按文件类型筛选">
        <option value="all">全部类型</option>
        <option v-for="type in availableFileTypes" :key="type" :value="type">.{{ type }}</option>
      </select>
      <select v-model="category" aria-label="按文档分类筛选">
        <option value="all">全部分类</option>
        <option value="SOURCE">原始资料</option>
        <option value="FORMAL_NOTE">正式笔记</option>
      </select>
    </div>

    <Teleport to="body">
      <div v-if="error" :class="['document-toast', errorType]" role="alert" aria-live="assertive">
        <span class="document-toast-icon">{{ errorType === 'warning' ? '!' : '×' }}</span>
        <span><strong>{{ errorType === 'warning' ? '请完善操作信息' : '操作失败' }}</strong>{{ error }}</span>
        <div class="document-toast-actions"><button v-if="lastFailedAction && !loading" type="button" class="retry" @click="clearError(); lastFailedAction()">重试</button><button type="button" aria-label="关闭提示" @click="clearError">×</button></div>
      </div>
    </Teleport>
    <Teleport to="body">
      <div v-if="notice" class="document-toast" role="status" aria-live="polite">
        <span class="document-toast-icon">✓</span>
        <span>{{ notice }}</span>
        <button type="button" aria-label="关闭提示" @click="clearNotice">×</button>
      </div>
    </Teleport>

    <div v-if="rebuildStatus" :class="['rebuild-status', rebuildStatus.stage]">
      <strong v-if="rebuildStatus.stage === 'rebuilding'">正在重建知识库</strong>
      <strong v-else-if="rebuildStatus.stage === 'success'">知识库重建成功</strong>
      <strong v-else>知识库重建失败</strong>
      <span>{{ rebuildStatus.message }}</span>
      <small v-if="rebuildStatus.stage === 'rebuilding'">当前阶段：清理旧的 Chroma 向量和文档索引，然后重新导入 docs 目录。</small>
      <small v-if="rebuildStatus.stage === 'success'">状态：{{ rebuildStatus.status }} · 耗时：{{ rebuildStatus.durationMs }}ms · requestId: {{ rebuildStatus.requestId || '-' }}</small>
      <small v-if="rebuildStatus.stage === 'failed' && rebuildStatus.requestId">requestId: {{ rebuildStatus.requestId }}</small>
    </div>

    <div v-if="syncStatus" :class="['sync-status', syncStatus.stage]">
      <strong v-if="syncStatus.stage === 'syncing'">正在同步知识库</strong>
      <strong v-else-if="syncStatus.stage === 'success'">知识库同步成功</strong>
      <strong v-else>知识库同步失败</strong>
      <span>{{ syncStatus.message }}</span>
      <small v-if="syncStatus.stage === 'syncing'">同步会跳过未变化文档，仅写入新增或修改内容，并清理磁盘中已删除文件的旧向量。</small>
      <small v-if="syncStatus.stage === 'success'">扫描 {{ syncStatus.scannedFiles }} 个文件 · 状态：{{ syncStatus.status }} · requestId: {{ syncStatus.requestId || '-' }}</small>
      <small v-if="syncStatus.stage === 'failed' && syncStatus.requestId">requestId: {{ syncStatus.requestId }}</small>
    </div>

    <div v-if="!documents.length && !loading" class="muted-card">暂无已导入文档，输入路径后导入，或直接导入 docs 目录</div>
    <div v-else-if="!filteredDocuments.length && !loading" class="muted-card">没有匹配的文档</div>
    <div v-if="loading" class="muted-card">正在处理文档索引...</div>

    <article
      v-for="document in filteredDocuments"
      :key="document.documentId"
      class="document-card"
    >
      <header class="document-card-heading">
        <button type="button" class="document-name-button" :title="`查看 ${document.fileName}`" @click="openDocument(document)">{{ document.fileName }}</button>
        <span class="document-chunk-count">{{ document.chunkCount }} chunks</span>
      </header>
      <div class="document-card-badges"><span>{{ categoryLabels[document.category || 'SOURCE'] }}</span><span class="indexed">{{ document.indexStatus === 'INDEXED' || !document.indexStatus ? '已索引' : document.indexStatus }}</span></div>
      <div class="document-card-details">
        <p><span>路径</span><code>{{ document.path }}</code></p>
        <p><span>更新时间</span><time :datetime="document.ingestedAt">{{ formatTime(document.ingestedAt) }}</time></p>
        <p><span>内容指纹</span><code>{{ document.contentHash }}</code></p>
      </div>
      <div class="document-card-actions">
        <button type="button" :disabled="loading" @click.stop="handleReingest(document)">重新导入</button>
        <button type="button" class="danger" :disabled="loading" @click.stop="handleDelete(document.documentId)">删除</button>
      </div>
    </article>

    <ConfirmDialog
      v-if="pendingDeleteDocument"
      title="删除文档索引？"
      :message="`${pendingDeleteDocument.fileName} 将不再参与知识库检索，docs 目录中的源文件仍会保留。`"
      confirm-text="删除索引"
      :busy="deleting"
      danger
      @confirm="confirmDelete"
      @cancel="pendingDeleteDocument = null"
    />
    <DocumentContentDialog v-if="contentDocument" :document="contentDocument" :loading="contentLoading" :error="contentError" @close="contentDocument = null" @retry="openDocument(contentDocument)" />

  </section>
</template>
