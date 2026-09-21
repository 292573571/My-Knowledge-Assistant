<script setup>
import { computed } from 'vue'
import FloatingAgentChat from './FloatingAgentChat.vue'
import { chatWithSupportAgent } from '../api/supportAgentApi'
import { chatWithSystemAgent, confirmSystemAgentAction, fetchSystemAgentTaskProgress } from '../api/systemAgentApi'

const props = defineProps({
  workspace: { type: Object, default: null },
  currentUser: { type: Object, default: null }
})

const isAdmin = computed(() => ['ADMIN', 'SUPER_ADMIN'].includes(props.currentUser?.systemRole))
const title = computed(() => isAdmin.value ? '系统管家' : '智能客服')
const welcome = computed(() => isAdmin.value
  ? '识海系统管家为您服务，可查询系统状态；涉及写操作时需要确认后执行。'
  : '识海智能机器人为您服务')
const sendLabel = computed(() => isAdmin.value ? '询问管家' : '提问')
const loadingLabel = computed(() => isAdmin.value ? '查询中…' : '查询中…')
const placeholder = computed(() => isAdmin.value
  ? '输入问题，按回车发送；Shift + 回车换行'
  : '输入问题，按回车发送；Shift + 回车换行')
const suggestions = computed(() => isAdmin.value
  ? ['系统现在整体运行状态怎么样？', '最近有哪些失败的文档处理任务？', '最近有没有异常日志或失败的审计事件？']
  : [])

const send = (message) => isAdmin.value
  ? chatWithSystemAgent(message, props.workspace?.id)
  : chatWithSupportAgent(message, props.workspace?.id)
const confirm = async (token) => {
  if (!isAdmin.value) return null
  const result = await confirmSystemAgentAction(token, props.workspace?.id)
  if ((!result.tasks || !result.tasks.length) && result.taskId) {
    return { ...result, tasks: [{ taskId: result.taskId, workspaceId: props.workspace?.id }] }
  }
  return result
}
const taskProgress = (tasks) => isAdmin.value ? fetchSystemAgentTaskProgress(tasks) : null

function confirmHint(action) {
  if (action === 'REBUILD_ALL_INDEX') return '将为全部知识空间提交索引重建任务，可能耗时较长。'
  if (action === 'SET_DEFAULT_MODEL') return '将切换全系统默认模型，会影响所有未单独配置的用户。'
  if (action === 'SET_USER_ROLE') return '将调整该用户的系统角色，立即生效。'
  if (action === 'CLEAR_SYSTEM_LOGS') return '将清理全部普通运行日志，不影响审计日志。'
  if (action === 'DELETE_DOCUMENT') return '这是不可撤销的删除操作。'
  return '请确认后才会执行。'
}
</script>

<template>
  <FloatingAgentChat
    :title="title"
    :welcome="welcome"
    :suggestions="suggestions"
    :send-label="sendLabel"
    :loading-label="loadingLabel"
    :placeholder="placeholder"
    :confirm="isAdmin ? confirm : null"
    :confirm-hint="isAdmin ? confirmHint : null"
    :task-progress="isAdmin ? taskProgress : null"
    :send="send"
  />
</template>
