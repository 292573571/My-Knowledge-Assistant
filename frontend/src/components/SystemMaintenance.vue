<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { formatApiError } from '../api/apiError'
import { createPoolModel, deletePoolModel, fetchModelPool, setDefaultPoolModel, testPoolModel, updatePoolModel } from '../api/modelConfigApi'
import { fetchLogs, clearLogs, fetchAuditEvents, purgeAuditEvents } from '../api/logApi'
import { fetchDocuments, fetchDocumentTasks, ingestDocument, ingestDocuments, rebuildDocuments, syncDocuments } from '../api/documentApi'
import ConfirmDialog from './ConfirmDialog.vue'
import RetrievalDebug from './RetrievalDebug.vue'
import MaintenanceAgentPanel from './MaintenanceAgentPanel.vue'
import ToastContainer from './ToastContainer.vue'

const toast = ref(null)
function notifyError(msg) { toast.value?.error(msg) }
function notifySuccess(msg) { toast.value?.success(msg) }

const props = defineProps({
  workspace: { type: Object, default: null },
  currentUser: { type: Object, default: null }
})

const documents = ref([])
const inputPath = ref('')
const busyAction = ref('')
const error = ref('')
const result = ref(null)
const pendingAction = ref('')
const activeTool = ref('maintenance')
const documentTasks = ref([])
const taskStatusOpen = ref(false)
const trackedTaskId = ref('')
let taskPollTimer = null
let disposed = false

const poolModels = ref([])
const poolLoading = ref(false)
const poolSaving = ref(false)
const poolError = ref('')
const poolSuccess = ref('')
const showPoolForm = ref(false)
const editingPoolModel = ref(null)
const testingModelId = ref(null)
const poolForm = reactive({
  name: '', baseUrl: '', apiKey: '', model: '', modelType: 'CHAT',
  temperature: '', topP: '', maxOutputTokens: '', requestTimeoutMs: '', fallbackModels: '',
  enabled: true, isDefault: false
})

const logEntries = ref([])
const auditEntries = ref([])
const auditLoading = ref(false)
const logTotal = ref(0)
const logPage = ref(0)
const logTotalPages = ref(0)
const logLoading = ref(false)
const logError = ref('')
const logLevel = ref('')
const logKeyword = ref('')
const logHours = ref(0)
const logAutoRefresh = ref(false)
let logRefreshTimer = null

async function loadLogs() {
  logLoading.value = true
  logError.value = ''
  try {
    const result = await fetchLogs({ page: logPage.value, size: 100, level: logLevel.value || undefined, keyword: logKeyword.value || undefined, hours: logHours.value })
    logEntries.value = result.entries || []
    logTotal.value = result.total || 0
    logTotalPages.value = result.totalPages || 0
  } catch (e) {
    notifyError(e.message || '加载日志失败')
  } finally {
    logLoading.value = false
  }
}

async function loadAuditEvents() {
  auditLoading.value = true
  try {
    auditEntries.value = await fetchAuditEvents()
  } catch (e) {
    notifyError(e.message || '加载审计日志失败')
  } finally {
    auditLoading.value = false
  }
}

async function purgeAllAuditEvents() {
  if (!window.confirm('确定删除全部审计日志？此操作仅限超级管理员，且不可恢复。')) return
  try {
    const result = await purgeAuditEvents()
    auditEntries.value = []
    notifySuccess(`已删除 ${result.deleted || 0} 条审计日志`)
  } catch (e) {
    notifyError(e.message || '删除审计日志失败')
  }
}

function auditTitle(entry) {
  return `previousHash=${entry.previousHash}\neventHash=${entry.eventHash}`
}

function toggleLogAutoRefresh() {
  logAutoRefresh.value = !logAutoRefresh.value
  if (logAutoRefresh.value) {
    logRefreshTimer = setInterval(loadLogs, 5000)
  } else {
    clearInterval(logRefreshTimer)
    logRefreshTimer = null
  }
}

watch([logLevel, logKeyword, logHours], () => { logPage.value = 0; loadLogs() })

function logLevelClass(level) {
  if (level === 'ERROR') return 'error'
  if (level === 'WARN') return 'warn'
  if (level === 'DEBUG') return 'debug'
  return ''
}

function logContextLabel(entry) {
  const values = [entry.requestId, entry.traceId, entry.userId, entry.workspaceId, entry.instanceId, entry.environment, entry.exceptionType]
    .filter(Boolean)
  return values.length ? `${values.length} 个上下文字段` : ''
}

