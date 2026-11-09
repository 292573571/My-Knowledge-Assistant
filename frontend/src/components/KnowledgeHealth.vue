<script setup>
import { computed, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { cleanupDocumentHealth, fetchDocumentHealth } from '../api/documentApi'
import ConfirmDialog from './ConfirmDialog.vue'
import ToastContainer from './ToastContainer.vue'

const toast = ref(null)
function notifyError(msg) { toast.value?.error(msg) }
function notifySuccess(msg) { toast.value?.success(msg) }

const health = ref(null)
const healthLoading = ref(false)
const healthError = ref('')
const healthRefreshedAt = ref('')
const healthElapsed = ref(0)
const healthCleanupPlan = ref(null)
const healthCleanupBusy = ref('')
const confirmTarget = ref('')

const healthIssueCount = computed(() => {
  if (!health.value) return 0
  return (health.value.orphanIndexes?.length || 0) + (health.value.missingSources?.length || 0)
    + (health.value.duplicates?.length || 0)
})

const confirmation = computed(() => {
  if (confirmTarget.value === 'health-cleanup-orphan') {
    const plan = healthCleanupPlan.value
    const names = healthCleanupFileNames(plan)
    const more = (plan?.removed?.length || 0) > 6 ? ` 等 ${plan.removed.length} 项` : ''
    return {
      title: `清理 ${plan?.removedCount || 0} 个孤儿索引？`,
      message: `将删除：${names}${more}。这些文档的索引和向量残留会一并清除，此操作不可恢复。`,
      confirmText: '清理孤儿索引',
      danger: true
    }
  }
  const plan = healthCleanupPlan.value
  return {
    title: `去重：删除 ${plan?.removedCount || 0} 个重复副本？`,
    message: '每组重复内容只保留一份：公开副本优先（公开检索与评测的唯一入口），同级再取最近入库。其余副本连同索引与向量一并删除，不可恢复，请逐条核对下面的保留项与删除项。',
    confirmText: '删除重复副本',
    danger: true
  }
})

// 公开副本被删、却保留工作空间副本时，后果是「公开检索/评测整体看不到这份文档」，
// 必须在确认前置顶告警——这是去重最容易踩的坑。
const duplicateVisibilityRisk = computed(() => {
  const plan = healthCleanupPlan.value
  if (confirmTarget.value !== 'health-cleanup-duplicate' || !plan) return ''
  const removedPublic = (plan.removed || []).filter((item) => item.visibility === 'PUBLIC')
  if (!removedPublic.length) return ''
  const keptPublic = (plan.kept || []).some((item) => item.visibility === 'PUBLIC')
  if (keptPublic) return ''
  return `将删除 ${removedPublic.length} 份「公开」副本、保留非公开副本：公开检索、评测与其他用户都会看不到这份内容。`
})

const visibilityLabels = { PUBLIC: '公开', WORKSPACE: '工作空间', PRIVATE: '私有' }

function visibilityText(visibility) {
  return visibilityLabels[visibility] || visibility || '未知可见性'
}

function workspaceText(workspaceId) {
  if (!workspaceId) return '未知空间'
  if (workspaceId.startsWith('personal-')) return '个人空间'
  return `空间 ${workspaceId.slice(0, 8)}`
}

function formatIngestTime(ingestedAt) {
  if (!ingestedAt) return '入库时间未知'
  const date = new Date(ingestedAt)
  if (Number.isNaN(date.getTime())) return '入库时间未知'
  return date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

onMounted(() => loadDocumentHealth())

async function loadDocumentHealth(options = {}) {
  // 首屏自动盘点静默进行；只有用户点「重新盘点」才用 Toast 明确告知结果。
  const manual = options?.manual === true
  if (healthLoading.value) return
  healthLoading.value = true
  healthError.value = ''
  const startedAt = Date.now()
  try {
    health.value = await fetchDocumentHealth()
    healthRefreshedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    healthElapsed.value = Date.now() - startedAt
    if (manual) notifySuccess(healthSummaryMessage(health.value, healthElapsed.value))
  } catch (exception) {
    healthError.value = formatApiError(exception, '知识库健康报告加载失败。')
    if (manual) notifyError(`盘点失败：${healthError.value}`)
  } finally {
    healthLoading.value = false
  }
}

function refreshDocumentHealth() {
  return loadDocumentHealth({ manual: true })
}

// 盘点结果的一句话摘要：文档/分块规模 + 发现的问题，让用户点完按钮能立刻知道「成功了没、发现什么」。
function healthSummaryMessage(report, elapsedMs) {
  if (!report) return '盘点完成。'
  const headline = `盘点完成：${report.totalDocuments ?? 0} 个文档 / ${(report.totalChunks ?? 0).toLocaleString('zh-CN')} 个分块`
  const findings = []
  if (report.duplicates?.length) findings.push(`${report.duplicates.length} 组重复文档`)
  if (report.orphanIndexes?.length) findings.push(`${report.orphanIndexes.length} 个孤儿索引`)
  if (report.missingSources?.length) findings.push(`${report.missingSources.length} 个源文件丢失`)
  const tail = findings.length ? `，发现 ${findings.join('、')}` : '，未发现问题'
  const cost = elapsedMs >= 800 ? `（耗时 ${(elapsedMs / 1000).toFixed(1)} 秒）` : ''
  return `${headline}${tail}${cost}`
}

function healthStatusText(status) {
  const labels = { INDEXED: '已索引', PENDING: '待索引', FAILED: '失败' }
  return labels[status] || status
}

function categoryText(category) {
  const labels = { SOURCE: '原始资料', FORMAL_NOTE: '正式笔记', WIKI_PAGE: 'Wiki 页面', LEARNING_RECORD: '学习记录' }
  return labels[category] || category
}

function shortHash(hash) {
  return typeof hash === 'string' && hash.length > 12 ? hash.slice(0, 12) : hash
}

async function openHealthCleanupConfirm(scope) {
  if (healthCleanupBusy.value) return
  healthCleanupBusy.value = scope
  try {
    const plan = await cleanupDocumentHealth(scope, true)
    if (!plan || !plan.removed?.length) {
      notifyError('没有需要清理的项目。')
      return
    }
    healthCleanupPlan.value = plan
    confirmTarget.value = scope === 'orphan' ? 'health-cleanup-orphan' : 'health-cleanup-duplicate'
  } catch (exception) {
    notifyError(`清理预览失败：${formatApiError(exception)}`)
  } finally {
    healthCleanupBusy.value = ''
  }
}

function healthCleanupFileNames(plan, limit = 6) {
  return (plan?.removed || []).slice(0, limit).map((item) => item.fileName).join('、')
}

async function runHealthCleanup(scope) {
  healthCleanupBusy.value = scope
  try {
    const result = await cleanupDocumentHealth(scope, false)
    const label = scope === 'orphan' ? '孤儿索引' : '重复文档'
    notifySuccess(`${label}清理完成：已删除 ${result?.removedCount ?? 0} 项。`)
    await loadDocumentHealth()
  } catch (exception) {
    notifyError(`清理失败：${formatApiError(exception)}`)
  } finally {
    healthCleanupBusy.value = ''
    healthCleanupPlan.value = null
    confirmTarget.value = ''
  }
}
</script>

<template>
  <section class="maintenance-section maintenance-health-section">
    <header class="maintenance-section-heading">
      <div>
        <p class="maintenance-section-kicker">KNOWLEDGE HEALTH</p>
        <h2>知识库健康</h2>
        <span>全系统盘点：重复导入、索引空转与源文件丢失。<template v-if="healthRefreshedAt">（盘点于 {{ healthRefreshedAt }}）</template></span>
      </div>
      <button type="button" class="secondary" :disabled="healthLoading" @click="refreshDocumentHealth">
        <span v-if="healthLoading" class="maintenance-health-spinner" aria-hidden="true"></span>
        {{ healthLoading ? '盘点中…' : '重新盘点' }}
      </button>
    </header>
    <p v-if="healthLoading" class="maintenance-message scanning" role="status">
      正在盘点知识库，扫描索引、分块与源文件完整性，请稍候…<small v-if="healthRefreshedAt">当前展示的是 {{ healthRefreshedAt }} 的盘点结果</small>
    </p>
    <p v-if="healthError" class="maintenance-message error">{{ healthError }}</p>
    <template v-if="health">
      <div class="maintenance-health-stats" :class="{ stale: healthLoading }">
        <article class="maintenance-health-stat">
          <strong>{{ health.totalDocuments }}</strong>
          <span>文档总数</span>
        </article>
        <article class="maintenance-health-stat">
          <strong>{{ health.totalChunks.toLocaleString('zh-CN') }}</strong>
          <span>分块总数</span>
        </article>
        <article class="maintenance-health-stat" :class="{ warn: healthIssueCount > 0 }">
          <strong>{{ healthIssueCount }}</strong>
          <span>待处理问题</span>
        </article>
      </div>
      <div class="maintenance-health-tags">
        <span v-for="(count, status) in health.byStatus" :key="`s-${status}`" class="maintenance-health-tag">{{ healthStatusText(status) }} {{ count }}</span>
        <span v-for="(count, category) in health.byCategory" :key="`c-${category}`" class="maintenance-health-tag neutral">{{ categoryText(category) }} {{ count }}</span>
      </div>
      <div v-if="health.duplicates?.length" class="maintenance-health-group">
        <h3>重复文档（同内容存在于多个空间）<button type="button" class="secondary maintenance-health-cleanup" :disabled="healthLoading || Boolean(healthCleanupBusy)" @click="openHealthCleanupConfirm('duplicate')">{{ healthCleanupBusy === 'duplicate' ? '预览中...' : '一键去重' }}</button></h3>
        <ul>
          <li v-for="group in health.duplicates" :key="group.contentHash">
            <code>{{ shortHash(group.contentHash) }}</code>
            <span>× {{ group.count }}</span>
            <small>{{ group.documents.map(doc => `${doc.fileName}（${visibilityText(doc.visibility)}）`).join('、') }}</small>
          </li>
        </ul>
      </div>
      <div v-if="health.orphanIndexes?.length" class="maintenance-health-group">
        <h3>孤儿索引（有索引但分块缺失，检索会空转）<button type="button" class="secondary maintenance-health-cleanup" :disabled="healthLoading || Boolean(healthCleanupBusy)" @click="openHealthCleanupConfirm('orphan')">{{ healthCleanupBusy === 'orphan' ? '预览中...' : '一键清理' }}</button></h3>
        <ul>
          <li v-for="issue in health.orphanIndexes" :key="issue.documentId">
            <span>{{ issue.fileName }}</span>
            <small>{{ issue.detail }}</small>
          </li>
        </ul>
      </div>
      <div v-if="health.missingSources?.length" class="maintenance-health-group">
        <h3>源文件丢失（无法重建索引）</h3>
        <ul>
          <li v-for="issue in health.missingSources" :key="`${issue.documentId}-${issue.detail}`">
            <span>{{ issue.fileName }}</span>
            <small>{{ issue.detail }}</small>
          </li>
        </ul>
      </div>
      <p v-if="!healthIssueCount" class="maintenance-message ok">未发现问题：无重复文档、无孤儿索引、源文件齐全。</p>
    </template>
    <ConfirmDialog v-if="confirmTarget" v-bind="confirmation" :busy="Boolean(healthCleanupBusy)" @confirm="runHealthCleanup(confirmTarget === 'health-cleanup-orphan' ? 'orphan' : 'duplicate')" @cancel="confirmTarget = ''; healthCleanupPlan = null">
      <template v-if="confirmTarget === 'health-cleanup-duplicate' && healthCleanupPlan">
        <p v-if="duplicateVisibilityRisk" class="health-cleanup-risk">{{ duplicateVisibilityRisk }}</p>
        <div class="health-cleanup-block keep">
          <p class="health-cleanup-block-title">保留 {{ healthCleanupPlan.kept?.length || 0 }} 份（公开优先，其次最近入库）</p>
          <ul>
            <li v-for="ref in healthCleanupPlan.kept" :key="`keep-${ref.documentId}`">
              <span class="health-cleanup-badge keep">保留</span>
              <span class="health-cleanup-name">{{ ref.fileName }}</span>
              <small>{{ visibilityText(ref.visibility) }} · {{ workspaceText(ref.workspaceId) }} · {{ formatIngestTime(ref.ingestedAt) }} 入库 · {{ ref.chunkCount }} 块</small>
            </li>
          </ul>
        </div>
        <div class="health-cleanup-block remove">
          <p class="health-cleanup-block-title">删除 {{ healthCleanupPlan.removed?.length || 0 }} 份</p>
          <ul>
            <li v-for="ref in healthCleanupPlan.removed" :key="`drop-${ref.documentId}`">
              <span class="health-cleanup-badge remove">删除</span>
              <span class="health-cleanup-name">{{ ref.fileName }}</span>
              <small>{{ visibilityText(ref.visibility) }} · {{ workspaceText(ref.workspaceId) }} · {{ formatIngestTime(ref.ingestedAt) }} 入库 · {{ ref.chunkCount }} 块</small>
            </li>
          </ul>
        </div>
      </template>
      <template v-else-if="confirmTarget === 'health-cleanup-orphan' && healthCleanupPlan">
        <ul class="health-cleanup-plain">
          <li v-for="ref in healthCleanupPlan.removed" :key="`orphan-${ref.documentId}`">
            <span class="health-cleanup-name">{{ ref.fileName }}</span>
            <small>{{ visibilityText(ref.visibility) }} · {{ workspaceText(ref.workspaceId) }}</small>
          </li>
        </ul>
      </template>
    </ConfirmDialog>
    <ToastContainer ref="toast" />
  </section>
</template>
