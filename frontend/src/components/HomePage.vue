<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import OnboardingTour from './OnboardingTour.vue'
import { fetchWorkbenchStatus } from '../api/statusApi'
import { fetchUsageSummary } from '../api/usageApi'
import { fetchDocuments } from '../api/documentApi'
import { fetchLearningRecords } from '../api/learningRecordApi'
import { fetchPersonalModels } from '../api/modelConfigApi'

const props = defineProps({
  user: { type: Object, required: true },
  workspaceId: { type: String, default: '' },
  workspace: { type: Object, default: null }
})
const emit = defineEmits(['navigate'])

const entries = [
  { section: 'assistant', title: 'AI 学习助手', desc: '提问、拆解、练习，把知识变成真正会用的能力', icon: 'M12 3a7 7 0 0 0-7 7v2.5a3.5 3.5 0 0 0 3.5 3.5H10v2H8.5a1.5 1.5 0 0 0 0 3H12a3 3 0 0 0 3-3v-2h.5a3.5 3.5 0 0 0 3.5-3.5V10a7 7 0 0 0-7-7Z' },
  { section: 'knowledge', title: '知识库', desc: '导入文档，构建属于你的专属知识空间', icon: 'M4 6.5 12 3l8 3.5-8 3.5-8-3.5ZM6 9.5l6 2.7 6-2.7M6 13l6 2.7 6-2.7M6 16.5l6 2.7 6-2.7' },
  { section: 'records', title: '学习记录', desc: '自动沉淀每日学习，随时回顾与复习', icon: 'M5.5 4.5A2.5 2.5 0 0 1 8 2h9.5a1 1 0 0 1 1 1v15.5a.5.5 0 0 1-.8.4L15 17l-2.7 1.9a.5.5 0 0 1-.6 0L9 17l-2.7 1.9a.5.5 0 0 1-.8-.4V4.5Z' }
]

const steps = [
  { title: '导入知识库', desc: '上传 PDF、Markdown 等文档，建立你的知识空间' },
  { title: '提问与学习', desc: '围绕资料提问，主题教学帮你真正理解一个概念' },
  { title: '沉淀学习记录', desc: '学习自动沉淀为每日记录，随时回顾薄弱点' }
]

// ---- 工作台真实数据：全部来自接口，不再用本地假进度 ----
const status = ref(null)
const usageRecent = ref(null)
const usageLong = ref(null)
const documents = ref([])
const recentRecord = ref(null)
const personalModelCount = ref(0)
const dashboardLoading = ref(true)
const dashboardFailed = ref(false)

const docCount = computed(() => documents.value.length)
const chunkCount = computed(() => documents.value.reduce((sum, item) => sum + (Number(item.chunkCount) || 0), 0))
const pendingDocCount = computed(() => documents.value.filter(item => item.indexStatus && item.indexStatus !== 'INDEXED').length)
const askCount = computed(() => usageRecent.value?.totals?.calls || 0)
const answeredWithSources = computed(() => {
  const retrieval = usageRecent.value?.retrieval
  if (!retrieval || !retrieval.assistantMessages) return null
  return Math.round((retrieval.answersWithSources / retrieval.assistantMessages) * 100)
})
const askedEver = computed(() => (usageLong.value?.totals?.calls || 0) > 0)
const modelName = computed(() => {
  const model = status.value?.model || ''
  if (!model) return '—'
  return model.includes('/') ? model.split('/').pop() : model
})
// 检索链路状态：区分「在线」「已配置但不可达」「未配置回退」，不要只说 "Chroma"
const retrievalState = computed(() => {
  const current = status.value
  if (!current) return { tone: 'muted', label: '检测中', detail: '正在读取系统状态' }
  if (current.chromaReachable) return { tone: 'ok', label: '本地检索正常', detail: 'Chroma 在线' }
  if (current.chromaConfigured) return { tone: 'bad', label: '本地检索不可达', detail: '已降级到内存回退' }
  return { tone: 'warn', label: '内存回退模式', detail: '未配置 Chroma' }
})
const workspaceLabel = computed(() => props.workspace?.name || '当前空间')

