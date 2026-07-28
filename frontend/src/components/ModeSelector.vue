<script setup>
defineProps({
  modelValue: {
    type: String,
    default: 'chat'
  }
})

defineEmits(['update:modelValue'])

const modes = [
  { value: 'chat', label: '普通' },
  { value: 'rag', label: '知识库' },
  { value: 'file', label: '文件' },
  { value: 'search', label: '搜索' },
  { value: 'agent', label: 'Agent' }
]
</script>

<template>
  <div class="mode-selector" role="group" aria-label="回答模式">
    <button
      v-for="mode in modes"
      :key="mode.value"
      type="button"
      :class="{ active: mode.value === modelValue }"
      @click="$emit('update:modelValue', mode.value)"
    >
      {{ mode.label }}
    </button>
  </div>
</template>
