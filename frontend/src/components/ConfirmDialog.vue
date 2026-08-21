<script setup>
import { ref } from 'vue'
import { useDialogFocus } from '../composables/useDialogFocus'

const props = defineProps({
  title: { type: String, required: true },
  message: { type: String, required: true },
  confirmText: { type: String, default: '确认' },
  busy: { type: Boolean, default: false },
  danger: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'cancel'])
const dialogRef = ref(null)
const titleId = `confirm-title-${Math.random().toString(36).slice(2)}`
const messageId = `confirm-message-${Math.random().toString(36).slice(2)}`

function cancel() {
  if (!props.busy) emit('cancel')
}

useDialogFocus(dialogRef, cancel)
</script>

<template>
  <Teleport to="body">
    <div class="confirm-dialog-backdrop" role="presentation" @click.self="cancel">
        <section ref="dialogRef" class="confirm-dialog" role="alertdialog" aria-modal="true" :aria-labelledby="titleId" :aria-describedby="messageId">
         <button type="button" class="confirm-dialog-close" aria-label="关闭确认弹窗" :disabled="busy" @click="cancel">×</button>
         <div :class="['confirm-dialog-icon', { danger }]" aria-hidden="true">
           <svg v-if="danger" viewBox="0 0 24 24"><path d="M12 3 2.8 19a1.6 1.6 0 0 0 1.4 2.4h15.6a1.6 1.6 0 0 0 1.4-2.4L12 3Z"/><path d="M12 9v4M12 17h.01"/></svg>
           <svg v-else viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M9.8 9a2.3 2.3 0 1 1 3.6 1.9c-.9.6-1.4 1-1.4 2.1M12 16.5h.01"/></svg>
         </div>
         <div class="confirm-dialog-copy">
           <span :class="['confirm-dialog-eyebrow', { danger }]">{{ danger ? '需要确认的操作' : '确认操作' }}</span>
           <h2 :id="titleId">{{ title }}</h2>
           <p :id="messageId">{{ message }}</p>
         </div>
        <div class="confirm-dialog-actions">
          <button type="button" class="confirm-cancel" :disabled="busy" @click="cancel">取消</button>
          <button type="button" :class="['confirm-submit', { danger }]" :disabled="busy" @click="$emit('confirm')">
            {{ busy ? '处理中...' : confirmText }}
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
