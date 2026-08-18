<script setup>
import { computed, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { fetchLearningRecord, fetchLearningRecords, promoteLearningRecord, updateLearningRecord } from '../api/learningRecordApi'
import { renderMarkdown } from '../utils/markdown'

const records = ref([])
const props = defineProps({ workspace: { type: Object, default: null } })
const activeRecord = ref(null)
const loading = ref(true)
const reading = ref(false)
const error = ref('')
const notice = ref('')
const editing = ref(false)
const draftContent = ref('')
const saving = ref(false)
const calendarMonth = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1))
const recordScroller = ref(null)
const scrollProgress = ref(0)

const content = computed(() => activeRecord.value?.content ? renderMarkdown(activeRecord.value.content) : '')
const recordCount = computed(() => records.value.length)
const latestRecord = computed(() => records.value[0] || null)
const thisWeekCount = computed(() => {
  const today = new Date()
  const weekStart = new Date(today)
  weekStart.setHours(0, 0, 0, 0)
  weekStart.setDate(today.getDate() - ((today.getDay() + 6) % 7))
  return records.value.filter((record) => new Date(`${record.date}T00:00:00`) >= weekStart).length
})
const consecutiveDays = computed(() => {
  const dates = new Set(records.value.map((record) => record.date))
  const cursor = new Date()
  cursor.setHours(0, 0, 0, 0)
  let count = 0

  while (dates.has(cursor.toISOString().slice(0, 10))) {
    count += 1
    cursor.setDate(cursor.getDate() - 1)
  }
  return count
})
const recordByDate = computed(() => new Map(records.value.map((record) => [record.date, record])))
const calendarTitle = computed(() => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long' }).format(calendarMonth.value))
const calendarDays = computed(() => {
  const year = calendarMonth.value.getFullYear()
  const month = calendarMonth.value.getMonth()
  const firstWeekday = new Date(year, month, 1).getDay()
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const days = Array.from({ length: firstWeekday }, () => null)

  for (let day = 1; day <= daysInMonth; day += 1) {
    const dateKey = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    days.push({ day, dateKey, record: recordByDate.value.get(dateKey) || null, isToday: dateKey === new Date().toISOString().slice(0, 10) })
  }
  return days
})

function formatDate(date) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' }).format(new Date(`${date}T00:00:00`))
}

function formatUpdated(value) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function changeCalendarMonth(offset) {
  calendarMonth.value = new Date(calendarMonth.value.getFullYear(), calendarMonth.value.getMonth() + offset, 1)
}

function updateScrollProgress(event) {
  const element = event.currentTarget
  const scrollable = element.scrollHeight - element.clientHeight
  scrollProgress.value = scrollable > 0 ? Math.min(100, Math.round((element.scrollTop / scrollable) * 100)) : 0
}

async function selectRecord(record) {
  reading.value = true
  error.value = ''
  try {
    activeRecord.value = await fetchLearningRecord(record.date, props.workspace?.id)
    draftContent.value = activeRecord.value.content
    editing.value = false
  } catch (exception) {
    if (exception.status === 404) {
      records.value = records.value.filter((item) => item.date !== record.date)
      activeRecord.value = null
      error.value = ''
      return
    }
    error.value = formatApiError(exception, '学习记录加载失败。')
  } finally {
    reading.value = false
  }
}

function startEditing() {
  draftContent.value = activeRecord.value.content
  editing.value = true
  notice.value = ''
}

async function saveRecord() {
  saving.value = true
  error.value = ''
  try {
    activeRecord.value = await updateLearningRecord(activeRecord.value.date, props.workspace?.id, draftContent.value)
    editing.value = false
    notice.value = '学习记录草稿已暂存。'
    await refreshSummaries()
  } catch (exception) {
    error.value = formatApiError(exception, '学习记录保存失败。')
  } finally {
    saving.value = false
  }
}

async function promoteRecord() {
  saving.value = true
  error.value = ''
  try {
    const promotedDate = activeRecord.value.date
    const contentToPromote = editing.value ? draftContent.value : activeRecord.value.content
    const result = await promoteLearningRecord(promotedDate, props.workspace?.id, contentToPromote)
    activeRecord.value = await fetchLearningRecord(promotedDate, props.workspace?.id)
    draftContent.value = activeRecord.value.content
    editing.value = false
    notice.value = `已提升为正式笔记：${result.fileName}，学习记录已同步为正式内容。`
    await refreshSummaries()
  } catch (exception) {
    error.value = formatApiError(exception, '保存正式笔记失败。')
  } finally {
    saving.value = false
  }
}

async function refreshSummaries() {
  records.value = await fetchLearningRecords(props.workspace?.id)
}

