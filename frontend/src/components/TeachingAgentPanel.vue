<script setup>
import { computed, onMounted, ref } from 'vue'
import { chatWithTeachingAgent, submitTeachingCheck, submitTeachingPractice } from '../api/teachingAgentApi'
import { fetchTeachingProgress } from '../api/learningRecordApi'
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
const checkAnswer = ref('')
const practiceScene = ref('')
const practiceTool = ref('')
const practiceResult = ref('')
const sessionId = ref('')
const result = ref(null)
const error = ref('')
const loading = ref(false)
const checking = ref(false)
const practicing = ref(false)
const showTrace = ref(false)
const qualityRetryCount = ref(0)
const maxQualityRetries = 2
const sessionUnavailable = ref(false)
const teachingProgress = ref([])

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
  CHECK: '回答检查问题',
  PRACTICE: '进入实践练习',
  REVIEW: '回顾薄弱点',
  RECHECK: '复习后重新检查',
  COMPLETE: '本课完成'
}
const traceStatusLabels = {
  SUCCEEDED: '成功',
  FAILED: '失败',
  REJECTED: '已拒绝'
}
const summaryStatusLabels = {
  IN_PROGRESS: '进行中',
  NEEDS_REVIEW: '需要复习',
  MASTERED: '已掌握'
}
const qualityStatusLabels = {
  PASS: '质量通过',
  NEEDS_REVIEW: '建议改进'
}
const practiceAnswer = computed(() => [
  `任务场景：${practiceScene.value.trim()}`,
  `使用的工具：${practiceTool.value.trim()}`,
  `工具结果的作用：${practiceResult.value.trim()}`
].join('\n'))
const normalizedTopic = (value) => (value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase()
const currentTopicProgress = computed(() => teachingProgress.value.find((item) => normalizedTopic(item.topic) === normalizedTopic(topic.value)) || null)
const reviewTopics = computed(() => teachingProgress.value
  .filter((item) => !item.latestPassed || item.masteryPercent < 60)
  .slice(0, 3))
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
    qualityRetryCount.value = 0
  }

  const normalizedTopic = topic.value.trim()
  const normalizedMessage = message.value.trim()
  if (!normalizedTopic || !normalizedMessage || !props.workspace?.id) return

  topic.value = normalizedTopic
  message.value = normalizedMessage
  const previousTopic = result.value?.topic
  error.value = ''
  sessionUnavailable.value = false
  result.value = null
  checkAnswer.value = ''
  practiceScene.value = ''
  practiceTool.value = ''
  practiceResult.value = ''
  showTrace.value = false
  loading.value = true

  if (previousTopic && previousTopic !== normalizedTopic) {
    sessionId.value = ''
    qualityRetryCount.value = 0
  }
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

function reviewTopic(progress) {
  ask({
    topic: progress.topic,
    message: `请带我复习“${progress.topic}”。我之前的理解检查得分是 ${progress.latestScore}/${progress.maxScore}，请先解释最容易混淆的部分，再给一个例子和新的检查问题。`
  })
}

async function improveExplanation() {
  if (loading.value || !result.value?.quality || result.value.quality.status === 'PASS'
    || qualityRetryCount.value >= maxQualityRetries) return
  const issues = result.value.quality.issues || []
  if (!issues.length) return
  message.value = `${message.value.trim()}\n\n请重新讲解，并重点补充：${issues.join('；')}。`
  qualityRetryCount.value += 1
  await ask()
}

async function submitPractice() {
  if (practicing.value || !result.value?.practice?.practiceId || !practiceScene.value.trim()
    || !practiceTool.value.trim() || !practiceResult.value.trim() || !props.workspace?.id) return
  practicing.value = true
  error.value = ''
  try {
    const practiced = await submitTeachingPractice({
      workspaceId: props.workspace.id,
      sessionId: sessionId.value,
      practiceId: result.value.practice.practiceId,
      answer: practiceAnswer.value
    })
    result.value = {
      ...result.value,
      ...practiced,
      practice: { ...result.value.practice, ...practiced }
    }
  } catch (exception) {
    if (exception?.status === 404) {
      sessionUnavailable.value = true
      error.value = ''
    } else {
      error.value = formatApiError(exception, '实践答案暂时无法评分。')
    }
  } finally {
    practicing.value = false
  }
}

