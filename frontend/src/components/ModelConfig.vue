<script setup>
import { onMounted, reactive, ref } from 'vue'
import { createPersonalModel, deletePersonalModel, fetchPersonalModels, testPersonalModel, testPersonalModelConfig, updatePersonalModel } from '../api/modelConfigApi'
import ConfirmDialog from './ConfirmDialog.vue'
import ToastContainer from './ToastContainer.vue'

const emit = defineEmits(['close', 'saved'])
const props = defineProps({ currentUser: { type: Object, required: true } })

const toast = ref(null)
const saving = ref(false)
const loading = ref(true)
const loadError = ref('')
const personalModels = ref([])
const editingModelId = ref(null)
const pendingDelete = ref(null)
const testingModelId = ref(null)
const testingForm = ref(false)
const customForm = reactive({
  name: '', baseUrl: '', apiKey: '', model: '',
  temperature: '', topP: '', maxOutputTokens: '', requestTimeoutMs: '', fallbackModels: ''
})

function resetForm() {
  Object.assign(customForm, { name: '', baseUrl: '', apiKey: '', model: '', temperature: '', topP: '', maxOutputTokens: '', requestTimeoutMs: '', fallbackModels: '' })
  editingModelId.value = null
}

function editModel(model) {
  editingModelId.value = model.id
  Object.assign(customForm, {
    name: model.name || '', baseUrl: '', apiKey: '', model: model.model || '',
    temperature: model.temperature == null ? '' : String(model.temperature),
    topP: model.topP == null ? '' : String(model.topP),
    maxOutputTokens: model.maxOutputTokens == null ? '' : String(model.maxOutputTokens),
    requestTimeoutMs: model.requestTimeoutMs == null ? '' : String(model.requestTimeoutMs),
    fallbackModels: model.fallbackModels || ''
  })
}

async function loadModels() {
  loading.value = true
  loadError.value = ''
  try { personalModels.value = await fetchPersonalModels() || [] }
  catch (error) {
    loadError.value = error.message || '个人模型加载失败。'
    toast.value?.error(loadError.value)
  }
  finally { loading.value = false }
}

async function handleSave() {
  saving.value = true
  const editing = Boolean(editingModelId.value)
  try {
    const data = formData()
    const saved = editingModelId.value
      ? await updatePersonalModel(editingModelId.value, data)
      : await createPersonalModel(data)
    if (editing) personalModels.value = personalModels.value.map(model => model.id === saved.id ? saved : model)
    else personalModels.value.push(saved)
    resetForm()
    toast.value?.success(editing ? '模型已更新。' : '模型已添加。')
    emit('saved')
  } catch (error) {
    toast.value?.error(error.message || '保存失败。')
  } finally {
    saving.value = false
  }
}

function formData() {
  return {
    name: customForm.name || customForm.model,
    baseUrl: customForm.baseUrl,
    apiKey: customForm.apiKey,
    model: customForm.model,
    modelType: 'CHAT',
    temperature: customForm.temperature ? Number(customForm.temperature) : null,
    topP: customForm.topP ? Number(customForm.topP) : null,
    maxOutputTokens: customForm.maxOutputTokens ? Number(customForm.maxOutputTokens) : null,
    requestTimeoutMs: customForm.requestTimeoutMs ? Number(customForm.requestTimeoutMs) : null,
    fallbackModels: customForm.fallbackModels || null,
    enabled: true,
    isDefault: false
  }
}

async function handleTestForm() {
  if (!customForm.baseUrl || !customForm.apiKey || !customForm.model) {
    toast.value?.error('请先填写 API 地址、API Key 和模型标识。')
    return
  }
  testingForm.value = true
  try {
    await testPersonalModelConfig(formData())
    toast.value?.success('当前模型配置连接测试通过。')
  } catch (error) {
    toast.value?.error(error.message || '连接测试失败。')
  } finally {
    testingForm.value = false
  }
}

async function confirmDelete() {
  const model = pendingDelete.value
  pendingDelete.value = null
  if (!model) return
  try {
    await deletePersonalModel(model.id)
    personalModels.value = personalModels.value.filter(item => item.id !== model.id)
    if (editingModelId.value === model.id) resetForm()
    emit('saved')
    toast.value?.success('模型已删除。')
  } catch (error) { toast.value?.error(error.message || '模型删除失败。') }
}

async function handleTestModel(model) {
  testingModelId.value = model.id
  try {
    await testPersonalModel(model.id)
    toast.value?.success(`「${model.name}」连接测试通过。`)
  } catch (error) {
    toast.value?.error(error.message || '连接测试失败。')
  } finally {
    testingModelId.value = null
  }
}