// ---- 新手任务：真实检测，不再手动打勾 ----
const tasks = computed(() => [
  { id: 'import', title: '导入第一篇文档', desc: '在「知识库 → 文档库」上传资料，系统会自动解析、切片并建立索引。', action: '去导入', section: 'knowledge', done: docCount.value > 0 },
  { id: 'model', title: '配置个人对话模型', desc: '在「AI 学习助手 → 管理模型」选择或新增属于你自己的对话模型。', action: '去配置', section: 'assistant', done: personalModelCount.value > 0 },
  { id: 'ask', title: '提出第一个问题', desc: '围绕资料提问，回答会带出处；提问后自动沉淀学习记录。', action: '去提问', section: 'assistant', done: askedEver.value }
])
const completedCount = computed(() => tasks.value.filter(task => task.done).length)
const progressPercent = computed(() => Math.round((completedCount.value / tasks.value.length) * 100))
const allDone = computed(() => tasks.value.length > 0 && completedCount.value === tasks.value.length)

async function loadDashboard() {
  dashboardLoading.value = true
  dashboardFailed.value = false
  const [statusResult, usageResult, usageLongResult, documentResult, recordResult, modelResult] = await Promise.allSettled([
    fetchWorkbenchStatus(),
    fetchUsageSummary(7),
    fetchUsageSummary(90),
    fetchDocuments(props.workspaceId),
    props.workspaceId ? fetchLearningRecords(props.workspaceId) : Promise.resolve([]),
    fetchPersonalModels()
  ])

  status.value = statusResult.status === 'fulfilled' ? statusResult.value : null
  usageRecent.value = usageResult.status === 'fulfilled' ? usageResult.value : null
  usageLong.value = usageLongResult.status === 'fulfilled' ? usageLongResult.value : null
  documents.value = documentResult.status === 'fulfilled' && Array.isArray(documentResult.value) ? documentResult.value : []
  const records = recordResult.status === 'fulfilled' && Array.isArray(recordResult.value) ? recordResult.value : []
  recentRecord.value = records[0] || null
  personalModelCount.value = modelResult.status === 'fulfilled' && Array.isArray(modelResult.value) ? modelResult.value.length : 0

  // 四项核心数据全挂才算失败，避免 embedding/模型单点异常拖垮整屏
  dashboardFailed.value = [statusResult, usageResult, documentResult].every(result => result.status === 'rejected')
  // 什么都没用过 = 新用户：默认展开指南；老用户默认收起
  if (docCount.value === 0 && !askedEver.value) guideOpen.value = true
  dashboardLoading.value = false
}

function goTask(task) {
  emit('navigate', task.section)
}

// ---- 新手指南（老用户默认收起） ----
const guideOpen = ref(false)

const concepts = [
  { badge: '间', title: '知识空间', body: '个人空间仅你可见；团队空间成员共享；公共空间所有用户可读。切换空间后，学习与检索都基于该空间内的文档。' },
  { badge: '模', title: '两类模型', body: '对话模型决定 AI 怎么回答（每人可配自己的）；知识库 embedding 模型决定文档怎么被检索（由系统统一配置，普通用户无需关心）。' },
  { badge: '诊', title: '检索诊断', body: '进阶开发者工具：输入问题可回显 RAG 实际检索到的候选切片与相似度分数，方便调优 topK 与阈值。' }
]

const faqs = [
  { q: '支持导入哪些格式的文档？', a: 'PDF、Markdown、Word(.docx/.doc)、Excel(.xlsx/.xls)、PowerPoint(.pptx/.ppt)、纯文本(.txt)、CSV、JSON、XML、RTF、ODT，以及带文字的图片（OCR 识别）。导入后系统会自动切片并建立向量索引。' },
  { q: '为什么检索不到我导入的内容？', a: '先看首页「检索链路」是否显示正常；若显示不可达，说明向量库已降级到内存回退，此时只能用近期内存中的数据。其余情况依次确认：① 文档已成功导入且索引进度到 100%；② 顶部知识空间是否选对；③ 用「AI 学习助手」的检索诊断查看实际命中的切片与分数。' },
  { q: '怎么更换对话模型？', a: '进入「AI 学习助手」，在对话框附近点「管理模型」，即可在模型池中选择或自定义你自己的对话模型；设置后仅对你本人生效。' },
  { q: '个人 / 团队 / 公共 空间有什么区别？', a: '个人空间只有你自己能读能写；团队空间由管理员创建、成员共享；公共空间所有用户可读。文档与索引都按空间隔离。' },
  { q: '对话模型和知识库模型是同一个吗？', a: '不是。对话模型（Chat）负责生成回答，你可以自选；知识库 embedding 模型负责把文档转成向量用于检索，由系统统一配置。' }
]
const openFaq = ref(-1)
function toggleFaq(index) {
  openFaq.value = openFaq.value === index ? -1 : index
}

