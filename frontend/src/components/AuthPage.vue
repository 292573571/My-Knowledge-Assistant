<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { login, register, sendCode } from '../api/authApi'

const emit = defineEmits(['authenticated'])
const mode = ref('login')
const account = ref('')
const password = ref('')
const code = ref('')
const error = ref('')
const notice = ref('')
const submitting = ref(false)
const sendingCode = ref(false)
const countdown = ref(0)
let countdownTimer = null

onBeforeUnmount(() => {
  if (countdownTimer) window.clearInterval(countdownTimer)
})

function isValidEmail(value) {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value)
}

async function submit() {
  error.value = ''
  notice.value = ''
  submitting.value = true
  try {
    if (mode.value === 'login') {
      const response = await login({ account: account.value, password: password.value })
      emit('authenticated', response)
    } else {
      const response = await register({ email: account.value, code: code.value, password: password.value })
      notice.value = response.message || '注册成功，请登录'
      switchMode('login')
    }
  } catch (requestError) {
    error.value = requestError.message || '认证失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

async function sendVerificationCode() {
  if (countdown.value > 0 || sendingCode.value) return
  error.value = ''
  notice.value = ''
  if (!isValidEmail(account.value)) {
    error.value = '请输入正确的邮箱地址'
    return
  }
  sendingCode.value = true
  try {
    const response = await sendCode(account.value)
    notice.value = response.message || '验证码已发送，请查收邮件'
    startCountdown()
  } catch (requestError) {
    error.value = requestError.message || '验证码发送失败。'
  } finally {
    sendingCode.value = false
  }
}

function startCountdown() {
  countdown.value = 60
  if (countdownTimer) window.clearInterval(countdownTimer)
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) window.clearInterval(countdownTimer)
  }, 1000)
}

function switchMode(nextMode) {
  mode.value = nextMode
  error.value = ''
  notice.value = ''
  password.value = ''
  code.value = ''
}
</script>

<template>
  <main class="auth-page">
    <form class="auth-card" @submit.prevent="submit">
      <p class="auth-kicker">PERSONAL AI WORKBENCH</p>
      <h1>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h1>
      <p class="auth-description">登录后访问你的知识库、对话和文档管理工作区。</p>

      <label>
        {{ mode === 'login' ? '账号' : '邮箱' }}
        <input
          v-model.trim="account"
          :type="mode === 'login' ? 'text' : 'email'"
          :autocomplete="mode === 'login' ? 'username' : 'email'"
          :placeholder="mode === 'login' ? '邮箱或管理员账号' : 'name@example.com'"
          maxlength="128"
          required
        />
      </label>
      <label>
        密码
        <input v-model="password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :minlength="mode === 'register' ? 8 : undefined" maxlength="128" required />
      </label>
      <label v-if="mode === 'register'">
        验证码
        <div class="auth-code-row">
          <input v-model.trim="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6 位数字验证码" required />
          <button type="button" class="auth-code-button" :disabled="sendingCode || countdown > 0" @click="sendVerificationCode">
            {{ sendingCode ? '发送中...' : countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
          </button>
        </div>
      </label>
      <p v-if="error" class="auth-error">{{ error }}</p>
      <p v-if="notice" class="auth-notice">{{ notice }}</p>
      <button type="submit" :disabled="submitting">{{ submitting ? '处理中...' : mode === 'login' ? '登录' : '注册' }}</button>
      <button type="button" class="auth-switch" :disabled="submitting" @click="switchMode(mode === 'login' ? 'register' : 'login')">
        {{ mode === 'login' ? '没有账号？注册' : '已有账号？登录' }}
      </button>
    </form>
  </main>
</template>
