<script setup>
import { computed, ref } from 'vue'
import LoadingDots from './LoadingDots.vue'
import { renderMarkdown } from '../utils/markdown'
import { deduplicateDisplayedSources, groupSourcesByFile } from '../utils/sources'
import { createWikiPage } from '../api/wikiPageApi'
import { submitMessageFeedback } from '../api/chatFeedbackApi'
import { formatApiError } from '../api/apiError'

const props = defineProps({
  message: {
    type: Object,
    required: true
  },
  streaming: {
    type: Boolean,
    default: false
  },
  workspaceId: {
    type: String,
    default: ''
  },
  conversationId: {
    type: String,
    default: ''
  },
  question: {
    type: String,
    default: ''
  }
})

defineEmits(['retry'])

const expandedUserMessage = ref(false)
const userMessageLimit = 180

const displayContent = computed(() => (props.message.content || '')
  .replace(/^\s*以上回答基于通用大模型知识，不是当前知识库内容。\s*$/gm, '')
  .trim())
const isLongUserMessage = computed(() => props.message.role === 'user'
  && (props.message.content || '').length > userMessageLimit)
const userMessageContent = computed(() => {
  const content = props.message.content || ''
  if (!isLongUserMessage.value || expandedUserMessage.value) return content
  return `${content.slice(0, userMessageLimit)}…`
})
const userMessageLineCount = computed(() => (props.message.content || '').split(/\r?\n/).length)
const html = computed(() => renderMarkdown(displayContent.value))
const roleLabel = computed(() => (props.message.role === 'user' ? '你' : '助手'))
const isAssistant = computed(() => props.message.role === 'assistant')
const displayedSources = computed(() => groupSourcesByFile(props.message.sources))
const noteSaveable = computed(() => isAssistant.value && !props.streaming && !props.message.error
  && !!props.message.content && !!props.workspaceId)

