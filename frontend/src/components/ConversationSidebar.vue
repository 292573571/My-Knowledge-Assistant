<script setup>
defineProps({
  conversations: {
    type: Array,
    default: () => []
  },
  activeId: {
    type: String,
    default: ''
  },
  deletingDisabled: {
    type: Boolean,
    default: false
  },
  mobileOpen: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['new', 'select', 'delete', 'close'])

function formatRelativeTime(value) {
  if (!value) return '刚刚'

  const minutes = Math.max(1, Math.round((Date.now() - new Date(value).getTime()) / 60000))
  if (minutes < 60) return `${minutes} 分钟前`

  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} 小时前`

  return `${Math.round(hours / 24)} 天前`
}

</script>

<template>
  <aside class="sidebar" :class="{ 'mobile-session-drawer': mobileOpen }">
    <header class="conversation-sidebar-header">
      <div>
        <span class="conversation-sidebar-kicker">你的学习空间</span>
        <h2>学习会话</h2>
      </div>
      <span class="conversation-count" :aria-label="`${conversations.length} 个会话`">{{ conversations.length }}</span>
      <button type="button" class="conversation-sidebar-close" aria-label="关闭会话列表" @click="emit('close')">×</button>
    </header>
    <nav class="conversation-list" aria-label="对话列表">
      <p v-if="!conversations.length" class="conversation-empty">还没有学习会话</p>
      <div
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation-item"
        :class="{ active: conversation.id === activeId }"
      >
          <button type="button" class="conversation-select" @click="$emit('select', conversation.id)">
            <strong>{{ conversation.title }}</strong>
            <small>{{ formatRelativeTime(conversation.updatedAt) }}</small>
          </button>
        <button type="button" class="conversation-delete" title="删除会话" aria-label="删除会话" :disabled="deletingDisabled" @click.stop="$emit('delete', conversation.id)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V5h6v2m-8 0 1 13h8l1-13M10 11v5m4-5v5" /></svg>
        </button>
      </div>
    </nav>

    <div class="sidebar-footer">
      <button class="new-chat" type="button" @click="$emit('new')"><span>＋</span> 新建对话</button>
    </div>
  </aside>
</template>