onMounted(loadModels)

</script>

<template>
  <div class="model-config-backdrop" @click.self="emit('close')">
    <div class="model-config-dialog" @keydown.esc="emit('close')">
      <header class="model-config-dialog-header">
         <h2>我的对话模型</h2>
        <button class="model-config-close-btn" aria-label="关闭" @click="emit('close')">&times;</button>
      </header>

         <form class="model-config-body" @submit.prevent="handleSave">
            <p class="model-config-hint">这里管理你自己的模型。选择哪个模型，就使用哪个模型回答。</p>
            <div class="personal-model-list">
              <p v-if="loading" class="model-config-empty">正在加载你的模型...</p>
              <div v-else-if="loadError" class="model-config-message error">
                <span>{{ loadError }}</span>
                <button type="button" class="model-config-retry-btn" @click="loadModels">重新加载</button>
              </div>
              <p v-else-if="!personalModels.length" class="model-config-empty">还没有个人模型，填写下面的表单添加一个。</p>
              <article v-for="model in personalModels" :key="model.id" class="maintenance-model-row">
                <div class="maintenance-model-info">
                  <strong>{{ model.name }}</strong>
                  <span class="maintenance-model-identifier">{{ model.model }}</span>
                  <span class="maintenance-model-tag chat">对话</span>
                  <span v-if="!model.enabled" class="maintenance-model-tag disabled">已停用</span>
                </div>
                <div class="maintenance-model-actions">
                  <button type="button" class="maintenance-model-btn" @click="editModel(model)">编辑</button>
                  <button type="button" class="maintenance-model-btn" :disabled="testingModelId === model.id" @click="handleTestModel(model)">{{ testingModelId === model.id ? '测试中...' : '测试' }}</button>
                  <button type="button" class="maintenance-model-btn danger" @click="pendingDelete = model">删除</button>
                </div>
              </article>
            </div>
           <p class="model-config-section-title">{{ editingModelId ? '编辑模型' : '添加模型' }}</p>
           <div class="model-config-custom-form">
             <div class="model-config-form-grid">
               <label><span>名称</span><input v-model.trim="customForm.name" type="text" placeholder="例如：DeepSeek V4" maxlength="64"></label>
               <label><span><span class="required">*</span>模型标识</span><input v-model.trim="customForm.model" type="text" placeholder="例如：deepseek-ai/DeepSeek-V4-Flash" maxlength="128" required></label>
               <label class="span-2"><span><span class="required">*</span>API 地址</span><input v-model.trim="customForm.baseUrl" type="url" :placeholder="editingModelId ? '请重新填写 API 地址' : 'https://api.example.com'" maxlength="256" required></label>
               <label class="span-2"><span><span class="required">*</span>API Key</span><input v-model.trim="customForm.apiKey" type="password" :placeholder="editingModelId ? '请重新填写 API Key' : 'sk-...'" maxlength="256" required></label>
               <label><span>温度</span><input v-model="customForm.temperature" type="number" step="0.1" min="0" max="2" placeholder="留空使用默认"></label>
               <label><span>Top P</span><input v-model="customForm.topP" type="number" step="0.01" min="0" max="1" placeholder="留空使用默认"></label>
               <label><span>最大输出 Token</span><input v-model="customForm.maxOutputTokens" type="number" min="256" placeholder="留空使用默认"></label>
               <label><span>请求超时(ms)</span><input v-model="customForm.requestTimeoutMs" type="number" min="1" placeholder="留空使用默认"></label>
               <label class="span-2"><span>备用模型 (逗号分隔)</span><input v-model.trim="customForm.fallbackModels" type="text" placeholder="例如：gpt-4o,claude-3" maxlength="256"></label>
             </div>
            </div>
            <div class="model-config-dialog-actions">
              <button class="maintenance-model-btn" type="button" :disabled="saving || testingForm" @click="handleTestForm">{{ testingForm ? '测试中...' : '测试连通性' }}</button>
              <button class="model-config-footer-btn secondary" type="button" @click="emit('close')">取消</button>
             <button class="model-config-footer-btn primary" type="submit" :disabled="saving" :aria-busy="saving">
                {{ saving ? '保存中...' : editingModelId ? '保存修改' : '添加模型' }}
             </button>
           </div>
         </form>
    </div>
     <ToastContainer ref="toast" />
     <ConfirmDialog v-if="pendingDelete" title="删除个人模型？" :message="`将删除「${pendingDelete.name}」，删除后无法恢复。`" confirm-text="删除模型" danger @confirm="confirmDelete" @cancel="pendingDelete = null" />
  </div>
</template>
