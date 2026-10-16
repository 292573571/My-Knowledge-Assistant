<script setup>
import DocumentPanel from './DocumentPanel.vue'

defineProps({
  workspace: { type: Object, default: null }
})

defineEmits(['manage-workspace'])
</script>

<template>
  <main class="knowledge-dashboard">
    <section class="knowledge-hero" data-reveal>
      <div class="knowledge-hero-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/><path d="M9 7h7"/><path d="M9 11h7"/></svg>
      </div>
      <div class="knowledge-hero-body">
        <h1>管理你的知识沉淀</h1>
        <p class="knowledge-hero-desc">{{ workspace?.type === 'PUBLIC' ? '维护平台公共知识源。这里发布的资料可供所有用户在个人和团队空间中检索使用。' : '统一查看当前空间的原始资料、正式笔记和 AI 学习记录，支持上传、筛选与精确删除。' }}</p>
      </div>
      <div class="knowledge-hero-aside">
        <span v-if="workspace?.name" class="knowledge-workspace-chip" :title="workspace.name">
          <i class="knowledge-chip-dot" aria-hidden="true" />{{ workspace.name }}
        </span>
        <button type="button" class="knowledge-manage-button" @click="$emit('manage-workspace')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06-2.12 2.12-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.03 1.55V20.3h-3v-.09a1.7 1.7 0 0 0-1.03-1.55 1.7 1.7 0 0 0-1.87.34l-.06.06-2.12-2.12.06-.06A1.7 1.7 0 0 0 7.04 15a1.7 1.7 0 0 0-1.55-1.03H5.4v-3h.09A1.7 1.7 0 0 0 7.04 9a1.7 1.7 0 0 0-.34-1.87l-.06-.06 2.12-2.12.06.06a1.7 1.7 0 0 0 1.87.34 1.7 1.7 0 0 0 1.03-1.55V3.7h3v.09a1.7 1.7 0 0 0 1.03 1.55 1.7 1.7 0 0 0 1.87-.34l.06-.06 2.12 2.12-.06.06A1.7 1.7 0 0 0 19.4 9a1.7 1.7 0 0 0 1.55 1.03h.09v3h-.09A1.7 1.7 0 0 0 19.4 15Z"/></svg>
          管理知识空间
        </button>
      </div>
    </section>
    <section class="knowledge-card" data-reveal>
      <DocumentPanel :workspace="workspace" />
    </section>
  </main>
</template>

<style scoped>
.knowledge-hero {
  justify-content: flex-start;
  gap: 16px;
  padding: 14px 4px 12px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.knowledge-hero-icon {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  color: var(--site-green, #6f9b78);
  background: var(--site-green-soft, #eaf3e7);
}

.knowledge-hero-icon svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.knowledge-hero-body {
  flex: 1 1 auto;
  min-width: 0;
}

.knowledge-hero-body h1 {
  margin: 0;
  font-size: 22px;
}

.knowledge-hero-desc {
  margin: 6px 0 0;
  color: var(--site-muted, #738097);
  font-size: 13px;
  line-height: 1.6;
}

.knowledge-hero-aside {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.knowledge-workspace-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 200px;
  padding: 5px 11px;
  border: 1px solid var(--site-line, #dce5f2);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.75);
  color: var(--site-ink-soft, #525f75);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.knowledge-chip-dot {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--site-green, #6f9b78);
}

@media (max-width: 900px) {
  .knowledge-hero {
    flex-wrap: wrap;
  }

  .knowledge-hero-aside {
    width: 100%;
    justify-content: flex-start;
  }

  .knowledge-workspace-chip {
    max-width: none;
  }
}
</style>
