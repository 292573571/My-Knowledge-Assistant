<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, onUpdated, reactive, ref, watch } from 'vue'
import ChatInput from './ChatInput.vue'
import ChatMessage from './ChatMessage.vue'
import ConversationSidebar from './ConversationSidebar.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import { formatApiError } from '../api/apiError'
import {
  createLearningSession,
  deleteLearningSession,
  fetchLearningSession,
  fetchLearningSessions,
  stopLearningSession,
  streamLearningMessage,
  submitLearningCheck,
  submitLearningPractice
} from '../api/learningAssistantApi'
import { createUuid } from '../utils/uuid'
import { fetchMyConfig } from '../api/modelConfigApi'

const emit = defineEmits(['manage-models'])
const sessions = ref([])
const activeSessionId = ref('')
const messages = ref([])
const progress = ref(null)
const pendingCheck = ref(null)
const pendingPractice = ref(null)
const userLevel = ref('BEGINNER')
const mode = ref('AUTO')
const checkAnswer = ref('')
const practiceAnswer = ref('')
const loading = ref(false)
const loadingSessions = ref(false)
const creatingSession = ref(false)
const deletingSessionId = ref('')
const pendingDeleteSession = ref(null)
const selectedModelId = ref(null)
const modelOptions = ref([])
const currentModel = ref(null)
const error = ref('')
const messagesEl = ref(null)

const modeLabels = { AUTO: '自动判断', CHAT: '直接回答', GUIDED: '主题教学', REVIEW: '复习模式', PRACTICE: '实践模式' }
const activeSession = computed(() => sessions.value.find(item => item.sessionId === activeSessionId.value))
const isLearningMode = computed(() => ['GUIDED', 'REVIEW', 'PRACTICE'].includes(mode.value))
const hoveredSource = ref(null)
const sourceTooltipPosition = ref({ top: 0, left: 0 })
const latestSources = computed(() => {
  const sources = [...messages.value].reverse().find(item => item.role === 'assistant' && item.sources?.length)?.sources || []
  const groups = new Map()
  sources.forEach((source, index) => {
    const key = source.documentId || source.path || source.fileName || source.file || source.title || source.name || source.url || `source-${index}`
    const page = source.pageNumber || source.page
    const current = groups.get(key) || { ...source, pages: [], chunks: [] }
    if (page && !current.pages.includes(page)) current.pages.push(page)
    const chunk = source.chunkIndex ?? source.chunkId
    if (chunk !== undefined && chunk !== null && !current.chunks.includes(chunk)) current.chunks.push(chunk)
    groups.set(key, current)
  })
  return [...groups.values()]
})
const closeStream = ref(null)
const streamingRequest = ref(false)
const mobileSessionsOpen = ref(false)
const inspirationSentinel = ref(null)
const visibleInspirations = ref([])
const inspirationLoading = ref(false)
const inspirationPool = [
  '用一个真实例子解释 RAG 的检索阶段',
  '把这篇资料整理成一张知识地图',
  '比较向量检索和关键词检索的适用场景',
  '设计一个可验证的 Agent 工具调用流程',
  '用费曼技巧检查我是否真正理解了这个概念',
  '把复杂文档拆成三层学习路径',
  '给我一个 Spring AI 的最小实践任务',
  '找出当前知识空间里最值得复习的主题'
]
const props = defineProps({
  workspaceId: { type: String, default: '' },
  currentUser: { type: Object, default: null },
  modelConfigVersion: { type: Number, default: 0 }
})
let inspirationObserver = null
let inspirationTimer = null
let activeRequestId = 0
let sessionOperationId = 0

