import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'

const focusableSelector = [
  'a[href]',
  'area[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  'iframe',
  'object',
  'embed',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

export function useDialogFocus(dialogRef, onEscape) {
  let previouslyFocused = null
  let active = false

  function getFocusableElements() {
    return [...(dialogRef.value?.querySelectorAll(focusableSelector) || [])]
      .filter(element => element.getClientRects().length > 0)
  }

  function focusInitialElement() {
    const dialog = dialogRef.value
    if (!dialog) return
    const target = dialog.querySelector('[autofocus]') || getFocusableElements()[0] || dialog
    if (target === dialog && !dialog.hasAttribute('tabindex')) dialog.setAttribute('tabindex', '-1')
    target.focus({ preventScroll: true })
  }

  function activate(dialog) {
    if (active) return
    active = true
    previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.addEventListener('keydown', handleKeydown)
    nextTick(() => {
      if (dialogRef.value === dialog) focusInitialElement()
    })
  }

  function deactivate() {
    if (!active) return
    active = false
    document.removeEventListener('keydown', handleKeydown)
    if (previouslyFocused?.isConnected && typeof previouslyFocused.focus === 'function') {
      previouslyFocused.focus({ preventScroll: true })
    }
    previouslyFocused = null
  }

  function handleKeydown(event) {
    if (!dialogRef.value) return
    if (event.target instanceof Node && !dialogRef.value.contains(event.target)) return
    const nestedDialog = event.target instanceof Element
      ? event.target.closest('[aria-modal="true"]')
      : null
    if (nestedDialog && nestedDialog !== dialogRef.value) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onEscape()
      return
    }
    if (event.key !== 'Tab') return

    const focusable = getFocusableElements()
    if (!focusable.length) {
      event.preventDefault()
      dialogRef.value.focus({ preventScroll: true })
      return
    }

    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  watch(dialogRef, (dialog) => {
    if (dialog) activate(dialog)
    else deactivate()
  })

  onMounted(() => {
    if (dialogRef.value) activate(dialogRef.value)
  })

  onBeforeUnmount(() => {
    deactivate()
  })
}
