<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { addWorkspaceMember, createPublicWorkspace, createTeamWorkspace, fetchWorkspaceAuditEvents, fetchWorkspaceMembers, removeWorkspaceMember, updateWorkspaceMemberRole } from '../api/workspaceApi'
import ConfirmDialog from './ConfirmDialog.vue'
import { useDialogFocus } from '../composables/useDialogFocus'

const props = defineProps({
  workspace: { type: Object, default: null },
  canCreatePublic: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'created'])

const tab = ref('members')
const members = ref([])
const audits = ref([])
const loading = ref(false)
const error = ref('')
const teamName = ref('')
const workspaceType = ref('TEAM')
const creating = ref(false)
const memberAccount = ref('')
const memberRole = ref('VIEWER')
const adding = ref(false)
const updatingMemberId = ref('')
const removingMember = ref(null)
const dialogRef = ref(null)

const isOwner = computed(() => props.workspace?.role === 'OWNER')
const canManageMembers = computed(() => isOwner.value && props.workspace?.type !== 'PERSONAL')

useDialogFocus(dialogRef, () => emit('close'))

onMounted(loadMembers)
watch(() => props.workspace?.id, () => {
  tab.value = 'members'
  audits.value = []
  loadMembers()
})

async function loadMembers() {
  if (!props.workspace?.id) return
  loading.value = true
  error.value = ''
  try {
    members.value = await fetchWorkspaceMembers(props.workspace.id)
  } catch (nextError) {
    error.value = nextError.message || '空间成员加载失败。'
  } finally {
    loading.value = false
  }
}

async function loadAudits() {
  if (!isOwner.value || audits.value.length) return
  loading.value = true
  error.value = ''
  try {
    audits.value = await fetchWorkspaceAuditEvents(props.workspace.id)
  } catch (nextError) {
    error.value = nextError.message || '审计记录加载失败。'
  } finally {
    loading.value = false
  }
}

function selectTab(nextTab) {
  tab.value = nextTab
  if (nextTab === 'audit') loadAudits()
}

async function createWorkspace() {
  const name = teamName.value.trim()
  if (!name) return
  creating.value = true
  error.value = ''
  try {
    const workspace = workspaceType.value === 'PUBLIC'
      ? await createPublicWorkspace(name)
      : await createTeamWorkspace(name)
    teamName.value = ''
    emit('created', workspace)
  } catch (nextError) {
    error.value = nextError.message || '知识空间创建失败。'
  } finally {
    creating.value = false
  }
}

async function addMember() {
  const account = memberAccount.value.trim()
  if (!account) return
  adding.value = true
  error.value = ''
  try {
    members.value.push(await addWorkspaceMember(props.workspace.id, account, memberRole.value))
    memberAccount.value = ''
    audits.value = []
  } catch (nextError) {
    error.value = nextError.message || '成员添加失败。'
  } finally {
    adding.value = false
  }
}

async function changeRole(member, event) {
  const previousRole = member.role
  const role = event.target.value
  updatingMemberId.value = member.publicId
  error.value = ''
  try {
    const updated = await updateWorkspaceMemberRole(props.workspace.id, member.publicId, role)
    Object.assign(member, updated)
    audits.value = []
  } catch (nextError) {
    event.target.value = previousRole
    error.value = nextError.message || '成员角色更新失败。'
  } finally {
    updatingMemberId.value = ''
  }
}

async function confirmRemove() {
  const member = removingMember.value
  if (!member) return
  updatingMemberId.value = member.publicId
  error.value = ''
  try {
    await removeWorkspaceMember(props.workspace.id, member.publicId)
    members.value = members.value.filter(item => item.publicId !== member.publicId)
    audits.value = []
    removingMember.value = null
  } catch (nextError) {
    error.value = nextError.message || '成员移除失败。'
  } finally {
    updatingMemberId.value = ''
  }
}

function roleLabel(role) {
  return { OWNER: '所有者', EDITOR: '编辑者', VIEWER: '查看者' }[role] || role
}

function actionLabel(action) {
  return {
    WORKSPACE_CREATE: '创建空间',
    WORKSPACE_MEMBER_ADD: '添加成员',
    WORKSPACE_MEMBER_ROLE_CHANGE: '修改角色',
    WORKSPACE_MEMBER_REMOVE: '移除成员',
    DOCUMENT_UPLOAD: '上传文档',
    DOCUMENT_DELETE: '删除文档'
  }[action] || action
}

function outcomeLabel(outcome) {
  return { SUCCESS: '成功', DENIED: '已拒绝', FAILED: '失败' }[outcome] || outcome
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}
</script>

