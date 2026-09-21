<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import { formatApiError } from '../api/apiError'

const props = defineProps({
  kicker: { type: String, default: 'AI AGENT' },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  badge: { type: String, default: '只读' },
  icon: { type: String, default: 'bot' },
  suggestions: { type: Array, default: () => [] },
  welcome: { type: String, default: '' },
  placeholder: { type: String, default: '描述你的问题…' },
  sendLabel: { type: String, default: '发送' },
  loadingLabel: { type: String, default: '思考中…' },
  emptyTitle: { type: String, default: '有什么可以帮你？' },
  emptyHint: { type: String, default: '试试下面的常见问题，或直接描述你的问题。' },
  send: { type: Function, required: true },
  confirm: { type: Function, default: null },
  confirmHint: { type: Function, default: null },
  taskProgress: { type: Function, default: null },
  showHeader: { type: Boolean, default: true }
})


const uid = Math.random().toString(36).slice(2, 8)
const titleId = `agent-title-${uid}`

const draft = ref('')
const question = ref('')
const answer = ref(null)
const error = ref('')
const loading = ref(false)
const confirming = ref(false)
const showTrace = ref(false)
const threadEl = ref(null)
const elapsedSeconds = ref(0)
const progressStage = ref(0)
const trackedTaskRefs = ref([])
const trackedTasks = ref([])
const taskProgressError = ref('')
const taskPolling = ref(false)
let progressTimer = null
let taskPollTimer = null
let taskPollGeneration = 0
let taskPollFailures = 0

const answerHtml = computed(() => renderMarkdown(answer.value?.answer || ''))
const hasThread = computed(() => Boolean(question.value || answer.value || loading.value || props.welcome))
const traces = computed(() => answer.value?.toolCalls || answer.value?.traces || [])
const pending = computed(() => answer.value?.pendingAction?.confirmationToken
  ? answer.value.pendingAction : null)
const busy = computed(() => loading.value || confirming.value)
const progressTitle = computed(() => confirming.value ? '正在执行已确认操作' : props.loadingLabel)
const progressSteps = computed(() => confirming.value
  ? ['确认请求已发送', '正在校验权限并提交任务', '等待服务返回执行结果']
  : ['问题已发送', 'Agent 正在分析并调用工具', '等待服务返回回答'])
const backgroundProgress = computed(() => {
  const tasks = trackedTasks.value
  if (!tasks.length) return null
  const terminal = tasks.filter(task => ['SUCCEEDED', 'FAILED'].includes(task.status)).length
  const failed = tasks.filter(task => task.status === 'FAILED').length
  const running = tasks.filter(task => task.status === 'RUNNING').length
  const queued = tasks.length - terminal - running
  const progress = Math.round(tasks.reduce((total, task) => total
    + (['SUCCEEDED', 'FAILED'].includes(task.status) ? 100 : (task.progress || 0)), 0) / tasks.length)
  return { total: tasks.length, terminal, failed, running, queued, progress, done: terminal === tasks.length }
})

watch([loading, confirming, answer, () => error.value], async () => {
  await nextTick()
  if (threadEl.value) threadEl.value.scrollTop = threadEl.value.scrollHeight
})

function startProgress() {
  stopProgress()
  elapsedSeconds.value = 0
  progressStage.value = 1
  progressTimer = window.setInterval(() => {
    elapsedSeconds.value += 1
    if (elapsedSeconds.value >= 4) progressStage.value = 2
  }, 1000)
}

function stopProgress() {
  if (progressTimer) window.clearInterval(progressTimer)
  progressTimer = null
}

function stopTaskPolling() {
  taskPollGeneration += 1
  taskPollFailures = 0
  if (taskPollTimer) window.clearTimeout(taskPollTimer)
  taskPollTimer = null
  taskPolling.value = false
}

