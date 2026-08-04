<script setup>
import { computed, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { fetchDocuments, ingestDocument, ingestDocuments, rebuildDocuments, syncDocuments } from '../api/documentApi'
import ConfirmDialog from './ConfirmDialog.vue'
import RetrievalDebug from './RetrievalDebug.vue'

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

const totalChunks = computed(() => documents.value.reduce((sum, document) => sum + (document.chunkCount || 0), 0))
const workspaceName = computed(() => props.workspace?.name || '当前空间')
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

onMounted(loadSummary)

async function loadSummary() {
  try {
    documents.value = await fetchDocuments(props.workspace?.id)
  } catch (exception) {
    error.value = `空间概览加载失败：${formatApiError(exception)}`
  }
}

async function run(actionKey, action, successTitle) {
  busyAction.value = actionKey
  error.value = ''
  result.value = null
  try {
    const data = await action()
    result.value = { title: successTitle, ...data }
    await loadSummary()
  } catch (exception) {
    error.value = formatApiError(exception)
    result.value = exception.requestId ? { title: '操作未完成', requestId: exception.requestId, failed: true } : null
  } finally {
    busyAction.value = ''
  }
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
    <section class="maintenance-hero">
      <div>
        <p class="records-badge">管理员工具</p>
        <h1>系统维护</h1>
        <p>集中处理资料迁移、索引同步和故障恢复。后续系统级工具将在此页面持续扩展。</p>
      </div>
      <div class="maintenance-scope">
        <span>当前维护范围</span>
        <strong>{{ workspaceName }}</strong>
        <small>{{ workspace?.type || 'SPACE' }} · {{ documents.length }} 个文档 · {{ totalChunks }} 个 chunks</small>
      </div>
    </section>

    <nav class="maintenance-tabs" aria-label="系统维护功能">
      <button type="button" :class="{ active: activeTool === 'maintenance' }" @click="activeTool = 'maintenance'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14.7 6.3a4 4 0 0 0-5 5L4 17l3 3 5.7-5.7a4 4 0 0 0 5-5l-2.2 2.2-3-3 2.2-2.2Z"/></svg>
        维护工具
      </button>
      <button type="button" :class="{ active: activeTool === 'retrieval' }" @click="activeTool = 'retrieval'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="5.5"/><path d="m15 15 5 5M7.5 10.5h6M10.5 7.5v6"/></svg>
        检索诊断
      </button>
    </nav>

    <p v-if="activeTool === 'maintenance' && error" class="maintenance-message error" role="alert"><strong>操作失败</strong>{{ error }}</p>
    <section v-if="activeTool === 'maintenance' && result" :class="['maintenance-message', { error: result.failed }]" role="status">
      <strong>{{ result.title }}</strong>
      <span v-if="result.taskId">任务已进入后台队列，可前往「知识库管理」查看状态和进度。</span>
      <span v-if="result.imported !== undefined">导入 {{ result.imported }} · 跳过 {{ result.skipped }} · 失败 {{ result.failed }} · {{ result.chunks }} chunks</span>
      <span v-else-if="result.scannedFiles !== undefined">扫描 {{ result.scannedFiles }} · 新增 {{ result.addedFiles }} · 更新 {{ result.updatedFiles }} · 删除 {{ result.deletedFiles }}</span>
      <span v-else-if="result.clearedDocuments !== undefined">清理 {{ result.clearedDocuments }} 个旧文档 · 重建 {{ result.files }} 个文件 · {{ result.chunks }} chunks</span>
      <small v-if="result.requestId">requestId: {{ result.requestId }}</small>
    </section>

    <div v-if="activeTool === 'maintenance'" class="maintenance-grid">
      <section class="maintenance-card maintenance-import-card">
        <header>
          <span class="maintenance-card-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7.5h6l2 2h8v9.5H4V7.5Z"/><path d="M12 17v-5m0 0-2 2m2-2 2 2"/></svg></span>
          <div><h2>服务器资料导入</h2><p>迁移服务器 docs 目录中的存量资料，并复制到当前空间的独立受管目录。</p></div>
        </header>
        <label class="maintenance-path-field">
          <span>文件或目录路径</span>
          <input v-model="inputPath" type="text" placeholder="例如 docs/mcp-notes.md 或 docs/archive" :disabled="Boolean(busyAction)">
          <small>仅允许访问 docs 目录，不能读取服务器其他位置。</small>
        </label>
        <div class="maintenance-actions">
          <button type="button" :disabled="Boolean(busyAction)" @click="importFile">{{ busyAction ? '处理中...' : '导入文件' }}</button>
          <button type="button" class="secondary" :disabled="Boolean(busyAction)" @click="importDirectory">导入目录</button>
        </div>
      </section>

      <section class="maintenance-card">
        <header>
          <span class="maintenance-card-icon sync"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 8a7 7 0 0 0-12-2L5 8m0 0V4m0 4h4M5 16a7 7 0 0 0 12 2l2-2m0 0v4m0-4h-4"/></svg></span>
          <div><h2>增量同步</h2><p>检测当前空间源文件的新增、变化和删除，只更新发生变化的索引。</p></div>
        </header>
        <div class="maintenance-card-footer"><span>推荐用于日常维护</span><button type="button" class="secondary" :disabled="Boolean(busyAction)" @click="pendingAction = 'sync'">同步当前空间</button></div>
      </section>

      <section class="maintenance-card maintenance-danger-card">
        <header>
          <span class="maintenance-card-icon rebuild"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v4m0 10v4M3 12h4m10 0h4M5.6 5.6l2.8 2.8m7.2 7.2 2.8 2.8m0-12.8-2.8 2.8m-7.2 7.2-2.8 2.8"/><circle cx="12" cy="12" r="3"/></svg></span>
          <div><h2>索引重建</h2><p>清理当前空间的旧索引和向量，并从受管源文件完整恢复。</p></div>
        </header>
        <div class="maintenance-card-footer"><span>仅在索引异常时使用</span><button type="button" class="danger" :disabled="Boolean(busyAction)" @click="pendingAction = 'rebuild'">重建当前空间</button></div>
      </section>

      <section class="maintenance-card maintenance-placeholder">
        <span>+</span><div><h2>更多维护能力</h2><p>模型配置、数据备份、任务监控等工具可继续在此扩展。</p></div>
      </section>
    </div>

    <RetrievalDebug v-else embedded />

    <ConfirmDialog v-if="pendingAction" v-bind="confirmation" :busy="Boolean(busyAction)" @confirm="confirmMaintenance" @cancel="pendingAction = ''" />
  </main>
</template>
