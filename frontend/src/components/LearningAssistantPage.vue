<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import ChatInput from './ChatInput.vue'
import ChatMessage from './ChatMessage.vue'
import ConversationSidebar from './ConversationSidebar.vue'
import { formatApiError } from '../api/apiError'
import {
  createLearningSession,
  deleteLearningSession,
  fetchLearningSession,
  fetchLearningSessions,
  sendLearningMessage,
  streamLearningMessage,
  submitLearningCheck,
  submitLearningPractice
} from '../api/learningAssistantApi'
import { getActiveWorkspaceId } from '../api/workspaceApi'
import { createUuid } from '../utils/uuid'

defineProps({ workspace: { type: Object, default: null } })

const sessions = ref([])
const activeSessionId = ref('')
const messages = ref([])
const progress = ref(null)
const pendingCheck = ref(null)
const pendingPractice = ref(null)
const topic = ref('')
const userLevel = ref('BEGINNER')
const mode = ref('AUTO')
const checkAnswer = ref('')
const practiceAnswer = ref('')
const loading = ref(false)
const loadingSessions = ref(false)
const error = ref('')
const messagesEl = ref(null)

const starterPrompts = [
  '总结当前知识库的主要内容',
  '请教我 Spring AI 如何实现 RAG',
  '帮我梳理文档导入和重建流程'
]
const modeLabels = { AUTO: '自动判断', CHAT: '直接回答', GUIDED: '主题教学', REVIEW: '复习模式', PRACTICE: '实践模式' }
const activeSession = computed(() => sessions.value.find(item => item.sessionId === activeSessionId.value))
const latestSources = computed(() => [...messages.value].reverse().find(item => item.role === 'assistant' && item.sources?.length)?.sources || [])
const closeStream = ref(null)

onMounted(loadSessions)
watch(activeSessionId, () => scrollLatest())
watch(() => messages.value.length, () => scrollLatest())

async function loadSessions() {
  loadingSessions.value = true
  error.value = ''
  try {
    const loaded = await fetchLearningSessions()
    sessions.value = loaded
    if (loaded.length) await selectSession(loaded[0].sessionId)
    else await newSession()
  } catch (exception) {
    error.value = formatApiError(exception, '学习会话加载失败。')
  } finally {
    loadingSessions.value = false
  }
}

async function newSession() {
  if (loading.value) return
  try {
    const created = await createLearningSession({ topic: topic.value, mode: mode.value, userLevel: userLevel.value })
    sessions.value.unshift(created)
    activeSessionId.value = created.sessionId
    messages.value = []
    progress.value = null
    pendingCheck.value = null
    pendingPractice.value = null
    error.value = ''
  } catch (exception) {
    error.value = formatApiError(exception, '创建学习会话失败。')
  }
}

async function selectSession(sessionId) {
  if (!sessionId || loading.value) return
  try {
    const session = await fetchLearningSession(sessionId)
    activeSessionId.value = session.sessionId
    messages.value = (session.messages || []).map(message => ({ ...message, streaming: false, error: null }))
    progress.value = session.progress || null
    topic.value = session.topic || ''
    mode.value = session.mode || 'AUTO'
    pendingCheck.value = null
    pendingPractice.value = null
  } catch (exception) {
    error.value = formatApiError(exception, '学习会话恢复失败。')
  }
}

async function deleteSession(sessionId) {
  if (!sessionId || loading.value) return
  try {
    await deleteLearningSession(sessionId)
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
    error.value = formatApiError(exception, '删除学习会话失败。')
  }
}

async function send(content) {
  const text = (content || '').trim()
  if (!text || loading.value) return
  if (!activeSessionId.value) await newSession()
  if (!activeSessionId.value) return

  loading.value = true
  error.value = ''
  messages.value.push({ id: createUuid(), role: 'user', content: text, createdAt: new Date().toISOString(), streaming: false })
  const assistant = { id: createUuid(), role: 'assistant', content: '', sources: [], toolCalls: [], streaming: true, createdAt: new Date().toISOString() }
  messages.value.push(assistant)
  try {
    const response = await streamMessage(activeSessionId.value, {
      message: text,
      topic: topic.value.trim() || null,
      mode: mode.value,
      userLevel: userLevel.value,
      clientRequestId: createUuid()
    }, assistant)
    assistant.content = assistant.content || response.answer || '本次请求没有返回正文。'
    assistant.sources = response.sources || assistant.sources || []
    assistant.learning = response
    progress.value = response.progress || progress.value
    if (response.check) pendingCheck.value = response.check
    if (response.practice) pendingPractice.value = response.practice
    if (response.topic) topic.value = response.topic
    const item = sessions.value.find(session => session.sessionId === activeSessionId.value)
    if (item) {
      item.title = item.title === '新的学习会话' ? text.slice(0, 24) : item.title
      item.updatedAt = new Date().toISOString()
      item.progress = progress.value
    }
  } catch (exception) {
    assistant.error = formatApiError(exception, '学习助手暂时无法回答。')
    error.value = assistant.error
  } finally {
    assistant.streaming = false
    loading.value = false
  }
}

function streamMessage(sessionId, payload, assistant) {
  return new Promise((resolve, reject) => {
    let response = null
    let settled = false
    closeStream.value = streamLearningMessage(sessionId, payload, (type, data) => {
      if (settled) return
      if (type === 'token') {
        assistant.content += data.text || ''
      } else if (type === 'source') {
        assistant.sources.push(data)
      } else if (type === 'check') {
        pendingCheck.value = data
      } else if (type === 'practice') {
        pendingPractice.value = data
      } else if (type === 'done') {
        response = data.response || response
        settled = true
        closeStream.value = null
        resolve(response || {})
      } else if (type === 'error') {
        settled = true
        closeStream.value = null
        reject(new Error(data.message || '学习助手回答失败。'))
      }
    })
  })
}