async function refreshTaskProgress() {
  if (!props.taskProgress || !trackedTaskRefs.value.length) {
    taskPolling.value = false
    return
  }
  const generation = taskPollGeneration
  taskPolling.value = true
  try {
    const tasks = await props.taskProgress(trackedTaskRefs.value)
    if (generation !== taskPollGeneration) return
    trackedTasks.value = tasks
    taskPollFailures = 0
    taskProgressError.value = ''
    if (trackedTasks.value.some(task => !['SUCCEEDED', 'FAILED'].includes(task.status))) {
      taskPollTimer = window.setTimeout(refreshTaskProgress, 2000)
    } else {
      taskPolling.value = false
    }
  } catch (exception) {
    if (generation !== taskPollGeneration) return
    taskProgressError.value = formatApiError(exception, '任务进度暂时无法获取。')
    taskPollFailures += 1
    if (exception?.retryable && taskPollFailures <= 3) {
      taskPollTimer = window.setTimeout(refreshTaskProgress, 5000 * taskPollFailures)
    } else {
      taskPolling.value = false
    }
  }
}

async function trackTasks(tasks) {
  stopTaskPolling()
  trackedTaskRefs.value = Array.isArray(tasks) ? tasks : []
  trackedTasks.value = []
  taskProgressError.value = ''
  if (trackedTaskRefs.value.length) await refreshTaskProgress()
}

onBeforeUnmount(() => {
  stopProgress()
  stopTaskPolling()
})

async function ask(message = question.value) {
  const normalized = (message || '').trim()
  if (!normalized || busy.value) return
  stopTaskPolling()
  trackedTaskRefs.value = []
  trackedTasks.value = []
  taskProgressError.value = ''
  question.value = normalized
  draft.value = ''
  error.value = ''
  answer.value = null
  showTrace.value = false
  loading.value = true
  startProgress()
  try {
    answer.value = await props.send(normalized)
  } catch (exception) {
    error.value = formatApiError(exception, '助手暂时无法回答。')
  } finally {
    loading.value = false
    stopProgress()
  }
}

function handleSubmit() {
  ask(draft.value)
}

function handleKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  handleSubmit()
}

async function confirmAction() {
  if (!props.confirm || !pending.value || confirming.value) return
  confirming.value = true
  error.value = ''
  startProgress()
  try {
    const result = await props.confirm(pending.value.confirmationToken)
    answer.value = { ...answer.value, answer: result.answer, pendingAction: null, readOnly: false }
    trackTasks(result.tasks)
  } catch (exception) {
    error.value = formatApiError(exception, '操作执行失败。')
  } finally {
    confirming.value = false
    stopProgress()
  }
}

function hint(action) {
  return props.confirmHint ? props.confirmHint(action) : '请确认后才会执行。'
}

function taskStatusLabel(status) {
  return {
    QUEUED: '排队中',
    RUNNING: '运行中',
    RETRY_WAIT: '等待重试',
    SUCCEEDED: '已完成',
    FAILED: '失败'
  }[status] || status
}

function reset() {
  stopTaskPolling()
  draft.value = ''
  question.value = ''
  answer.value = null
  error.value = ''
  showTrace.value = false
  trackedTaskRefs.value = []
  trackedTasks.value = []
  taskProgressError.value = ''
}
</script>

