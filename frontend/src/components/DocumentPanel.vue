<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { deleteDocument, fetchDocumentContent, fetchDocuments, fetchDocumentTaskBatches, fetchDocumentTasks, fetchDocumentTaskSource, retryDocumentTask, uploadWorkspaceDocument } from '../api/documentApi'
import ConfirmDialog from './ConfirmDialog.vue'
import DocumentContentDialog from './DocumentContentDialog.vue'
import { useDialogFocus } from '../composables/useDialogFocus'
import { createUuid } from '../utils/uuid'

const props = defineProps({
  workspace: { type: Object, default: null }
})

const documents = ref([])
const loading = ref(false)
const error = ref('')
const errorTitle = ref('操作失败')
const errorType = ref('error')
const errorCopied = ref(false)
const notice = ref('')
const lastFailedAction = ref(null)
const searchQuery = ref('')
const fileType = ref('all')
const category = ref('all')
const pendingDeleteDocument = ref(null)
const deleting = ref(false)
const contentDocument = ref(null)
const contentLoading = ref(false)
const contentError = ref('')
const uploadInput = ref(null)
const uploadDialogOpen = ref(false)
const uploadDragActive = ref(false)
const selectedUploadFiles = ref([])
const documentTasks = ref([])
const retryingTaskId = ref('')
const openingTaskId = ref('')
const dismissedLatestTaskId = ref('')
const currentUploadTaskId = ref('')
const uploadHistoryOpen = ref(false)
const uploadHistoryDialogRef = ref(null)
const uploadDialogRef = ref(null)
const expandedTaskId = ref('')
const taskBatches = ref({})
const loadingBatchTaskId = ref('')
let noticeTimer = null
let errorTimer = null
let taskPollTimer = null
let disposed = false

const categoryLabels = {
  SOURCE: '原始资料',
  FORMAL_NOTE: '正式笔记'
}

const totalChunks = computed(() => {
  return documents.value.reduce((sum, document) => sum + (document.chunkCount || 0), 0)
})

const availableFileTypes = computed(() => [...new Set(documents.value.map((document) => fileExtension(displayFileName(document))))].filter(Boolean).sort())

const filteredDocuments = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()

  return documents.value.filter((document) => {
    const matchesType = fileType.value === 'all' || fileExtension(displayFileName(document)) === fileType.value
    const matchesCategory = category.value === 'all' || (document.category || 'SOURCE') === category.value
    const text = `${displayFileName(document) || ''} ${document.path || ''}`.toLowerCase()
    return matchesType && matchesCategory && (!query || text.includes(query))
  })
})

const uploadTasks = computed(() => documentTasks.value.filter((task) => task.type === 'UPLOAD'))
const visibleLatestUploadTask = computed(() => {
  if (!currentUploadTaskId.value || currentUploadTaskId.value === dismissedLatestTaskId.value) return null
  return uploadTasks.value.find((task) => task.taskId === currentUploadTaskId.value) || null
})
const hasPendingTasks = computed(() => documentTasks.value.some((task) => ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(task.status)))
const originalFileNames = computed(() => new Map(
  uploadTasks.value
    .filter((task) => task.status === 'SUCCEEDED' && task.documentId && task.fileName)
    .map((task) => [task.documentId, task.fileName])
))

const taskStatusLabels = {
  QUEUED: '等待处理',
  RUNNING: '处理中',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '处理完成',
  FAILED: '处理失败'
}

const taskTypeLabels = {
  UPLOAD: '上传',
  INGEST_FILE: '文件导入',
  INGEST_DIRECTORY: '目录导入',
  SYNC: '空间同步',
  REBUILD: '索引重建'
}

const taskStageLabels = {
  QUEUED: '任务已进入队列',
  PARSING: '正在解析文档',
  OCR: '正在识别扫描文字',
  SCANNING: '正在扫描文件',
  REBUILDING: '正在逐个重建文件',
  CHUNKING: '正在切分内容',
  VECTORIZING: '正在生成向量',
  PERSISTING_INDEX: '正在保存索引',
  RETRY_WAIT: '稍后自动重试',
  DONE: '文档可以参与检索',
  FAILED: '请查看失败原因'
}

