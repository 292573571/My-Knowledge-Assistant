<script setup>
import FloatingAgentChat from './FloatingAgentChat.vue'
import { chatWithSupportAgent } from '../api/supportAgentApi'

const props = defineProps({
  workspace: { type: Object, default: null }
})

const suggestions = [
  '怎么上传文档到知识库？',
  '为什么我的文档一直处理失败？',
  '支持哪些文件格式？',
  '怎么新建知识空间？',
  '学习记录和正式笔记有什么区别？'
]

const send = (message) => chatWithSupportAgent(message, props.workspace?.id)
</script>

<template>
  <FloatingAgentChat
    kicker="SUPPORT AGENT"
    title="使用帮助"
    description="基于公共知识空间的帮助文档回答，只读。"
    badge="只读"
    icon="help"
    send-label="提问"
    loading-label="查询中…"
    placeholder="例如：上传文档后一直没索引成功，可能是什么原因？"
    empty-title="遇到问题？直接问我"
    empty-hint="我会检索帮助文档；如果是你自己的文档处理失败，我也会查看你的任务状态。"
    :suggestions="suggestions"
    :send="send"
  />
</template>
