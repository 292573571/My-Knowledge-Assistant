<script setup>
import { ref, onBeforeUnmount } from 'vue'

const toasts = ref([])
let id = 0

function show(message, type = 'error', duration = 5000) {
  const toastId = ++id
  toasts.value.push({ id: toastId, message, type })
  if (duration > 0) {
    setTimeout(() => dismiss(toastId), duration)
  }
}

function dismiss(toastId) {
  toasts.value = toasts.value.filter(t => t.id !== toastId)
}

defineExpose({
  error: (msg, duration) => show(msg, 'error', duration),
  success: (msg, duration) => show(msg, 'success', duration),
  warn: (msg, duration) => show(msg, 'warn', duration),
  dismiss
})
</script>

<template>
  <Teleport to="body">
    <div class="toast-container">
      <TransitionGroup name="toast">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          :class="['toast', `toast-${toast.type}`]"
          @click="dismiss(toast.id)"
        >
          <span class="toast-icon" v-if="toast.type === 'error'">✕</span>
          <span class="toast-icon" v-else-if="toast.type === 'success'">✓</span>
          <span class="toast-icon" v-else-if="toast.type === 'warn'">!</span>
          <span class="toast-text">{{ toast.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-container {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10000;
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: none;
  max-width: 90vw;
}
.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  pointer-events: auto;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(8px);
  min-width: 280px;
  max-width: 600px;
}
.toast-error {
  background: rgba(220, 38, 38, 0.95);
  color: #fff;
}
.toast-success {
  background: rgba(22, 163, 74, 0.95);
  color: #fff;
}
.toast-warn {
  background: rgba(217, 119, 6, 0.95);
  color: #fff;
}
.toast-icon {
  font-size: 18px;
  font-weight: bold;
  flex-shrink: 0;
}
.toast-text {
  flex: 1;
  line-height: 1.4;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateY(-20px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateX(100%);
}
</style>
