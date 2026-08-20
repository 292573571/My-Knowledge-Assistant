<script setup>
import { defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { changePassword, clearLegacyAccessToken, fetchAvatarUrl, fetchCurrentUser, logout } from './api/authApi'
import AuthPage from './components/AuthPage.vue'
import LearningAssistantPage from './components/LearningAssistantPage.vue'
import HomePage from './components/HomePage.vue'
import ModelConfig from './components/ModelConfig.vue'
import { fetchWorkspaces, initializePersonalWorkspace, setActiveWorkspaceId } from './api/workspaceApi'

const LearningRecords = defineAsyncComponent(() => import('./components/LearningRecords.vue'))
const KnowledgeBase = defineAsyncComponent(() => import('./components/KnowledgeBase.vue'))
const UserProfile = defineAsyncComponent(() => import('./components/UserProfile.vue'))
const ConfirmDialog = defineAsyncComponent(() => import('./components/ConfirmDialog.vue'))
const WorkspaceManager = defineAsyncComponent(() => import('./components/WorkspaceManager.vue'))
const UserManagement = defineAsyncComponent(() => import('./components/UserManagement.vue'))
const SystemMaintenance = defineAsyncComponent(() => import('./components/SystemMaintenance.vue'))

const currentUser = ref(null)
const checkingSession = ref(true)
const restoringWorkspace = ref(false)
const appReady = ref(false)
const activeSection = ref('home')
const assistantMounted = ref(false)
const accountMenuOpen = ref(false)
const passwordFormOpen = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordError = ref('')
const passwordSuccess = ref('')
const modelConfigOpen = ref(false)
const changingPassword = ref(false)
const avatarSrc = ref('')
const logoutConfirmOpen = ref(false)
const loggingOut = ref(false)
const workspaces = ref([])
const activeWorkspaceId = ref('')
const workspaceError = ref('')
const switchingWorkspace = ref(false)
const workspaceManagerOpen = ref(false)
const mobileNavOpen = ref(false)
const accountMenuRef = ref(null)
let revealObserver = null
let revealMutationObserver = null

const navigableSections = new Set(['home', 'assistant', 'records', 'knowledge', 'users', 'maintenance', 'profile'])

function readUrlState() {
  const params = new URLSearchParams(window.location.search)
  const section = params.get('section') || window.location.hash.slice(1)
  return {
    section: navigableSections.has(section) ? section : 'home',
    workspaceId: params.get('workspace') || ''
  }
}

function canAccessSection(section, user) {
  return !['users', 'maintenance'].includes(section)
    || user?.systemRole === 'ADMIN'
    || user?.systemRole === 'SUPER_ADMIN'
}

function syncUrlState({ replace = false } = {}) {
  const url = new URL(window.location.href)
  url.searchParams.set('section', activeSection.value)
  if (activeWorkspaceId.value) url.searchParams.set('workspace', activeWorkspaceId.value)
  else url.searchParams.delete('workspace')
  url.hash = ''
  window.history[replace ? 'replaceState' : 'pushState']({}, '', url)
}

function applyUrlState() {
  const state = readUrlState()
  activeSection.value = canAccessSection(state.section, currentUser.value) ? state.section : 'assistant'
  const workspace = workspaces.value.find(item => item.id === state.workspaceId)
  if (workspace) {
    activeWorkspaceId.value = workspace.id
    setActiveWorkspaceId(workspace.id)
  }
}

function handlePopState() {
  if (!currentUser.value) return
  applyUrlState()
  nextTick(() => window.scrollTo({ top: 0, behavior: 'auto' }))
}

function handleAccountKeydown(event) {
  if (event.key === 'Escape') {
    accountMenuOpen.value = false
  }
}

function handleAccountFocusout(event) {
  if (accountMenuOpen.value && accountMenuRef.value && !accountMenuRef.value.contains(event.relatedTarget)) {
    accountMenuOpen.value = false
  }
}

function handleDocumentClick(event) {
  if (accountMenuOpen.value && accountMenuRef.value && !accountMenuRef.value.contains(event.target)) {
    accountMenuOpen.value = false
  }
}

onMounted(async () => {
  setupScrollReveal()
  window.addEventListener('popstate', handlePopState)
  document.addEventListener('keydown', handleAccountKeydown)
  document.addEventListener('click', handleDocumentClick)
  clearLegacyAccessToken()
  try {
    const user = await fetchCurrentUser()
    await restoreWorkspace(user)
  } catch {
    currentUser.value = null
    setActiveWorkspaceId('')
  } finally {
    checkingSession.value = false
    appReady.value = true
  }
})

function revealPendingElements() {
  document.querySelectorAll('[data-reveal]:not(.is-visible)').forEach((element) => {
    if (!revealObserver) element.classList.add('is-visible')
    else revealObserver.observe(element)
  })
}

function setupScrollReveal() {
  if (!('IntersectionObserver' in window)) {
    revealPendingElements()
    return
  }
  revealObserver = new IntersectionObserver((entries, observer) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return
      entry.target.classList.add('is-visible')
      observer.unobserve(entry.target)
    })
  }, { rootMargin: '0px 0px -10% 0px', threshold: 0.08 })
  revealMutationObserver = new MutationObserver(revealPendingElements)
  revealMutationObserver.observe(document.body, { childList: true, subtree: true })
  revealPendingElements()
}