onMounted(() => {
  loadSessions()
  loadModelOptions()
  loadMoreInspirations()
  document.addEventListener('pointermove', closeSourceOnPointerMove)
  if ('IntersectionObserver' in window) {
    inspirationObserver = new IntersectionObserver((entries) => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreInspirations()
    }, { root: messagesEl.value, rootMargin: '180px 0px' })
    nextTick(observeInspirationSentinel)
  }
})
onBeforeUnmount(() => {
  cancelLocalStream()
  const sessionId = activeSessionId.value
  if (sessionId) void stopLearningSession(sessionId, props.workspaceId)
  activeRequestId += 1
  loading.value = false
  inspirationObserver?.disconnect()
  if (inspirationTimer) window.clearTimeout(inspirationTimer)
  document.removeEventListener('pointermove', closeSourceOnPointerMove)
})
watch(activeSessionId, () => scrollLatest())
watch(() => messages.value.length, () => scrollLatest())
watch(() => props.modelConfigVersion, () => loadModelOptions())
onUpdated(observeInspirationSentinel)

function observeInspirationSentinel() {
  if (inspirationObserver && inspirationSentinel.value) inspirationObserver.observe(inspirationSentinel.value)
}

async function loadModelOptions() {
  try {
    const config = await fetchMyConfig()
    modelOptions.value = (config.poolModels || []).filter(model => model.enabled && model.modelType === 'CHAT')
    currentModel.value = config.resolved || null
  } catch (exception) {
    modelOptions.value = []
    currentModel.value = null
  }
}

function loadMoreInspirations() {
  if (inspirationLoading.value) return
  if (inspirationObserver && inspirationSentinel.value) inspirationObserver.unobserve(inspirationSentinel.value)
  inspirationLoading.value = true
  const start = visibleInspirations.value.length
  inspirationTimer = window.setTimeout(() => {
    const next = Array.from({ length: 3 }, (_, index) => inspirationPool[(start + index) % inspirationPool.length])
    visibleInspirations.value.push(...next.map((prompt, index) => ({ id: `${start + index}-${prompt}`, prompt })))
    inspirationLoading.value = false
    inspirationTimer = null
  }, 180)
}

function handleLearningScroll(event) {
  const element = event.currentTarget
  if (element.scrollHeight - element.scrollTop - element.clientHeight < 220) observeInspirationSentinel()
}

async function loadSessions() {
  const operationId = ++sessionOperationId
  loadingSessions.value = true
  error.value = ''
  try {
    const loaded = await fetchLearningSessions()
    if (operationId !== sessionOperationId) return
    sessions.value = loaded
    loadingSessions.value = false
    if (loaded.length) await selectSession(loaded[0].sessionId)
    else await newSession()
  } catch (exception) {
    if (operationId === sessionOperationId) error.value = formatApiError(exception, '学习会话加载失败。')
  } finally {
    if (operationId === sessionOperationId) loadingSessions.value = false
  }
}

async function newSession() {
  if (loading.value || creatingSession.value) return
  const operationId = ++sessionOperationId
  creatingSession.value = true
  mode.value = 'AUTO'
  userLevel.value = 'BEGINNER'
  try {
    const created = await createLearningSession({ mode: 'AUTO', userLevel: 'BEGINNER' })
    if (operationId !== sessionOperationId) return
    sessions.value.unshift(created)
    activeSessionId.value = created.sessionId
    messages.value = []
    progress.value = null
    pendingCheck.value = null
    pendingPractice.value = null
    error.value = ''
  } catch (exception) {
    if (operationId === sessionOperationId) error.value = formatApiError(exception, '创建学习会话失败。')
  } finally {
    creatingSession.value = false
  }
}

async function selectSession(sessionId) {
  if (!sessionId || loading.value || loadingSessions.value || creatingSession.value || deletingSessionId.value) return
  const operationId = ++sessionOperationId
  try {
    const session = await fetchLearningSession(sessionId)
    if (operationId !== sessionOperationId) return
    activeSessionId.value = session.sessionId
    messages.value = (session.messages || []).map(message => ({ ...message, streaming: false, error: null }))
    progress.value = session.progress || null
    mode.value = session.mode || 'AUTO'
    userLevel.value = session.userLevel || 'BEGINNER'
    pendingCheck.value = session.pendingCheck || null
    pendingPractice.value = session.pendingPractice || null
  } catch (exception) {
    if (operationId === sessionOperationId) error.value = formatApiError(exception, '学习会话恢复失败。')
  }
}

