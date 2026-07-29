<script setup>
import { computed, onMounted, ref } from 'vue'
import { formatApiError } from '../api/apiError'
import { createEvalCase, deleteEvalCase, downloadEvalImport, fetchEvalCases, fetchEvalImports, fetchEvalRun, fetchEvalRuns, importEvalCases, runEvals, updateEvalCase } from '../api/ragApi'

const evalSummary = ref(null)
const evalLoading = ref(false)
const evalError = ref('')
const evalCases = ref([])
const selectedCaseIds = ref([])
const casesLoading = ref(false)
const caseSaving = ref(false)
const caseDeletingId = ref(null)
const editingCaseId = ref(null)
const caseFormOpen = ref(false)
const caseForm = ref(newCaseForm())
const caseQuery = ref('')
const evalRuns = ref([])
const runsLoading = ref(false)
const selectedRunId = ref('')
const historyOpen = ref(false)
const importOpen = ref(false)
const importFile = ref(null)
const importInput = ref(null)
const importLoading = ref(false)
const importDragActive = ref(false)
const importsOpen = ref(false)
const evalImports = ref([])
const importsLoading = ref(false)

const filteredEvalCases = computed(() => {
  const query = caseQuery.value.trim().toLocaleLowerCase()
  if (!query) return evalCases.value
  return evalCases.value.filter(item => [item.caseId, item.question].some(value => String(value || '').toLocaleLowerCase().includes(query)))
})

function percentage(value) {
  const number = Number(value)
  return Number.isFinite(number) ? `${Math.round(number * 100)}%` : '-'
}

function formatRunTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}

function formatFileSize(value) {
  const size = Number(value) || 0
  return size < 1024 * 1024 ? `${Math.max(1, Math.round(size / 1024))} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`
}

function newCaseForm(caseItem = {}) {
  return {
    mode: caseItem.mode || '',
    type: caseItem.type || '',
    question: caseItem.question || '',
    expectNoAnswer: Boolean(caseItem.expectNoAnswer),
    requireLocalEvidence: Boolean(caseItem.requireLocalEvidence),
    allowModelFallback: Boolean(caseItem.allowModelFallback),
    expectedSources: arrayText(caseItem.expectedSources),
    expectedHeadingPaths: arrayText(caseItem.expectedHeadingPaths),
    expectedKeywords: arrayText(caseItem.expectedKeywords),
    forbiddenKeywords: arrayText(caseItem.forbiddenKeywords)
  }
}

function arrayText(value) {
  return Array.isArray(value) ? value.join('\n') : ''
}

function textArray(value) {
  return value.split(/[\n,]/).map(item => item.trim()).filter(Boolean)
}

function listText(value) {
  return Array.isArray(value) && value.length ? value.join('、') : '—'
}

function ruleSummary(item) {
  return [
    ['来源', item.expectedSources], ['路径', item.expectedHeadingPaths],
    ['关键词', item.expectedKeywords], ['禁用', item.forbiddenKeywords]
  ].filter(([, value]) => Array.isArray(value) && value.length).map(([label, value]) => `${label}: ${listText(value)}`).join(' | ') || '未配置规则'
}

function casePayload() {
  const form = caseForm.value
  return {
    mode: form.mode.trim(), type: form.type.trim(), question: form.question.trim(),
    expectNoAnswer: form.expectNoAnswer, requireLocalEvidence: form.requireLocalEvidence, allowModelFallback: form.allowModelFallback,
    expectedSources: textArray(form.expectedSources), expectedHeadingPaths: textArray(form.expectedHeadingPaths),
    expectedKeywords: textArray(form.expectedKeywords), forbiddenKeywords: textArray(form.forbiddenKeywords)
  }
}

async function loadEvalCases() {
  if (casesLoading.value) return
  casesLoading.value = true
  evalError.value = ''
  try {
    const cases = await fetchEvalCases()
    evalCases.value = Array.isArray(cases) ? cases : []
    const ids = new Set(evalCases.value.map(item => item.id))
    selectedCaseIds.value = selectedCaseIds.value.filter(id => ids.has(id))
  } catch (exception) {
    evalError.value = formatApiError(exception, '加载评测题失败。')
  } finally {
    casesLoading.value = false
  }
}

