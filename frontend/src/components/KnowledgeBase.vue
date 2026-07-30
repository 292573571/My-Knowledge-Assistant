<script setup>
import DocumentPanel from './DocumentPanel.vue'

defineProps({
  workspace: { type: Object, default: null }
})

defineEmits(['manage-workspace'])
</script>

<template>
  <main class="knowledge-dashboard">
    <section class="knowledge-hero">
      <div>
        <p class="records-badge">知识库管理</p>
        <h1>管理你的知识沉淀</h1>
        <p v-if="workspace?.type === 'PUBLIC'">维护平台公共知识源。这里发布的资料可供所有用户在个人和团队空间中检索使用。</p>
        <p v-else>统一查看当前空间的原始资料、正式笔记和 AI 学习记录，支持上传、筛选与精确删除。</p>
      </div>
      <button type="button" class="knowledge-manage-button" @click="$emit('manage-workspace')">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06-2.12 2.12-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.03 1.55V20.3h-3v-.09a1.7 1.7 0 0 0-1.03-1.55 1.7 1.7 0 0 0-1.87.34l-.06.06-2.12-2.12.06-.06A1.7 1.7 0 0 0 7.04 15a1.7 1.7 0 0 0-1.55-1.03H5.4v-3h.09A1.7 1.7 0 0 0 7.04 9a1.7 1.7 0 0 0-.34-1.87l-.06-.06 2.12-2.12.06.06a1.7 1.7 0 0 0 1.87.34 1.7 1.7 0 0 0 1.03-1.55V3.7h3v.09a1.7 1.7 0 0 0 1.03 1.55 1.7 1.7 0 0 0 1.87-.34l.06-.06 2.12 2.12-.06.06A1.7 1.7 0 0 0 19.4 9a1.7 1.7 0 0 0 1.55 1.03h.09v3h-.09A1.7 1.7 0 0 0 19.4 15Z"/></svg>
        管理知识空间
      </button>
    </section>
    <section class="knowledge-card">
      <DocumentPanel :workspace="workspace" />
    </section>
  </main>
</template>
