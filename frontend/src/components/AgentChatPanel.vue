<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import { formatApiError } from '../api/apiError'

const props = defineProps({
  kicker: { type: String, default: 'AI AGENT' },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  badge: { type: String, default: '只读' },
  icon: { type: String, default: 'bot' },
  suggestions: { type: Array, default: () => [] },
  placeholder: { type: String, default: '描述你的问题…' },
  sendLabel: { type: String, default: '发送' },
  loadingLabel: { type: String, default: '思考中…' },
  emptyTitle: { type: String, default: '有什么可以帮你？' },
  emptyHint: { type: String, default: '试试下面的常见问题，或直接描述你的问题。' },
  send: { type: Function, required: true },
  confirm: { type: Function, default: null },
  confirmHint: { type: Function, default: null }
})

const uid = Math.random().toString(36).slice(2, 8)
const titleId = `agent-title-${uid}`

const question = ref('')
const answer = ref(null)
const error = ref('')
const loading = ref(false)
const confirming = ref(false)
const showTrace = ref(false)
const threadEl = ref(null)

const answerHtml = computed(() => renderMarkdown(answer.value?.answer || ''))
const hasThread = computed(() => Boolean(question.value || answer.value || loading.value))
const traces = computed(() => answer.value?.toolCalls || answer.value?.traces || [])
const pending = computed(() => answer.value?.pendingAction?.confirmationToken
  ? answer.value.pendingAction : null)

watch([loading, answer, () => error.value], async () => {
  await nextTick()
  if (threadEl.value) threadEl.value.scrollTop = threadEl.value.scrollHeight
})

async function ask(message = question.value) {
  const normalized = (message || '').trim()
  if (!normalized || loading.value) return
  question.value = normalized
  error.value = ''
  answer.value = null
  showTrace.value = false
  loading.value = true
  try {
    answer.value = await props.send(normalized)
  } catch (exception) {
    error.value = formatApiError(exception, '助手暂时无法回答。')
  } finally {
    loading.value = false
  }
}

function handleSubmit() {
  ask()
}

async function confirmAction() {
  if (!props.confirm || !pending.value || confirming.value) return
  confirming.value = true
  error.value = ''
  try {
    const result = await props.confirm(pending.value.confirmationToken)
    answer.value = { ...answer.value, answer: result.answer, pendingAction: null, readOnly: false }
  } catch (exception) {
    error.value = formatApiError(exception, '操作执行失败。')
  } finally {
    confirming.value = false
  }
}

function hint(action) {
  return props.confirmHint ? props.confirmHint(action) : '请确认后才会执行。'
}

function reset() {
  question.value = ''
  answer.value = null
  error.value = ''
  showTrace.value = false
}
</script>

<template>
  <section class="agent-chat" :aria-labelledby="titleId">
    <header class="agent-chat-header">
      <div class="agent-chat-identity">
        <span class="agent-chat-mark" aria-hidden="true">
          <svg v-if="icon === 'help'" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.25-.9.75-.9 1.4v.3"/><path d="M12 17h.01"/></svg>
          <svg v-else-if="icon === 'wrench'" viewBox="0 0 24 24"><path d="M14.7 6.3a4 4 0 0 0-5 5L4 17l3 3 5.7-5.7a4 4 0 0 0 5-5l-2.2 2.2-3-3 2.2-2.2Z"/></svg>
          <svg v-else viewBox="0 0 24 24"><path d="M12 3.5a8.5 8.5 0 0 0-8.5 8.5c0 1.8.56 3.47 1.52 4.84L4 20.5l4.05-1.02A8.5 8.5 0 1 0 12 3.5Z"/><path d="M8 12h.01M12 12h.01M16 12h.01"/></svg>
        </span>
        <div>
          <p class="agent-chat-kicker">{{ kicker }}</p>
          <h2 :id="titleId">{{ title }}</h2>
          <p v-if="description">{{ description }}</p>
        </div>
      </div>
      <span class="agent-chat-badge">{{ badge }}</span>
    </header>

    <div ref="threadEl" class="agent-chat-thread">
      <div v-if="!hasThread" class="agent-chat-empty">
        <strong>{{ emptyTitle }}</strong>
        <span>{{ emptyHint }}</span>
      </div>

      <div v-if="question" class="agent-msg agent-msg-user">
        <div class="agent-avatar">你</div>
        <div class="agent-bubble"><p>{{ question }}</p></div>
      </div>

      <div v-if="loading" class="agent-msg agent-msg-bot">
        <div class="agent-avatar">AI</div>
        <div class="agent-bubble">
          <span class="agent-typing" aria-label="正在生成回答"><i></i><i></i><i></i></span>
        </div>
      </div>

      <div v-if="answer && !loading" class="agent-msg agent-msg-bot">
        <div class="agent-avatar">AI</div>
        <div class="agent-bubble">
          <div class="agent-bubble-meta">
            <span>回答</span>
            <small>{{ answer.readOnly ? '只读' : '请核对执行状态' }} · {{ answer.steps }} 步</small>
          </div>
          <div class="markdown-body" v-html="answerHtml"></div>

          <div v-if="pending" class="maintenance-agent-confirmation">
            <strong>{{ hint(pending.action) }}</strong>
            <button type="button" :disabled="confirming" @click="confirmAction">
              {{ confirming ? '执行中…' : '确认执行' }}
            </button>
          </div>

          <div v-if="traces.length" class="maintenance-agent-trace">
            <button type="button" @click="showTrace = !showTrace">
              {{ showTrace ? '收起调用记录' : `查看调用记录（${traces.length}）` }}
            </button>
            <ul v-if="showTrace">
              <li v-for="trace in traces" :key="`${trace.step}-${trace.toolName}`">
                <strong>{{ trace.toolName }}</strong>
                <span :class="trace.status.toLowerCase()">{{ trace.status }}</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>

    <div v-if="suggestions.length" class="agent-chat-suggestions" aria-label="常见问题">
      <button v-for="suggestion in suggestions" :key="suggestion" type="button"
              :disabled="loading" @click="ask(suggestion)">{{ suggestion }}</button>
    </div>

    <form class="agent-chat-composer" @submit.prevent="handleSubmit">
      <textarea v-model="question" rows="2" :disabled="loading"
                :placeholder="placeholder" :aria-label="`向${title}提问`"></textarea>
      <button type="submit" :disabled="loading || !question.trim()">
        {{ loading ? loadingLabel : sendLabel }}
      </button>
    </form>

    <p v-if="error" class="agent-chat-error" role="alert">{{ error }}</p>
    <div v-if="hasThread" class="agent-chat-footer">
      <button type="button" :disabled="loading" @click="reset">清空对话</button>
    </div>
  </section>
</template>
