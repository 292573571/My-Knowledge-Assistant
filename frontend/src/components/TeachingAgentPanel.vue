<script setup>
import { computed, ref } from 'vue'
import { chatWithTeachingAgent } from '../api/teachingAgentApi'
import { formatApiError } from '../api/apiError'
import { createUuid } from '../utils/uuid'
import { renderMarkdown } from '../utils/markdown'
import SourcePanel from './SourcePanel.vue'

const props = defineProps({
  workspace: { type: Object, default: null }
})

const topic = ref('')
const userLevel = ref('BEGINNER')
const message = ref('')
const sessionId = ref('')
const result = ref(null)
const error = ref('')
const loading = ref(false)
const showTrace = ref(false)

const workspaceName = computed(() => props.workspace?.name || '当前空间')
const answerHtml = computed(() => renderMarkdown(result.value?.answer || ''))
const sourceEmptyMessage = computed(() => result.value
  ? '本次讲解没有返回当前空间的资料依据，请把它当作待核实的通用解释。'
  : '完成讲解后，这里会显示 Teaching Agent 使用的当前空间资料。')
const levelLabels = {
  BEGINNER: '初学者',
  INTERMEDIATE: '进阶',
  ADVANCED: '高级'
}
const stageLabels = {
  EXPLAIN: '讲解',
  CHECK: '理解检查',
  PRACTICE: '练习',
  REVIEW: '复习'
}
const actionLabels = {
  CHECK: '回答检查问题'
}
const suggestions = [
  { topic: 'Agent', message: '请解释什么是 Agent，并说明它和普通 RAG 问答有什么区别。' },
  { topic: 'RAG', message: '请用一个简单例子解释 RAG 的工作流程。' },
  { topic: 'Tool Calling', message: '请解释模型为什么需要调用工具，以及工具调用有哪些安全边界。' }
]

async function ask(suggestion = null) {
  if (loading.value) return
  if (suggestion) {
    topic.value = suggestion.topic
    message.value = suggestion.message
  }

  const normalizedTopic = topic.value.trim()
  const normalizedMessage = message.value.trim()
  if (!normalizedTopic || !normalizedMessage || !props.workspace?.id) return

  topic.value = normalizedTopic
  message.value = normalizedMessage
  const previousTopic = result.value?.topic
  error.value = ''
  result.value = null
  showTrace.value = false
  loading.value = true

  if (previousTopic && previousTopic !== normalizedTopic) sessionId.value = ''
  if (!sessionId.value) sessionId.value = createUuid()

  try {
    result.value = await chatWithTeachingAgent({
      workspaceId: props.workspace.id,
      sessionId: sessionId.value,
      topic: normalizedTopic,
      userLevel: userLevel.value,
      message: normalizedMessage
    })
    sessionId.value = result.value.sessionId || sessionId.value
  } catch (exception) {
    error.value = formatApiError(exception, '教学助手暂时无法回答。')
  } finally {
    loading.value = false
  }
}

function startNewLesson() {
  sessionId.value = ''
  result.value = null
  error.value = ''
  showTrace.value = false
}
</script>

