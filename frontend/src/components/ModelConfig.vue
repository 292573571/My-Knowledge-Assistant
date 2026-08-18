<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchModelPool, fetchMyConfig, saveMyConfig } from '../api/modelConfigApi'

const emit = defineEmits(['close', 'saved'])

const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const poolModels = ref([])
const myConfig = ref(null)

const mode = ref('FOLLOW_DEFAULT')
const selectedModelId = ref(null)
const customForm = reactive({
  name: '', baseUrl: '', apiKey: '', model: '',
  temperature: '', topP: '', maxOutputTokens: '', requestTimeoutMs: '', fallbackModels: ''
})

const modeLabel = (m) => ({ FOLLOW_DEFAULT: '跟随默认', USE_POOL_MODEL: '使用池模型', CUSTOM: '自定义' }[m] || m)
const modeDesc = (m) => ({
  FOLLOW_DEFAULT: '使用管理员设置的全局默认模型',
  USE_POOL_MODEL: '从模型池中选择一个已启用的模型',
  CUSTOM: '填写自定义 API 地址和模型'
}[m] || '')

const resolvedSpec = computed(() => myConfig.value?.resolved)
const resolvedDisplay = computed(() => {
  const spec = resolvedSpec.value
  if (!spec) return '—'
  return `${spec.name || spec.model || '—'}  ·  ${spec.model || '—'}`
})

async function loadData() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [pool, config] = await Promise.all([fetchModelPool(), fetchMyConfig()])
    poolModels.value = pool || []
    myConfig.value = config
    if (config) {
      mode.value = config.mode || 'FOLLOW_DEFAULT'
      selectedModelId.value = config.modelId || null
      if (config.custom) {
        Object.assign(customForm, {
          name: config.custom.name || '',
          baseUrl: config.custom.baseUrl || '',
          apiKey: config.custom.apiKey || '',
          model: config.custom.model || '',
          temperature: config.custom.temperature != null ? String(config.custom.temperature) : '',
          topP: config.custom.topP != null ? String(config.custom.topP) : '',
          maxOutputTokens: config.custom.maxOutputTokens != null ? String(config.custom.maxOutputTokens) : '',
          requestTimeoutMs: config.custom.requestTimeoutMs != null ? String(config.custom.requestTimeoutMs) : '',
          fallbackModels: config.custom.fallbackModels || ''
        })
      }
    }
  } catch (error) {
    errorMessage.value = error.message || '加载失败。'
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const body = { mode: mode.value }
    if (mode.value === 'USE_POOL_MODEL') body.modelId = selectedModelId.value || null
    if (mode.value === 'CUSTOM') {
      body.name = customForm.name || null
      body.baseUrl = customForm.baseUrl || null
      body.apiKey = customForm.apiKey || null
      body.model = customForm.model || null
      body.temperature = customForm.temperature ? Number(customForm.temperature) : null
      body.topP = customForm.topP ? Number(customForm.topP) : null
      body.maxOutputTokens = customForm.maxOutputTokens ? Number(customForm.maxOutputTokens) : null
      body.requestTimeoutMs = customForm.requestTimeoutMs ? Number(customForm.requestTimeoutMs) : null
      body.fallbackModels = customForm.fallbackModels || null
    }
    const result = await saveMyConfig(body)
    myConfig.value = result
    successMessage.value = '已保存。'
    emit('saved')
    setTimeout(() => emit('close'), 800)
  } catch (error) {
    errorMessage.value = error.message || '保存失败。'
  } finally {
    saving.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="model-config-backdrop" @click.self="emit('close')">
    <div class="model-config-dialog" @keydown.esc="emit('close')">
      <header class="model-config-dialog-header">
        <h2>模型配置</h2>
        <button class="model-config-close-btn" aria-label="关闭" @click="emit('close')">&times;</button>
      </header>

      <div v-if="loading" class="model-config-state">加载中...</div>
      <template v-else>
        <div class="model-config-body">
          <div v-if="errorMessage" class="model-config-message error">{{ errorMessage }}</div>
          <div v-if="successMessage" class="model-config-message success">{{ successMessage }}</div>

          <div class="model-config-current" v-if="resolvedSpec">
            <span class="model-config-current-label">当前生效</span>
            <strong>{{ resolvedDisplay }}</strong>
            <span v-if="myConfig" class="model-config-mode-badge">{{ modeLabel(myConfig.mode) }}</span>
          </div>
          <p v-else class="model-config-hint">尚未配置，当前使用全局默认模型。</p>

          <p class="model-config-section-title">选择模式</p>
          <div class="model-config-mode-select">
            <label v-for="m in ['FOLLOW_DEFAULT', 'USE_POOL_MODEL', 'CUSTOM']" :key="m"
                   :class="{ active: mode === m }" class="model-config-mode-option">
              <input v-model="mode" type="radio" :value="m" name="model-mode">
              <div>
                <span>{{ modeLabel(m) }}</span>
                <span class="model-config-mode-desc">{{ modeDesc(m) }}</span>
              </div>
            </label>
          </div>

          <div v-if="mode === 'USE_POOL_MODEL'" class="model-config-pool-list">
            <p v-if="poolModels.filter(m => m.enabled).length === 0" class="model-config-empty">模型池为空，请联系管理员添加模型。</p>
            <label v-for="model in poolModels.filter(m => m.enabled)" :key="model.id"
                   :class="{ selected: selectedModelId === model.id }" class="model-config-pool-item">
              <input v-model="selectedModelId" type="radio" :value="model.id" name="pool-model">
              <div>
                <strong>{{ model.name }}</strong>
                <span class="model-config-model-id">{{ model.model }}</span>
                <span v-if="model.isDefault" class="model-config-tag default">默认</span>
              </div>
            </label>
          </div>

          <div v-if="mode === 'CUSTOM'" class="model-config-custom-form">
            <p class="model-config-section-title">自定义模型参数</p>
            <div class="model-config-form-grid">
              <label>名称<input v-model="customForm.name" placeholder="例如：DeepSeek V4" maxlength="64"></label>
              <label>模型标识<input v-model="customForm.model" placeholder="例如：deepseek-ai/DeepSeek-V4-Flash" maxlength="128"></label>
              <label class="span-2">API 地址<input v-model="customForm.baseUrl" placeholder="https://api.example.com" maxlength="256"></label>
              <label class="span-2">API Key<input v-model="customForm.apiKey" type="password" placeholder="sk-..." maxlength="256"></label>
              <label>温度<input v-model="customForm.temperature" type="number" step="0.1" min="0" max="2" placeholder="留空使用默认"></label>
              <label>Top P<input v-model="customForm.topP" type="number" step="0.01" min="0" max="1" placeholder="留空使用默认"></label>
              <label>最大输出 Token<input v-model="customForm.maxOutputTokens" type="number" placeholder="留空使用默认"></label>
              <label>请求超时(ms)<input v-model="customForm.requestTimeoutMs" type="number" placeholder="留空使用默认"></label>
              <label class="span-2">备用模型 (逗号分隔)<input v-model="customForm.fallbackModels" placeholder="例如：gpt-4o,claude-3" maxlength="256"></label>
            </div>
          </div>
        </div>

        <div class="model-config-dialog-actions">
          <button class="model-config-footer-btn secondary" @click="emit('close')">取消</button>
          <button class="model-config-footer-btn primary" :disabled="saving" :aria-busy="saving" @click="handleSave">
            {{ saving ? '保存中...' : '保存配置' }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>