function selectAllCases() { selectedCaseIds.value = evalCases.value.map(item => item.id) }
function clearCaseSelection() { selectedCaseIds.value = [] }
function beginCreateCase() { editingCaseId.value = null; caseForm.value = newCaseForm(); caseFormOpen.value = true }
function beginImportCases() { importFile.value = null; importOpen.value = true }
function chooseImportFile(event) { importFile.value = event.target.files?.[0] || null }
function dropImportFile(event) { importDragActive.value = false; importFile.value = event.dataTransfer?.files?.[0] || null }
function cancelImportCases() { if (importLoading.value) return; importDragActive.value = false; importFile.value = null; importOpen.value = false; if (importInput.value) importInput.value.value = '' }
function beginEditCase(caseItem) { editingCaseId.value = caseItem.id; caseForm.value = newCaseForm(caseItem); caseFormOpen.value = true }
function cancelCaseEdit() { editingCaseId.value = null; caseForm.value = newCaseForm(); caseFormOpen.value = false }

async function saveEvalCase() {
  if (caseSaving.value || !caseForm.value.question.trim()) return
  caseSaving.value = true
  evalError.value = ''
  try {
    const payload = casePayload()
    if (editingCaseId.value === null) await createEvalCase(payload)
    else await updateEvalCase(editingCaseId.value, payload)
    cancelCaseEdit()
    await loadEvalCases()
  } catch (exception) {
    evalError.value = formatApiError(exception, '保存评测题失败。')
  } finally {
    caseSaving.value = false
  }
}

async function submitImportCases() {
  if (importLoading.value || !importFile.value) return
  importLoading.value = true
  evalError.value = ''
  try {
    const result = await importEvalCases(importFile.value)
    importFile.value = null
    importOpen.value = false
    if (importInput.value) importInput.value.value = ''
    await loadEvalCases()
    evalError.value = `已成功导入 ${result?.imported || 0} 条评测题。`
  } catch (exception) {
    evalError.value = formatApiError(exception, '导入评测题失败。')
  } finally {
    importLoading.value = false
  }
}

async function openImportRecords() {
  importsOpen.value = true
  if (importsLoading.value) return
  importsLoading.value = true
  evalError.value = ''
  try {
    const items = await fetchEvalImports()
    evalImports.value = Array.isArray(items) ? items : []
  } catch (exception) {
    evalError.value = formatApiError(exception, '加载导入记录失败。')
  } finally {
    importsLoading.value = false
  }
}

async function downloadImport(item) {
  try {
    const file = await downloadEvalImport(item.id)
    const url = URL.createObjectURL(file.content)
    const link = document.createElement('a')
    link.href = url; link.download = file.name; link.click()
    URL.revokeObjectURL(url)
  } catch (exception) {
    evalError.value = formatApiError(exception, '下载导入文件失败。')
  }
}

async function removeEvalCase(caseItem) {
  if (!window.confirm(`确定删除评测题“${caseItem.caseId || caseItem.question}”吗？`)) return
  caseDeletingId.value = caseItem.id
  evalError.value = ''
  try {
    await deleteEvalCase(caseItem.id)
    if (editingCaseId.value === caseItem.id) cancelCaseEdit()
    await loadEvalCases()
  } catch (exception) {
    evalError.value = formatApiError(exception, '删除评测题失败。')
  } finally {
    caseDeletingId.value = null
  }
}

async function runSelectedEvals(enhanced) {
  if (evalLoading.value || !selectedCaseIds.value.length) return

  evalLoading.value = true
  evalError.value = ''
  try {
    evalSummary.value = { ...(await runEvals(selectedCaseIds.value, enhanced)), enhanced }
    evalRuns.value = [{ ...evalSummary.value, createdAt: new Date().toISOString() }, ...evalRuns.value.filter(item => item.runId !== evalSummary.value.runId)]
  } catch (exception) {
    evalError.value = formatApiError(exception, '评测执行失败。')
  } finally {
    evalLoading.value = false
  }
}

async function loadEvalRuns() {
  if (runsLoading.value) return
  runsLoading.value = true
  evalError.value = ''
  try {
    const runs = await fetchEvalRuns()
    evalRuns.value = Array.isArray(runs) ? runs : []
  } catch (exception) {
    evalError.value = formatApiError(exception, '加载检索历史失败。')
  } finally {
    runsLoading.value = false
  }
}

async function openEvalHistory() {
  historyOpen.value = true
  await loadEvalRuns()
}

async function openEvalRun(run) {
  if (runsLoading.value) return
  runsLoading.value = true
  selectedRunId.value = run.runId
  evalError.value = ''
  try {
    evalSummary.value = { ...(await fetchEvalRun(run.runId)), enhanced: Boolean(run.enhanced) }
  } catch (exception) {
    evalError.value = formatApiError(exception, '加载检索结果失败。')
  } finally {
    runsLoading.value = false
  }
}

function returnToCases() {
  evalSummary.value = null
  if (!selectedRunId.value) historyOpen.value = false
}