<template>
  <section class="agent-chat" :aria-labelledby="titleId">
    <header v-if="showHeader" class="agent-chat-header">
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

      <div v-if="props.welcome" class="agent-msg agent-msg-bot">
        <div class="agent-avatar">AI</div>
        <div class="agent-bubble">
          <div class="markdown-body"><p>{{ props.welcome }}</p></div>
        </div>
      </div>

      <div v-if="question" class="agent-msg agent-msg-user">
        <div class="agent-avatar">你</div>
        <div class="agent-bubble"><p>{{ question }}</p></div>
      </div>

      <div v-if="loading" class="agent-msg agent-msg-bot">
        <div class="agent-avatar">AI</div>
        <div class="agent-bubble agent-progress-card" role="status" aria-live="polite">
          <div class="agent-progress-heading">
            <span class="agent-progress-spinner" aria-hidden="true"></span>
            <strong>{{ progressTitle }}</strong>
            <time>{{ elapsedSeconds }} 秒</time>
          </div>
          <div class="agent-progress-track" aria-hidden="true"><span></span></div>
          <ol class="agent-progress-steps">
            <li v-for="(step, index) in progressSteps" :key="step"
                :class="{ done: index < progressStage, active: index === progressStage }">
              <i aria-hidden="true"></i><span>{{ step }}</span>
            </li>
          </ol>
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
            <template v-if="!confirming">
              <strong>{{ hint(pending.action) }}</strong>
              <button type="button" @click="confirmAction">确认执行</button>
            </template>
            <div v-else class="agent-confirm-progress" role="status" aria-live="polite">
              <div class="agent-progress-heading">
                <span class="agent-progress-spinner" aria-hidden="true"></span>
                <strong>{{ progressTitle }}</strong>
                <time>{{ elapsedSeconds }} 秒</time>
              </div>
              <div class="agent-progress-track" aria-hidden="true"><span></span></div>
              <ol class="agent-progress-steps">
                <li v-for="(step, index) in progressSteps" :key="step"
                    :class="{ done: index < progressStage, active: index === progressStage }">
                  <i aria-hidden="true"></i><span>{{ step }}</span>
                </li>
              </ol>
            </div>
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

          <section v-if="backgroundProgress" class="agent-task-progress" role="status" aria-live="polite">
            <header>
              <div>
                <span>{{ backgroundProgress.done ? '后台任务已完成' : '后台任务执行中' }}</span>
                <strong>{{ backgroundProgress.progress }}%</strong>
              </div>
              <small>{{ backgroundProgress.terminal }}/{{ backgroundProgress.total }} 个空间完成</small>
            </header>
            <div class="agent-task-progress-track" :aria-label="`后台任务进度 ${backgroundProgress.progress}%`">
              <span :style="{ width: `${backgroundProgress.progress}%` }"></span>
            </div>
            <dl>
              <div><dt>运行中</dt><dd>{{ backgroundProgress.running }}</dd></div>
              <div><dt>排队中</dt><dd>{{ backgroundProgress.queued }}</dd></div>
              <div><dt>已完成</dt><dd>{{ backgroundProgress.terminal - backgroundProgress.failed }}</dd></div>
              <div :class="{ failed: backgroundProgress.failed }"><dt>失败</dt><dd>{{ backgroundProgress.failed }}</dd></div>
            </dl>
            <p v-if="taskPolling && !backgroundProgress.done"><span class="agent-live-dot"></span>每 2 秒自动更新</p>
            <details class="agent-task-progress-details">
              <summary>查看各空间进度</summary>
              <ul>
                <li v-for="task in trackedTasks" :key="task.taskId" :class="task.status.toLowerCase()">
                  <code>{{ task.workspaceId }}</code>
                  <span>{{ taskStatusLabel(task.status) }} · {{ task.progress }}%</span>
                </li>
              </ul>
            </details>
          </section>
          <p v-if="taskProgressError" class="agent-task-progress-error">{{ taskProgressError }}</p>
        </div>
      </div>
    </div>

    <div v-if="suggestions.length && !question && !answer && !loading && !confirming"
         class="agent-chat-suggestions" aria-label="常见问题">
      <button v-for="suggestion in suggestions" :key="suggestion" type="button"
              :disabled="busy" @click="ask(suggestion)">{{ suggestion }}</button>
    </div>

    <form class="agent-chat-composer" @submit.prevent="handleSubmit">
      <textarea v-model="draft" rows="2" :disabled="busy"
                :placeholder="placeholder" :aria-label="`向${title}提问`"
                @keydown="handleKeydown"></textarea>
      <button type="submit" :disabled="busy || !draft.trim()">
        {{ busy ? (confirming ? '执行中…' : loadingLabel) : sendLabel }}
      </button>
    </form>

    <p v-if="error" class="agent-chat-error" role="alert">{{ error }}</p>
    <div v-if="hasThread" class="agent-chat-footer">
      <button type="button" :disabled="busy" @click="reset">清空对话</button>
    </div>
  </section>
</template>