// ---- 交互式新手引导 Tour ----
const showTour = ref(false)
function startTour() {
  // 指南默认收起时先展开，否则 Tour 找不到 system-intro / concepts / faq 的锚点
  guideOpen.value = true
  nextTick(() => { showTour.value = true })
}

onMounted(() => {
  loadDashboard()
  try {
    if (!localStorage.getItem('shihai_tour_done_v1')) startTour()
  } catch { /* 忽略隐私模式读取失败 */ }
})

watch(() => props.workspaceId, () => loadDashboard())
</script>

<template>
  <main class="home-page">
    <section class="home-hero" data-reveal>
      <div class="home-hero-copy">
        <span class="home-eyebrow">识海 · 学习工作室</span>
        <h1>把知识变成<br><em>真正会用的能力。</em></h1>
        <p>{{ user.userName }}，欢迎回来。在这里导入资料、提问学习，让每一次学习都沉淀成可复用的知识资产。</p>
        <div class="home-hero-actions">
          <button type="button" class="home-primary" @click="emit('navigate', 'assistant')">开始学习</button>
          <button type="button" class="home-secondary" @click="emit('navigate', 'knowledge')">先导入资料</button>
        </div>
      </div>
      <div class="home-hero-visual" aria-hidden="true">
        <div class="home-orbit">✦</div>
        <div class="home-visual-card home-visual-card-a"><span>LEARN</span><strong>RAG → Agent</strong></div>
        <div class="home-visual-card home-visual-card-b"><span>REVIEW</span><strong>检查 · 实践 · 复习</strong></div>
      </div>
    </section>

    <!-- 工作台概览：全部真实数据，异常会立刻显形 -->
    <section class="home-metrics" aria-label="工作台概览">
      <header class="home-metrics-head">
        <h2>{{ workspaceLabel }}</h2>
        <p v-if="dashboardLoading">正在读取状态…</p>
        <button v-else-if="dashboardFailed" type="button" class="home-metrics-retry" @click="loadDashboard">状态读取失败，重试</button>
        <p v-else>数据实时来自知识库、检索与用量统计。</p>
      </header>
      <div class="home-metric-grid">
        <button type="button" class="home-metric" @click="emit('navigate', 'knowledge')">
          <span class="home-metric-label">本空间文档</span>
          <strong class="home-metric-value">{{ docCount }}<small>篇</small></strong>
          <span class="home-metric-sub">
            {{ chunkCount }} 个切片
            <em v-if="pendingDocCount"> · {{ pendingDocCount }} 篇处理中</em>
          </span>
        </button>
        <div class="home-metric" :class="`tone-${retrievalState.tone}`">
          <span class="home-metric-label">检索链路</span>
          <strong class="home-metric-value is-text">{{ retrievalState.label }}</strong>
          <span class="home-metric-sub">{{ retrievalState.detail }}</span>
        </div>
        <button type="button" class="home-metric" @click="emit('navigate', 'assistant')">
          <span class="home-metric-label">近 7 天问答</span>
          <strong class="home-metric-value">{{ askCount }}<small>次</small></strong>
          <span class="home-metric-sub">{{ answeredWithSources === null ? '暂无带出处记录' : `带出处 ${answeredWithSources}%` }}</span>
        </button>
        <button type="button" class="home-metric" @click="emit('navigate', 'assistant')">
          <span class="home-metric-label">对话模型</span>
          <strong class="home-metric-value is-text" :title="status?.model || ''">{{ modelName }}</strong>
          <span class="home-metric-sub">{{ status?.provider || '未配置' }}</span>
        </button>
      </div>
    </section>

    <!-- 继续最近学习 -->
    <section v-if="recentRecord" class="home-resume">
      <div class="home-resume-copy">
        <span class="home-eyebrow">继续学习 · {{ recentRecord.date }}</span>
        <strong>{{ recentRecord.title || '最近一次学习记录' }}</strong>
      </div>
      <button type="button" class="home-secondary home-resume-action" @click="emit('navigate', 'records')">查看记录 →</button>
    </section>

    <section class="home-entries" data-reveal>
      <header><h2>从这里开始</h2><p>三个核心能力，覆盖从知识导入到掌握的全过程。</p></header>
      <div class="home-entry-grid">
        <button v-for="entry in entries" :key="entry.section" type="button" @click="emit('navigate', entry.section)">
          <span class="home-entry-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path :d="entry.icon" /></svg></span>
          <strong>{{ entry.title }}</strong>
          <p>{{ entry.desc }}</p>
          <b aria-hidden="true">↗</b>
        </button>
      </div>
    </section>

    <!-- 上手进度：由真实行为决定，全部完成时只留一行 -->
    <section v-if="!allDone" class="home-checklist" data-reveal>
      <header>
        <h2>上手进度</h2>
        <p>不用手动勾选——导入过文档、配过模型、提过问题，这里会自动点亮。</p>
      </header>
      <div class="home-checklist-progress">
        <div class="home-checklist-track"><span class="home-checklist-fill" :style="{ width: progressPercent + '%' }"></span></div>
        <span class="home-checklist-count">{{ completedCount }} / {{ tasks.length }}</span>
      </div>
      <ul class="home-checklist-items">
        <li v-for="task in tasks" :key="task.id" :class="{ done: task.done }">
          <span class="home-check-box" :aria-hidden="true">
            <svg v-if="task.done" viewBox="0 0 24 24"><path d="M5 12.5 10 17.5 19 7" /></svg>
          </span>
          <div class="home-check-body">
            <strong>{{ task.title }}</strong>
            <p>{{ task.desc }}</p>
          </div>
          <button v-if="!task.done" type="button" class="home-check-go" @click="goTask(task)">{{ task.action }} →</button>
          <span v-else class="home-check-done">已完成</span>
        </li>
      </ul>
    </section>
    <p v-else class="home-checklist-done">✓ 上手任务已全部完成</p>

    <!-- 新手指南：老用户默认收起 -->
    <section class="home-guide">
      <button type="button" class="home-guide-toggle" :aria-expanded="guideOpen" @click="guideOpen = !guideOpen">
        <span class="home-guide-title">新手指南<small>系统简介 · 三步上手 · 核心概念 · 常见问题</small></span>
        <svg viewBox="0 0 24 24" class="home-guide-caret" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
      <div v-show="guideOpen" class="home-guide-body">
        <section class="home-system-intro">
          <div class="home-system-intro-copy">
            <span class="home-eyebrow">系统简介 · KNOWLEDGE BASE</span>
            <h2>你的知识，不只是被保存，而是随时可以被调用。</h2>
            <p>识海会把资料变成可检索、可理解、可复用的知识资产。你可以上传文档到个人或团队空间，系统自动解析、切分并建立索引，再让 AI 基于你有权限访问的内容回答问题。</p>
          </div>
          <div class="home-system-flow" aria-label="知识沉淀流程">
            <div><span>01</span><strong>导入资料</strong><small>PDF、Word、Markdown、表格、图片等</small></div>
            <b aria-hidden="true">→</b>
            <div><span>02</span><strong>自动建立索引</strong><small>解析、OCR、切分、向量化</small></div>
            <b aria-hidden="true">→</b>
            <div><span>03</span><strong>提问与沉淀</strong><small>基于授权知识回答并形成学习记录</small></div>
          </div>
          <div class="home-system-tags" aria-label="知识库能力">
            <span>个人 / 团队 / 组织 / 公共空间</span>
            <span>语义 + 关键词混合检索</span>
            <span>来源与页码追踪</span>
            <span>异步任务与失败重试</span>
          </div>
        </section>

        <section class="home-steps">
          <header><h2>三步上手</h2><p>第一次使用，跟着走一遍就懂了。</p></header>
          <div class="home-step-grid">
            <div v-for="(step, index) in steps" :key="step.title" class="home-step">
              <span class="home-step-index">{{ index + 1 }}</span>
              <strong>{{ step.title }}</strong>
              <p>{{ step.desc }}</p>
            </div>
          </div>
        </section>

        <section class="home-concepts">
          <header><h2>先搞懂三个概念</h2><p>理解它们，后面就不会迷路。</p></header>
          <div class="home-concept-grid">
            <article v-for="concept in concepts" :key="concept.title" class="home-concept">
              <span class="home-concept-badge" aria-hidden="true">{{ concept.badge }}</span>
              <strong>{{ concept.title }}</strong>
              <p>{{ concept.body }}</p>
            </article>
          </div>
        </section>

        <section class="home-faq">
          <header><h2>常见问题</h2><p>新手最常问的几件事。</p></header>
          <div class="home-faq-list">
            <div v-for="(faq, index) in faqs" :key="index" class="home-faq-item" :class="{ open: openFaq === index }">
              <button type="button" class="home-faq-q" :aria-expanded="openFaq === index" @click="toggleFaq(index)">
                <span>{{ faq.q }}</span>
                <svg viewBox="0 0 24 24" class="home-faq-caret" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
              </button>
              <div v-show="openFaq === index" class="home-faq-a"><p>{{ faq.a }}</p></div>
            </div>
          </div>
        </section>

        <div class="home-guide-actions">
          <button type="button" class="home-checklist-reset" @click="startTour">重看交互引导</button>
        </div>
      </div>
    </section>
  </main>

  <OnboardingTour :open="showTour" @close="showTour = false" />
