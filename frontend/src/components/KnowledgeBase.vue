<script setup>
import DocumentPanel from './DocumentPanel.vue'
</script>

<template>
  <main class="knowledge-dashboard">
    <section class="knowledge-hero">
      <div>
        <p class="records-badge">知识库管理</p>
        <h1>管理你的知识沉淀</h1>
        <p>统一查看原始资料、正式笔记和 AI 学习记录，支持导入、重建索引与精确删除。</p>
      </div>
    </section>
    <section class="knowledge-card">
      <DocumentPanel />
    </section>
  </main>
</template>
