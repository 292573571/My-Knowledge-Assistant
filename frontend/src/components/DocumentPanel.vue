<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { deleteDocument, fetchDocumentContent, fetchDocuments, uploadWorkspaceDocument } from '../api/documentApi'
import ConfirmDialog from './ConfirmDialog.vue'
import DocumentContentDialog from './DocumentContentDialog.vue'

const props = defineProps({
  workspace: { type: Object, default: null }
})

const documents = ref([])
const loading = ref(false)
const error = ref('')
const errorType = ref('error')
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
    documents.value = await fetchDocuments(props.workspace?.id)
  } catch (exception) {
    showError(`文档列表加载失败：${formatApiError(exception)}`)
  } finally {
    loading.value = false
  }
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
  selectedUploadFiles.value = [...files]
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
  await runIngest(async () => {
    for (const file of files) {
      const result = await uploadWorkspaceDocument(file, targetWorkspaceId)
      if (result.workspaceId !== targetWorkspaceId
          || (props.workspace?.type === 'PUBLIC' && result.visibility !== 'PUBLIC')) {
        throw new Error('服务端返回的文档归属与当前空间不一致')
      }
    }
  }, files.length === 1
    ? `文档已上传到“${targetWorkspaceName}”并完成索引`
    : `${files.length} 个文档已上传到“${targetWorkspaceName}”并完成索引`)
  if (!error.value) {
    selectedUploadFiles.value = []
    uploadDialogOpen.value = false
  }
}

async function openDocument(document) {
  contentDocument.value = { ...document, content: '' }
  contentLoading.value = true
  contentError.value = ''
  try {
    contentDocument.value = await fetchDocumentContent(document.documentId, props.workspace?.id)
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
        <h2>空间文档</h2>
        <p>{{ documents.length }} 个文档，{{ totalChunks }} 个 chunks</p>
      </div>
      <div class="document-actions">
        <button type="button" class="document-upload-action" :disabled="loading" @click="openUploadDialog">上传文档</button>
        <button type="button" :disabled="loading" @click="loadDocuments">刷新</button>
      </div>
    </div>

    <div v-if="workspace?.type === 'PUBLIC'" class="public-document-warning" role="note">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 2.8 20h18.4L12 3Z"/><path d="M12 9v5m0 3h.01"/></svg>
      <span><strong>全局可见</strong>上传到“{{ workspace.name }}”的文档会成为平台公共知识，可被所有用户在个人或团队空间提问时检索。</span>
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

    <div v-if="!documents.length && !loading" class="muted-card">当前空间暂无文档，可上传 Markdown、TXT、HTML、DOCX 或文本型 PDF 开始构建知识库</div>
    <div v-else-if="!filteredDocuments.length && !loading" class="muted-card">没有匹配的文档</div>
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
        <button type="button" class="danger" :disabled="loading" @click.stop="handleDelete(document.documentId)">删除</button>
      </div>
    </article>

    <Teleport to="body">
      <div v-if="uploadDialogOpen" class="document-upload-backdrop" role="presentation" @click.self="closeUploadDialog">
        <section class="document-upload-dialog" role="dialog" aria-modal="true" aria-labelledby="document-upload-title">
          <header class="document-upload-dialog-header">
            <div>
              <p class="eyebrow">知识库文档</p>
              <h2 id="document-upload-title">上传文档</h2>
              <span>添加到“{{ workspace?.name || '当前空间' }}”，上传后会自动完成索引。</span>
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
            <input ref="uploadInput" hidden multiple type="file" accept=".md,.txt,.html,.htm,.pdf,.docx,text/markdown,text/plain,text/html,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document" @change="handleUploadInput">
            <span class="document-dropzone-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4" /></svg></span>
            <strong>{{ selectedUploadFiles.length ? `${selectedUploadFiles.length} 个文件已准备好` : '拖拽文件到这里' }}</strong>
            <span>{{ selectedUploadFiles.length ? '可以继续选择以替换文件' : '或点击选择文件' }}</span>
            <small>支持 Markdown、TXT、HTML、DOCX 和文本型 PDF；单个文件最大 50 MB</small>
          </div>

          <ul v-if="selectedUploadFiles.length" class="document-upload-file-list">
            <li v-for="file in selectedUploadFiles" :key="`${file.name}-${file.size}-${file.lastModified}`">
              <span>{{ file.name }}</span>
              <small>{{ (file.size / 1024 / 1024).toFixed(1) }} MB</small>
            </li>
          </ul>

          <div v-if="loading" class="document-upload-progress" role="status" aria-live="polite">
            <span class="document-upload-spinner" aria-hidden="true"></span>
            <div><strong>正在处理文档</strong><span>文件上传完成后，系统正在解析并建立知识索引，请稍候。</span></div>
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
