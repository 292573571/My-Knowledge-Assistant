<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchAdminUsers, updateUserSystemRole } from '../api/adminUserApi'

const props = defineProps({
  currentUser: { type: Object, required: true }
})

const users = ref([])
const query = ref('')
const loading = ref(false)
const updatingId = ref('')
const error = ref('')
const success = ref('')

const isSuperAdmin = computed(() => props.currentUser.systemRole === 'SUPER_ADMIN')
const filteredUsers = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  if (!keyword) return users.value
  return users.value.filter(user => [user.account, user.userName, user.publicId]
    .some(value => String(value || '').toLocaleLowerCase().includes(keyword)))
})
const roleCounts = computed(() => ({
  total: users.value.length,
  administrators: users.value.filter(user => user.systemRole !== 'USER').length,
  users: users.value.filter(user => user.systemRole === 'USER').length
}))

onMounted(loadUsers)

async function loadUsers() {
  loading.value = true
  error.value = ''
  try {
    users.value = await fetchAdminUsers()
  } catch (nextError) {
    error.value = nextError.message || '用户列表加载失败。'
  } finally {
    loading.value = false
  }
}

async function changeRole(user, nextRole) {
  if (nextRole === user.systemRole || updatingId.value) return
  updatingId.value = user.publicId
  error.value = ''
  success.value = ''
  try {
    Object.assign(user, await updateUserSystemRole(user.publicId, nextRole))
    success.value = `${user.userName} 的系统角色已更新。`
  } catch (nextError) {
    error.value = nextError.message || '系统角色更新失败。'
  } finally {
    updatingId.value = ''
  }
}

function roleLabel(role) {
  return { SUPER_ADMIN: '超级管理员', ADMIN: '管理员', USER: '普通用户' }[role] || role
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}
</script>

<template>
  <main class="user-management-dashboard">
    <section class="user-management-hero" data-reveal>
      <div>
        <p class="records-badge">系统管理</p>
        <h1>用户管理</h1>
        <p>查看平台注册用户与系统角色。超级管理员可以授权或撤销管理员权限。</p>
      </div>
      <div class="user-management-stats">
        <span><strong>{{ roleCounts.total }}</strong>全部用户</span>
        <span><strong>{{ roleCounts.administrators }}</strong>管理员</span>
        <span><strong>{{ roleCounts.users }}</strong>普通用户</span>
      </div>
    </section>

    <section class="user-management-card" data-reveal>
      <header>
        <div><h2>用户列表</h2><p>{{ isSuperAdmin ? '可调整普通用户和管理员角色' : '当前账号拥有只读管理权限' }}</p></div>
        <input v-model="query" type="search" placeholder="搜索账号、名称或用户 ID" aria-label="搜索用户">
      </header>
      <p v-if="error" class="user-management-message error">{{ error }}</p>
      <p v-if="success" class="user-management-message success">{{ success }}</p>
      <p v-if="loading" class="user-management-empty">正在加载用户...</p>
      <div v-else class="user-management-table-wrap" tabindex="0" aria-label="用户列表，可横向滚动">
        <table class="user-management-table">
          <thead><tr><th>用户</th><th>账号</th><th>注册时间</th><th>系统角色</th></tr></thead>
          <tbody>
            <tr v-for="user in filteredUsers" :key="user.publicId">
              <td><div class="user-management-identity"><span>{{ user.userName.slice(0, 1) }}</span><div><strong>{{ user.userName }}</strong><small>{{ user.publicId }}</small></div></div></td>
              <td><code>{{ user.account }}</code></td>
              <td>{{ formatTime(user.createdAt) }}</td>
              <td>
                <span v-if="user.systemRole === 'SUPER_ADMIN' || !isSuperAdmin" :class="['user-system-role', user.systemRole.toLowerCase()]">{{ roleLabel(user.systemRole) }}</span>
                <label v-else :class="['user-role-select', user.systemRole.toLowerCase(), { updating: updatingId === user.publicId }]">
                  <span class="sr-only">调整 {{ user.userName }} 的系统角色</span>
                  <select
                    :value="user.systemRole"
                    :disabled="updatingId === user.publicId"
                    @change="changeRole(user, $event.target.value)"
                  >
                    <option value="USER">普通用户</option>
                    <option value="ADMIN">管理员</option>
                  </select>
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
                </label>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!filteredUsers.length" class="user-management-empty">没有匹配的用户</p>
      </div>
    </section>
  </main>
</template>