<template>
  <div class="workspace-manager-backdrop" @click.self="emit('close')">
     <section ref="dialogRef" class="workspace-manager" role="dialog" aria-modal="true" aria-labelledby="workspace-manager-title">
      <header>
        <div>
          <span>{{ workspace?.type === 'PERSONAL' ? '个人空间' : workspace?.type === 'TEAM' ? '团队空间' : '公共空间' }}</span>
          <h2 id="workspace-manager-title">{{ workspace?.name || '空间管理' }}</h2>
        </div>
        <button type="button" class="workspace-manager-close" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <form class="workspace-create-row workspace-create-form" @submit.prevent="createWorkspace">
        <select v-if="canCreatePublic" v-model="workspaceType" aria-label="新空间类型">
          <option value="TEAM">团队空间</option>
          <option value="PUBLIC">平台公共知识源</option>
        </select>
        <input v-model="teamName" maxlength="80" :placeholder="workspaceType === 'PUBLIC' ? '公共知识源名称' : '新团队空间名称'" aria-label="新空间名称">
        <button type="submit" :disabled="creating || !teamName.trim()">{{ creating ? '创建中' : workspaceType === 'PUBLIC' ? '创建公共知识源' : '创建团队' }}</button>
        <small v-if="workspaceType === 'PUBLIC'">公共知识源中的文档会对所有用户的 AI 检索开放，仅管理员可以进入维护。</small>
      </form>

      <nav class="workspace-manager-tabs" aria-label="空间管理分类">
        <button type="button" :class="{ active: tab === 'members' }" @click="selectTab('members')">成员</button>
        <button v-if="isOwner" type="button" :class="{ active: tab === 'audit' }" @click="selectTab('audit')">审计</button>
      </nav>

      <p v-if="error" class="workspace-manager-error">{{ error }}</p>

      <div v-if="tab === 'members'" class="workspace-manager-body">
        <form v-if="canManageMembers" class="workspace-member-add" @submit.prevent="addMember">
          <input v-model="memberAccount" autocomplete="off" placeholder="成员账号" aria-label="成员账号">
          <select v-model="memberRole" aria-label="成员角色"><option value="VIEWER">查看者</option><option value="EDITOR">编辑者</option></select>
          <button type="submit" :disabled="adding || !memberAccount.trim()">{{ adding ? '添加中' : '添加' }}</button>
        </form>
        <p v-if="loading" class="workspace-manager-empty">正在加载...</p>
        <div v-else class="workspace-member-list">
          <article v-for="member in members" :key="member.publicId">
            <span class="workspace-member-avatar">{{ member.userName.slice(0, 1) }}</span>
            <div><strong>{{ member.userName }}</strong></div>
            <span v-if="!canManageMembers || member.role === 'OWNER'" class="workspace-role-badge">{{ roleLabel(member.role) }}</span>
            <select v-else :value="member.role" :disabled="updatingMemberId === member.publicId" aria-label="修改成员角色" @change="changeRole(member, $event)">
              <option value="VIEWER">查看者</option><option value="EDITOR">编辑者</option>
            </select>
            <button v-if="canManageMembers && member.role !== 'OWNER'" type="button" class="workspace-member-remove" :disabled="updatingMemberId === member.publicId" @click="removingMember = member">移除</button>
          </article>
          <p v-if="!members.length" class="workspace-manager-empty">暂无成员</p>
        </div>
      </div>

      <div v-else class="workspace-manager-body workspace-audit-list">
        <p v-if="loading" class="workspace-manager-empty">正在加载...</p>
        <article v-for="event in audits" v-else :key="event.id">
          <span :class="['workspace-audit-outcome', event.outcome.toLowerCase()]">{{ outcomeLabel(event.outcome) }}</span>
          <div><strong>{{ actionLabel(event.action) }}</strong><small>{{ formatTime(event.createdAt) }}</small></div>
          <span>{{ event.reasonCode === 'NONE' ? '操作完成' : outcomeLabel(event.outcome) }}</span>
        </article>
        <p v-if="!loading && !audits.length" class="workspace-manager-empty">暂无审计事件</p>
      </div>
    </section>
    <ConfirmDialog v-if="removingMember" title="移除空间成员？" :message="`移除后，${removingMember.userName} 将无法继续访问该空间。`" confirm-text="确认移除" :busy="Boolean(updatingMemberId)" danger @confirm="confirmRemove" @cancel="removingMember = null" />
  </div>
</template>
