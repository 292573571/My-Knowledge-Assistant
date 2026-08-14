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

function cancel() {
  if (!props.busy) emit('cancel')
}

useDialogFocus(dialogRef, cancel)
</script>

<template>
  <Teleport to="body">
    <div class="confirm-dialog-backdrop" role="presentation" @click.self="cancel">
       <section ref="dialogRef" class="confirm-dialog" role="alertdialog" aria-modal="true" :aria-labelledby="`confirm-title-${title}`">
        <div :class="['confirm-dialog-icon', { danger }]">{{ danger ? '!' : '?' }}</div>
        <div class="confirm-dialog-copy">
          <h2 :id="`confirm-title-${title}`">{{ title }}</h2>
          <p>{{ message }}</p>
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
