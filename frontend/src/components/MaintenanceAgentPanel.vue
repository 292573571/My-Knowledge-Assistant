<script setup>
import { computed } from 'vue'
import FloatingAgentChat from './FloatingAgentChat.vue'
import { chatWithMaintenanceAgent, confirmMaintenanceAgentAction } from '../api/maintenanceAgentApi'

const props = defineProps({
  workspace: { type: Object, default: null },
  currentUser: { type: Object, default: null }
})

const isSuperAdmin = computed(() => props.currentUser?.systemRole === 'SUPER_ADMIN')
const workspaceName = computed(() => props.workspace?.name || '当前空间')

const suggestions = computed(() => {
  const items = [
    '检查当前空间的知识库状态',
    '有哪些失败的文档处理任务？',
    '当前空间有多少份文档和分块？'
  ]
  if (isSuperAdmin.value) items.push('重建所有向量索引')
  return items
})

const send = (message) => chatWithMaintenanceAgent(message, props.workspace?.id)
const confirm = (token) => confirmMaintenanceAgentAction(token, props.workspace?.id)

function confirmationHint(action) {
  if (action === 'DELETE_DOCUMENT') return '这是不可撤销的删除操作。'
  if (action === 'REBUILD_ALL_INDEX') return '将为全部知识空间提交索引重建任务，可能耗时较长。'
  return '请确认后才会执行。'
}
</script>

<template>
  <FloatingAgentChat
    kicker="KNOWLEDGE MAINTENANCE"
    title="知识库维护助手"
    :description="`检查「${workspaceName}」的索引、任务和批次状态。`"
    :badge="isSuperAdmin ? '只读问答 + 待确认操作' : '只读模式'"
    icon="wrench"
    send-label="询问助手"
    loading-label="检查中…"
    placeholder="例如：检查当前空间是否有失败任务，并告诉我原因和建议。"
    empty-title="想检查什么？"
    empty-hint="可以问索引状态、失败任务、任务批次和文档清单。"
    :suggestions="suggestions"
    :send="send"
    :confirm="confirm"
    :confirm-hint="confirmationHint"
  />
</template>