const batchStatusLabels = {
  QUEUED: '等待处理',
  RUNNING: '处理中',
  SUCCEEDED: '已完成',
  FAILED: '处理失败'
}

const indexStatusLabels = {
  INDEXED: '已索引',
  PENDING: '等待索引',
  RUNNING: '索引中',
  FAILED: '索引失败'
}

const supportedUploadExtensions = new Set(['md', 'txt', 'html', 'htm', 'pdf', 'docx', 'doc', 'xlsx', 'xls', 'pptx', 'ppt', 'csv', 'json', 'jsonl', 'xml', 'rtf', 'odt', 'png', 'jpg', 'jpeg'])
const maxUploadBytes = 50 * 1024 * 1024

function fileExtension(fileName = '') {
  const index = fileName.lastIndexOf('.')
  return index < 0 ? '' : fileName.slice(index + 1).toLowerCase()
}

function displayFileName(document) {
  return originalFileNames.value.get(document.documentId) || document.fileName
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

function taskProgressLabel(task) {
  return task.status === 'FAILED' ? '已终止' : `${task.progress}%`
}

function taskFailureGuidance(task) {
  if (task.retryable) return '这是临时故障，可以重试当前任务。'
  if (task.errorMessage?.includes('PDF 页数超过限制')) return '请拆分 PDF 或调整允许的最大页数后重新上传。'
  if (task.errorMessage?.includes('OCR 页数')) return '扫描页数量超过 OCR 限制，请拆分文件后重新上传。'
  if (task.errorMessage?.includes('尺寸') || task.errorMessage?.includes('像素')) return '页面尺寸过大，请压缩或拆分文件后重新上传。'
  return '请处理原文件或配置限制后重新上传。'
}

function taskStageText(task) {
  if (task.status === 'FAILED') return '处理已终止'
  if (task.currentBatch && task.totalBatches) {
    return `${taskStageLabels[task.stage] || task.stage} · 第 ${task.currentBatch}/${task.totalBatches} 批`
      + (task.currentStartPage ? ` · 第 ${task.currentStartPage}-${task.currentEndPage} 页` : '')
  }
  return taskStageLabels[task.stage] || task.stage
}

async function toggleTaskBatches(task) {
  if (expandedTaskId.value === task.taskId) {
    expandedTaskId.value = ''
    return
  }
  expandedTaskId.value = task.taskId
  if (taskBatches.value[task.taskId]) return
  loadingBatchTaskId.value = task.taskId
  try {
    const batches = await fetchDocumentTaskBatches(task.taskId, props.workspace?.id)
    taskBatches.value = { ...taskBatches.value, [task.taskId]: batches }
  } catch (exception) {
    showError(`批次详情加载失败：${formatApiError(exception)}`)
  } finally {
    loadingBatchTaskId.value = ''
  }
}

async function loadDocuments() {
  loading.value = true
  clearError()

  try {
    documents.value = await fetchDocuments(props.workspace?.id)
  } catch (exception) {
    showError(`文档列表加载失败：${formatApiError(exception)}`)
  } finally {
    loading.value = false
  }
}

async function loadDocumentTasks() {
  try {
    const previous = new Map(documentTasks.value.map((task) => [task.taskId, task.status]))
    documentTasks.value = await fetchDocumentTasks(props.workspace?.id)
    if (expandedTaskId.value) {
      const batches = await fetchDocumentTaskBatches(expandedTaskId.value, props.workspace?.id)
      taskBatches.value = { ...taskBatches.value, [expandedTaskId.value]: batches }
    }
    if (documentTasks.value.some((task) => task.status === 'SUCCEEDED' && previous.get(task.taskId) !== 'SUCCEEDED')) {
      await loadDocuments()
    }
  } catch (exception) {
    if (!documentTasks.value.length) showError(`任务状态加载失败：${formatApiError(exception)}`)
  } finally {
    if (!disposed) scheduleTaskPoll()
  }
}

function scheduleTaskPoll() {
  if (taskPollTimer) window.clearTimeout(taskPollTimer)
  taskPollTimer = window.setTimeout(loadDocumentTasks, hasPendingTasks.value ? 2000 : 5000)
}

function dismissLatestUploadTask() {
  dismissedLatestTaskId.value = currentUploadTaskId.value
}

function closeUploadHistory() {
  uploadHistoryOpen.value = false
}

function showNotice(message) {
  notice.value = message
  if (noticeTimer) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => {
    notice.value = ''
    noticeTimer = null
  }, 3000)
}

