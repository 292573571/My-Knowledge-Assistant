<script setup>
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
  </main>
</template>
