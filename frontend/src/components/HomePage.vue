<script setup>
import { ref, computed, onMounted } from 'vue'
import OnboardingTour from './OnboardingTour.vue'

const props = defineProps({
  user: { type: Object, required: true }
})
const emit = defineEmits(['navigate'])

const entries = [
  { section: 'assistant', title: 'AI 学习助手', desc: '提问、拆解、练习，把知识变成真正会用的能力', icon: 'M12 3a7 7 0 0 0-7 7v2.5a3.5 3.5 0 0 0 3.5 3.5H10v2H8.5a1.5 1.5 0 0 0 0 3H12a3 3 0 0 0 3-3v-2h.5a3.5 3.5 0 0 0 3.5-3.5V10a7 7 0 0 0-7-7Z' },
  { section: 'knowledge', title: '知识库管理', desc: '导入文档，构建属于你的专属知识空间', icon: 'M4 6.5 12 3l8 3.5-8 3.5-8-3.5ZM6 9.5l6 2.7 6-2.7M6 13l6 2.7 6-2.7M6 16.5l6 2.7 6-2.7' },
  { section: 'records', title: '学习记录', desc: '自动沉淀每日学习，随时回顾与复习', icon: 'M5.5 4.5A2.5 2.5 0 0 1 8 2h9.5a1 1 0 0 1 1 1v15.5a.5.5 0 0 1-.8.4L15 17l-2.7 1.9a.5.5 0 0 1-.6 0L9 17l-2.7 1.9a.5.5 0 0 1-.8-.4V4.5Z' }
]

const steps = [
  { title: '导入知识库', desc: '上传 PDF、Markdown 等文档，建立你的知识空间' },
  { title: '提问与学习', desc: '围绕资料提问，主题教学帮你真正理解一个概念' },
  { title: '沉淀学习记录', desc: '学习自动沉淀为每日记录，随时回顾薄弱点' }
]

// ---- 新手任务清单（进度存本机，不区分角色） ----
const STORAGE_KEY = 'shihai_onboarding_tasks_v1'
const tasks = [
  { id: 'space', title: '认识你的知识空间', desc: '顶部可切换 个人 / 团队 / 公共 空间，文档只存在于当前所选空间内。', action: '去知识库', section: 'knowledge' },
  { id: 'import', title: '导入第一篇文档', desc: '在「知识库管理」上传 PDF、Markdown、Word 或图片，系统会自动切片并建立索引。', action: '去导入', section: 'knowledge' },
  { id: 'model', title: '配置个人对话模型', desc: '在「AI 学习助手」里点「管理模型」，选择或设置属于你自己的对话模型。', action: '去配置', section: 'assistant' },
  { id: 'ask', title: '提出第一个问题', desc: '围绕你的资料提问，系统会基于知识库检索内容并给出带出处的回答。', action: '去提问', section: 'assistant' }
]
const doneMap = ref({})
const completedCount = computed(() => tasks.filter(t => doneMap.value[t.id]).length)
const progressPercent = computed(() => Math.round((completedCount.value / tasks.length) * 100))
const allDone = computed(() => tasks.length > 0 && completedCount.value === tasks.length)

function loadDone() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    doneMap.value = raw ? JSON.parse(raw) : {}
  } catch {
    doneMap.value = {}
  }
}
function persistDone() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(doneMap.value)) } catch { /* 忽略隐私模式写入失败 */ }
}
function toggleTask(id) {
  doneMap.value = { ...doneMap.value, [id]: !doneMap.value[id] }
  persistDone()
}
function goTask(task) {
  doneMap.value = { ...doneMap.value, [task.id]: true }
  persistDone()
  emit('navigate', task.section)
}
function resetOnboarding() {
  doneMap.value = {}
  try { localStorage.removeItem(STORAGE_KEY) } catch { /* 忽略 */ }
}

// ---- 交互式新手引导 Tour ----
const showTour = ref(false)
function startTour() {
  resetOnboarding()
  showTour.value = true
}

// ---- 核心概念 ----
const concepts = [
  { badge: '间', title: '知识空间', body: '个人空间仅你可见；团队空间成员共享；公共空间所有用户可读。切换空间后，学习与检索都基于该空间内的文档。' },
  { badge: '模', title: '两类模型', body: '对话模型决定 AI 怎么回答（每人可配自己的）；知识库 embedding 模型决定文档怎么被检索（由系统统一配置，普通用户无需关心）。' },
  { badge: '诊', title: '检索诊断', body: '进阶开发者工具：输入问题可回显 RAG 实际检索到的候选切片与相似度分数，方便调优 topK 与阈值。' }
]

// ---- 常见问题 ----
const faqs = [
  { q: '支持导入哪些格式的文档？', a: 'PDF、Markdown、Word(.docx/.doc)、Excel(.xlsx/.xls)、PowerPoint(.pptx/.ppt)、纯文本(.txt)、CSV、JSON、XML、RTF、ODT，以及带文字的图片（OCR 识别）。导入后系统会自动切片并建立向量索引。' },
  { q: '为什么检索不到我导入的内容？', a: '先确认：① 文档已成功导入且索引进度到 100%；② 顶部知识空间是否选对；③ 可去「AI 学习助手」用检索诊断功能查看实际命中的切片与分数。' },
  { q: '怎么更换对话模型？', a: '进入「AI 学习助手」，在对话框附近点「管理模型」，即可在模型池中选择或自定义你自己的对话模型；设置后仅对你本人生效。' },
  { q: '个人 / 团队 / 公共 空间有什么区别？', a: '个人空间只有你自己能读能写；团队空间由管理员创建、成员共享；公共空间所有用户可读。文档与索引都按空间隔离。' },
  { q: '对话模型和知识库模型是同一个吗？', a: '不是。对话模型（Chat）负责生成回答，你可以自选；知识库 embedding 模型负责把文档转成向量用于检索，默认由系统统一配置。' }
]
const openFaq = ref(-1)
function toggleFaq(i) {
  openFaq.value = openFaq.value === i ? -1 : i
}

