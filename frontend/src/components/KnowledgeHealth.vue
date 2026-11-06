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
  const names = healthCleanupFileNames(plan)
  const more = (plan?.removed?.length || 0) > 6 ? ` 等 ${plan.removed.length} 项` : ''
  return {
    title: `去重：删除 ${plan?.removedCount || 0} 个重复副本？`,
    message: `每组重复内容只保留最近入库的一份（重建索引后的新解析产物会胜出），将删除：${names}${more}。删除后无法恢复，请确认这些副本不再被各自空间引用。`,
    confirmText: '删除重复副本',
    danger: true
  }
})

onMounted(loadDocumentHealth)

async function loadDocumentHealth() {
  healthLoading.value = true
  healthError.value = ''
  try {
    health.value = await fetchDocumentHealth()
    healthRefreshedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (exception) {
    healthError.value = formatApiError(exception, '知识库健康报告加载失败。')
  } finally {
    healthLoading.value = false
  }
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
  healthLoading.value = true
  try {
    const result = await cleanupDocumentHealth(scope, false)
    const label = scope === 'orphan' ? '孤儿索引' : '重复文档'
    notifySuccess(`${label}清理完成：已删除 ${result?.removedCount ?? 0} 项。`)
    await loadDocumentHealth()
  } catch (exception) {
    notifyError(`清理失败：${formatApiError(exception)}`)
  } finally {
    healthCleanupBusy.value = ''
    healthLoading.value = false
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
      <button type="button" class="secondary" :disabled="healthLoading" @click="loadDocumentHealth">{{ healthLoading ? '盘点中...' : '重新盘点' }}</button>
    </header>
    <p v-if="healthError" class="maintenance-message">{{ healthError }}</p>
    <template v-if="health">
      <div class="maintenance-health-stats">
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
            <small>{{ group.documents.map(doc => doc.fileName).join('、') }}</small>
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
    <ConfirmDialog v-if="confirmTarget" v-bind="confirmation" :busy="Boolean(healthCleanupBusy)" @confirm="runHealthCleanup(confirmTarget === 'health-cleanup-orphan' ? 'orphan' : 'duplicate')" @cancel="confirmTarget = ''; healthCleanupPlan = null" />
    <ToastContainer ref="toast" />
  </section>
</template>