function showError(message, type = 'error', title = '') {
  error.value = message
  errorType.value = type
  errorTitle.value = title || (type === 'warning' ? '请完善操作信息' : '操作失败')
  errorCopied.value = false
  if (errorTimer) window.clearTimeout(errorTimer)
  errorTimer = type === 'warning' ? window.setTimeout(() => {
    clearError()
  }, 6000) : null
}

function clearError() {
  error.value = ''
  errorCopied.value = false
  if (errorTimer) window.clearTimeout(errorTimer)
  errorTimer = null
}

async function copyError() {
  try {
    await navigator.clipboard.writeText(`${errorTitle.value}\n${error.value}`)
    errorCopied.value = true
  } catch {
    errorCopied.value = false
  }
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
  try {
    const result = await action()
    await loadDocuments()
    showNotice(successText)
  } catch (exception) {
    await loadDocuments()
    lastFailedAction.value = () => runIngest(action, successText)
    showError(`${successText}失败：${formatApiError(exception)}`)
  } finally {
    loading.value = false
  }
}

function openUploadDialog() {
  clearError()
  selectedUploadFiles.value = []
  uploadDragActive.value = false
  uploadDialogOpen.value = true
}

function closeUploadDialog() {
  if (!loading.value) uploadDialogOpen.value = false
}

function chooseUploadFiles(files) {
  const selectedFiles = [...files]
  const unsupportedFiles = selectedFiles.filter((file) => !supportedUploadExtensions.has(fileExtension(file.name)))
  if (unsupportedFiles.length) {
    selectedUploadFiles.value = []
    showError(
      `不支持 ${unsupportedFiles.map((file) => `“${file.name}”`).join('、')}。请选择 Markdown、TXT、HTML、DOCX、DOC、XLSX、XLS、PPTX、PPT、CSV、JSON、XML、RTF、ODT、PDF、PNG 或 JPEG 文件。`,
      'error',
      '文件格式不支持'
    )
    uploadDragActive.value = false
    return
  }
  const oversizedFiles = selectedFiles.filter((file) => file.size > maxUploadBytes)
  if (oversizedFiles.length) {
    selectedUploadFiles.value = []
    showError(
      `${oversizedFiles.map((file) => `“${file.name}”`).join('、')} 超过单文件 50 MB 限制。`,
      'error',
      '文件过大'
    )
    uploadDragActive.value = false
    return
  }
  clearError()
  selectedUploadFiles.value = selectedFiles
  uploadDragActive.value = false
}

function handleUploadInput(event) {
  chooseUploadFiles(event.target.files || [])
  event.target.value = ''
}

function handleUploadDrop(event) {
  chooseUploadFiles(event.dataTransfer?.files || [])
}

async function uploadSelectedFiles() {
  const files = selectedUploadFiles.value
  if (!files.length) return
  const targetWorkspaceId = props.workspace?.id
  const targetWorkspaceName = props.workspace?.name || '当前空间'
  const pendingFiles = files.map((file) => ({ file, clientRequestId: createUuid() }))
  await runIngest(async () => {
    while (pendingFiles.length) {
      const pending = pendingFiles[0]
      const result = await uploadWorkspaceDocument(pending.file, targetWorkspaceId, pending.clientRequestId)
      if (result.workspaceId !== targetWorkspaceId) throw new Error('服务端返回的任务归属与当前空间不一致')
      currentUploadTaskId.value = result.taskId
      dismissedLatestTaskId.value = ''
      pendingFiles.shift()
    }
    await loadDocumentTasks()
  }, files.length === 1
    ? `文档已上传到“${targetWorkspaceName}”，正在后台建立索引`
    : `${files.length} 个文档已上传到“${targetWorkspaceName}”，正在后台建立索引`)
  if (!error.value) {
    selectedUploadFiles.value = []
    uploadDialogOpen.value = false
  }
}