async function handleLogout() {
  loggingOut.value = true
  try {
    await logout()
    if (avatarSrc.value) URL.revokeObjectURL(avatarSrc.value)
    avatarSrc.value = ''
    currentUser.value = null
    workspaces.value = []
    activeWorkspaceId.value = ''
    setActiveWorkspaceId('')
    activeSection.value = 'home'
    syncUrlState({ replace: true })
    accountMenuOpen.value = false
    passwordFormOpen.value = false
    workspaceManagerOpen.value = false
    logoutConfirmOpen.value = false
  } finally {
    loggingOut.value = false
  }
}

async function restoreWorkspace(user) {
  appReady.value = false
  restoringWorkspace.value = true
  try {
    activeSection.value = 'home'
    accountMenuOpen.value = false
    passwordFormOpen.value = false
    workspaceManagerOpen.value = false
    workspaces.value = await initializePersonalWorkspace()
    activeWorkspaceId.value = workspaces.value.find(workspace => workspace.type === 'PERSONAL')?.id || ''
    currentUser.value = user
    applyUrlState()
    syncUrlState({ replace: true })
    await nextTick()
    window.scrollTo({ top: 0, behavior: 'auto' })
    await refreshAvatar()
  } finally {
    restoringWorkspace.value = false
    appReady.value = true
  }
}

async function switchWorkspace(event) {
  const workspaceId = event.target.value
  if (!workspaceId || workspaceId === activeWorkspaceId.value || switchingWorkspace.value) return
  switchingWorkspace.value = true
  workspaceError.value = ''
  try {
    setActiveWorkspaceId(workspaceId)
    activeWorkspaceId.value = workspaceId
    syncUrlState()
  } catch {
    workspaceError.value = '空间切换失败，请稍后重试。'
  } finally {
    switchingWorkspace.value = false
  }
}

function workspaceTypeLabel(type) {
  return { PERSONAL: '个人', TEAM: '团队', PUBLIC: '公共' }[type] || '空间'
}

function workspaceDisplayName(workspace) {
  return workspace.type === 'PERSONAL' ? `${currentUser.value.userName}的个人空间` : workspace.name
}