onMounted(() => {
  loadDone()
  try {
    if (!localStorage.getItem('shihai_tour_done_v1')) showTour.value = true
  } catch { /* 忽略隐私模式读取失败 */ }
})
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

    <section class="home-system-intro" data-reveal>
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

    <section class="home-steps" data-reveal>
      <header><h2>三步上手</h2><p>第一次使用，跟着走一遍就懂了。</p></header>
      <div class="home-step-grid">
        <div v-for="(step, index) in steps" :key="step.title" class="home-step">
          <span class="home-step-index">{{ index + 1 }}</span>
          <strong>{{ step.title }}</strong>
          <p>{{ step.desc }}</p>
        </div>
      </div>
    </section>

    <section class="home-checklist" data-reveal>
      <header>
        <h2>新手任务清单</h2>
        <p>跟着做完这 4 步，你就能玩转识海。进度会自动保存在本机，刷新不丢。</p>
      </header>
      <div class="home-checklist-progress">
        <div class="home-checklist-track"><span class="home-checklist-fill" :style="{ width: progressPercent + '%' }"></span></div>
        <span class="home-checklist-count">{{ completedCount }} / {{ tasks.length }}<em v-if="allDone"> · 已全部完成 🎉</em></span>
        <button type="button" class="home-checklist-reset" @click="startTour">重看引导</button>
      </div>
      <ul class="home-checklist-items">
        <li v-for="task in tasks" :key="task.id" :class="{ done: !!doneMap[task.id] }">
          <button type="button" class="home-check-box" :aria-pressed="!!doneMap[task.id]" :aria-label="doneMap[task.id] ? '已完成，点击取消' : '标记为完成'" @click="toggleTask(task.id)">
            <svg v-if="doneMap[task.id]" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5 10 17.5 19 7" /></svg>
          </button>
          <div class="home-check-body">
            <strong>{{ task.title }}</strong>
            <p>{{ task.desc }}</p>
          </div>
          <button type="button" class="home-check-go" @click="goTask(task)">{{ task.action }} →</button>
        </li>
      </ul>
    </section>

    <section class="home-concepts" data-reveal>
      <header><h2>先搞懂三个概念</h2><p>理解它们，后面就不会迷路。</p></header>
      <div class="home-concept-grid">
        <article v-for="c in concepts" :key="c.title" class="home-concept">
          <span class="home-concept-badge" aria-hidden="true">{{ c.badge }}</span>
          <strong>{{ c.title }}</strong>
          <p>{{ c.body }}</p>
        </article>
      </div>
    </section>

    <section class="home-faq" data-reveal>
      <header><h2>常见问题</h2><p>新手最常问的几件事。</p></header>
      <div class="home-faq-list">
        <div v-for="(f, i) in faqs" :key="i" class="home-faq-item" :class="{ open: openFaq === i }">
          <button type="button" class="home-faq-q" :aria-expanded="openFaq === i" @click="toggleFaq(i)">
            <span>{{ f.q }}</span>
            <svg viewBox="0 0 24 24" class="home-faq-caret" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
          </button>
          <div v-show="openFaq === i" class="home-faq-a"><p>{{ f.a }}</p></div>
        </div>
      </div>
    </section>
  </main>

  <OnboardingTour :open="showTour" @close="showTour = false" />
</template>

<style scoped>
.home-checklist,
.home-concepts,
.home-faq {
  margin-top: 36px;
}
.home-system-intro {
  display: grid;
  grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr);
  gap: 26px 46px;
  align-items: center;
  margin-top: 64px;
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
.home-checklist > header,
.home-concepts > header,
.home-faq > header {
  margin-bottom: 22px;
}
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
.home-checklist-count em {
  font-style: normal;
  color: var(--site-green, #1d5939);
}
.home-checklist-reset {
  border: 1px solid #d7ddd1;
  background: #fffef9;
  color: var(--site-muted, #718074);
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 8px;
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
.home-check-body {
  flex: 1;
  min-width: 0;
}
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
.home-faq-item + .home-faq-item {
  border-top: 1px solid #eef0ea;
}
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
}
.home-faq-q:hover {
  color: var(--site-green, #1d5939);
}
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
.home-faq-a {
  padding: 0 18px 16px;
}
.home-faq-a p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--site-ink-soft, #314438);
}

@media (max-width: 760px) {
  .home-system-intro {
    grid-template-columns: 1fr;
    gap: 22px;
    padding: 22px;
  }
  .home-system-flow {
    grid-template-columns: 1fr;
  }
  .home-system-flow > div {
    min-height: 0;
  }
  .home-system-flow > b {
    justify-self: center;
    transform: rotate(90deg);
  }
  .home-concept-grid {
    grid-template-columns: 1fr;
  }
  .home-checklist-items li {
    flex-wrap: wrap;
  }
  .home-check-go {
    margin-left: 40px;
  }
}
</style>
