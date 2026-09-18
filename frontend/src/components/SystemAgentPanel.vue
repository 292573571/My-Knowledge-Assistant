<script setup>
import { computed } from 'vue'
import AgentChatPanel from './AgentChatPanel.vue'
import { chatWithSystemAgent, confirmSystemAgentAction } from '../api/systemAgentApi'

const props = defineProps({
  workspace: { type: Object, default: null },
  currentUser: { type: Object, default: null }
})

const isSuperAdmin = computed(() => props.currentUser?.systemRole === 'SUPER_ADMIN')

const suggestions = computed(() => {
  const items = [
    '系统现在整体运行状态怎么样？',
    '最近有哪些失败的文档处理任务？',
    '最近有没有异常日志或失败的审计事件？',
    '列出当前所有的知识空间',
    '现在有多少用户，角色分布如何？',
    '当前模型池里有哪些模型，默认模型是哪个？'
  ]
  if (isSuperAdmin.value) items.push('把模型 1 设为默认模型')
  return items
})

const send = (message) => chatWithSystemAgent(message, props.workspace?.id)
const confirm = (token) => confirmSystemAgentAction(token, props.workspace?.id)

function confirmationHint(action) {
  if (action === 'DELETE_DOCUMENT') return '这是不可撤销的删除操作。'
  if (action === 'REBUILD_ALL_INDEX') return '将为全部知识空间提交索引重建任务，可能耗时较长。'
  if (action === 'SET_DEFAULT_MODEL') return '将切换全系统默认模型，会影响所有未单独配置的用户。'
  if (action === 'SET_USER_ROLE') return '将调整该用户的系统角色，立即生效。'
  if (action === 'CLEAR_SYSTEM_LOGS') return '将清理全部普通运行日志，不影响审计日志。'
  return '请确认后才会执行。'
}
</script>

<template>
  <AgentChatPanel
    kicker="SYSTEM BUTLER"
    title="系统管家"
    description="用自然语言查询用户、模型、审计、日志、空间与评测；写操作需要确认后执行。"
    :badge="isSuperAdmin ? '管理员 · 可待确认写' : '管理员 · 只读'"
    icon="wrench"
    send-label="询问管家"
    loading-label="查询中…"
    placeholder="例如：最近有哪些失败任务和异常日志？"
    empty-title="想了解系统哪一块？"
    empty-hint="可以问运行状态、失败任务、异常日志、用户与角色、模型配置、空间和评测。"
    :suggestions="suggestions"
    :send="send"
    :confirm="confirm"
    :confirm-hint="confirmationHint"
  />
</template>