</template>

<style scoped>
/* ---- 工作台概览 ---- */
.home-metrics {
  margin-top: 44px;
  border: 1px solid #e2e7dc;
  border-radius: 18px;
  padding: 20px 22px 22px;
  background: linear-gradient(180deg, #fffef9, #fafbf6);
  box-shadow: 0 12px 30px rgba(56, 64, 49, .05);
}
.home-metrics-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-bottom: 16px;
}
.home-metrics-head h2 {
  margin: 0;
  color: var(--site-ink, #17221b);
  font-size: 15px;
  letter-spacing: -.02em;
}
.home-metrics-head p {
  margin: 0;
  color: var(--site-muted, #718074);
  font-size: 12px;
}
.home-metrics-retry {
  border: 1px solid #d9c4b6;
  border-radius: 8px;
  padding: 4px 10px;
  color: #a34a2a;
  background: #fdf4ec;
  font-size: 12px;
  cursor: pointer;
}
.home-metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.home-metric {
  display: grid;
  gap: 6px;
  align-content: start;
  min-height: 96px;
  border: 1px solid #e7eae3;
  border-radius: 13px;
  padding: 14px 15px;
  background: #fffef9;
  text-align: left;
  font: inherit;
  cursor: default;
}
button.home-metric { cursor: pointer; transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease; }
button.home-metric:hover {
  border-color: #a8c4a9;
  box-shadow: 0 10px 22px rgba(56, 64, 49, .08);
  transform: translateY(-2px);
}
.home-metric-label {
  color: var(--site-muted, #718074);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .06em;
}
.home-metric-value {
  color: var(--site-ink, #17221b);
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.home-metric-value small {
  margin-left: 3px;
  color: var(--site-muted, #718074);
  font-size: 12px;
  font-weight: 600;
}
.home-metric-value.is-text {
  font-size: 15px;
  line-height: 1.4;
  word-break: break-word;
}
.home-metric-sub {
  color: var(--site-muted, #718074);
  font-size: 11px;
  line-height: 1.5;
}
.home-metric-sub em { font-style: normal; color: #a8701a; }

/* 检索状态：正常绿 / 未配置琥珀 / 不可达红 */
.home-metric.tone-ok { border-color: #c3dcc4; background: #f6faf3; }
.home-metric.tone-ok .home-metric-value { color: #1d5939; }
.home-metric.tone-warn { border-color: #e8d9b6; background: #fdfaf0; }
.home-metric.tone-warn .home-metric-value { color: #96660f; }
.home-metric.tone-bad { border-color: #e8c4bb; background: #fdf4f1; }
.home-metric.tone-bad .home-metric-value { color: #b4402f; }
.home-metric.tone-muted .home-metric-value { color: var(--site-muted, #718074); font-size: 15px; }

/* ---- 继续学习 ---- */
.home-resume {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 16px;
  border: 1px solid #d8e2d6;
  border-radius: 14px;
  padding: 14px 18px;
  background: linear-gradient(135deg, #f5f8ef, #fffef9);
}
.home-resume-copy { display: grid; gap: 4px; min-width: 0; }
.home-resume-copy .home-eyebrow { margin: 0; font-size: 10px; }
.home-resume-copy strong {
  color: var(--site-ink, #17221b);
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.home-resume-action { min-height: 34px; padding: 0 14px; font-size: 12px; }

/* ---- 上手进度 ---- */
.home-checklist-done {
  margin: 26px 0 0;
  border: 1px dashed #c8d8c6;
  border-radius: 12px;
  padding: 12px 16px;
  color: #2f7a52;
  background: #f6faf3;
  font-size: 12px;
}
.home-check-done {
  flex: 0 0 auto;
  color: var(--site-green, #1d5939);
  font-size: 12px;
  font-weight: 650;
}

/* ---- 新手指南折叠 ---- */
.home-guide {
  margin-top: 36px;
  border-top: 1px solid #e7eae3;
  padding-top: 18px;
}
.home-guide-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 0;
  padding: 8px 2px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.home-guide-title {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 10px;
  color: var(--site-ink, #17221b);
  font-size: 15px;
  font-weight: 700;
}
.home-guide-title small {
  color: var(--site-muted, #718074);
  font-size: 11px;
  font-weight: 500;
}
.home-guide-caret {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  fill: none;
  stroke: var(--site-muted, #718074);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform .2s ease;
}
.home-guide-toggle[aria-expanded="true"] .home-guide-caret { transform: rotate(180deg); }
.home-guide-toggle:hover .home-guide-title { color: var(--site-green, #1d5939); }
.home-guide-body { padding-top: 8px; }
.home-guide-body .home-system-intro { margin-top: 20px; }
.home-guide-actions { margin-top: 22px; text-align: right; }

/* ---- 系统简介 ---- */
.home-system-intro {
  display: grid;
  grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr);
  gap: 26px 46px;
  align-items: center;
  border: 1px solid #d8e2d6;
  border-radius: 22px;
  padding: 30px;
  background: linear-gradient(135deg, #f5f8ef, #fffef9 58%, #f1f5ed);
  box-shadow: 0 18px 38px rgba(56, 64, 49, .06);
}
.home-system-intro-copy h2 {
  max-width: 460px;
  margin: 10px 0 0;
  color: var(--site-ink, #17221b);
  font-size: clamp(24px, 3vw, 34px);
  line-height: 1.18;
  letter-spacing: -.04em;
}
.home-system-intro-copy > p {
  max-width: 520px;
  margin: 14px 0 0;
  color: var(--site-muted, #718074);
  font-size: 13px;
  line-height: 1.8;
}
.home-system-flow {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 12px;
  align-items: center;
}
.home-system-flow > div {
  display: grid;
  gap: 6px;
  min-height: 128px;
  align-content: center;
  border: 1px solid #dce5d8;
  border-radius: 14px;
  padding: 15px;
  background: rgba(255, 255, 255, .72);
}
.home-system-flow span {
  color: var(--site-green, #1d5939);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .14em;
}
.home-system-flow strong {
  color: var(--site-ink-soft, #314438);
  font-size: 14px;
}
.home-system-flow small {
  color: var(--site-muted, #718074);
  font-size: 11px;
  line-height: 1.55;
}
.home-system-flow > b {
  color: #8aa18c;
  font-size: 20px;
  font-weight: 500;
}
.home-system-tags {
  display: flex;
  grid-column: 1 / -1;
  flex-wrap: wrap;
  gap: 8px;
}
.home-system-tags span {
  border-radius: 999px;
  padding: 7px 10px;
  color: #55705a;
  background: rgba(224, 235, 220, .72);
  font-size: 11px;
}

.home-checklist,
.home-concepts,
.home-faq { margin-top: 36px; }
.home-checklist > header,
.home-concepts > header,
.home-faq > header { margin-bottom: 22px; }
.home-checklist h2,
.home-concepts h2,
.home-faq h2 {
  margin: 0;
  color: var(--site-ink, #17221b);
  font-size: 22px;
  letter-spacing: -.03em;
}
.home-checklist header p,
.home-concepts header p,
.home-faq header p {
  margin: 6px 0 0;
  color: var(--site-muted, #718074);
  font-size: 13px;
}
.home-steps { margin-top: 36px; }
.home-steps > header { margin-bottom: 22px; }
.home-steps h2 { margin: 0; color: var(--site-ink, #17221b); font-size: 22px; letter-spacing: -.03em; }
.home-steps header p { margin: 6px 0 0; color: var(--site-muted, #718074); font-size: 13px; }

/* ---- 任务清单 ---- */
.home-checklist-progress {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
}
.home-checklist-track {
  flex: 1;
  height: 8px;
  border-radius: 999px;
  background: #e9ece6;
  overflow: hidden;
}
.home-checklist-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--site-green, #1d5939), #2f7a52);
  transition: width .35s ease;
}
.home-checklist-count {
  font-size: 12px;
  font-weight: 650;
  color: var(--site-ink-soft, #314438);
  white-space: nowrap;
}
.home-checklist-reset {
  border: 1px solid #d7ddd1;
  background: #fffef9;
  color: var(--site-muted, #718074);
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 8px;
  cursor: pointer;
}
.home-checklist-reset:hover {
  border-color: #7ba287;
  color: var(--site-green, #1d5939);
  background: var(--site-green-soft, #e5eee1);
}
.home-checklist-items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}
.home-checklist-items li {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border: 1px solid #e7eae3;
  border-radius: 14px;
  background: #fffef9;
  transition: border-color .2s ease, box-shadow .2s ease, transform .2s ease;
}
.home-checklist-items li:hover {
  border-color: #a8c4a9;
  box-shadow: 0 14px 30px rgba(56, 64, 49, .08);
  transform: translateY(-2px);
}
.home-checklist-items li.done {
  background: var(--site-green-soft, #e5eee1);
  border-color: #b9d3bd;
}
.home-check-box {
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: 1.5px solid #c2cabd;
  background: #fff;
  display: grid;
  place-items: center;
  padding: 0;
}
.home-check-box svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: #fff;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.home-checklist-items li.done .home-check-box {
  background: var(--site-green, #1d5939);
  border-color: var(--site-green, #1d5939);
}
.home-check-body { flex: 1; min-width: 0; }
.home-check-body strong {
  display: block;
  font-size: 15px;
  color: var(--site-ink, #17221b);
}
.home-check-body p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--site-muted, #718074);
}
.home-check-go {
  flex: 0 0 auto;
  border: 1px solid #cbd4c5;
  background: #fff;
  color: var(--site-green, #1d5939);
  font-size: 12px;
  font-weight: 650;
  padding: 7px 12px;
  border-radius: 9px;
  white-space: nowrap;
  cursor: pointer;
}
.home-check-go:hover {
  border-color: #7ba287;
  background: var(--site-green-soft, #e5eee1);
}

/* ---- 核心概念 ---- */
.home-concept-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.home-concept {
  padding: 20px;
  border: 1px solid #e7eae3;
  border-radius: 14px;
  background: #fffef9;
}
.home-concept-badge {
  display: inline-grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--site-green-soft, #e5eee1);
  color: var(--site-green, #1d5939);
  font-weight: 800;
  font-size: 15px;
  margin-bottom: 12px;
}
.home-concept strong {
  display: block;
  font-size: 15px;
  color: var(--site-ink, #17221b);
  margin-bottom: 6px;
}
.home-concept p {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--site-muted, #718074);
}

/* ---- FAQ ---- */
.home-faq-list {
  border: 1px solid #e7eae3;
  border-radius: 14px;
  overflow: hidden;
  background: #fffef9;
}
.home-faq-item + .home-faq-item { border-top: 1px solid #eef0ea; }
.home-faq-q {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border: 0;
  background: transparent;
  text-align: left;
  font-size: 14px;
  font-weight: 600;
  color: var(--site-ink, #17221b);
  cursor: pointer;
}
.home-faq-q:hover { color: var(--site-green, #1d5939); }
.home-faq-caret {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  fill: none;
  stroke: var(--site-muted, #718074);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform .2s ease;
}
.home-faq-item.open .home-faq-caret {
  transform: rotate(180deg);
  stroke: var(--site-green, #1d5939);
}
.home-faq-a { padding: 0 18px 16px; }
.home-faq-a p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--site-ink-soft, #314438);
}

@media (max-width: 900px) {
  .home-metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .home-metrics { margin-top: 30px; padding: 16px; }
  .home-metric { min-height: 0; }
  .home-system-intro { grid-template-columns: 1fr; gap: 22px; padding: 22px; }
  .home-system-flow { grid-template-columns: 1fr; }
  .home-system-flow > div { min-height: 0; }
  .home-system-flow > b { justify-self: center; transform: rotate(90deg); }
  .home-concept-grid { grid-template-columns: 1fr; }
  .home-checklist-items li { flex-wrap: wrap; }
  .home-check-go { margin-left: 40px; }
  .home-guide-actions { text-align: left; }
}
@media (max-width: 520px) {
  .home-metric-grid { grid-template-columns: 1fr; }
}
</style>