function logContextTitle(entry) {
  return [
    ['requestId', entry.requestId],
    ['traceId', entry.traceId],
    ['userId', entry.userId],
    ['workspaceId', entry.workspaceId],
    ['instanceId', entry.instanceId],
    ['environment', entry.environment],
    ['exceptionType', entry.exceptionType],
    ['stackTrace', entry.stackTrace]
  ].filter(([, value]) => value).map(([key, value]) => `${key}=${value}`).join('\n')
}

async function clearAllLogs() {
  if (!window.confirm('确定清理全部普通运行日志？审计日志不受影响。')) return
  try {
    const result = await clearLogs()
    logEntries.value = []
    logTotal.value = 0
    logTotalPages.value = 0
    logPage.value = 0
  } catch (e) {
    notifyError(e.message || '清除日志失败')
  }
}

const totalChunks = computed(() => documents.value.reduce((sum, document) => sum + (document.chunkCount || 0), 0))
const workspaceName = computed(() => props.workspace?.name || '当前空间')
const workspaceTypeLabel = computed(() => ({ PERSONAL: '个人', TEAM: '团队', PUBLIC: '公共' })[props.workspace?.type] || '空间')
const latestRebuildTask = computed(() => documentTasks.value.find((task) => task.type === 'REBUILD') || null)
const latestMaintenanceTask = computed(() => documentTasks.value.find((task) => ['REBUILD', 'SYNC'].includes(task.type)) || null)
const rebuildRunning = computed(() => latestRebuildTask.value
  && ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(latestRebuildTask.value.status))
const maintenanceTask = computed(() => documentTasks.value.find((task) => task.taskId === trackedTaskId.value) || null)
const maintenanceTaskRunning = computed(() => maintenanceTask.value
  && ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(maintenanceTask.value.status))
const hasUnfinishedMaintenanceTask = computed(() => latestMaintenanceTask.value
  && ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(latestMaintenanceTask.value.status))
const maintenanceTaskTitle = computed(() => maintenanceTask.value?.type === 'SYNC' ? '空间同步' : '索引重建')
const maintenanceTaskSummary = computed(() => {
  const task = maintenanceTask.value
  if (!task) return ''
  if (task.type === 'SYNC') {
    if (task.status === 'QUEUED') return '同步任务已排队，等待开始扫描当前空间。'
    if (task.status === 'RUNNING') return '正在检查新增、修改和已删除的空间源文件。'
    if (task.status === 'RETRY_WAIT') return '同步遇到临时问题，将在稍后自动重试。'
    if (task.status === 'SUCCEEDED') return '空间同步已完成，当前索引已更新。'
    return task.errorMessage || '空间同步未完成，请查看失败原因。'
  }
  const summaryTask = task
  if (summaryTask.status === 'QUEUED') return '任务正在等待执行，开始后会逐个统计空间源文件。'
  if (summaryTask.status === 'RETRY_WAIT') return '任务将在稍后自动重试，已完成的统计会继续保留。'
  if (summaryTask.status === 'FAILED') return summaryTask.errorMessage || '重建未完成，请查看失败原因。'
  if (summaryTask.status === 'SUCCEEDED') return `本次计划处理 ${summaryTask.totalItems} 个文件，成功 ${summaryTask.succeededItems} 个，失败 ${summaryTask.failedItems} 个，生成 ${summaryTask.resultChunks} 个 chunks。`
  return summaryTask.totalItems
    ? `正在处理第 ${Math.min(summaryTask.completedItems + 1, summaryTask.totalItems)} / ${summaryTask.totalItems} 个文件，已成功 ${summaryTask.succeededItems} 个。`
    : '正在扫描当前空间的受管源文件，文件总数确认后将开始重建。'
})
const taskStageLabels = {
  QUEUED: '任务已进入队列',
  PARSING: '正在准备重建',
  SCANNING: '正在扫描文件',
  REBUILDING: '正在逐个重建文件',
  PERSISTING_INDEX: '正在保存索引',
  RETRY_WAIT: '稍后自动重试',
  DONE: '全部文件已完成索引',
  FAILED: '请查看失败原因'
}
const confirmation = computed(() => pendingAction.value === 'rebuild'
  ? {
      title: '重建当前空间索引？',
      message: `将清理"${workspaceName.value}"现有文档索引和向量，再从空间源文件重新生成。其他空间不会受到影响。`,
      confirmText: '开始重建',
      danger: true
    }
  : {
      title: '同步当前空间？',
      message: `将扫描"${workspaceName.value}"的受管源文件，更新变化内容并清理已丢失文件的旧索引。`,
      confirmText: '开始同步',
      danger: false
    })

onMounted(() => {
  loadSummary()
  loadDocumentTasks()
  loadPool()
})

onBeforeUnmount(() => {
  disposed = true
  if (taskPollTimer) window.clearTimeout(taskPollTimer)
  if (logRefreshTimer) clearInterval(logRefreshTimer)
})