async function retryTask(task) {
  retryingTaskId.value = task.taskId
  clearError()
  try {
    await retryDocumentTask(task.taskId, props.workspace?.id)
    await loadDocumentTasks()
    showNotice(`“${task.fileName}”已重新进入处理队列`)
  } catch (exception) {
    showError(`任务重试失败：${formatApiError(exception)}`)
  } finally {
    retryingTaskId.value = ''
  }
}

async function openTaskSource(task) {
  openingTaskId.value = task.taskId
  clearError()
  const previewWindow = window.open('', '_blank')
  try {
    const blob = await fetchDocumentTaskSource(task.taskId, props.workspace?.id)
    const url = URL.createObjectURL(blob)
    if (previewWindow) {
      previewWindow.opener = null
      previewWindow.location.replace(url)
    } else {
      const link = document.createElement('a')
      link.href = url
      link.target = '_blank'
      link.rel = 'noopener'
      link.click()
    }
    window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (exception) {
    previewWindow?.close()
    showError(`源文件打开失败：${formatApiError(exception)}`, 'error', '无法打开源文件')
  } finally {
    openingTaskId.value = ''
  }
}

async function openDocument(document) {
  contentDocument.value = { ...document, content: '' }
  contentLoading.value = true
  contentError.value = ''
  try {
    contentDocument.value = {
      ...await fetchDocumentContent(document.documentId, props.workspace?.id),
      fileName: displayFileName(document)
    }
  } catch (exception) {
    contentError.value = formatApiError(exception, '文件内容加载失败。')
  } finally {
    contentLoading.value = false
  }
}

async function handleDelete(documentId) {
  const document = documents.value.find((item) => item.documentId === documentId)
  pendingDeleteDocument.value = document
    ? { ...document, fileName: displayFileName(document) }
    : { documentId, fileName: '这个文档' }
}

async function confirmDelete() {
  const documentId = pendingDeleteDocument.value?.documentId
  if (!documentId) return

  loading.value = true
  deleting.value = true
  clearError()
  notice.value = ''

  try {
    await deleteDocument(documentId, props.workspace?.id)
    documents.value = documents.value.filter((document) => document.documentId !== documentId)
    pendingDeleteDocument.value = null
    showNotice('文档已从当前知识空间删除')
  } catch (exception) {
    showError(`文档删除失败：${formatApiError(exception)}`)
  } finally {
    loading.value = false
    deleting.value = false
  }
}

onMounted(() => {
  loadDocuments()
  loadDocumentTasks()
})
onBeforeUnmount(() => {
  disposed = true
  if (noticeTimer) window.clearTimeout(noticeTimer)
  if (errorTimer) window.clearTimeout(errorTimer)
  if (taskPollTimer) window.clearTimeout(taskPollTimer)
})

useDialogFocus(uploadHistoryDialogRef, closeUploadHistory)
useDialogFocus(uploadDialogRef, closeUploadDialog)
</script>

<template>
  <section class="document-panel">
    <div class="document-panel-header">
      <div>
        <h2>空间文档</h2>
        <p>{{ documents.length }} 个文档，{{ totalChunks }} 个 chunks</p>
      </div>
      <div class="document-actions">
        <button type="button" class="document-history-action" @click="uploadHistoryOpen = true">上传历史</button>
        <button type="button" class="document-upload-action" :disabled="loading" @click="openUploadDialog">上传文档</button>
        <button type="button" :disabled="loading" :aria-busy="loading" @click="loadDocuments">{{ loading ? '刷新中...' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="workspace?.type === 'PUBLIC'" class="public-document-warning" role="note">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 2.8 20h18.4L12 3Z"/><path d="M12 9v5m0 3h.01"/></svg>
      <span><strong>全局可见</strong>上传到“{{ workspace.name }}”的文档会成为平台公共知识，可被所有用户在个人或团队空间提问时检索。</span>
    </div>

    <section v-if="visibleLatestUploadTask" class="document-task-list document-latest-task" aria-label="最近一次上传任务">
      <header>
        <div><h3>最近上传</h3><span>解析和索引在后台执行</span></div>
        <div class="document-task-header-actions"><span v-if="hasPendingTasks" class="document-task-live">自动更新</span><button type="button" class="document-task-dismiss" aria-label="关闭最近上传任务" @click="dismissLatestUploadTask">×</button></div>
      </header>
      <article class="document-task-item" :class="visibleLatestUploadTask.status.toLowerCase()">
        <div class="document-task-main">
          <div><strong>{{ visibleLatestUploadTask.fileName }}</strong><span>{{ taskTypeLabels[visibleLatestUploadTask.type] || visibleLatestUploadTask.type }} · {{ taskStatusLabels[visibleLatestUploadTask.status] || visibleLatestUploadTask.status }} · {{ taskStageText(visibleLatestUploadTask) }}</span></div>
          <b>{{ taskProgressLabel(visibleLatestUploadTask) }}</b>
        </div>
        <div class="document-task-progress" :aria-label="`处理进度 ${visibleLatestUploadTask.progress}%`"><span :style="{ width: `${visibleLatestUploadTask.progress}%` }"></span></div>
        <div class="document-task-meta">
          <small v-if="visibleLatestUploadTask.errorMessage"><strong>{{ visibleLatestUploadTask.errorMessage }}</strong><span>{{ taskFailureGuidance(visibleLatestUploadTask) }}</span></small>
          <small v-else>尝试 {{ visibleLatestUploadTask.attemptCount }}/{{ visibleLatestUploadTask.maxAttempts }} · {{ formatTime(visibleLatestUploadTask.createdAt) }}</small>
          <button v-if="visibleLatestUploadTask.status === 'FAILED' && visibleLatestUploadTask.retryable" type="button" :disabled="retryingTaskId === visibleLatestUploadTask.taskId" @click="retryTask(visibleLatestUploadTask)">{{ retryingTaskId === visibleLatestUploadTask.taskId ? '重试中...' : '重试' }}</button>
        </div>
      </article>
    </section>

    <Teleport to="body">
      <div v-if="uploadHistoryOpen" class="document-task-history-backdrop" @click.self="closeUploadHistory">
         <section ref="uploadHistoryDialogRef" class="document-task-history-dialog" role="dialog" aria-modal="true" aria-labelledby="document-task-history-title">
          <header>
            <div><p>UPLOAD HISTORY</p><h2 id="document-task-history-title">上传历史</h2><span>最近 {{ uploadTasks.length }} 条上传与索引任务</span></div>
            <button type="button" aria-label="关闭上传历史" @click="closeUploadHistory">×</button>
          </header>
          <div class="document-task-history-list">
            <div v-if="!uploadTasks.length" class="document-task-history-empty">当前空间暂无上传记录</div>
            <article v-for="task in uploadTasks" :key="task.taskId" class="document-task-item" :class="task.status.toLowerCase()">
              <div class="document-task-main">
                 <div><button v-if="!task.documentDeleted" type="button" class="document-task-file-link" :disabled="openingTaskId === task.taskId" :title="`打开源文件 ${task.fileName}`" @click="openTaskSource(task)">{{ openingTaskId === task.taskId ? '正在打开...' : task.fileName }}</button><strong v-else class="document-task-file-deleted">{{ task.fileName }}<em>已删除</em></strong><span>{{ task.documentDeleted ? '知识库文档已删除' : (taskStatusLabels[task.status] || task.status) }} · {{ taskStageText(task) }}</span></div>
                <b>{{ taskProgressLabel(task) }}</b>
              </div>
              <div class="document-task-progress" :aria-label="`处理进度 ${task.progress}%`"><span :style="{ width: `${task.progress}%` }"></span></div>
              <div class="document-task-meta">
                <small v-if="task.errorMessage"><strong>{{ task.errorMessage }}</strong><span>{{ taskFailureGuidance(task) }}</span></small>
                <small v-else>尝试 {{ task.attemptCount }}/{{ task.maxAttempts }} · {{ formatTime(task.createdAt) }}</small>
                 <button v-if="task.status === 'FAILED' && task.retryable" type="button" :disabled="retryingTaskId === task.taskId" @click="retryTask(task)">{{ retryingTaskId === task.taskId ? '重试中...' : '重试' }}</button>
                 <button v-if="task.totalBatches" type="button" class="document-task-batches-toggle" @click="toggleTaskBatches(task)">{{ expandedTaskId === task.taskId ? '收起批次' : '查看批次' }}</button>
               </div>
               <div v-if="expandedTaskId === task.taskId" class="document-task-batches">
                 <p v-if="loadingBatchTaskId === task.taskId">正在加载批次详情...</p>
                 <p v-else-if="!taskBatches[task.taskId]?.length">暂无批次记录</p>
                 <ol v-else>
                   <li v-for="batch in taskBatches[task.taskId]" :key="batch.batchIndex" :class="batch.status.toLowerCase()">
                     <span><strong>第 {{ batch.batchIndex }} 批</strong><small>{{ batch.startPage ? `第 ${batch.startPage}-${batch.endPage} 页` : '等待确定页码' }}</small></span>
                      <span><b>{{ batchStatusLabels[batch.status] || '处理中' }}</b><small>{{ batch.chunkCount }} 个知识片段</small></span>
                     <em v-if="batch.errorMessage">{{ batch.errorMessage }}</em>
                   </li>
                 </ol>
               </div>
             </article>
          </div>
        </section>
      </div>
    </Teleport>

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
        <span class="document-toast-icon" aria-hidden="true">{{ errorType === 'warning' ? '!' : '×' }}</span>
        <div class="document-toast-content">
          <strong>{{ errorTitle }}</strong>
          <p>{{ error }}</p>
        </div>
        <div class="document-toast-actions">
          <button type="button" class="copy" @click="copyError">{{ errorCopied ? '已复制' : '复制详情' }}</button>
          <button v-if="lastFailedAction && !loading" type="button" class="retry" @click="clearError(); lastFailedAction()">重试</button>
          <button type="button" class="close" aria-label="关闭提示" @click="clearError">×</button>
        </div>
      </div>
    </Teleport>
    <Teleport to="body">
      <div v-if="notice" class="document-toast" role="status" aria-live="polite">
         <span class="document-toast-icon" aria-hidden="true">✓</span>
        <span>{{ notice }}</span>
        <button type="button" aria-label="关闭提示" @click="clearNotice">×</button>
      </div>
    </Teleport>

    <div v-if="loading && !documents.length" class="document-list-skeleton" role="status" aria-label="正在加载文档列表"><span></span><span></span><span></span></div>
    <div v-else-if="!documents.length" class="muted-card document-empty-state"><strong>当前空间暂无文档</strong><span>上传 Markdown、TXT、HTML、DOCX、DOC、XLSX、XLS、PPTX、PPT、CSV、JSON、XML、RTF、ODT、PDF、PNG 或 JPEG，开始构建知识库。</span><button type="button" class="document-empty-action" @click="openUploadDialog">上传第一份文档</button></div>
    <div v-else-if="!filteredDocuments.length && !loading" class="muted-card">没有匹配的文档</div>
    <article
      v-for="document in filteredDocuments"
      :key="document.documentId"
      class="document-card"
    >
      <header class="document-card-heading">
        <button type="button" class="document-name-button" :title="`查看 ${displayFileName(document)}`" @click="openDocument(document)">{{ displayFileName(document) }}</button>
        <span class="document-chunk-count">{{ document.chunkCount }} 个知识片段</span>
      </header>
      <div class="document-card-status-row">
         <div class="document-card-badges"><span>{{ categoryLabels[document.category || 'SOURCE'] }}</span><span class="indexed">{{ indexStatusLabels[document.indexStatus] || (document.indexStatus ? '处理中' : '已索引') }}</span></div>
        <button type="button" class="danger" :disabled="loading" @click.stop="handleDelete(document.documentId)">删除</button>
      </div>
      <div class="document-card-details">
        <p><span>更新时间</span><time :datetime="document.ingestedAt">{{ formatTime(document.ingestedAt) }}</time></p>
      </div>
    </article>

    <Teleport to="body">
      <div v-if="uploadDialogOpen" class="document-upload-backdrop" role="presentation" @click.self="closeUploadDialog">
         <section ref="uploadDialogRef" class="document-upload-dialog" role="dialog" aria-modal="true" aria-labelledby="document-upload-title">
          <header class="document-upload-dialog-header">
            <div>
              <p class="eyebrow">知识库文档</p>
              <h2 id="document-upload-title">上传文档</h2>
              <span>添加到“{{ workspace?.name || '当前空间' }}”，文件保存后将在后台自动解析和索引。</span>
            </div>
            <button type="button" class="document-upload-close" :disabled="loading" aria-label="关闭上传窗口" @click="closeUploadDialog">×</button>
          </header>

          <div
            class="document-dropzone"
            :class="{ active: uploadDragActive, selected: selectedUploadFiles.length }"
            role="button"
            tabindex="0"
            @click="uploadInput?.click()"
            @keydown.enter.prevent="uploadInput?.click()"
            @keydown.space.prevent="uploadInput?.click()"
            @dragenter.prevent="uploadDragActive = true"
            @dragover.prevent="uploadDragActive = true"
            @dragleave.prevent="uploadDragActive = false"
            @drop.prevent="handleUploadDrop"
          >
            <input ref="uploadInput" hidden multiple type="file" accept=".md,.txt,.html,.htm,.pdf,.docx,.doc,.xlsx,.xls,.pptx,.ppt,.csv,.json,.jsonl,.xml,.rtf,.odt,.png,.jpg,.jpeg,text/markdown,text/plain,text/html,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/csv,application/json,application/xml,application/rtf,application/vnd.oasis.opendocument.text,image/png,image/jpeg" @change="handleUploadInput">
            <span class="document-dropzone-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4" /></svg></span>
            <strong>{{ selectedUploadFiles.length ? `${selectedUploadFiles.length} 个文件已准备好` : '拖拽文件到这里' }}</strong>
            <span>{{ selectedUploadFiles.length ? '可以继续选择以替换文件' : '或点击选择文件' }}</span>
            <small>支持 Markdown、TXT、HTML、DOCX、DOC、Excel、PPT、CSV、JSON、XML、RTF、ODT、PDF、PNG 和 JPEG；扫描内容会自动 OCR，单个文件最大 50 MB</small>
          </div>

          <ul v-if="selectedUploadFiles.length" class="document-upload-file-list">
            <li v-for="file in selectedUploadFiles" :key="`${file.name}-${file.size}-${file.lastModified}`">
              <span>{{ file.name }}</span>
              <small>{{ (file.size / 1024 / 1024).toFixed(1) }} MB</small>
            </li>
          </ul>

          <div v-if="loading" class="document-upload-progress" role="status" aria-live="polite">
            <span class="document-upload-spinner" aria-hidden="true"></span>
            <div><strong>正在保存文档</strong><span>保存完成后即可关闭窗口，解析和索引会在后台继续。</span></div>
          </div>

          <footer class="document-upload-dialog-actions">
            <button type="button" class="document-upload-cancel" :disabled="loading" @click="closeUploadDialog">取消</button>
            <button type="button" class="document-upload-submit" :disabled="loading || !selectedUploadFiles.length" @click="uploadSelectedFiles">{{ loading ? '处理中...' : '开始上传' }}</button>
          </footer>
        </section>
      </div>
    </Teleport>

    <ConfirmDialog
      v-if="pendingDeleteDocument"
      title="删除文档？"
      :message="`${pendingDeleteDocument.fileName} 将不再参与当前空间检索。通过页面上传的源文件也会被永久删除，此操作无法撤销。`"
      confirm-text="确认删除"
      :busy="deleting"
      danger
      @confirm="confirmDelete"
      @cancel="pendingDeleteDocument = null"
    />
    <DocumentContentDialog v-if="contentDocument" :document="contentDocument" :loading="contentLoading" :error="contentError" @close="contentDocument = null" @retry="openDocument(contentDocument)" />

  </section>
</template>