async function deleteSession(sessionId) {
  if (!sessionId || loading.value || deletingSessionId.value) return
  const operationId = ++sessionOperationId
  deletingSessionId.value = sessionId
  try {
    await deleteLearningSession(sessionId)
    if (operationId !== sessionOperationId) return
    sessions.value = sessions.value.filter(item => item.sessionId !== sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = ''
      messages.value = []
      progress.value = null
      pendingCheck.value = null
      pendingPractice.value = null
      if (sessions.value.length) await selectSession(sessions.value[0].sessionId)
      else await newSession()
    }
  } catch (exception) {
    if (operationId === sessionOperationId) error.value = formatApiError(exception, '删除学习会话失败。')
  } finally {
    deletingSessionId.value = ''
  }
}

function requestDeleteSession(sessionId) {
  if (!sessionId || loading.value || deletingSessionId.value) return
  pendingDeleteSession.value = sessions.value.find(session => session.sessionId === sessionId) || { sessionId, title: '这条学习会话' }
}

async function confirmDeleteSession() {
  const sessionId = pendingDeleteSession.value?.sessionId
  pendingDeleteSession.value = null
  if (sessionId) await deleteSession(sessionId)
}

async function send(content) {
  const text = (content || '').trim()
  if (!text || loading.value) return
  if (!activeSessionId.value) await newSession()
  if (!activeSessionId.value) return

  loading.value = true
  streamingRequest.value = true
  const requestId = ++activeRequestId
  error.value = ''
  messages.value.push({ id: createUuid(), role: 'user', content: text, createdAt: new Date().toISOString(), streaming: false })
  const assistant = reactive({ id: createUuid(), role: 'assistant', content: '', sources: [], toolCalls: [], streaming: true, retrieving: false, createdAt: new Date().toISOString() })
  messages.value.push(assistant)
  try {
    const response = await streamMessage(activeSessionId.value, {
       message: text,
       mode: mode.value,
       userLevel: userLevel.value,
       modelId: selectedModelId.value,
       clientRequestId: createUuid()
    }, assistant)
    if (requestId !== activeRequestId) return
    assistant.content = assistant.content || response.answer || '本次请求没有返回正文。'
    assistant.sources = response.sources || assistant.sources || []
    assistant.learning = response
    progress.value = response.progress || progress.value
    if (response.check) pendingCheck.value = response.check
    if (response.practice) pendingPractice.value = response.practice
    const item = sessions.value.find(session => session.sessionId === activeSessionId.value)
    if (item) {
      item.title = item.title === '新的学习会话' ? text.slice(0, 24) : item.title
      item.updatedAt = new Date().toISOString()
      item.progress = progress.value
    }
  } catch (exception) {
    if (requestId !== activeRequestId || exception?.name === 'AbortError') return
    assistant.error = formatApiError(exception, '学习助手暂时无法回答。')
    error.value = assistant.error
  } finally {
    assistant.streaming = false
    streamingRequest.value = false
    if (requestId === activeRequestId) loading.value = false
  }
}

function streamMessage(sessionId, payload, assistant) {
  return new Promise((resolve, reject) => {
    let settled = false
    const close = streamLearningMessage(sessionId, payload, (type, data) => {
      if (settled) return
      if (type === 'token') {
        assistant.retrieving = false
        assistant.content += data.text || ''
      } else if (type === 'source') {
        assistant.sources.push(data)
      } else if (type === 'tool_call_start') {
        assistant.retrieving = true
      } else if (type === 'tool_call_result') {
        assistant.retrieving = false
      } else if (type === 'check') {
        pendingCheck.value = data
      } else if (type === 'practice') {
        pendingPractice.value = data
      } else if (type === 'done') {
        settled = true
        if (closeStream.value?.close === close) closeStream.value = null
        resolve(data.response || {})
      } else if (type === 'error') {
        settled = true
        if (closeStream.value?.close === close) closeStream.value = null
        reject(data.apiError || Object.assign(new Error(data.message || '学习助手回答失败。'), {
          status: data.status || null,
          requestId: data.requestId || ''
        }))
      }
    })
    closeStream.value = {
      close,
      cancel() {
        if (settled) return
        settled = true
        close()
        reject(new DOMException('请求已停止', 'AbortError'))
      }
    }
  })
}