async function loadSummary() {
  try {
    documents.value = await fetchDocuments(props.workspace?.id)
  } catch (exception) {
    notifyError(`空间概览加载失败：${formatApiError(exception)}`)
  }
}

async function loadDocumentTasks() {
  try {
    const previousStatus = latestMaintenanceTask.value?.status
    documentTasks.value = await fetchDocumentTasks(props.workspace?.id)
    if (latestMaintenanceTask.value?.status === 'SUCCEEDED' && previousStatus !== 'SUCCEEDED') await loadSummary()
  } catch (exception) {
    if (!documentTasks.value.length) notifyError(`任务状态加载失败：${formatApiError(exception)}`)
  } finally {
    if (!disposed) scheduleTaskPoll()
  }
}

function scheduleTaskPoll() {
  if (taskPollTimer) window.clearTimeout(taskPollTimer)
  taskPollTimer = window.setTimeout(loadDocumentTasks, maintenanceTaskRunning.value ? 2000 : 5000)
}

function formatTime(value) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  }).format(new Date(value))
}

async function run(actionKey, action, successTitle) {
  busyAction.value = actionKey
  error.value = ''
  result.value = null
  try {
    const data = await action()
    result.value = { title: successTitle, ...data }
    if (data?.taskId) {
      trackedTaskId.value = data.taskId
      taskStatusOpen.value = true
    }
    await loadDocumentTasks()
    await loadSummary()
  } catch (exception) {
    notifyError(formatApiError(exception))
    result.value = { title: '操作未完成', failed: true }
  } finally {
    busyAction.value = ''
  }
}

function closeTaskStatus() {
  taskStatusOpen.value = false
}

async function importFile() {
  const path = inputPath.value.trim()
  if (!path) {
    error.value = '请输入 docs 目录下的文件路径。'
    return
  }
  await run('import-file', () => ingestDocument(path, false, props.workspace?.id), '文件导入任务已提交')
}

async function importDirectory() {
  const path = inputPath.value.trim()
  if (!path) {
    error.value = '请输入 docs 目录下的文件夹路径。'
    return
  }
  await run('import-directory', () => ingestDocuments(path, false, props.workspace?.id), '目录导入任务已提交')
}

async function confirmMaintenance() {
  const action = pendingAction.value
  pendingAction.value = ''
  if (action === 'sync') await run('sync', () => syncDocuments(props.workspace?.id), '空间同步任务已提交')
  if (action === 'rebuild') await run('rebuild', () => rebuildDocuments(props.workspace?.id), '索引重建任务已提交')
}

async function loadPool() {
  poolLoading.value = true
  poolError.value = ''
  try { poolModels.value = await fetchModelPool() || [] }
  catch (e) { notifyError(formatApiError(e, '加载模型列表失败')) }
  finally { poolLoading.value = false }
}

function openAddPoolModel() {
  editingPoolModel.value = null
  resetPoolForm()
  showPoolForm.value = true
}

function openEditPoolModel(model) {
  editingPoolModel.value = model
  Object.assign(poolForm, {
    name: model.name || '', baseUrl: model.baseUrl || '', apiKey: model.apiKey || '',
    model: model.model || '', modelType: model.modelType || 'CHAT',
    temperature: model.temperature != null ? String(model.temperature) : '',
    topP: model.topP != null ? String(model.topP) : '',
    maxOutputTokens: model.maxOutputTokens != null ? String(model.maxOutputTokens) : '',
    requestTimeoutMs: model.requestTimeoutMs != null ? String(model.requestTimeoutMs) : '',
    fallbackModels: model.fallbackModels || '',
    enabled: model.enabled, isDefault: model.isDefault || false
  })
  showPoolForm.value = true
}

function resetPoolForm() {
  Object.assign(poolForm, { name: '', baseUrl: '', apiKey: '', model: '', modelType: 'CHAT', temperature: '', topP: '', maxOutputTokens: '', requestTimeoutMs: '', fallbackModels: '', enabled: true, isDefault: false })
}