function returnToHistory() {
  selectedRunId.value = ''
  evalSummary.value = null
  historyOpen.value = true
}

onMounted(loadEvalCases)
</script>

<template>
  <main class="retrieval-debug">
    <section class="retrieval-debug-hero">
      <div>
        <p class="retrieval-debug-kicker">RAG OBSERVATORY</p>
        <h1>检索评测</h1>
        <p>维护评测题，并对比基线检索与增强检索的实际效果。</p>
      </div>
      <span class="retrieval-debug-status">30 条评测题</span>
    </section>

    <section v-if="!evalSummary && !historyOpen && !importsOpen" class="retrieval-eval-workbench" aria-labelledby="eval-workbench-title">
      <header class="retrieval-eval-workbench-header"><div><p class="retrieval-debug-kicker">EVALUATION WORKBENCH</p><h2 id="eval-workbench-title">评测题库</h2><p>选择题目后，运行基线或增强检索并比较质量指标。</p></div></header>
      <div class="retrieval-eval-search"><label for="eval-case-query">筛选题目</label><input id="eval-case-query" v-model="caseQuery" type="search" placeholder="按 Case ID 或评测问题模糊查询"><span>{{ filteredEvalCases.length }} 条结果</span><div class="retrieval-eval-search-actions"><button type="button" class="retrieval-eval-secondary" :disabled="importsLoading" @click="openImportRecords">导入记录</button><button type="button" class="retrieval-eval-secondary" @click="beginImportCases">导入</button><button type="button" class="retrieval-eval-primary" @click="beginCreateCase">新增</button><button type="button" class="retrieval-eval-secondary" :disabled="casesLoading" @click="loadEvalCases">{{ casesLoading ? '加载中...' : '刷新' }}</button></div></div>
      <div class="retrieval-eval-actions"><span><b>{{ selectedCaseIds.length }}</b> / {{ evalCases.length }} 已选</span><button type="button" class="retrieval-eval-secondary" :disabled="casesLoading || !evalCases.length" @click="selectAllCases">全选</button><button type="button" class="retrieval-eval-secondary" :disabled="!selectedCaseIds.length" @click="clearCaseSelection">清空</button><i></i><button type="button" class="retrieval-eval-primary" :disabled="evalLoading || !selectedCaseIds.length" @click="runSelectedEvals(false)">{{ evalLoading ? '评测中...' : '运行标准检索' }}</button><button type="button" class="retrieval-eval-primary enhanced" :disabled="evalLoading || !selectedCaseIds.length" @click="runSelectedEvals(true)">{{ evalLoading ? '评测中...' : '运行增强检索' }}</button><button type="button" class="retrieval-eval-secondary retrieval-eval-records-button" :disabled="runsLoading" @click="openEvalHistory">{{ runsLoading ? '加载中...' : '检索记录' }}</button></div>
      <div v-if="casesLoading" class="retrieval-eval-empty">正在加载评测题...</div>
      <div v-else-if="!evalCases.length" class="retrieval-eval-empty">暂无评测题。请使用下方表单新增一条 Eval Case。</div>
      <div v-else class="retrieval-eval-case-list">
        <table class="retrieval-eval-table">
          <thead><tr><th scope="col">选择</th><th scope="col">Case ID / 评测问题</th><th scope="col">模式</th><th scope="col">类型</th><th scope="col">评测规则</th><th scope="col">判定配置</th><th scope="col" class="retrieval-eval-sticky-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in filteredEvalCases" :key="item.id" :class="{ selected: selectedCaseIds.includes(item.id) }">
              <td><input v-model="selectedCaseIds" type="checkbox" :aria-label="`选择 ${item.caseId || `Case #${item.id}`}`" :value="item.id"></td>
              <td><div class="retrieval-eval-question"><strong>{{ item.caseId || `Case #${item.id}` }}</strong><p :title="item.question">{{ item.question }}</p></div></td>
              <td><span class="retrieval-eval-tag">{{ item.mode || '未设模式' }}</span></td>
              <td><span class="retrieval-eval-tag">{{ item.type || '未设类型' }}</span></td>
              <td><span class="retrieval-eval-cell-text retrieval-eval-rule" :title="ruleSummary(item)">{{ ruleSummary(item) }}</span></td>
              <td><div class="retrieval-eval-booleans"><span :class="['retrieval-eval-boolean', item.expectNoAnswer ? 'yes' : 'no']">无回答 {{ item.expectNoAnswer ? '是' : '否' }}</span><span :class="['retrieval-eval-boolean', item.requireLocalEvidence ? 'yes' : 'no']">本地证据 {{ item.requireLocalEvidence ? '是' : '否' }}</span><span :class="['retrieval-eval-boolean', item.allowModelFallback ? 'yes' : 'no']">模型兜底 {{ item.allowModelFallback ? '是' : '否' }}</span></div></td>
              <td class="retrieval-eval-sticky-action"><div class="retrieval-eval-table-actions"><button type="button" @click="beginEditCase(item)">编辑</button><button type="button" class="danger" :disabled="caseDeletingId === item.id" @click="removeEvalCase(item)">{{ caseDeletingId === item.id ? '删除中...' : '删除' }}</button></div></td>
            </tr>
            <tr v-if="!filteredEvalCases.length"><td colspan="7" class="retrieval-eval-no-results">未找到匹配的 Case ID 或评测问题。</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="historyOpen && !evalSummary" class="retrieval-eval-summary retrieval-eval-results-view retrieval-eval-history-view" aria-live="polite">
      <header><div><p>RETRIEVAL RECORDS</p><h2>检索记录</h2></div><div class="retrieval-eval-results-actions"><span>{{ evalRuns.length }} 次运行</span><button type="button" class="retrieval-eval-secondary" @click="historyOpen = false">返回题库</button></div></header>
      <div v-if="runsLoading" class="retrieval-eval-empty">正在加载检索历史...</div>
      <div v-else-if="!evalRuns.length" class="retrieval-eval-empty">暂无检索运行记录。</div>
      <div v-else class="retrieval-eval-history-list"><button v-for="run in evalRuns" :key="run.runId" type="button" :class="{ active: selectedRunId === run.runId }" @click="openEvalRun(run)"><strong>{{ run.enhanced ? '增强检索' : '标准检索' }}</strong><span>{{ formatRunTime(run.createdAt) }}</span><em>{{ percentage(run.passRate) }} 通过</em><small>{{ run.passed || 0 }} / {{ run.total || 0 }} · 命中 {{ percentage(run.retrievalHitRate) }}</small></button></div>
    </section>

    <section v-if="importsOpen" class="retrieval-eval-summary retrieval-eval-results-view retrieval-eval-history-view" aria-live="polite">
      <header><div><p>IMPORT RECORDS</p><h2>导入记录</h2></div><div class="retrieval-eval-results-actions"><span>{{ evalImports.length }} 个文件</span><button type="button" class="retrieval-eval-secondary" @click="importsOpen = false">返回题库</button></div></header>
      <div v-if="importsLoading" class="retrieval-eval-empty">正在加载导入记录...</div>
      <div v-else-if="!evalImports.length" class="retrieval-eval-empty">暂无已保存的导入文件。</div>
      <div v-else class="retrieval-eval-import-records"><article v-for="item in evalImports" :key="item.id"><div><strong :title="item.originalFileName">{{ item.originalFileName }}</strong><span>{{ formatRunTime(item.createdAt) }} · {{ formatFileSize(item.fileSize) }}</span></div><p>已导入 <b>{{ item.importedCount }}</b> 条评测题</p><button type="button" class="retrieval-eval-secondary" @click="downloadImport(item)">下载</button></article></div>
    </section>

    <Teleport to="body">
      <div v-if="caseFormOpen" class="retrieval-eval-modal-backdrop" @click.self="cancelCaseEdit">
        <form class="retrieval-eval-form retrieval-eval-modal" @submit.prevent="saveEvalCase"><header><h3>{{ editingCaseId === null ? '新增 Eval Case' : '编辑 Eval Case' }}</h3><button type="button" class="retrieval-eval-secondary" :disabled="caseSaving" @click="cancelCaseEdit">关闭</button></header><div class="retrieval-eval-form-grid"><label>模式<input v-model="caseForm.mode" placeholder="例如 LOCAL"></label><label>类型<input v-model="caseForm.type" placeholder="例如 retrieval"></label><label class="wide">问题<textarea v-model="caseForm.question" required placeholder="输入评测问题"></textarea></label><label>期望来源<textarea v-model="caseForm.expectedSources" placeholder="每行或逗号分隔"></textarea></label><label>期望标题路径<textarea v-model="caseForm.expectedHeadingPaths" placeholder="每行或逗号分隔"></textarea></label><label>期望关键词<textarea v-model="caseForm.expectedKeywords" placeholder="每行或逗号分隔"></textarea></label><label>禁用关键词<textarea v-model="caseForm.forbiddenKeywords" placeholder="每行或逗号分隔"></textarea></label></div><div class="retrieval-eval-checkboxes"><label><input v-model="caseForm.expectNoAnswer" type="checkbox">期望无回答</label><label><input v-model="caseForm.requireLocalEvidence" type="checkbox">要求本地证据</label><label><input v-model="caseForm.allowModelFallback" type="checkbox">允许模型兜底</label></div><div class="retrieval-eval-modal-actions"><button type="button" class="retrieval-eval-secondary" :disabled="caseSaving" @click="cancelCaseEdit">取消</button><button type="submit" class="retrieval-eval-primary" :disabled="caseSaving || !caseForm.question.trim()">{{ caseSaving ? '保存中...' : editingCaseId === null ? '新增评测题' : '保存修改' }}</button></div></form>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="importOpen" class="retrieval-eval-modal-backdrop" @click.self="cancelImportCases">
        <form class="retrieval-eval-form retrieval-eval-modal retrieval-eval-import-modal" @submit.prevent="submitImportCases"><header><div><h3>导入评测题</h3><span>选择文件并批量新增题目，原始文件会自动保留。</span></div></header><label :class="['retrieval-eval-file-picker', { dragging: importDragActive, selected: importFile }]" @dragenter.prevent="importDragActive = true" @dragover.prevent="importDragActive = true" @dragleave.prevent="importDragActive = false" @drop.prevent="dropImportFile"><input ref="importInput" type="file" accept=".xlsx,.md,.json" @change="chooseImportFile"><span class="retrieval-eval-file-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4"/></svg></span><span class="retrieval-eval-file-picker-action">{{ importFile ? '重新选择' : '选择文件' }}</span><strong>{{ importFile ? importFile.name : '也可以将文件拖放到这里' }}</strong><span class="retrieval-eval-file-types"><i>XLSX</i><i>MD</i><i>JSON</i><small>最大 5 MB</small></span></label><section class="retrieval-eval-import-guide"><header><h4>格式说明</h4><span>“问题”为必填项</span></header><div><article><strong>表格文件</strong><p>Excel 和 Markdown 第一行为表头，支持模式、类型、问题、期望规则和判定开关。</p></article><article><strong>JSON 文件</strong><p>支持对象数组或 JSONL，字段与新增评测题保持一致。</p></article><article><strong>填写规则</strong><p>列表用逗号、顿号或换行分隔；布尔值可用 是/否、true/false、1/0。</p></article></div><footer>Case ID 由系统自动生成，导入文件中的编号不会保留。</footer></section><div class="retrieval-eval-modal-actions"><button type="button" class="retrieval-eval-secondary" :disabled="importLoading" @click="cancelImportCases">取消</button><button type="submit" class="retrieval-eval-primary" :disabled="importLoading || !importFile">{{ importLoading ? '导入中...' : '开始导入' }}</button></div></form>
      </div>
    </Teleport>

    <p v-if="evalError" class="retrieval-debug-error" role="alert">{{ evalError }}</p>

    <section v-if="evalSummary" class="retrieval-eval-summary retrieval-eval-results-view" aria-live="polite">
      <header>
        <div><p>QUALITY BASELINE</p><h2>评测结果</h2></div>
        <div class="retrieval-eval-results-actions"><span>{{ evalSummary.enhanced ? '增强检索' : '标准检索' }} · 运行 ID：{{ evalSummary.runId || '-' }}</span><button type="button" class="retrieval-eval-secondary" @click="selectedRunId ? returnToHistory() : returnToCases()">{{ selectedRunId ? '返回记录' : '返回题库' }}</button></div>
      </header>
      <div class="retrieval-eval-metrics">
        <div><span>总体通过率</span><strong>{{ percentage(evalSummary.passRate) }}</strong><small>{{ evalSummary.passed || 0 }} / {{ evalSummary.total || 0 }}</small></div>
        <div><span>检索命中率</span><strong>{{ percentage(evalSummary.retrievalHitRate) }}</strong></div>
        <div><span>引用正确率</span><strong>{{ percentage(evalSummary.citationCorrectnessRate) }}</strong></div>
        <div><span>关键点覆盖率</span><strong>{{ percentage(evalSummary.keyPointCoverageRate) }}</strong></div>
        <div><span>无依据回答率</span><strong>{{ percentage(evalSummary.unsupportedAnswerRate) }}</strong></div>
        <div><span>拒答正确率</span><strong>{{ percentage(evalSummary.refusalCorrectnessRate) }}</strong></div>
      </div>
      <div class="retrieval-eval-cases">
        <article v-for="item in evalSummary.results || []" :key="item.id" :class="{ failed: !item.passed }">
          <span>{{ item.passed ? '通过' : '失败' }}</span><strong>{{ item.id }}</strong><p>{{ item.question }}</p><small>{{ item.reason || '-' }}</small>
        </article>
      </div>
    </section>

  </main>
</template>