async function stop() {
  const sessionId = activeSessionId.value
  cancelLocalStream()
  activeRequestId += 1
  loading.value = false
  const streaming = [...messages.value].reverse().find(message => message.streaming)
  if (streaming) {
    streaming.streaming = false
    if (!streaming.content) messages.value = messages.value.filter(message => message !== streaming)
  }
  if (sessionId) {
    try {
      await stopLearningSession(sessionId)
    } catch (exception) {
      error.value = formatApiError(exception, '停止学习请求失败。')
    }
  }
}

function cancelLocalStream() {
  const current = closeStream.value
  closeStream.value = null
  current?.cancel()
}

async function submitCheck() {
  if (!pendingCheck.value || !checkAnswer.value.trim() || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await submitLearningCheck(activeSessionId.value, { checkId: pendingCheck.value.checkId, answer: checkAnswer.value.trim(), modelId: selectedModelId.value, clientRequestId: createUuid() })
    appendResult(response)
    pendingCheck.value = null
    pendingPractice.value = response.practice || null
    checkAnswer.value = ''
  } catch (exception) {
    error.value = formatApiError(exception, '理解检查暂时无法评分。')
  } finally {
    loading.value = false
  }
}

async function submitPractice() {
  if (!pendingPractice.value || !practiceAnswer.value.trim() || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await submitLearningPractice(activeSessionId.value, { practiceId: pendingPractice.value.practiceId, answer: practiceAnswer.value.trim(), modelId: selectedModelId.value, clientRequestId: createUuid() })
    appendResult(response)
    pendingPractice.value = response.practice?.status === 'PENDING' ? response.practice : null
    practiceAnswer.value = ''
  } catch (exception) {
    error.value = formatApiError(exception, '实践答案暂时无法评分。')
  } finally {
    loading.value = false
  }
}

function appendResult(response) {
  messages.value.push({ id: createUuid(), role: 'assistant', content: response.answer || response.feedback || '', sources: response.sources || [], learning: response, streaming: false, createdAt: new Date().toISOString() })
  progress.value = response.progress || progress.value
}

function scrollLatest() {
  nextTick(() => { if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight })
}

function modeChanged(nextMode) {
  mode.value = nextMode
}

function modelChanged(nextModelId) {
  selectedModelId.value = nextModelId
}

function sourceLabel(source) {
  const name = source.fileName || source.file || source.title || source.name || '来源'
  const pages = source.pages?.length ? ` · ${source.pages.map(page => `第 ${page} 页`).join('、')}` : ''
  return `${name}${pages}`
}

function sourceKey(source, index) {
  return [source.documentId, source.path, source.fileName, source.file, source.title, source.name, index]
    .filter(value => value !== undefined && value !== null && value !== '')
    .join(':')
}

function sourceHeading(source) {
  return Array.isArray(source.headingPath) ? source.headingPath.join(' > ') : source.headingPath || '-'
}

function sourcePreview(source) {
  const value = source.snippet || source.excerpt || source.content || ''
  return value.length > 220 ? `${value.slice(0, 220)}...` : value || '后端未返回片段预览。'
}

function showSource(source, event) {
  hoveredSource.value = source
  const rect = event.currentTarget.getBoundingClientRect()
  const width = Math.min(360, window.innerWidth - 24)
  const left = Math.min(rect.left, window.innerWidth - width - 12)
  const top = rect.bottom + 8
  sourceTooltipPosition.value = {
    top: Math.min(top, window.innerHeight - 260),
    left: Math.max(12, left)
  }
}

function hideSource(source) {
  if (hoveredSource.value === source) hoveredSource.value = null
}

function closeSourceOnPointerMove(event) {
  if (!hoveredSource.value || event.target.closest('.learning-source-item')) return
  hoveredSource.value = null
}
</script>

