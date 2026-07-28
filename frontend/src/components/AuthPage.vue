<script setup>
import { ref } from 'vue'
import { login, register, saveAccessToken } from '../api/authApi'

const emit = defineEmits(['authenticated'])
const mode = ref('login')
const account = ref('')
const password = ref('')
const error = ref('')
const submitting = ref(false)

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    const response = await (mode.value === 'login' ? login : register)({
      account: account.value,
      password: password.value
    })
    saveAccessToken(response.token)
    emit('authenticated', response)
  } catch (requestError) {
    error.value = requestError.message || '认证失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <form class="auth-card" @submit.prevent="submit">
      <p class="auth-kicker">PERSONAL AI WORKBENCH</p>
      <h1>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h1>
      <p class="auth-description">登录后访问你的知识库、对话和文档管理工作区。</p>

      <label>
        账号
        <input v-model.trim="account" autocomplete="username" minlength="3" maxlength="64" pattern="[A-Za-z0-9_-]+" required />
      </label>
      <label>
        密码
        <input v-model="password" type="password" autocomplete="current-password" :minlength="mode === 'register' ? 8 : undefined" maxlength="128" required />
      </label>
      <p v-if="error" class="auth-error">{{ error }}</p>
      <button type="submit" :disabled="submitting">{{ submitting ? '处理中...' : mode === 'login' ? '登录' : '注册并登录' }}</button>
      <button type="button" class="auth-switch" :disabled="submitting" @click="mode = mode === 'login' ? 'register' : 'login'">
        {{ mode === 'login' ? '没有账号？注册' : '已有账号？登录' }}
      </button>
    </form>
  </main>
</template>