async function submitCheck() {
  if (checking.value || !result.value?.check?.checkId || !checkAnswer.value.trim() || !props.workspace?.id) return
  checking.value = true
  error.value = ''
  try {
    const checked = await submitTeachingCheck({
      workspaceId: props.workspace.id,
      sessionId: sessionId.value,
      checkId: result.value.check.checkId,
      answer: checkAnswer.value.trim()
    })
    result.value = { ...result.value, ...checked }
    await loadTeachingProgress()
  } catch (exception) {
    if (exception?.status === 404) {
      sessionUnavailable.value = true
      error.value = ''
    } else {
      error.value = formatApiError(exception, '教学检查暂时无法评分。')
    }
  } finally {
    checking.value = false
  }
}

function recheckAfterReview() {
  ask()
}

async function restartUnavailableSession() {
  sessionId.value = ''
  sessionUnavailable.value = false
  result.value = null
  checkAnswer.value = ''
  practiceScene.value = ''
  practiceTool.value = ''
  practiceResult.value = ''
  showTrace.value = false
  await ask()
}

function startNewLesson() {
  sessionId.value = ''
  result.value = null
  checkAnswer.value = ''
  practiceScene.value = ''
  practiceTool.value = ''
  practiceResult.value = ''
  error.value = ''
  sessionUnavailable.value = false
  showTrace.value = false
  qualityRetryCount.value = 0
}

async function loadTeachingProgress() {
  try {
    if (!props.workspace?.id) {
      teachingProgress.value = []
      return
    }
    teachingProgress.value = await fetchTeachingProgress(props.workspace.id)
  } catch {
    teachingProgress.value = []
  }
}

