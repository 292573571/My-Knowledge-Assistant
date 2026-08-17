<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { fetchDocuments, fetchDocumentTasks, ingestDocument, ingestDocuments, rebuildDocuments, syncDocuments } from '../api/documentApi'
import ConfirmDialog from './ConfirmDialog.vue'
import RetrievalDebug from './RetrievalDebug.vue'
import MaintenanceAgentPanel from './MaintenanceAgentPanel.vue'

const props = defineProps({
  workspace: { type: Object, default: null }
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
      message: `将清理“${workspaceName.value}”现有文档索引和向量，再从空间源文件重新生成。其他空间不会受到影响。`,
      confirmText: '开始重建',
      danger: true
    }
  : {
      title: '同步当前空间？',
      message: `将扫描“${workspaceName.value}”的受管源文件，更新变化内容并清理已丢失文件的旧索引。`,
      confirmText: '开始同步',
      danger: false
    })

onMounted(() => {
  loadSummary()
  loadDocumentTasks()
})

onBeforeUnmount(() => {
  disposed = true
  if (taskPollTimer) window.clearTimeout(taskPollTimer)
})

async function loadSummary() {
  try {
    documents.value = await fetchDocuments(props.workspace?.id)
  } catch (exception) {
    error.value = `空间概览加载失败：${formatApiError(exception)}`
  }
}

async function loadDocumentTasks() {
  try {
    const previousStatus = latestMaintenanceTask.value?.status
    documentTasks.value = await fetchDocumentTasks(props.workspace?.id)
    if (latestMaintenanceTask.value?.status === 'SUCCEEDED' && previousStatus !== 'SUCCEEDED') await loadSummary()
  } catch (exception) {
    if (!documentTasks.value.length) error.value = `任务状态加载失败：${formatApiError(exception)}`
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
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
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
    error.value = formatApiError(exception)
    result.value = exception.requestId ? { title: '操作未完成', requestId: exception.requestId, failed: true } : null
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
    </nav>

    <p v-if="activeTool === 'maintenance' && error" class="maintenance-message error" role="alert"><strong>操作失败</strong>{{ error }}</p>
    <section v-if="activeTool === 'maintenance' && result" :class="['maintenance-message', { error: result.failed }]" role="status">
      <strong>{{ result.title }}</strong>
      <span v-if="result.taskId">任务已进入后台队列，本页会自动更新状态和进度。</span>
      <span v-if="result.imported !== undefined">导入 {{ result.imported }} · 跳过 {{ result.skipped }} · 失败 {{ result.failed }} · {{ result.chunks }} chunks</span>
      <span v-else-if="result.scannedFiles !== undefined">扫描 {{ result.scannedFiles }} · 新增 {{ result.addedFiles }} · 更新 {{ result.updatedFiles }} · 删除 {{ result.deletedFiles }}</span>
      <span v-else-if="result.clearedDocuments !== undefined">清理 {{ result.clearedDocuments }} 个旧文档 · 重建 {{ result.files }} 个文件 · {{ result.chunks }} chunks</span>
      <small v-if="result.requestId">requestId: {{ result.requestId }}</small>
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
           <article><span class="maintenance-future-icon neutral" aria-hidden="true">◈</span><div><strong>模型配置</strong><small>统一管理模型与检索策略</small></div><em>即将推出</em></article>
           <article><span class="maintenance-future-icon sand" aria-hidden="true">◇</span><div><strong>数据备份</strong><small>保护索引配置与知识库数据</small></div><em>即将推出</em></article>
        </div>
      </section>
    </div>

    <RetrievalDebug v-else embedded />

    <ConfirmDialog v-if="pendingAction" v-bind="confirmation" :busy="Boolean(busyAction)" @confirm="confirmMaintenance" @cancel="pendingAction = ''" />
  </main>
</template>