async function handleWorkspaceCreated(workspace) {
  workspaceError.value = ''
  try {
    workspaces.value = await fetchWorkspaces()
    workspaceManagerOpen.value = false
    await switchWorkspace({ target: { value: workspace.id } })
  } catch {
    workspaceError.value = '团队空间已创建，但空间列表刷新失败。'
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
  navigateTo('profile')
  accountMenuOpen.value = false
  mobileNavOpen.value = false
}

function navigateTo(section) {
  if (!navigableSections.has(section) || !canAccessSection(section, currentUser.value)) return
  const update = () => {
    if (section === 'assistant') assistantMounted.value = true
    activeSection.value = section
    mobileNavOpen.value = false
    syncUrlState()
    nextTick(() => window.scrollTo({ top: 0, behavior: 'auto' }))
  }
  if (document.startViewTransition && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    document.startViewTransition(update)
  } else {
    update()
  }
}

onBeforeUnmount(() => {
  if (avatarSrc.value) URL.revokeObjectURL(avatarSrc.value)
  revealObserver?.disconnect()
  revealMutationObserver?.disconnect()
  window.removeEventListener('popstate', handlePopState)
  document.removeEventListener('keydown', handleAccountKeydown)
  document.removeEventListener('click', handleDocumentClick)
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
  <div v-if="checkingSession || restoringWorkspace || !appReady" class="auth-page"><p>{{ currentUser ? '正在打开工作台...' : '正在恢复登录状态...' }}</p></div>
  <AuthPage v-else-if="!currentUser" @authenticated="restoreWorkspace" />
  <template v-else>
    <div class="learning-portal" :class="{ 'content-page': activeSection !== 'assistant' }" @keydown.esc="mobileNavOpen = false">
      <header class="portal-header">
        <button type="button" class="portal-brand" @click="navigateTo('home')">
           <img class="portal-mark" src="/brand/ChatGPT%20Image%202026%E5%B9%B48%E6%9C%8817%E6%97%A5%2016_29_03.png" width="40" height="40" alt="识海" />
          <span>识海 <small>LEARNING STUDIO</small></span>
        </button>
         <button type="button" class="portal-mobile-menu-button" :aria-expanded="mobileNavOpen" aria-controls="portal-navigation" :aria-label="mobileNavOpen ? '关闭主导航' : '打开主导航'" @click="mobileNavOpen = !mobileNavOpen"><span></span><span></span><span></span></button>
        <nav id="portal-navigation" class="portal-nav" :class="{ 'mobile-open': mobileNavOpen }" aria-label="主导航">
          <button type="button" class="portal-nav-close" aria-label="关闭主导航" @click="mobileNavOpen = false">×</button>
          <button :class="{ active: activeSection === 'home' }" type="button" @click="navigateTo('home')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 11.5 12 4l8 7.5-1.5 1.3-.5-.5V20a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-7.7l-.5.5zM9.5 21v-6h5v6"/></svg>
            <span>首页</span>
          </button>
          <button :class="{ active: activeSection === 'assistant' }" type="button" @click="navigateTo('assistant')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a7 7 0 0 0-7 7v2.5a3.5 3.5 0 0 0 3.5 3.5H10v2H8.5a1.5 1.5 0 0 0 0 3H12a3 3 0 0 0 3-3v-2h.5a3.5 3.5 0 0 0 3.5-3.5V10a7 7 0 0 0-7-7Z"/><path d="M9 10h.01M15 10h.01M9.5 13.5c1.4 1 3.6 1 5 0"/></svg>
            <span>AI 学习助手</span>
          </button>
          <button :class="{ active: activeSection === 'records' }" type="button" @click="navigateTo('records')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5.5 4.5A2.5 2.5 0 0 1 8 2h9.5a1 1 0 0 1 1 1v15.5a.5.5 0 0 1-.8.4L15 17l-2.7 1.9a.5.5 0 0 1-.6 0L9 17l-2.7 1.9a.5.5 0 0 1-.8-.4V4.5Z"/><path d="M9 6.5h5.5M9 10h5.5"/></svg>
            <span>学习记录</span>
          </button>
          <button :class="{ active: activeSection === 'knowledge' }" type="button" @click="navigateTo('knowledge')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6.5 12 3l8 3.5-8 3.5-8-3.5Z"/><path d="m6 9.5 6 2.7 6-2.7M6 13l6 2.7 6-2.7M6 16.5l6 2.7 6-2.7"/></svg>
            <span>知识库管理</span>
          </button>
          <button v-if="currentUser.systemRole === 'ADMIN' || currentUser.systemRole === 'SUPER_ADMIN'" :class="{ active: activeSection === 'users' }" type="button" @click="navigateTo('users')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0M16 7.5a2.5 2.5 0 0 1 0 5M17 15a4 4 0 0 1 3.5 4"/></svg>
            <span>用户管理</span>
          </button>
          <button v-if="currentUser.systemRole === 'ADMIN' || currentUser.systemRole === 'SUPER_ADMIN'" :class="{ active: activeSection === 'maintenance' }" type="button" @click="navigateTo('maintenance')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14.7 6.3a4 4 0 0 0-5 5L4 17l3 3 5.7-5.7a4 4 0 0 0 5-5l-2.2 2.2-3-3 2.2-2.2Z"/></svg>
            <span>系统维护</span>
          </button>
          </nav>
        <div class="portal-user-tools">
          <label class="portal-workspace-select" title="当前知识空间">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6.5 12 3l8 3.5-8 3.5-8-3.5Z"/><path d="m6 10 6 2.7 6-2.7M6 14l6 2.7 6-2.7"/></svg>
            <select :value="activeWorkspaceId" :disabled="switchingWorkspace" aria-label="切换知识空间" @change="switchWorkspace">
              <option v-for="workspace in workspaces" :key="workspace.id" :value="workspace.id">
                {{ workspaceDisplayName(workspace) }} · {{ workspaceTypeLabel(workspace.type) }}
              </option>
            </select>
          </label>
          <span v-if="workspaceError" class="portal-workspace-error">{{ workspaceError }}</span>
           <div ref="accountMenuRef" class="portal-account-menu" @focusout="handleAccountFocusout">
             <button type="button" class="portal-avatar-button" :aria-label="accountMenuOpen ? '关闭账户菜单' : '打开账户菜单'" :aria-expanded="accountMenuOpen" aria-controls="portal-account-dropdown" @click="accountMenuOpen = !accountMenuOpen">
              <img v-if="avatarSrc" :src="avatarSrc" width="38" height="38" loading="lazy" alt="">
              <span v-else>{{ currentUser.userName.slice(0, 1) }}</span>
            </button>
            <strong class="portal-user-name">{{ currentUser.userName }}</strong>
             <div v-if="accountMenuOpen" id="portal-account-dropdown" class="portal-account-dropdown">
              <div class="portal-account-profile"><img v-if="avatarSrc" :src="avatarSrc" width="34" height="34" loading="lazy" alt=""><span v-else>{{ currentUser.userName.slice(0, 1) }}</span><strong>{{ currentUser.userName }}</strong></div>
              <button type="button" @click="modelConfigOpen = true; accountMenuOpen = false">模型配置</button>
              <button type="button" @click="openProfile">个人资料</button>
              <button type="button" @click="passwordFormOpen = !passwordFormOpen">修改密码</button>
           <form v-if="passwordFormOpen" class="portal-password-form" aria-live="polite" @submit.prevent="submitPasswordChange">
             <input v-model="currentPassword" type="password" autocomplete="current-password" placeholder="当前密码" required aria-label="当前密码">
             <input v-model="newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="新密码（至少 8 位）" required aria-label="新密码">
             <input v-model="confirmPassword" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="确认新密码" required aria-label="确认新密码">
                <p v-if="passwordError" class="portal-password-error">{{ passwordError }}</p>
                <p v-if="passwordSuccess" class="portal-password-success">{{ passwordSuccess }}</p>
                 <button class="portal-password-submit" type="submit" :disabled="changingPassword" :aria-busy="changingPassword">{{ changingPassword ? '正在更新...' : '确认修改' }}</button>
              </form>
              <button type="button" class="portal-logout" @click="logoutConfirmOpen = true; accountMenuOpen = false">退出登录</button>
            </div>
          </div>
        </div>
      </header>
      <div v-if="mobileNavOpen" class="portal-nav-backdrop" role="presentation" @click="mobileNavOpen = false"></div>
       <HomePage v-if="activeSection === 'home'" :user="currentUser" @navigate="navigateTo" />
        <LearningAssistantPage v-if="assistantMounted" v-show="activeSection === 'assistant'" :key="`learning-${activeWorkspaceId}`" :workspace-id="activeWorkspaceId" />
       <LearningRecords v-if="activeSection === 'records'" :key="`records-${activeWorkspaceId}`" :workspace="workspaces.find(item => item.id === activeWorkspaceId)" />
      <UserManagement v-else-if="activeSection === 'users'" :current-user="currentUser" />
       <SystemMaintenance v-else-if="activeSection === 'maintenance'" :key="`maintenance-${activeWorkspaceId}`" :workspace="workspaces.find(item => item.id === activeWorkspaceId)" :current-user="currentUser" />
      <KnowledgeBase v-else-if="activeSection === 'knowledge'" :key="`knowledge-${activeWorkspaceId}`" :workspace="workspaces.find(item => item.id === activeWorkspaceId)" @manage-workspace="workspaceManagerOpen = true" />
      <UserProfile v-else-if="activeSection === 'profile'" :user="currentUser" :avatar-src="avatarSrc" @updated="updateCurrentUser" @avatar-updated="refreshAvatar" />
      <ConfirmDialog v-if="logoutConfirmOpen" title="确认退出登录？" message="退出后需要重新输入账号和密码才能进入学习工作台。" confirm-text="退出登录" :busy="loggingOut" danger @confirm="handleLogout" @cancel="logoutConfirmOpen = false" />
      <WorkspaceManager v-if="workspaceManagerOpen" :workspace="workspaces.find(item => item.id === activeWorkspaceId)" :can-create-public="currentUser.systemRole === 'ADMIN' || currentUser.systemRole === 'SUPER_ADMIN'" @close="workspaceManagerOpen = false" @created="handleWorkspaceCreated" />
      <ModelConfig v-if="modelConfigOpen" @close="modelConfigOpen = false" />
    </div>
  </template>
</template>