async function submitPoolForm() {
  poolSaving.value = true
  poolError.value = ''
  try {
    const data = {
      name: poolForm.name, baseUrl: poolForm.baseUrl, apiKey: poolForm.apiKey, model: poolForm.model,
      modelType: poolForm.modelType,
      temperature: poolForm.temperature ? Number(poolForm.temperature) : null,
      topP: poolForm.topP ? Number(poolForm.topP) : null,
      maxOutputTokens: poolForm.maxOutputTokens ? Number(poolForm.maxOutputTokens) : null,
      requestTimeoutMs: poolForm.requestTimeoutMs ? Number(poolForm.requestTimeoutMs) : null,
      fallbackModels: poolForm.fallbackModels || null,
      enabled: poolForm.enabled, isDefault: poolForm.isDefault
    }
    if (editingPoolModel.value) await updatePoolModel(editingPoolModel.value.id, data)
    else await createPoolModel(data)
    showPoolForm.value = false
    await loadPool()
    notifySuccess(editingPoolModel.value ? '模型已更新。' : '模型已添加。')
  } catch (e) {
    notifyError(formatApiError(e, '保存模型失败'))
  } finally {
    poolSaving.value = false
  }
}

async function handleDeletePoolModel(model) {
  if (!confirm(`确定要删除「${model.name}」吗？`)) return
  poolSaving.value = true
  poolError.value = ''
  try {
    await deletePoolModel(model.id)
    await loadPool()
    notifySuccess('模型已删除。')
  } catch (e) {
    notifyError(formatApiError(e, '删除模型失败'))
  } finally {
    poolSaving.value = false
  }
}

async function handleSetDefault(model) {
  poolSaving.value = true
  poolError.value = ''
  try {
    await setDefaultPoolModel(model.id)
    await loadPool()
    notifySuccess(`已将「${model.name}」设为默认${model.modelType === 'EMBEDDING' ? '嵌入' : '对话'}模型。`)
  } catch (e) {
    notifyError(formatApiError(e, '设置默认模型失败'))
  } finally {
    poolSaving.value = false
  }
}

async function handleTestModel(model) {
  testingModelId.value = model.id
  poolError.value = ''
  poolSuccess.value = ''
  try {
    await testPoolModel(model.id)
    notifySuccess(`「${model.name}」连接测试通过。`)
  } catch (e) {
    notifyError(formatApiError(e, '连接测试失败'))
  } finally {
    testingModelId.value = null
  }
}
</script>