<template>
  <main class="learning-assistant-page">
     <div class="learning-assistant-grid" :class="{ 'guided-mode': isLearningMode }">
      <ConversationSidebar
        :conversations="sessions.map(session => ({ id: session.sessionId, title: session.title, updatedAt: session.updatedAt }))"
        :active-id="activeSessionId"
        :deleting-disabled="loading || Boolean(deletingSessionId)"
        :mobile-open="mobileSessionsOpen"
        @new="newSession"
        @select="selectSession"
         @delete="requestDeleteSession"
        @close="mobileSessionsOpen = false"
      />
      <ConfirmDialog
        v-if="pendingDeleteSession"
        title="删除学习会话？"
        :message="`将删除「${pendingDeleteSession.title || '这条学习会话'}」及其中的对话内容，此操作不可恢复。`"
        confirm-text="删除会话"
        :busy="Boolean(deletingSessionId)"
        danger
        @confirm="confirmDeleteSession"
        @cancel="pendingDeleteSession = null"
      />
      <div v-if="mobileSessionsOpen" class="session-drawer-backdrop" role="presentation" @click="mobileSessionsOpen = false"></div>
       <section class="learning-conversation-panel">
         <section ref="messagesEl" class="learning-messages" aria-label="统一学习对话" @scroll="handleLearningScroll">
          <div v-if="loadingSessions" class="learning-session-skeleton" aria-live="polite" aria-label="正在加载学习会话">
            <span></span><span></span><span></span>
          </div>
          <div v-else-if="!messages.length" class="learning-empty-state">
            <div class="learning-bento-grid learning-featured-scroll" data-reveal aria-label="精选学习路径">
              <article class="learning-bento-card featured"><span>精选</span><strong>从问题到掌握</strong><p>让每一次回答都留下可继续的学习线索。</p></article>
              <article class="learning-bento-card"><span>问答</span><strong>快速问答</strong><p>先解决眼前的问题。</p></article>
              <article class="learning-bento-card"><span>教学</span><strong>主题教学</strong><p>解释、检查、实践。</p></article>
              <article class="learning-bento-card learning-bento-note"><span>说明</span><strong>你的知识空间</strong><p>依据来自当前工作区，不凭空编造。</p></article>
            </div>
            <section class="learning-inspiration-flow" data-reveal aria-labelledby="inspiration-title">
              <header><div><span class="learning-eyebrow">AI 灵感流</span><h2 id="inspiration-title">下一步，可以从这里开始</h2></div><span>{{ visibleInspirations.length }} 个灵感</span></header>
              <div class="learning-inspiration-list">
                <button v-for="item in visibleInspirations" :key="item.id" type="button" @click="send(item.prompt)">{{ item.prompt }} <b aria-hidden="true">↗</b></button>
                <span ref="inspirationSentinel" class="learning-inspiration-sentinel" aria-hidden="true"></span>
              </div>
            </section>
            <section class="learning-skill-marquee" data-reveal aria-label="学习能力标签">
              <div class="learning-skill-track"><span v-for="skill in ['检索增强', '主题教学', '理解检查', '实践迁移', '知识地图', '来源核验']" :key="skill">{{ skill }}</span><span aria-hidden="true">检索增强</span><span aria-hidden="true">主题教学</span><span aria-hidden="true">理解检查</span></div>
            </section>
            <div v-if="error" class="learning-empty-error" role="alert"><strong>暂时无法打开学习空间</strong><span>{{ error }}</span><button type="button" @click="loadSessions">重试</button></div>
          </div>
          <ChatMessage v-for="message in messages" :key="message.id" :message="message" :streaming="message.streaming" />
        </section>
        <div v-if="error && messages.length" class="learning-assistant-error" role="alert"><span>{{ error }}</span></div>
         <footer class="learning-input-bar">
           <button type="button" class="session-mobile-trigger" aria-label="打开会话列表" @click="mobileSessionsOpen = true"><span></span><span></span><span></span></button>
           <ChatInput :disabled="loading" :stoppable="streamingRequest" :mode="mode" :mode-labels="modeLabels" :model-id="selectedModelId" :model-options="modelOptions" :current-model="currentModel" :can-manage-models="Boolean(currentUser)" @send="send" @stop="stop" @update:mode="modeChanged" @update:model-id="modelChanged" @manage-models="emit('manage-models')" />
         </footer>
      </section>
        <aside v-if="isLearningMode" class="learning-stage-sidebar">
         <header><span class="learning-eyebrow">教学状态</span><h2>学习进度</h2></header>
        <div v-if="progress" class="learning-progress-card">
          <div class="learning-progress-heading"><strong>{{ progress.masteryPercent }}%</strong><span>{{ progress.status === 'MASTERED' ? '已掌握' : progress.status === 'NEEDS_REVIEW' ? '需要复习' : '进行中' }}</span></div>
          <div class="learning-progress-bar"><span :style="{ width: `${progress.masteryPercent}%` }"></span></div>
          <p>完成 {{ progress.completedItems }}/{{ progress.requiredItems }} 个学习环节，得分 {{ progress.score }}/{{ progress.maxScore }}。</p>
          <ul v-if="progress.weakPoints?.length"><li v-for="point in progress.weakPoints" :key="point">{{ point }}</li></ul>
        </div>
        <div v-else class="learning-sidebar-empty">进入主题教学后，这里会显示讲解、检查和实践进度。</div>
        <section v-if="pendingCheck" class="learning-action-card check-card">
          <span class="learning-action-tag">检查</span><h3>检查你的理解</h3><p>{{ pendingCheck.question }}</p>
          <textarea v-model="checkAnswer" rows="5" maxlength="4000" placeholder="用自己的话回答"></textarea>
           <button type="button" :disabled="loading || !checkAnswer.trim()" :aria-busy="loading" @click="submitCheck">{{ loading ? '正在评分...' : '提交检查' }}</button>
        </section>
        <section v-if="pendingPractice" class="learning-action-card practice-card">
          <span class="learning-action-tag">实践</span><h3>把概念带到真实任务</h3><p>{{ pendingPractice.question }}</p>
          <textarea v-model="practiceAnswer" rows="7" maxlength="4000" placeholder="说明任务目标、工具调用，以及结果如何影响下一步"></textarea>
           <button type="button" :disabled="loading || !practiceAnswer.trim()" :aria-busy="loading" @click="submitPractice">{{ loading ? '正在评估...' : '提交实践' }}</button>
        </section>
          <section v-if="latestSources.length" class="learning-sources-card">
            <h3>本次依据 <small>悬停查看详情</small></h3>
            <div
              v-for="(source, index) in latestSources"
              :key="sourceKey(source, index)"
              class="learning-source-item"
              tabindex="0"
              @pointerenter="showSource(source, $event)"
              @mouseleave="hideSource(source)"
              @focus="showSource(source, $event)"
              @blur="hideSource(source)"
            >
              <span>{{ sourceLabel(source) }}</span>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
            </div>
          </section>
        </aside>
        <Teleport to="body">
          <aside v-if="hoveredSource" class="learning-source-tooltip" :style="{ top: `${sourceTooltipPosition.top}px`, left: `${sourceTooltipPosition.left}px` }">
            <strong>{{ sourceLabel(hoveredSource) }}</strong>
            <dl>
              <div><dt>标题</dt><dd>{{ sourceHeading(hoveredSource) }}</dd></div>
              <div><dt>页码</dt><dd>{{ hoveredSource.pages?.length ? hoveredSource.pages.map(page => `第 ${page} 页`).join('、') : '-' }}</dd></div>
              <div><dt>分块</dt><dd>{{ hoveredSource.chunks?.length ? hoveredSource.chunks.map(chunk => `#${chunk}`).join('、') : '-' }}</dd></div>
            </dl>
            <p>{{ sourcePreview(hoveredSource) }}</p>
          </aside>
        </Teleport>
      </div>
  </main>
</template>
