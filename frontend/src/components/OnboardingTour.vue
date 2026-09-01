<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['close'])

const STORAGE_KEY = 'shihai_tour_done_v1'
const steps = [
  { selector: '.home-hero-actions', title: '欢迎使用识海', body: '这里是你的主入口：「开始学习」进入 AI 学习助手，「先导入资料」去知识库。', placement: 'bottom' },
  { selector: '.home-system-intro', title: '认识你的知识库', body: '资料会经过解析、OCR、切分和索引，之后 AI 只基于你有权限访问的知识回答并展示来源。', placement: 'top' },
  { selector: '.home-entry-grid', title: '三个核心能力', body: 'AI 学习助手、知识库管理、学习记录——覆盖从导入到掌握的全过程。', placement: 'top' },
  { selector: '.home-checklist', title: '跟着清单做', body: '这 4 步做完，你就能玩转系统。勾选或点「去做」会自动记录进度。', placement: 'top' },
  { selector: '.home-concepts', title: '先搞懂三个概念', body: '知识空间、两类模型、检索诊断——理解它们后面就不会迷路。', placement: 'top' },
  { selector: '.home-faq', title: '遇到问题看这里', body: '新手最常问的几件事都在这儿，随时回来翻。', placement: 'top' }
]

const stepIndex = ref(0)
const popoverStyle = ref({})
let currentEl = null

function clearSpotlight() {
  if (currentEl) {
    currentEl.classList.remove('tour-spotlight')
    currentEl = null
  }
}
function updatePosition() {
  if (!currentEl) return
  const rect = currentEl.getBoundingClientRect()
  const step = steps[stepIndex.value]
  const gap = 14
  const pw = 320
  const top = step.placement === 'top' ? rect.top - gap : rect.bottom + gap
  let left = rect.left + rect.width / 2
  left = Math.min(Math.max(left, pw / 2 + 12), window.innerWidth - pw / 2 - 12)
  popoverStyle.value = {
    position: 'fixed',
    top: `${top}px`,
    left: `${left}px`,
    transform: step.placement === 'top' ? 'translate(-50%, -100%)' : 'translate(-50%, 0)',
    width: `${pw}px`
  }
}
function applyStep() {
  clearSpotlight()
  const step = steps[stepIndex.value]
  const el = document.querySelector(step.selector)
  if (!el) {
    if (stepIndex.value < steps.length - 1) {
      stepIndex.value += 1
      nextTick(applyStep)
    } else {
      finish()
    }
    return
  }
  currentEl = el
  el.classList.add('tour-spotlight')
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  updatePosition()
}
function next() {
  if (stepIndex.value < steps.length - 1) {
    stepIndex.value += 1
    nextTick(applyStep)
  } else {
    finish()
  }
}
function prev() {
  if (stepIndex.value > 0) {
    stepIndex.value -= 1
    nextTick(applyStep)
  }
}
function finish() {
  try { localStorage.setItem(STORAGE_KEY, '1') } catch { /* 忽略隐私模式写入失败 */ }
  clearSpotlight()
  emit('close')
}
function onScrollResize() {
  if (props.open) updatePosition()
}

watch(() => props.open, (v) => {
  if (v) {
    stepIndex.value = 0
    nextTick(applyStep)
  } else {
    clearSpotlight()
  }
})

onMounted(() => {
  window.addEventListener('scroll', onScrollResize, true)
  window.addEventListener('resize', onScrollResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScrollResize, true)
  window.removeEventListener('resize', onScrollResize)
  clearSpotlight()
})
</script>

<template>
  <div v-if="open" class="tour-root">
    <div class="tour-overlay"></div>
    <div class="tour-popover" :style="popoverStyle" role="dialog" aria-modal="true" aria-label="新手引导">
      <div class="tour-progress">{{ stepIndex + 1 }} / {{ steps.length }}</div>
      <strong class="tour-title">{{ steps[stepIndex].title }}</strong>
      <p class="tour-body">{{ steps[stepIndex].body }}</p>
      <div class="tour-actions">
        <button type="button" class="tour-skip" @click="finish">跳过</button>
        <div class="tour-nav">
          <button v-if="stepIndex > 0" type="button" class="tour-prev" @click="prev">上一步</button>
          <button type="button" class="tour-next" @click="next">{{ stepIndex === steps.length - 1 ? '完成' : '下一步' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<!-- 注意：.tour-spotlight 作用于首页其它组件的元素，必须使用全局样式（非 scoped）才能生效 -->
<style>
.tour-overlay {
  position: fixed;
  inset: 0;
  z-index: 999;
  background: rgba(18, 28, 22, .55);
}
.tour-spotlight {
  position: relative;
  z-index: 1000;
  border-radius: 12px;
  box-shadow: 0 0 0 4px #fff, 0 0 0 9999px rgba(18, 28, 22, .55);
  pointer-events: auto;
}
.tour-popover {
  position: fixed;
  z-index: 1001;
  background: #fffef9;
  border: 1px solid #e7eae3;
  border-radius: 14px;
  padding: 18px;
  box-shadow: 0 20px 50px rgba(20, 30, 25, .28);
}
.tour-progress {
  font-size: 11px;
  color: var(--site-muted, #718074);
  font-weight: 700;
  letter-spacing: .08em;
}
.tour-title {
  display: block;
  margin: 6px 0 8px;
  font-size: 16px;
  color: var(--site-ink, #17221b);
}
.tour-body {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--site-ink-soft, #314438);
}
.tour-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.tour-skip {
  border: 0;
  background: transparent;
  color: var(--site-muted, #718074);
  font-size: 12px;
  padding: 6px 4px;
}
.tour-skip:hover {
  color: var(--site-green, #1d5939);
}
.tour-nav {
  display: flex;
  gap: 8px;
}
.tour-prev {
  border: 1px solid #cbd4c5;
  background: #fff;
  color: var(--site-green, #1d5939);
  font-size: 12px;
  font-weight: 650;
  padding: 7px 12px;
  border-radius: 9px;
}
.tour-prev:hover {
  border-color: #7ba287;
  background: var(--site-green-soft, #e5eee1);
}
.tour-next {
  border: 1px solid var(--site-green, #1d5939);
  background: var(--site-green, #1d5939);
  color: #f8f7ef;
  font-size: 12px;
  font-weight: 650;
  padding: 7px 14px;
  border-radius: 9px;
}
.tour-next:hover {
  background: var(--site-green-dark, #16472d);
}
</style>
