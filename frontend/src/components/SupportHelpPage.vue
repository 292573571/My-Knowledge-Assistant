<script setup>
import { ref } from 'vue'

const props = defineProps({
  workspace: { type: Object, default: null }
})

const faqs = ref([
  { q: '怎么上传文档到知识库？', a: '进入「知识库管理」页面，点击上传按钮或将文件拖入受管目录，系统会自动解析并建立索引。' },
  { q: '为什么我的文档一直处理失败？', a: '常见原因包括文件加密、图片扫描件未启用 OCR、格式不支持或网络超时。可在系统维护的「任务状态」中查看具体错误。' },
  { q: '支持哪些文件格式？', a: '目前支持 PDF、Word、Markdown、TXT、Excel、PowerPoint 及常见图片格式。' },
  { q: '怎么新建知识空间？', a: '点击顶部导航栏的当前空间下拉框，选择「管理空间」即可创建个人、团队或公共空间。' },
  { q: '学习记录和正式笔记有什么区别？', a: '学习记录是学习助手对话中的临时探索内容，不会进入知识库检索；正式笔记是确认沉淀后的知识，可被检索引用。' }
])
</script>

<template>
  <div class="agent-page">
    <section class="agent-page-hero">
      <p class="agent-page-hero-kicker">SUPPORT</p>
      <h1>使用帮助</h1>
      <p>回答「这个功能怎么用」「为什么我的文档没处理成功」这类问题。点击页面右下角的机器人头像即可唤起智能客服，它会检索公共知识空间里的帮助文档，并只读查看你自己的任务状态。</p>
    </section>

    <section class="support-help-faqs">
      <h2>常见问题</h2>
      <div class="support-faq-grid">
        <article v-for="(item, index) in faqs" :key="index" class="support-faq-card">
          <h3>{{ item.q }}</h3>
          <p>{{ item.a }}</p>
        </article>
      </div>
    </section>
  </div>
</template>
