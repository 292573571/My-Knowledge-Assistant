<script setup>
import { ref, watch } from 'vue'
import { formatApiError } from '../api/apiError'
import { updateProfile, uploadAvatar } from '../api/authApi'

const props = defineProps({
  user: { type: Object, required: true },
  avatarSrc: { type: String, default: '' }
})
const emit = defineEmits(['updated', 'avatar-updated'])

const userName = ref(props.user.userName)
const editingName = ref(false)
const saving = ref(false)
const uploading = ref(false)
const error = ref('')
const notice = ref('')

watch(() => props.user.userName, (value) => { userName.value = value })

function formatCreatedAt(value) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))
}

async function copyPublicId() {
  try {
    await navigator.clipboard.writeText(props.user.publicId)
    notice.value = '用户 ID 已复制。'
  } catch {
    error.value = '复制失败，请手动选择用户 ID。'
  }
}

async function saveName() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const updated = await updateProfile({ userName: userName.value })
    emit('updated', updated)
    editingName.value = false
    notice.value = '昵称已更新。'
  } catch (exception) {
    error.value = formatApiError(exception, '昵称修改失败。')
  } finally {
    saving.value = false
  }
}

async function selectAvatar(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  uploading.value = true
  error.value = ''
  notice.value = ''
  try {
    const updated = await uploadAvatar(file)
    emit('updated', updated)
    emit('avatar-updated')
    notice.value = '头像已更新。'
  } catch (exception) {
    error.value = formatApiError(exception, '头像上传失败。')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <main class="profile-dashboard">
    <section class="profile-intro" data-reveal>
      <img :src="avatarSrc" width="104" height="104" alt="用户头像">
      <h1>欢迎，{{ user.userName }}</h1>
      <p>管理你的个人信息、头像和账户身份。</p>
    </section>

    <section class="profile-card" data-reveal>
      <div class="profile-row">
        <strong>用户 ID</strong>
        <div class="profile-value"><code>{{ user.publicId }}</code><button type="button" @click="copyPublicId">复制</button></div>
      </div>
      <div class="profile-row profile-avatar-row">
        <strong>头像</strong>
        <div class="profile-value">
          <img :src="avatarSrc" width="72" height="72" loading="lazy" alt="当前头像">
          <label class="profile-link">{{ uploading ? '上传中...' : '更换头像' }}<input type="file" accept="image/jpeg,image/png,image/webp" :disabled="uploading" @change="selectAvatar"></label>
          <small>支持 JPEG、PNG、WebP，文件不超过 2 MB</small>
        </div>
      </div>
      <div class="profile-row">
        <strong>昵称</strong>
        <div v-if="editingName" class="profile-name-editor"><input v-model.trim="userName" maxlength="64" aria-label="昵称"><button type="button" :disabled="saving" @click="saveName">保存</button><button type="button" :disabled="saving" @click="editingName = false; userName = user.userName">取消</button></div>
        <div v-else class="profile-value"><span>{{ user.userName }}</span><button type="button" @click="editingName = true">修改</button></div>
      </div>
      <div class="profile-row">
        <strong>登录账号</strong>
        <div class="profile-value"><span>{{ user.account }}</span><small>登录账号不可修改</small></div>
      </div>
      <div class="profile-row">
        <strong>注册时间</strong>
        <div class="profile-value"><span>{{ formatCreatedAt(user.createdAt) }}</span></div>
      </div>
      <p v-if="error" class="profile-message error">{{ error }}</p>
      <p v-if="notice" class="profile-message">{{ notice }}</p>
    </section>
  </main>
</template>
