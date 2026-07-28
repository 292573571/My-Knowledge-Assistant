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
  }
})

defineEmits(['new', 'select', 'delete'])

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
  <aside class="sidebar">
    <nav class="conversation-list" aria-label="对话列表">
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
        <button type="button" class="conversation-delete" aria-label="删除会话" :disabled="deletingDisabled" @click.stop="$emit('delete', conversation.id)">
          ×
        </button>
      </div>
    </nav>

    <div class="sidebar-footer">
      <button class="new-chat" type="button" @click="$emit('new')"><span>＋</span> 新建对话</button>
    </div>
  </aside>
</template>