<template>
  <main class="maintenance-dashboard">
    <section class="maintenance-hero" data-reveal>
      <div>
        <p class="records-badge">管理员工具</p>
        <h1>系统维护</h1>
        <p>集中处理资料迁移、索引同步和故障恢复。后续系统级工具将在此页面持续扩展。</p>
      </div>
      <div class="maintenance-scope">
        <span>当前维护范围</span>
        <strong>{{ workspaceName }}</strong>
        <small>{{ workspaceTypeLabel }} · {{ documents.length }} 个文档 · {{ totalChunks }} 个分块</small>
      </div>
    </section>

    <nav class="maintenance-tabs" aria-label="系统维护功能">
      <button type="button" :aria-pressed="activeTool === 'maintenance'" :class="{ active: activeTool === 'maintenance' }" @click="activeTool = 'maintenance'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14.7 6.3a4 4 0 0 0-5 5L4 17l3 3 5.7-5.7a4 4 0 0 0 5-5l-2.2 2.2-3-3 2.2-2.2Z"/></svg>
        维护工具
      </button>
      <button type="button" :aria-pressed="activeTool === 'retrieval'" :class="{ active: activeTool === 'retrieval' }" @click="activeTool = 'retrieval'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="5.5"/><path d="m15 15 5 5M7.5 10.5h6M10.5 7.5v6"/></svg>
        检索诊断
      </button>
      <button type="button" :aria-pressed="activeTool === 'model-pool'" :class="{ active: activeTool === 'model-pool' }" @click="activeTool = 'model-pool'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 7V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v2"/><path d="M4 8v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"/><path d="M12 12.5v4M9 12.5h6"/></svg>
        模型管理
      </button>
      <button type="button" :aria-pressed="activeTool === 'logs'" :class="{ active: activeTool === 'logs' }" @click="activeTool = 'logs'; loadLogs()">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/></svg>
        日志中心
      </button>
      <button type="button" :aria-pressed="activeTool === 'audit'" :class="{ active: activeTool === 'audit' }" @click="activeTool = 'audit'; loadAuditEvents()">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5 6v5c0 4.7 2.9 8.5 7 10 4.1-1.5 7-5.3 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-4"/></svg>
        审计日志
      </button>
    </nav>

    <p v-if="activeTool === 'maintenance' && error" class="maintenance-message error" role="alert"><strong>操作失败</strong>{{ error }}</p>
    <section v-if="activeTool === 'maintenance' && result" :class="['maintenance-message', { error: result.failed }]" role="status">
      <strong>{{ result.title }}</strong>
      <span v-if="result.taskId">任务已进入后台队列，本页会自动更新状态和进度。</span>
       <span v-if="result.imported !== undefined">导入 {{ result.imported }} 份 · 跳过 {{ result.skipped }} 份 · 失败 {{ result.failed }} 份 · 生成 {{ result.chunks }} 个知识片段</span>
       <span v-else-if="result.scannedFiles !== undefined">扫描 {{ result.scannedFiles }} 份 · 新增 {{ result.addedFiles }} 份 · 更新 {{ result.updatedFiles }} 份 · 删除 {{ result.deletedFiles }} 份</span>
       <span v-else-if="result.clearedDocuments !== undefined">清理 {{ result.clearedDocuments }} 个旧文档 · 重建 {{ result.files }} 个文件 · 生成 {{ result.chunks }} 个知识片段</span>
    </section>

    <div v-if="activeTool === 'maintenance'" class="maintenance-sections">
      <MaintenanceAgentPanel :workspace="workspace" />
      <section class="maintenance-section maintenance-index-section">
        <header class="maintenance-section-heading">
          <div>
            <p class="maintenance-section-kicker">INDEX OPERATIONS</p>
            <h2>索引维护</h2>
            <span>查看当前状态，并选择适合的索引操作。</span>
          </div>
          <span class="maintenance-section-hint">日常优先使用增量同步</span>
        </header>
        <div class="maintenance-index-toolbar">
          <span v-if="hasUnfinishedMaintenanceTask">后台任务仍在运行，关闭详情不会中断任务。</span>
          <button v-if="hasUnfinishedMaintenanceTask && !taskStatusOpen" type="button" class="maintenance-progress-button" @click="trackedTaskId = latestMaintenanceTask.taskId; taskStatusOpen = true">
            <span class="maintenance-live-dot" aria-hidden="true"></span>
            查看进度
          </button>
        </div>
        <section v-if="taskStatusOpen && maintenanceTask" :class="['document-rebuild-status', 'maintenance-task-status', maintenanceTask.status.toLowerCase()]" role="status" aria-live="polite">
          <header>
            <div class="document-rebuild-title">
              <span class="document-rebuild-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 1 0-2.34 5.66M20 5v6h-6"/></svg>
              </span>
              <div>
                <span>{{ maintenanceTaskRunning ? `${maintenanceTaskTitle}进行中` : `${maintenanceTaskTitle}${maintenanceTask.status === 'SUCCEEDED' ? '已完成' : '未完成'}` }}</span>
                <strong>{{ taskStageLabels[maintenanceTask.stage] || maintenanceTask.stage }}</strong>
              </div>
            </div>
            <div class="maintenance-task-header-actions"><b>{{ maintenanceTask.progress }}%</b><button type="button" class="document-rebuild-close" aria-label="关闭任务状态" @click="closeTaskStatus">×</button></div>
          </header>
          <div class="document-rebuild-progress" :aria-label="`${maintenanceTaskTitle}进度 ${maintenanceTask.progress}%`"><span :style="{ width: `${maintenanceTask.progress}%` }"></span></div>
          <div class="document-rebuild-counts">
            <div><span>计划文件</span><strong>{{ maintenanceTask.totalItems || (maintenanceTaskRunning ? '统计中' : 0) }}</strong></div>
            <div><span>已处理</span><strong>{{ maintenanceTask.completedItems }}</strong></div>
            <div><span>成功</span><strong>{{ maintenanceTask.succeededItems }}</strong></div>
            <div><span>失败</span><strong>{{ maintenanceTask.failedItems }}</strong></div>
            <div><span>生成分块</span><strong>{{ maintenanceTask.resultChunks }}</strong></div>
          </div>
          <footer>
            <span>{{ maintenanceTaskSummary }}</span>
            <time :datetime="maintenanceTask.finishedAt || maintenanceTask.createdAt">{{ maintenanceTask.finishedAt ? `完成于 ${formatTime(maintenanceTask.finishedAt)}` : `开始于 ${formatTime(maintenanceTask.createdAt)}` }}</time>
          </footer>
        </section>
        <div class="maintenance-index-grid">
          <article class="maintenance-card maintenance-card-sync">
            <header>
              <span class="maintenance-card-icon sync"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 8a7 7 0 0 0-12-2L5 8m0 0V4m0 4h4M5 16a7 7 0 0 0 12 2l2-2m0 0v4m0-4h-4"/></svg></span>
              <div><div class="maintenance-card-label">推荐操作</div><h3>增量同步</h3><p>检测新增、修改和删除的源文件，只更新发生变化的索引。</p></div>
            </header>
            <footer class="maintenance-card-footer"><span>适合日常维护，影响范围较小</span><button type="button" class="secondary" :disabled="Boolean(busyAction) || maintenanceTaskRunning" @click="pendingAction = 'sync'">同步当前空间</button></footer>
          </article>
          <article class="maintenance-card maintenance-card-rebuild">
            <header>
              <span class="maintenance-card-icon rebuild"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v4m0 10v4M3 12h4m10 0h4M5.6 5.6l2.8 2.8m7.2 7.2 2.8 2.8m0-12.8-2.8 2.8m-7.2 7.2-2.8 2.8"/><circle cx="12" cy="12" r="3"/></svg></span>
              <div><div class="maintenance-card-label">故障恢复</div><h3>索引重建</h3><p>清理旧索引和向量，从受管源文件完整恢复当前空间。</p></div>
            </header>
            <footer class="maintenance-card-footer"><span>{{ maintenanceTaskRunning && maintenanceTask?.type === 'REBUILD' ? '当前空间正在重建' : '仅在索引异常或解析策略变更后使用' }}</span><button type="button" class="danger" :disabled="Boolean(busyAction) || maintenanceTaskRunning" @click="pendingAction = 'rebuild'">{{ maintenanceTaskRunning && maintenanceTask?.type === 'REBUILD' ? '重建进行中' : '重建当前空间' }}</button></footer>
          </article>
        </div>
      </section>

      <section class="maintenance-section maintenance-import-section">
        <header class="maintenance-section-heading">
          <div>
            <p class="maintenance-section-kicker">SOURCE INGESTION</p>
            <h2>资料导入</h2>
            <span>将服务器上的存量资料复制到当前空间的受管目录。</span>
          </div>
          <span class="maintenance-section-hint">仅允许访问 docs 目录</span>
        </header>
        <article class="maintenance-card maintenance-import-card">
          <div class="maintenance-import-main">
            <span class="maintenance-card-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7.5h6l2 2h8v9.5H4V7.5Z"/><path d="M12 17v-5m0 0-2 2m2-2 2 2"/></svg></span>
            <div><h3>服务器资料导入</h3><p>支持导入单个文件或整个目录，资料会复制到当前空间后异步建立索引。</p></div>
          </div>
          <label class="maintenance-path-field">
            <span>文件或目录路径</span>
            <input v-model="inputPath" type="text" placeholder="例如 docs/mcp-notes.md 或 docs/archive" :disabled="Boolean(busyAction)">
            <small>不能读取服务器 docs 目录以外的其他位置。</small>
          </label>
          <div class="maintenance-actions">
            <button type="button" :disabled="Boolean(busyAction)" @click="importFile">{{ busyAction ? '处理中...' : '导入文件' }}</button>
            <button type="button" class="secondary" :disabled="Boolean(busyAction)" @click="importDirectory">导入目录</button>
          </div>
        </article>
      </section>

      <section class="maintenance-future-section">
        <header class="maintenance-section-heading">
          <div>
            <p class="maintenance-section-kicker">COMING NEXT</p>
            <h2>更多维护能力</h2>
            <span>把高频的系统管理动作集中到这里，逐步减少手工排查。</span>
          </div>
          <span class="maintenance-section-hint">规划中</span>
        </header>
        <div class="maintenance-future-grid">
           <article><span class="maintenance-future-icon green" aria-hidden="true">◒</span><div><strong>任务监控</strong><small>查看所有后台任务的执行记录</small></div><em>即将推出</em></article>
           <article><span class="maintenance-future-icon neutral" aria-hidden="true">◈</span><div><strong>数据备份</strong><small>保护索引配置与知识库数据</small></div><em>即将推出</em></article>
           <article><span class="maintenance-future-icon sand" aria-hidden="true">◇</span><div><strong>日志中心</strong><small>集中查看和检索系统运行时日志</small></div><em>即将推出</em></article>
        </div>
      </section>
    </div>

    <div v-if="activeTool === 'model-pool'" class="maintenance-model-pool">
      <section class="maintenance-section">
        <header class="maintenance-section-heading">
          <div>
            <p class="maintenance-section-kicker">MODEL MANAGEMENT</p>
            <h2>全局模型池</h2>
            <span>管理可用的 AI 模型，设置默认模型，控制模型启用状态。</span>
          </div>
        </header>

        <p v-if="poolLoading" class="maintenance-message">加载中...</p>

        <div class="maintenance-index-toolbar">
          <button class="maintenance-add-btn" @click="openAddPoolModel">+ 添加模型</button>
        </div>

        <div v-if="poolModels.length === 0 && !poolLoading" class="maintenance-message">模型池为空，请添加第一个模型。</div>

        <div v-for="model in poolModels" :key="model.id" class="maintenance-model-row">
          <div class="maintenance-model-info">
            <strong>{{ model.name }}</strong>
            <span class="maintenance-model-identifier">{{ model.model }}</span>
            <span :class="['maintenance-model-tag', model.modelType === 'EMBEDDING' ? 'embedding' : 'chat']">{{ model.modelType === 'EMBEDDING' ? '嵌入' : '对话' }}</span>
            <span v-if="model.isDefault" class="maintenance-model-tag default">{{ model.modelType === 'EMBEDDING' ? '默认嵌入' : '默认对话' }}</span>
            <span v-if="!model.enabled" class="maintenance-model-tag disabled">已停用</span>
          </div>
          <div class="maintenance-model-actions">
            <button class="maintenance-model-btn" @click="openEditPoolModel(model)">编辑</button>
            <button v-if="!model.isDefault && model.enabled" class="maintenance-model-btn primary" @click="handleSetDefault(model)">设默认</button>
            <button class="maintenance-model-btn" :disabled="testingModelId === model.id" @click="handleTestModel(model)">{{ testingModelId === model.id ? '测试中...' : '测试' }}</button>
            <button class="maintenance-model-btn danger" @click="handleDeletePoolModel(model)">删除</button>
          </div>
        </div>
      </section>
    </div>

    <RetrievalDebug v-else-if="activeTool === 'retrieval'" embedded />

    <section v-else-if="activeTool === 'logs'" class="log-viewer-section">
      <header class="log-viewer-header">
        <div><p class="retrieval-debug-kicker">SYSTEM LOGS</p><h2>日志中心</h2><p>查看应用运行日志，排查问题。</p></div>
      </header>
      <div class="log-viewer-toolbar">
        <label class="log-filter-label">级别
          <select v-model="logLevel" class="log-filter-select">
            <option value="">全部</option>
            <option value="ERROR">ERROR</option>
            <option value="WARN">WARN</option>
            <option value="INFO">INFO</option>
            <option value="DEBUG">DEBUG</option>
          </select>
        </label>
        <label class="log-filter-label">时间
          <select v-model="logHours" class="log-filter-select">
            <option :value="0">全部时间</option>
            <option :value="1">最近 1 小时</option>
            <option :value="2">最近 2 小时</option>
            <option :value="6">最近 6 小时</option>
            <option :value="24">最近 24 小时</option>
            <option :value="72">最近 3 天</option>
            <option :value="168">最近 7 天</option>
          </select>
        </label>
        <label class="log-filter-label">关键词
          <input v-model="logKeyword" type="text" class="log-filter-input" placeholder="搜索关键词" @keydown.enter="loadLogs">
        </label>
        <button type="button" class="retrieval-eval-secondary" :disabled="logLoading" @click="loadLogs">{{ logLoading ? '加载中...' : '刷新' }}</button>
        <button type="button" :class="['retrieval-eval-secondary', { active: logAutoRefresh }]" @click="toggleLogAutoRefresh">{{ logAutoRefresh ? '停止自动' : '自动刷新' }}</button>
        <button type="button" class="retrieval-eval-secondary" style="margin-left:12px" @click="clearAllLogs">清理运行日志</button>
        <span class="log-count">{{ logTotal }} 条</span>
      </div>

      <div class="log-viewer-body">
        <div v-if="logLoading && !logEntries.length" class="retrieval-eval-empty">正在加载日志...</div>
        <div v-else-if="!logEntries.length" class="retrieval-eval-empty">暂无日志。</div>
        <template v-else>
          <div class="log-line-list">
            <div v-for="entry in logEntries" :key="entry.id" :class="['log-line', logLevelClass(entry.level)]">
              <span class="log-line-time">{{ entry.timestamp }}</span>
              <span class="log-line-level">{{ entry.level }}</span>
              <span class="log-line-logger">{{ entry.logger }}</span>
              <span class="log-line-text">{{ entry.message }}</span>
              <span class="log-line-context" :title="logContextTitle(entry)">{{ logContextLabel(entry) }}</span>
            </div>
          </div>
          <div v-if="logTotalPages > 1" class="log-pagination">
            <button type="button" class="retrieval-eval-secondary" :disabled="logPage === 0 || logLoading" @click="logPage--; loadLogs()">上一页</button>
            <span class="log-page-info">{{ logPage + 1 }} / {{ logTotalPages }}</span>
            <button type="button" class="retrieval-eval-secondary" :disabled="logPage >= logTotalPages - 1 || logLoading" @click="logPage++; loadLogs()">下一页</button>
          </div>
        </template>
      </div>
    </section>

    <section v-else-if="activeTool === 'audit'" class="log-viewer-section">
      <header class="log-viewer-header">
        <div><p class="retrieval-debug-kicker">IMMUTABLE AUDIT TRAIL</p><h2>审计日志</h2><p>记录登录、权限、成员、文档和模型配置变更。普通管理员只读，删除仅限超级管理员。</p></div>
      </header>
      <div class="log-viewer-toolbar">
        <button type="button" class="retrieval-eval-secondary" :disabled="auditLoading" @click="loadAuditEvents">{{ auditLoading ? '加载中...' : '刷新' }}</button>
        <button v-if="currentUser?.systemRole === 'SUPER_ADMIN'" type="button" class="retrieval-eval-secondary" style="margin-left:12px" @click="purgeAllAuditEvents">删除全部审计日志</button>
        <span class="log-count">{{ auditEntries.length }} 条 · 普通管理员只读</span>
      </div>
      <div class="log-viewer-body">
        <div v-if="auditLoading && !auditEntries.length" class="retrieval-eval-empty">正在加载审计日志...</div>
        <div v-else-if="!auditEntries.length" class="retrieval-eval-empty">暂无审计日志。</div>
        <div v-else class="log-line-list">
          <div v-for="entry in auditEntries" :key="entry.id" class="log-line" :title="auditTitle(entry)">
            <span class="log-line-time">{{ entry.createdAt }}</span>
            <span class="log-line-level">{{ entry.outcome }}</span>
            <span class="log-line-logger">{{ entry.action }}</span>
            <span class="log-line-text">{{ entry.resourceType }} / {{ entry.resourceId }} · 操作人 {{ entry.actorPublicId }}</span>
            <span class="log-line-context">链 {{ entry.eventHash?.slice(0, 12) }}</span>
          </div>
        </div>
      </div>
    </section>

    <ConfirmDialog v-if="pendingAction" v-bind="confirmation" :busy="Boolean(busyAction)" @confirm="confirmMaintenance" @cancel="pendingAction = ''" />

    <div v-if="showPoolForm" class="model-config-backdrop" @click.self="showPoolForm = false">
      <div class="model-config-dialog">
        <header class="model-config-dialog-header">
          <h2>{{ editingPoolModel ? '编辑模型' : '添加模型' }}</h2>
          <button class="model-config-close-btn" aria-label="关闭" @click="showPoolForm = false">&times;</button>
        </header>
        <div class="pool-edit-body">
          <div class="model-config-form-grid">
            <label><span>类型</span><select v-model="poolForm.modelType">
              <option value="CHAT">对话模型</option>
              <option value="EMBEDDING">嵌入模型</option>
            </select></label>
            <label><span><span class="required">*</span>名称</span><input v-model="poolForm.name" type="text" placeholder="例如：DeepSeek V4" maxlength="64" required></label>
            <label class="span-2"><span><span class="required">*</span>模型标识</span><input v-model="poolForm.model" type="text" placeholder="例如：deepseek-ai/DeepSeek-V4-Flash" maxlength="128" required></label>
            <label class="span-2"><span><span class="required">*</span>API 地址</span><input v-model="poolForm.baseUrl" type="text" placeholder="https://api.example.com" maxlength="256" required></label>
            <label class="span-2"><span><span class="required">*</span>API Key</span><input v-model="poolForm.apiKey" type="password" placeholder="sk-..." maxlength="256" required></label>
            <label><span>温度</span><input v-model="poolForm.temperature" type="number" step="0.1" min="0" max="2" placeholder="留空使用默认"></label>
            <label><span>Top P</span><input v-model="poolForm.topP" type="number" step="0.01" min="0" max="1" placeholder="留空使用默认"></label>
            <label><span>最大输出 Token</span><input v-model="poolForm.maxOutputTokens" type="number" placeholder="留空使用默认"></label>
            <label><span>请求超时(ms)</span><input v-model="poolForm.requestTimeoutMs" type="number" placeholder="留空使用默认"></label>
            <label class="span-2"><span>备用模型 (逗号分隔)</span><input v-model="poolForm.fallbackModels" type="text" placeholder="例如：gpt-4o,claude-3" maxlength="256"></label>
            <label class="span-2 checkbox"><input v-model="poolForm.enabled" type="checkbox"> 启用模型</label>
            <label class="span-2 checkbox"><input v-model="poolForm.isDefault" type="checkbox"> 设为该类型的默认模型</label>
          </div>
        </div>
        <div class="pool-edit-footer">
          <button class="model-config-footer-btn secondary" :disabled="poolSaving" @click="showPoolForm = false">取消</button>
          <button class="model-config-footer-btn primary" :disabled="poolSaving" :aria-busy="poolSaving" @click="submitPoolForm">
            {{ poolSaving ? '保存中...' : (editingPoolModel ? '更新' : '添加') }}
          </button>
        </div>
      </div>
    </div>

    <ToastContainer ref="toast" />
  </main>
</template>