// ============ 保存为知识笔记 ============
const noteState = ref('idle') // idle | saving | done | error
const noteError = ref('')
const noteTitle = computed(() => {
  const base = (props.question || displayContent.value.replace(/[#>*`\-\n]/g, ' ').trim()).slice(0, 60)
  return base || 'AI 回答笔记'
})
const noteContent = computed(() => {
  const parts = []
  if (props.question) parts.push(`> 提问：${props.question}\n`)
  parts.push(displayContent.value)
  const sources = deduplicateDisplayedSources(props.message.sources || [])
  if (sources.length) {
    const lines = sources.map(source => {
      const file = source.fileName || source.file || source.title || source.name || '未知文件'
      const page = source.pageNumber ?? source.page
      return `- ${file}${page != null && page !== '' ? `（第 ${page} 页）` : ''}`
    })
    parts.push(`\n## 来源\n${lines.join('\n')}`)
  }
  return parts.join('\n\n')
})

async function saveAsNote() {
  if (noteState.value === 'saving' || noteState.value === 'done') return
  noteState.value = 'saving'
  noteError.value = ''
  try {
    await createWikiPage(props.workspaceId, {
      title: noteTitle.value,
      slug: '',
      parentId: '',
      contentMd: noteContent.value
    })
    noteState.value = 'done'
  } catch (exception) {
    noteState.value = 'error'
    noteError.value = formatApiError(exception, '保存知识笔记失败。')
  }
}
const isModelSupplement = computed(() => isAssistant.value && !props.message.error && !props.message.sources?.length
  && props.message.content?.includes('以上回答基于通用大模型知识'))

// ---- 反馈（👍/👎）：仅对已落库的助手消息开放 ----
const feedbackRating = ref(0)
const feedbackState = ref('idle') // idle | sending | done | error
const feedbackError = ref('')
const reasonOpen = ref(false)
const reasonDraft = ref('')
const feedbackTargetId = computed(() => props.message.id ?? props.message.learning?.dbMessageId ?? null)
const feedbackEligible = computed(() => isAssistant.value && !props.streaming && !props.message.error
  && !!props.message.content && !!props.workspaceId && !!props.conversationId && feedbackTargetId.value != null)

async function sendFeedback(rating, reason = '') {
  if (feedbackState.value === 'sending' || feedbackState.value === 'done') return
  feedbackState.value = 'sending'
  feedbackError.value = ''
  try {
    await submitMessageFeedback(feedbackTargetId.value, props.conversationId, rating, reason)
    feedbackRating.value = rating
    feedbackState.value = 'done'
    reasonOpen.value = false
  } catch (exception) {
    feedbackState.value = 'idle'
    feedbackError.value = formatApiError(exception, '反馈提交失败，请稍后重试。')
  }
}

function onThumbUp() {
  void sendFeedback(1)
}

function onThumbDown() {
  if (feedbackRating.value === -1) return
  reasonOpen.value = !reasonOpen.value
  if (!reasonOpen.value) return
  // 先记下用户点了踩，理由可等提交；避免展开输入框期间重复触发
  feedbackRating.value = -1
  feedbackState.value = 'idle'
}

async function submitReason() {
  await sendFeedback(-1, reasonDraft.value)
}
function sourceLabel(group) {
  const file = group.fileName || group.file || group.title || group.name || '来源'
  const pages = group.pages?.length ? ` · 第 ${group.pages.join('、')} 页` : ''

  return `${file}${pages}`
}

function sourceKey(group) {
  return group.key || [group.fileName, group.file, group.title, group.name].filter(Boolean).join(':')
}
const timeLabel = computed(() => {
  if (!props.message.createdAt) return ''

  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(props.message.createdAt))
})

async function copyCode(event) {
  const button = event.target.closest('.code-copy-button')
  if (!button) return
  const code = button.closest('.code-block')?.querySelector('code')?.textContent || ''
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    button.textContent = '已复制'
    window.setTimeout(() => {
      if (button.isConnected) button.textContent = '复制'
    }, 1500)
  } catch {
    button.textContent = '复制失败'
  }
}
</script>

<template>
  <article v-if="message.role === 'user' || (message.role === 'assistant' && (message.streaming || message.content || message.error))" class="message" :class="`message-${message.role}`">
    <div class="avatar">{{ roleLabel }}</div>
    <div class="message-body" :class="{ 'error-only': isAssistant && message.error && !message.content }">
      <div class="message-meta">
        <strong>{{ roleLabel }}</strong>
        <time v-if="timeLabel" :datetime="message.createdAt">{{ timeLabel }}</time>
      </div>
      <LoadingDots v-if="streaming && !message.content && !message.retrieving" />
      <div v-if="streaming && !message.content && message.retrieving" style="color: var(--site-muted, #718074); font-size: 13px; padding: 4px 0;">正在检索知识库，请稍候…</div>
       <div v-if="message.content && message.role === 'user'" class="message-user-content">
         <span>{{ userMessageContent }}</span>
         <button v-if="isLongUserMessage" type="button" class="message-expand"
                 @click="expandedUserMessage = !expandedUserMessage">
           {{ expandedUserMessage ? '收起原文' : `展开原文 · ${message.content.length} 字 / ${userMessageLineCount} 行` }}
         </button>
       </div>
       <div v-else-if="message.content" class="markdown-body" @click="copyCode" v-html="html"></div>
      <div v-if="isAssistant && message.error" class="message-alert error">
        <strong>模型或后端调用失败</strong>
        <span>{{ message.error }}</span>
        <button v-if="message.streaming === false" type="button" class="message-retry" @click="$emit('retry')">重新生成</button>
      </div>
      <div v-else-if="isAssistant && message.noRagMatch" class="message-alert warning">
        <strong>RAG 未命中</strong>
        <span>当前知识库没有找到足够依据，已尝试使用通用大模型知识补充回答。</span>
      </div>
      <div v-if="!streaming && isModelSupplement" class="model-supplement-label"><strong>模型补充</strong><span>不含本地资料依据，请对关键事实进行核实。</span></div>
      <div v-if="isAssistant && displayedSources.length" class="source-chips">
        <span v-for="group in displayedSources" :key="sourceKey(group)">
          {{ sourceLabel(group) }}
        </span>
      </div>
      <div v-if="noteSaveable" class="message-actions">
        <button
          type="button"
          class="message-action"
          :class="{ done: noteState === 'done' }"
          :disabled="noteState === 'saving' || noteState === 'done'"
          @click="saveAsNote"
        >
          {{ noteState === 'saving' ? '保存中…' : noteState === 'done' ? '已存入知识笔记 ✓' : '保存为知识笔记' }}
        </button>
        <span v-if="noteState === 'error'" class="message-action-error">{{ noteError }}</span>
      </div>
      <div v-if="feedbackEligible" class="message-feedback">
        <template v-if="feedbackState !== 'done'">
          <button type="button" class="feedback-button" title="回答有帮助" :disabled="feedbackState === 'sending'" @click="onThumbUp">👍</button>
          <button type="button" class="feedback-button" title="回答有问题" :class="{ active: reasonOpen }" :disabled="feedbackState === 'sending'" @click="onThumbDown">👎</button>
          <span v-if="feedbackError" class="message-action-error">{{ feedbackError }}</span>
        </template>
        <span v-else class="feedback-done">已收到反馈，谢谢！{{ feedbackRating === 1 ? '👍' : '👎' }}</span>
        <div v-if="reasonOpen && feedbackState !== 'done'" class="feedback-reason">
          <textarea v-model="reasonDraft" rows="2" maxlength="500" placeholder="问题出在哪？（可选）" aria-label="反馈原因"></textarea>
          <button type="button" class="message-action" :disabled="feedbackState === 'sending'" @click="submitReason">
            {{ feedbackState === 'sending' ? '提交中…' : '提交反馈' }}
          </button>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.message-feedback {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.feedback-button {
  border: 1px solid transparent;
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 14px;
  background: transparent;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.15s ease, border-color 0.15s ease;
}

.feedback-button:hover,
.feedback-button.active {
  opacity: 1;
  border-color: var(--site-border, #d8ded4);
}

.feedback-button:disabled {
  cursor: wait;
  opacity: 0.4;
}

.feedback-done {
  color: var(--site-muted, #718074);
  font-size: 12px;
}

.feedback-reason {
  display: flex;
  flex-basis: 100%;
  flex-direction: column;
  gap: 6px;
  margin-top: 4px;
}

.feedback-reason textarea {
  width: 100%;
  resize: vertical;
  border: 1px solid var(--site-border, #d8ded4);
  border-radius: 8px;
  padding: 8px 10px;
  font: inherit;
  font-size: 13px;
}

.message-alert.error {
  display: grid;
  gap: 4px;
}

.message-body.error-only {
  max-width: min(520px, 88%);
  border: 0;
  padding: 0;
  background: transparent;
  box-shadow: none;
}

.message-retry {
  justify-self: start;
  margin-top: 6px;
  padding: 5px 12px;
  border: 1px solid currentColor;
  border-radius: 8px;
  color: inherit;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}

.message-retry:hover {
  background: rgb(255 255 255 / 45%);
}

.message-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}

.message-action {
  padding: 5px 12px;
  border: 1px solid var(--site-line, #d8e2d8);
  border-radius: 8px;
  color: var(--site-green, #2f6b49);
  background: var(--site-green-soft, #eaf3e7);
  cursor: pointer;
  font-size: 12px;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.message-action:hover:not(:disabled) {
  background: var(--site-green, #2f6b49);
  color: #fff;
  border-color: var(--site-green, #2f6b49);
}

.message-action:disabled {
  cursor: default;
  opacity: 0.85;
}

.message-action.done {
  color: var(--site-green, #2f6b49);
  background: transparent;
}

.message-action-error {
  color: #b4483c;
  font-size: 12px;
}
</style>
