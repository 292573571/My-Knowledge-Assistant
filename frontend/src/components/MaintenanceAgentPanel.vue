<script setup>
import { computed, ref } from 'vue'
import { chatWithMaintenanceAgent } from '../api/maintenanceAgentApi'
import { formatApiError } from '../api/apiError'

const props = defineProps({
  workspace: { type: Object, default: null }
})

const question = ref('')
const answer = ref(null)
const error = ref('')
const loading = ref(false)
const showTrace = ref(false)
const suggestions = [
  '检查当前空间的知识库状态',
  '有哪些失败的文档处理任务？',
  '当前空间有多少份文档和分块？'
]
const workspaceName = computed(() => props.workspace?.name || '当前空间')

async function ask(message = question.value) {
  const normalized = message.trim()
  if (!normalized || loading.value) return
  question.value = normalized
  error.value = ''
  answer.value = null
  loading.value = true
  try {
    answer.value = await chatWithMaintenanceAgent(normalized, props.workspace?.id)
  } catch (exception) {
    error.value = formatApiError(exception, '维护助手暂时无法回答。')
  } finally {
    loading.value = false
  }
}

function handleSubmit() {
  ask()
}
</script>

<template>
  <section class="maintenance-agent-panel" aria-labelledby="maintenance-agent-title">
    <header class="maintenance-agent-header">
      <div class="maintenance-agent-heading">
        <span class="maintenance-agent-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M12 3.5a8.5 8.5 0 0 0-8.5 8.5c0 1.8.56 3.47 1.52 4.84L4 20.5l4.05-1.02A8.5 8.5 0 1 0 12 3.5Z"/><path d="M8 12h.01M12 12h.01M16 12h.01"/></svg>
        </span>
        <div>
          <p class="maintenance-section-kicker">READ-ONLY AGENT</p>
          <h2 id="maintenance-agent-title">知识库维护助手</h2>
          <span>让助手帮你检查“{{ workspaceName }}”的索引、任务和批次状态。</span>
        </div>
      </div>
      <span class="maintenance-agent-badge">只读模式</span>
    </header>

    <div class="maintenance-agent-suggestions" aria-label="快捷问题">
      <button v-for="suggestion in suggestions" :key="suggestion" type="button" :disabled="loading" @click="ask(suggestion)">{{ suggestion }}</button>
    </div>

    <form class="maintenance-agent-form" @submit.prevent="handleSubmit">
      <textarea v-model="question" rows="2" :disabled="loading" placeholder="例如：检查当前空间是否有失败任务，并告诉我原因和建议。" aria-label="向知识库维护助手提问"></textarea>
      <button type="submit" :disabled="loading || !question.trim()">{{ loading ? '检查中...' : '询问助手' }}</button>
    </form>

    <p v-if="error" class="maintenance-agent-error" role="alert">{{ error }}</p>
    <article v-if="answer" class="maintenance-agent-answer" aria-live="polite">
      <header><span>助手回答</span><small>{{ answer.readOnly ? '只读' : '请核对执行状态' }} · {{ answer.steps }} 步</small></header>
      <p>{{ answer.answer }}</p>
      <div v-if="answer.toolCalls?.length || answer.traces?.length" class="maintenance-agent-trace">
        <button type="button" @click="showTrace = !showTrace">{{ showTrace ? '收起调用记录' : '查看调用记录' }}</button>
        <ul v-if="showTrace">
          <li v-for="trace in (answer.toolCalls || answer.traces)" :key="`${trace.step}-${trace.toolName}`">
            <strong>{{ trace.toolName }}</strong><span :class="trace.status.toLowerCase()">{{ trace.status }}</span>
          </li>
        </ul>
      </div>
    </article>
  </section>
</template>