async function load() {
  loading.value = true
  error.value = ''
  if (!props.workspace?.id) {
    records.value = []
    loading.value = false
    return
  }
  try {
    records.value = await fetchLearningRecords(props.workspace?.id)
    if (records.value.length) {
      const latest = new Date(`${records.value[0].date}T00:00:00`)
      calendarMonth.value = new Date(latest.getFullYear(), latest.getMonth(), 1)
      await selectRecord(records.value[0])
    }
  } catch (exception) {
    error.value = formatApiError(exception, '学习记录加载失败。')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="records-dashboard">
       <section class="records-hero" data-reveal>
      <div>
        <p class="records-badge"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H12v16H6.5A2.5 2.5 0 0 0 4 21V5.5ZM20 5.5A2.5 2.5 0 0 0 17.5 3H12v16h5.5A2.5 2.5 0 0 1 20 21V5.5Z"/></svg> 学习记录 · {{ workspace?.name || '当前空间' }}</p>
        <h1>你的学习沉淀</h1>
        <p>每次有效问答都会自动沉淀为每日记录，方便你回顾已经理解的问题与知识。</p>
      </div>
      <div class="records-hero-calendar">
        <header class="records-calendar-header"><strong>{{ calendarTitle }}</strong><div class="calendar-month-switcher"><button type="button" aria-label="上个月" @click="changeCalendarMonth(-1)">‹</button><button type="button" aria-label="下个月" @click="changeCalendarMonth(1)">›</button></div></header>
        <div class="learning-calendar">
          <span v-for="weekday in ['日', '一', '二', '三', '四', '五', '六']" :key="weekday" class="calendar-weekday">{{ weekday }}</span>
          <span v-for="(item, index) in calendarDays" :key="item?.dateKey || `empty-${index}`" class="calendar-day" :class="{ empty: !item, today: item?.isToday, learned: item?.record, active: item?.record && activeRecord?.date === item.dateKey }">
            <template v-if="item"><button v-if="item.record" type="button" :aria-label="`${item.dateKey} 已学习，查看记录`" @click="selectRecord(item.record)"><b>{{ item.day }}</b><i>✓</i></button><b v-else>{{ item.day }}</b></template>
          </span>
        </div>
      </div>
    </section>

     <section class="records-stats" data-reveal aria-label="学习记录统计">
      <div><span>累计记录</span><strong>{{ recordCount }}</strong><small>天</small></div>
      <div><span>本周沉淀</span><strong>{{ thisWeekCount }}</strong><small>天</small></div>
      <div><span>连续记录</span><strong>{{ consecutiveDays }}</strong><small>天</small></div>
      <div><span>最近更新</span><strong class="records-latest">{{ latestRecord ? formatDate(latestRecord.date).replace(/.*年/, '') : '暂无' }}</strong><button type="button" class="records-refresh" :disabled="loading" @click="load">刷新</button></div>
    </section>

     <section class="records-dashboard-grid" data-reveal>
      <aside class="records-recent-card">
        <header><h2>最近学习记录</h2><span>点击查看详情</span></header>
        <div v-if="loading" class="records-list-skeleton" aria-label="正在加载学习记录"><span></span><span></span><span></span></div>
        <div v-else-if="!records.length" class="records-empty-state"><strong>还没有学习记录</strong><span>完成一次有效问答后，这里会自动形成每日沉淀。</span></div>
        <button v-for="record in records" :key="record.date" type="button" :class="{ active: activeRecord?.date === record.date }" @click="selectRecord(record)">
          <strong>{{ formatDate(record.date) }}</strong><span>{{ formatUpdated(record.updatedAt) }}</span>
        </button>
      </aside>

      <section ref="recordScroller" class="record-detail-card" @scroll="updateScrollProgress">
      <div class="record-scroll-progress" aria-hidden="true"><span :style="{ width: `${scrollProgress}%` }"></span></div>
      <div v-if="error" class="record-reader-error"><span>{{ error }}</span><button type="button" @click="load">重试</button></div>
      <p v-if="notice" class="record-operation-notice">{{ notice }}</p>
      <div v-if="reading" class="record-reader-placeholder">正在打开学习记录...</div>
      <article v-else-if="activeRecord" class="record-article">
        <header>
          <div class="record-heading-row"><div><p class="section-kicker">每日笔记</p><h2>{{ formatDate(activeRecord.date) }}</h2></div><div class="record-actions"><button v-if="!editing" type="button" :disabled="saving" @click="startEditing">编辑</button><button type="button" :disabled="saving" @click="promoteRecord">保存为正式笔记</button></div></div>
          <p>编辑后可先暂存草稿；保存为正式笔记后，内容才会进入知识库参与检索。</p>
        </header>
         <div v-if="editing" class="record-editor"><textarea v-model="draftContent" aria-label="编辑学习记录"></textarea><div><button type="button" :disabled="saving" @click="editing = false">取消</button><button class="primary" type="button" :disabled="saving" :aria-busy="saving" @click="saveRecord">{{ saving ? '暂存中...' : '暂存' }}</button></div></div>
        <div v-else class="record-markdown markdown-body" v-html="content"></div>
      </article>
      <div v-else class="record-reader-placeholder">从左侧选择一个已学习日期，查看当天的学习沉淀。</div>
      </section>
    </section>
  </main>
</template>
