<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { changePassword, clearAccessToken, fetchAvatarUrl, fetchCurrentUser, getAccessToken, logout } from './api/authApi'
import AuthPage from './components/AuthPage.vue'
import ChatLayout from './components/ChatLayout.vue'
import LearningRecords from './components/LearningRecords.vue'
import KnowledgeBase from './components/KnowledgeBase.vue'
import UserProfile from './components/UserProfile.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import { useChatStore } from './stores/chatStore'

const currentUser = ref(null)
const checkingSession = ref(Boolean(getAccessToken()))
const restoringWorkspace = ref(false)
const activeSection = ref('assistant')
const accountMenuOpen = ref(false)
const passwordFormOpen = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordError = ref('')
const passwordSuccess = ref('')
const changingPassword = ref(false)
const avatarSrc = ref('')
const logoutConfirmOpen = ref(false)
const loggingOut = ref(false)
const { setUser } = useChatStore()

onMounted(async () => {
  if (!getAccessToken()) return
  try {
    const user = await fetchCurrentUser()
    await restoreWorkspace(user)
  } catch {
    clearAccessToken()
  } finally {
    checkingSession.value = false
  }
})

async function handleLogout() {
  loggingOut.value = true
  try {
    await logout()
    if (avatarSrc.value) URL.revokeObjectURL(avatarSrc.value)
    avatarSrc.value = ''
    currentUser.value = null
    logoutConfirmOpen.value = false
  } finally {
    loggingOut.value = false
  }
}

async function restoreWorkspace(user) {
  restoringWorkspace.value = true
  try {
    await setUser(user.account)
    currentUser.value = user
    await refreshAvatar()
  } finally {
    restoringWorkspace.value = false
  }
}

async function refreshAvatar() {
  try {
    const nextAvatar = await fetchAvatarUrl()
    if (avatarSrc.value) URL.revokeObjectURL(avatarSrc.value)
    avatarSrc.value = nextAvatar
  } catch {
    avatarSrc.value = ''
  }
}

function updateCurrentUser(user) {
  currentUser.value = { ...currentUser.value, ...user }
}

function openProfile() {
  activeSection.value = 'profile'
  accountMenuOpen.value = false
}

onBeforeUnmount(() => {
  if (avatarSrc.value) URL.revokeObjectURL(avatarSrc.value)
})

async function submitPasswordChange() {
  passwordError.value = ''
  passwordSuccess.value = ''
  changingPassword.value = true
  try {
    await changePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value
    })
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    passwordSuccess.value = '密码已更新，其他设备已退出登录。'
  } catch (error) {
    passwordError.value = error.message || '修改密码失败，请稍后重试。'
  } finally {
    changingPassword.value = false
  }
}
</script>

<template>
  <div v-if="checkingSession" class="auth-page"><p>正在恢复登录状态...</p></div>
  <div v-else-if="restoringWorkspace" class="auth-page"><p>正在打开工作台...</p></div>
  <AuthPage v-else-if="!currentUser" @authenticated="restoreWorkspace" />
  <template v-else>
    <div class="learning-portal" :class="{ 'records-active': activeSection !== 'assistant' }">
      <header class="portal-header">
        <button type="button" class="portal-brand" @click="activeSection = 'assistant'">
          <img class="portal-mark" src="/brand/ChatGPT%20Image%202026%E5%B9%B47%E6%9C%8826%E6%97%A5%2020_41_19.png" alt="智海" />
          <span>智海 <small>LEARNING STUDIO</small></span>
        </button>
        <nav class="portal-nav" aria-label="主导航">
          <button :class="{ active: activeSection === 'assistant' }" type="button" @click="activeSection = 'assistant'">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a7 7 0 0 0-7 7v2.5a3.5 3.5 0 0 0 3.5 3.5H10v2H8.5a1.5 1.5 0 0 0 0 3H12a3 3 0 0 0 3-3v-2h.5a3.5 3.5 0 0 0 3.5-3.5V10a7 7 0 0 0-7-7Z"/><path d="M9 10h.01M15 10h.01M9.5 13.5c1.4 1 3.6 1 5 0"/></svg>
            <span>AI 学习助手</span>
          </button>
          <button :class="{ active: activeSection === 'records' }" type="button" @click="activeSection = 'records'">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5.5 4.5A2.5 2.5 0 0 1 8 2h9.5a1 1 0 0 1 1 1v15.5a.5.5 0 0 1-.8.4L15 17l-2.7 1.9a.5.5 0 0 1-.6 0L9 17l-2.7 1.9a.5.5 0 0 1-.8-.4V4.5Z"/><path d="M9 6.5h5.5M9 10h5.5"/></svg>
            <span>学习记录</span>
          </button>
          <button :class="{ active: activeSection === 'knowledge' }" type="button" @click="activeSection = 'knowledge'">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6.5 12 3l8 3.5-8 3.5-8-3.5Z"/><path d="m6 9.5 6 2.7 6-2.7M6 13l6 2.7 6-2.7M6 16.5l6 2.7 6-2.7"/></svg>
            <span>知识库管理</span>
          </button>
        </nav>
        <div class="portal-account-menu" @mouseenter="accountMenuOpen = true" @mouseleave="accountMenuOpen = false">
        <button type="button" class="portal-avatar-button" aria-label="打开个人资料" :aria-expanded="accountMenuOpen" @click="openProfile">
          <img v-if="avatarSrc" :src="avatarSrc" alt="">
          <span v-else>{{ currentUser.userName.slice(0, 1) }}</span>
          </button>
          <strong class="portal-user-name">{{ currentUser.userName }}</strong>
          <div v-if="accountMenuOpen" class="portal-account-dropdown">
            <div class="portal-account-profile"><img v-if="avatarSrc" :src="avatarSrc" alt=""><span v-else>{{ currentUser.userName.slice(0, 1) }}</span><strong>{{ currentUser.userName }}</strong></div>
            <button type="button" @click="openProfile">个人资料</button>
            <button type="button" @click="passwordFormOpen = !passwordFormOpen">修改密码</button>
            <form v-if="passwordFormOpen" class="portal-password-form" @submit.prevent="submitPasswordChange">
              <input v-model="currentPassword" type="password" autocomplete="current-password" placeholder="当前密码" required>
              <input v-model="newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="新密码（至少 8 位）" required>
              <input v-model="confirmPassword" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="确认新密码" required>
              <p v-if="passwordError" class="portal-password-error">{{ passwordError }}</p>
              <p v-if="passwordSuccess" class="portal-password-success">{{ passwordSuccess }}</p>
              <button class="portal-password-submit" type="submit" :disabled="changingPassword">{{ changingPassword ? '正在更新...' : '确认修改' }}</button>
            </form>
            <button type="button" class="portal-logout" @click="logoutConfirmOpen = true; accountMenuOpen = false">退出登录</button>
          </div>
        </div>
      </header>
      <LearningRecords v-if="activeSection === 'records'" />
      <KnowledgeBase v-else-if="activeSection === 'knowledge'" />
      <UserProfile v-else-if="activeSection === 'profile'" :user="currentUser" :avatar-src="avatarSrc" @updated="updateCurrentUser" @avatar-updated="refreshAvatar" />
      <ChatLayout v-else :user-name="currentUser.userName" />
      <ConfirmDialog v-if="logoutConfirmOpen" title="确认退出登录？" message="退出后需要重新输入账号和密码才能进入学习工作台。" confirm-text="退出登录" :busy="loggingOut" danger @confirm="handleLogout" @cancel="logoutConfirmOpen = false" />
    </div>
  </template>
</template>