<template>
  <main class="teaching-dashboard">
    <section class="teaching-hero">
      <div>
        <p class="teaching-kicker">GUIDED LEARNING · {{ workspaceName }}</p>
        <h1>主题教学</h1>
        <p>让 Agent 不只回答问题，而是先解释，再用一个问题检查你是否真正理解。</p>
      </div>
      <div class="teaching-lesson-note">
        <span class="teaching-note-dot"></span>
        <strong>本课范围</strong>
        <span>EXPLAIN → CHECK</span>
      </div>
    </section>

    <section class="teaching-layout">
      <div class="teaching-main-column">
        <article class="teaching-card teaching-setup-card">
          <header class="teaching-card-header">
            <div>
              <span class="teaching-card-index">01</span>
              <div>
                <h2>设定你的学习目标</h2>
                <p>选择一个主题和学习水平，教学 Agent 会结合当前空间资料组织讲解。</p>
              </div>
            </div>
            <button v-if="result" type="button" class="teaching-reset-button" @click="startNewLesson">开始新主题</button>
          </header>

          <div class="teaching-fields">
            <label>
              <span>学习主题</span>
              <input v-model="topic" maxlength="120" type="text" placeholder="例如：Agent、RAG、Tool Calling">
            </label>
            <label>
              <span>你的水平</span>
              <select v-model="userLevel">
                <option v-for="(label, value) in levelLabels" :key="value" :value="value">{{ label }}</option>
              </select>
            </label>
          </div>

          <div class="teaching-suggestions" aria-label="推荐主题">
            <button v-for="suggestion in suggestions" :key="suggestion.topic" type="button" :disabled="loading" @click="ask(suggestion)">
              {{ suggestion.topic }}
            </button>
          </div>
        </article>

        <article class="teaching-card teaching-question-card">
          <header class="teaching-card-header compact">
            <div>
              <span class="teaching-card-index">02</span>
              <div>
                <h2>告诉 Agent 你想知道什么</h2>
                <p>问题越具体，讲解越容易贴合你的学习目标。</p>
              </div>
            </div>
          </header>
          <form @submit.prevent="ask()">
            <textarea v-model="message" maxlength="4000" rows="5" :disabled="loading" placeholder="例如：请用一个生活中的例子解释 Agent 如何选择工具。" aria-label="教学问题"></textarea>
            <div class="teaching-form-footer">
              <small>{{ message.length }} / 4000 · 当前空间：{{ workspaceName }}</small>
              <button type="submit" :disabled="loading || !topic.trim() || !message.trim() || !props.workspace?.id">
                {{ loading ? '正在组织讲解...' : '开始讲解' }}
              </button>
            </div>
          </form>
        </article>

        <p v-if="error" class="teaching-error" role="alert">{{ error }}</p>

        <article v-if="result" class="teaching-card teaching-answer-card" aria-live="polite">
          <header class="teaching-answer-header">
            <div>
              <span class="teaching-card-index">03</span>
              <div>
                <span class="teaching-answer-label">教学 Agent 的讲解</span>
                <h2>{{ result.topic }}</h2>
              </div>
            </div>
            <span class="teaching-readonly-badge">只读教学</span>
          </header>
          <div class="teaching-answer-meta">
            <span>当前阶段：{{ stageLabels[result.stage] || result.stage }}</span>
            <span>下一步：{{ actionLabels[result.nextAction] || result.nextAction }}</span>
            <span>{{ result.steps }} 步执行</span>
          </div>
          <div class="teaching-answer-markdown markdown-body" v-html="answerHtml"></div>

          <div class="teaching-check-prompt">
            <span class="teaching-check-icon">?</span>
            <div>
              <strong>接下来检查你的理解</strong>
              <p>当前接口已经提出 CHECK 阶段，但答案提交和自动评分将在下一课实现。</p>
            </div>
          </div>

          <div v-if="result.traces?.length" class="teaching-trace">
            <button type="button" @click="showTrace = !showTrace">{{ showTrace ? '收起工具链路' : '查看工具链路' }}</button>
            <ul v-if="showTrace">
              <li v-for="trace in result.traces" :key="`${trace.step}-${trace.toolName}`">
                <span><b>#{{ trace.step }}</b> {{ trace.toolName }}</span>
                <span :class="trace.status.toLowerCase()">{{ trace.status }}</span>
              </li>
            </ul>
          </div>
        </article>
      </div>

      <aside class="teaching-side-column">
        <section class="teaching-side-card">
          <span class="teaching-side-label">学习节奏</span>
          <h2>先理解，再证明你理解了。</h2>
          <div class="teaching-steps">
            <div class="active"><span>1</span><p><strong>讲解</strong><small>建立概念模型</small></p></div>
            <div><span>2</span><p><strong>检查</strong><small>回答一个理解问题</small></p></div>
            <div><span>3</span><p><strong>练习</strong><small>迁移到真实任务</small></p></div>
          </div>
        </section>
        <section class="teaching-side-card teaching-source-card">
          <header><span class="teaching-side-label">知识依据</span><span v-if="result?.sources?.length">{{ result.sources.length }} 条</span></header>
          <SourcePanel :sources="result?.sources || []" :empty-message="sourceEmptyMessage" />
        </section>
      </aside>
    </section>
  </main>
</template>