onMounted(loadTeachingProgress)
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
        <span>EXPLAIN → CHECK → PRACTICE</span>
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

        <section v-if="sessionUnavailable" class="teaching-session-recovery" role="alert">
          <div>
            <strong>本次教学会话已失效</strong>
            <p>会话可能已超过 30 分钟，或服务刚刚重启。原来的检查题和实践状态无法安全恢复，但你的主题和问题仍然保留。</p>
          </div>
          <button type="button" :disabled="loading" @click="restartUnavailableSession">
            {{ loading ? '正在重新开始...' : '重新开始本主题' }}
          </button>
        </section>

        <article v-if="result" class="teaching-card teaching-answer-card" aria-live="polite">
          <header class="teaching-answer-header">
            <div>
              <span class="teaching-card-index">03</span>
              <div>
                <span class="teaching-answer-label">教学 Agent 的讲解</span>
                <h2>{{ result.topic }}</h2>
              </div>
            </div>
            <span class="teaching-readonly-badge">{{ result.readOnly ? '只读教学' : '已记录检查' }}</span>
          </header>
           <div class="teaching-answer-meta">
            <span>当前阶段：{{ stageLabels[result.stage] || result.stage }}</span>
            <span>下一步：{{ actionLabels[result.nextAction] || result.nextAction }}</span>
            <span>{{ result.steps }} 步执行</span>
           </div>
           <section v-if="result.quality" class="teaching-quality-card" :class="result.quality.status.toLowerCase()">
             <header>
               <span><b>讲解质量</b> · {{ qualityStatusLabels[result.quality.status] || result.quality.status }}</span>
               <strong>{{ result.quality.score }}%</strong>
             </header>
             <ul v-if="result.quality.issues?.length">
               <li v-for="issue in result.quality.issues" :key="issue">{{ issue }}</li>
             </ul>
             <button v-if="result.quality.status !== 'PASS' && qualityRetryCount < maxQualityRetries"
               type="button" :disabled="loading" @click="improveExplanation">
               {{ loading ? '正在重新组织...' : `按问题重新讲解（还可尝试 ${maxQualityRetries - qualityRetryCount} 次）` }}
             </button>
             <small v-else-if="result.quality.status !== 'PASS'" class="teaching-quality-limit">已达到自动改进次数，请修改问题或开始新主题。</small>
           </section>
          <section v-if="result.sessionSummary" class="teaching-summary-card" :class="result.sessionSummary.status.toLowerCase()">
            <header>
              <div><small>SESSION MASTERY</small><h3>本节掌握度</h3></div>
              <strong>{{ result.sessionSummary.masteryPercent }}%</strong>
            </header>
            <div class="teaching-summary-progress"><span :style="{ width: `${result.sessionSummary.masteryPercent}%` }"></span></div>
            <div class="teaching-summary-stats">
              <span>总分 <b>{{ result.sessionSummary.score }}/{{ result.sessionSummary.maxScore }}</b></span>
              <span>完成 <b>{{ result.sessionSummary.completedItems }}/{{ result.sessionSummary.requiredItems }}</b></span>
              <span>状态 <b>{{ summaryStatusLabels[result.sessionSummary.status] || result.sessionSummary.status }}</b></span>
            </div>
            <ul v-if="result.sessionSummary.weakPoints?.length" class="teaching-summary-weak-points">
              <li v-for="point in result.sessionSummary.weakPoints" :key="point">{{ point }}</li>
            </ul>
          </section>
          <div class="teaching-answer-markdown markdown-body" v-html="answerHtml"></div>

          <div v-if="result.nextAction === 'CHECK' && result.check" class="teaching-check-prompt">
            <span class="teaching-check-icon" aria-hidden="true">?</span>
            <div>
              <strong>接下来检查你的理解</strong>
              <p>{{ result.check.question }}</p>
              <textarea v-model="checkAnswer" maxlength="4000" rows="4" :disabled="checking" placeholder="用自己的话回答，不必追求术语完整。" aria-label="理解检查答案"></textarea>
              <button type="button" :disabled="checking || !checkAnswer.trim()" @click="submitCheck">{{ checking ? '正在评分...' : '提交答案' }}</button>
            </div>
          </div>
          <div v-else-if="result.nextAction === 'PRACTICE' || result.nextAction === 'RECHECK'" class="teaching-check-result" :class="{ passed: result.passed }">
            <strong>{{ result.passed ? '理解检查通过' : '建议回顾后再检查' }} · {{ result.score }}/{{ result.maxScore }}</strong>
            <p>{{ result.feedback }}</p>
            <small>{{ result.saved ? `已加入 ${result.recordDate} 学习记录。` : '评分已返回，但学习记录暂时未保存。' }}</small>
          </div>

          <section v-if="result.stage === 'REVIEW' && result.review" class="teaching-review-card">
            <header>
              <span class="teaching-review-mark">R</span>
              <div><small>REVIEW</small><h3>针对性复习</h3></div>
            </header>
            <dl>
              <div><dt>薄弱点</dt><dd>{{ result.review.weakPoint }}</dd></div>
              <div><dt>关键解释</dt><dd>{{ result.review.explanation }}</dd></div>
              <div><dt>复习建议</dt><dd>{{ result.review.suggestion }}</dd></div>
            </dl>
            <button type="button" :disabled="loading" @click="recheckAfterReview">{{ loading ? '正在重新讲解...' : '复习后重新检查' }}</button>
          </section>

          <section v-if="result.practice && (result.nextAction === 'PRACTICE' || result.practice.status === 'COMPLETED')" class="teaching-practice-card">
            <header>
              <span class="teaching-practice-mark">P</span>
              <div><small>PRACTICE</small><h3>{{ result.practice.status === 'COMPLETED' ? '实践结果' : '把概念带到真实任务' }}</h3></div>
            </header>
            <p class="teaching-practice-question">{{ result.practice.question }}</p>
            <p class="teaching-practice-instruction">请完成下面三步，不需要写成长文章。</p>
            <template v-if="result.practice.status === 'PENDING'">
              <div class="teaching-practice-fields">
                <label><span>1. 你想让 Agent 完成什么任务？</span><textarea v-model="practiceScene" maxlength="1200" rows="2" :disabled="practicing" placeholder="例如：查找公司的报销规定并回答同事的问题。"></textarea></label>
                <label><span>2. 它需要调用什么工具？</span><textarea v-model="practiceTool" maxlength="800" rows="2" :disabled="practicing" placeholder="例如：搜索知识库。"></textarea></label>
                <label><span>3. 工具结果有什么用？</span><textarea v-model="practiceResult" maxlength="1200" rows="2" :disabled="practicing" placeholder="例如：找到相关制度后，帮助 Agent 判断如何组织回答。"></textarea></label>
              </div>
              <small class="teaching-practice-hint">请先写下任务场景和工具使用方式</small>
              <button type="button" :disabled="practicing || !practiceScene.trim() || !practiceTool.trim() || !practiceResult.trim()" @click="submitPractice">{{ practicing ? '正在评估...' : '提交答案' }}</button>
            </template>
            <div v-else class="teaching-practice-complete" :class="{ passed: result.practice.passed }">
              <strong>{{ result.practice.passed ? '实践完成' : '实践需要补充' }} · {{ result.practice.score }}/{{ result.practice.maxScore }}</strong>
              <p>{{ result.practice.feedback }}</p>
            </div>
          </section>

           <div v-if="result.traces?.length" class="teaching-trace">
            <button type="button" @click="showTrace = !showTrace">{{ showTrace ? '收起工具链路' : '查看工具链路' }}</button>
            <ul v-if="showTrace">
              <li v-for="trace in result.traces" :key="`${trace.step}-${trace.toolName}`">
               <span><b>#{{ trace.step }}</b> {{ trace.toolName }}</span>
                <span :class="trace.status.toLowerCase()">
                  {{ traceStatusLabels[trace.status] || trace.status }}
                  <small v-if="trace.detail"> · {{ trace.detail }}</small>
                </span>
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
            <div :class="{ active: result?.stage === 'EXPLAIN' }"><span>1</span><p><strong>讲解</strong><small>建立概念模型</small></p></div>
            <div :class="{ active: result?.stage === 'CHECK' }"><span>2</span><p><strong>检查</strong><small>回答一个理解问题</small></p></div>
            <div :class="{ active: result?.nextAction === 'PRACTICE' || result?.practice?.status === 'COMPLETED' }"><span>3</span><p><strong>练习</strong><small>迁移到真实任务</small></p></div>
            <div :class="{ active: result?.stage === 'REVIEW' }"><span>4</span><p><strong>复习</strong><small>修正薄弱概念</small></p></div>
          </div>
        </section>
        <section class="teaching-side-card teaching-source-card">
          <header><span class="teaching-side-label">知识依据</span><span v-if="result?.sources?.length">{{ result.sources.length }} 条</span></header>
          <SourcePanel :sources="result?.sources || []" :empty-message="sourceEmptyMessage" />
        </section>
        <section class="teaching-side-card teaching-history-card">
          <header><span class="teaching-side-label">长期进度</span><span v-if="teachingProgress.length">{{ teachingProgress.length }} 个主题</span></header>
          <div v-if="currentTopicProgress" class="teaching-history-current">
            <strong>{{ currentTopicProgress.topic }}</strong>
            <span>最佳掌握度 {{ currentTopicProgress.masteryPercent }}%</span>
            <small>最近 {{ currentTopicProgress.latestScore }}/{{ currentTopicProgress.maxScore }} · 通过 {{ currentTopicProgress.passedAttempts }} 次</small>
          </div>
          <div v-else-if="!teachingProgress.length" class="teaching-history-empty">完成一次理解检查后，这里会保留主题历史进度。</div>
          <ul v-else class="teaching-history-list">
            <li v-for="item in teachingProgress.slice(0, 4)" :key="item.topic">
              <span><strong>{{ item.topic }}</strong><small>最近 {{ item.latestDate }}</small></span>
              <b>{{ item.masteryPercent }}%</b>
            </li>
          </ul>
        </section>
        <section v-if="reviewTopics.length" class="teaching-side-card teaching-review-topics-card">
          <header><span class="teaching-side-label">建议复习</span><span>优先补弱项</span></header>
          <p>根据历史检查结果，先回顾这些还不稳定的主题。</p>
          <ul class="teaching-review-topics-list">
            <li v-for="item in reviewTopics" :key="item.topic">
              <div><strong>{{ item.topic }}</strong><small>最近 {{ item.latestScore }}/{{ item.maxScore }} · 最佳 {{ item.masteryPercent }}%</small></div>
              <button type="button" :disabled="loading" @click="reviewTopic(item)">开始复习</button>
            </li>
          </ul>
        </section>
        <section class="teaching-side-card teaching-safety-card">
          <span class="teaching-side-label">安全边界</span>
          <h2>资料是依据，不是指令。</h2>
          <p>教学 Agent 只读当前空间，工具参数有边界，知识库内容不会改变它的权限或执行规则。</p>
        </section>
      </aside>
    </section>
  </main>
</template>
