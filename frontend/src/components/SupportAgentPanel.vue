<script setup>
import FloatingAgentChat from './FloatingAgentChat.vue'
import { chatWithSupportAgent } from '../api/supportAgentApi'

const props = defineProps({
  workspace: { type: Object, default: null }
})

const send = (message) => chatWithSupportAgent(message, props.workspace?.id)
</script>

<template>
  <FloatingAgentChat
    title="智能客服"
    welcome="识海智能机器人为您服务"
    send-label="提问"
    loading-label="查询中…"
    placeholder="例如：上传文档后一直没索引成功，可能是什么原因？"
    :send="send"
  />
</template>