function stop() {
  closeStream.value?.()
  closeStream.value = null
  loading.value = false
  const streaming = [...messages.value].reverse().find(message => message.streaming)
  if (streaming) {
    streaming.streaming = false
    if (!streaming.content) messages.value = messages.value.filter(message => message !== streaming)
  }
}

async function submitCheck() {
  if (!pendingCheck.value || !checkAnswer.value.trim() || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await submitLearningCheck(activeSessionId.value, { checkId: pendingCheck.value.checkId, answer: checkAnswer.value.trim(), clientRequestId: createUuid() })
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
    const response = await submitLearningPractice(activeSessionId.value, { practiceId: pendingPractice.value.practiceId, answer: practiceAnswer.value.trim(), clientRequestId: createUuid() })
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

function modeChanged() {
  if (mode.value === 'GUIDED' && !topic.value.trim()) topic.value = 'Agent'
}

function sourceLabel(source) {
  return source.fileName || source.file || source.title || source.name || '来源'
}
</script>

<template>
  <main class="learning-assistant-page">
    <div class="learning-assistant-grid">
      <ConversationSidebar
        :conversations="sessions.map(session => ({ id: session.sessionId, title: session.title, updatedAt: session.updatedAt }))"
        :active-id="activeSessionId"
        :deleting-disabled="loading"
        @new="newSession"
        @select="selectSession"
        @delete="deleteSession"
      />
      <section class="learning-conversation-panel">
        <header class="learning-conversation-header">
          <div>
            <span class="learning-eyebrow">LEARNING ASSISTANT</span>
            <h1>{{ activeSession?.title || '新的学习会话' }}</h1>
            <p>普通问题直接回答，想真正掌握时切换为主题教学。</p>
          </div>
          <div class="learning-controls">
            <label>模式 <select v-model="mode" @change="modeChanged"><option v-for="(label, value) in modeLabels" :key="value" :value="value">{{ label }}</option></select></label>
            <label v-if="mode !== 'CHAT'">主题 <input v-model="topic" maxlength="120" placeholder="例如 Agent、RAG" /></label>
          </div>
        </header>
        <section ref="messagesEl" class="learning-messages" aria-label="统一学习对话">
          <div v-if="!messages.length" class="learning-empty-state">
            <div class="learning-orbit">✦</div>
            <h2>从一个问题开始学习</h2>
            <p>我会在当前知识空间内回答；当你明确要学习、复习或练习时，会进入带检查的教学流程。</p>
            <div class="starter-prompts"><button v-for="prompt in starterPrompts" :key="prompt" type="button" @click="send(prompt)">{{ prompt }}</button></div>
          </div>
          <ChatMessage v-for="message in messages" :key="message.id" :message="message" :streaming="message.streaming" />
        </section>
        <p v-if="error" class="learning-assistant-error" role="alert">{{ error }}</p>
        <footer class="learning-input-bar"><ChatInput :disabled="loading" @send="send" @stop="stop" /></footer>
      </section>
      <aside class="learning-stage-sidebar">
        <header><span class="learning-eyebrow">GUIDED STATUS</span><h2>学习进度</h2></header>
        <div v-if="progress" class="learning-progress-card">
          <div class="learning-progress-heading"><strong>{{ progress.masteryPercent }}%</strong><span>{{ progress.status === 'MASTERED' ? '已掌握' : progress.status === 'NEEDS_REVIEW' ? '需要复习' : '进行中' }}</span></div>
          <div class="learning-progress-bar"><span :style="{ width: `${progress.masteryPercent}%` }"></span></div>
          <p>完成 {{ progress.completedItems }}/{{ progress.requiredItems }} 个学习环节，得分 {{ progress.score }}/{{ progress.maxScore }}。</p>
          <ul v-if="progress.weakPoints?.length"><li v-for="point in progress.weakPoints" :key="point">{{ point }}</li></ul>
        </div>
        <div v-else class="learning-sidebar-empty">进入主题教学后，这里会显示讲解、检查和实践进度。</div>
        <section v-if="pendingCheck" class="learning-action-card check-card">
          <span class="learning-action-tag">CHECK</span><h3>检查你的理解</h3><p>{{ pendingCheck.question }}</p>
          <textarea v-model="checkAnswer" rows="5" maxlength="4000" placeholder="用自己的话回答"></textarea>
          <button type="button" :disabled="loading || !checkAnswer.trim()" @click="submitCheck">{{ loading ? '正在评分...' : '提交检查' }}</button>
        </section>
        <section v-if="pendingPractice" class="learning-action-card practice-card">
          <span class="learning-action-tag">PRACTICE</span><h3>把概念带到真实任务</h3><p>{{ pendingPractice.question }}</p>
          <textarea v-model="practiceAnswer" rows="7" maxlength="4000" placeholder="说明任务目标、工具调用，以及结果如何影响下一步"></textarea>
          <button type="button" :disabled="loading || !practiceAnswer.trim()" @click="submitPractice">{{ loading ? '正在评估...' : '提交实践' }}</button>
        </section>
        <section v-if="latestSources.length" class="learning-sources-card"><h3>本次依据</h3><span v-for="source in latestSources" :key="sourceLabel(source)">{{ sourceLabel(source) }}</span></section>
      </aside>
    </div>
  </main>
</template>
