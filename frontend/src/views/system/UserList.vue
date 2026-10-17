<script setup lang="ts">
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { roleApi, userApi } from '@/api'
import LwButton from '@/components/ui/LwButton.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTableCard from '@/components/ui/LwTableCard.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { usePermission } from '@/composables/usePermission'
import type { RoleVO, UserREQ, UserVO } from '@/types/api'
import { formatTime, USER_STATE } from '@/utils/const'
import { encryptSecret } from '@/utils/crypto'

/** 用户管理：检索、分页、增删改、启停、重置密码，按钮按权限点显隐 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const loading = ref(false)
const error = ref('')
const list = ref<UserVO[]>([])
const total = ref(0)
const roles = ref<RoleVO[]>([])

const query = reactive({ keyword: '', state: '', page: 1, size: 10 })

const dialogVisible = ref(false)
const dialogLoading = ref(false)
const form = reactive<UserREQ>({ userId: '', username: '', password: '', nickname: '', email: '', roleIds: [] })
const isEdit = ref(false)

const resetVisible = ref(false)
const resetLoading = ref(false)
const resetTarget = ref<UserVO | null>(null)
const resetForm = reactive({ userId: '', newPassword: '' })

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await userApi.page({ ...query })
    list.value = result.list
    total.value = result.total
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('common.failed')
  } finally {
    loading.value = false
  }
}

/** 拉取角色下拉，失败不阻断主流程 */
async function loadRoles(): Promise<void> {
  try {
    roles.value = await roleApi.list()
  } catch {
    roles.value = []
  }
}

function handleSearch(): void {
  query.page = 1
  void load()
}

function handleReset(): void {
  query.keyword = ''
  query.state = ''
  handleSearch()
}

function openDialog(user?: UserVO): void {
  isEdit.value = Boolean(user)
  form.userId = user?.userId ?? ''
  form.username = user?.username ?? ''
  form.password = ''
  form.nickname = user?.nickname ?? ''
  form.email = user?.email ?? ''
  form.roleIds = user?.roleIds ? [...user.roleIds] : []
  dialogVisible.value = true
}

/** 保存用户：新建 create（初始密码必填且加密），编辑 update（不提交密码字段） */
async function submitDialog(): Promise<void> {
  if (!isEdit.value && (form.password ?? '').length < 8) {
    ElMessage.warning(t('system.user.initPasswordTip'))
    return
  }
  if (!form.username?.trim() || !form.nickname?.trim()) {
    ElMessage.warning(t('system.user.requiredTip'))
    return
  }
  dialogLoading.value = true
  try {
    if (isEdit.value) {
      await userApi.update({
        userId: form.userId,
        username: form.username,
        nickname: form.nickname,
        email: form.email,
        roleIds: [...(form.roleIds ?? [])],
      })
    } else {
      // 初始密码与登录口径一致：RSA 密文提交，失败不乱发明文
      const { keyId, cipher } = await encryptSecret(form.password ?? '')
      await userApi.create({ ...form, password: cipher, passwordKeyId: keyId })
    }
    ElMessage.success(t('common.saveSuccess'))
    dialogVisible.value = false
    void load()
  } finally {
    dialogLoading.value = false
  }
}

async function toggleState(user: UserVO): Promise<void> {
  const action = user.state === USER_STATE.NORMAL ? t('common.disable') : t('common.enable')
  try {
    await ElMessageBox.confirm(t('common.confirmToggle', { action, name: user.nickname }), t('common.confirm'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning',
    })
  } catch {
    return
  }
  await userApi.toggle(user.userId)
  ElMessage.success(t('common.saveSuccess'))
  void load()
}

function openReset(user: UserVO): void {
  resetTarget.value = user
  resetForm.userId = user.userId
  resetForm.newPassword = ''
  resetVisible.value = true
}

async function submitReset(): Promise<void> {
  if ((resetForm.newPassword ?? '').length < 8) {
    ElMessage.warning(t('system.user.initPasswordTip'))
    return
  }
  resetLoading.value = true
  try {
    const { keyId, cipher } = await encryptSecret(resetForm.newPassword)
    await userApi.resetPassword({ userId: resetForm.userId, newPassword: cipher, keyId })
    ElMessage.success(t('common.saveSuccess'))
    resetVisible.value = false
  } finally {
    resetLoading.value = false
  }
}

async function removeUser(user: UserVO): Promise<void> {
  try {
    await ElMessageBox.confirm(t('common.confirmDelete', { name: user.nickname }), t('common.delete'), {
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
      type: 'error',
    })
  } catch {
    return
  }
  await userApi.remove(user.userId)
  ElMessage.success(t('common.deleteSuccess'))
  void load()
}

function roleNames(roleIds?: string[]): string {
  if (!roleIds?.length) return '-'
  return roleIds.map((id) => roles.value.find((item) => item.roleId === id)?.roleName ?? id).join('、')
}

function stateLabel(state: string): string {
  if (state === USER_STATE.NORMAL) return t('system.user.stateNormal')
  if (state === USER_STATE.LOCKED) return t('system.user.stateLocked')
  return t('system.user.stateDisabled')
}

function stateTone(state: string): 'success' | 'warning' | 'error' | 'muted' {
  if (state === USER_STATE.NORMAL) return 'success'
  if (state === USER_STATE.LOCKED) return 'warning'
  if (state === USER_STATE.DISABLED) return 'muted'
  return 'error'
}

onMounted(() => {
  void load()
  void loadRoles()
})
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('system.user.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('system.user.desc') }}</p>
      </div>
      <LwButton v-if="hasPerm('system:user:create')" @click="openDialog()">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('system.user.create') }}
      </LwButton>
    </div>

    <LwTableCard
      :title="t('system.user.title')"
      :total="total"
      :loading="loading"
      :error="error"
      :empty="!list.length"
      :empty-text="t('common.empty')"
      @retry="load"
    >
      <template #filter>
        <el-input
          v-model="query.keyword"
          :placeholder="t('common.keyword')"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.state" :placeholder="t('common.status')" clearable style="width: 140px">
          <el-option :label="t('system.user.stateNormal')" :value="USER_STATE.NORMAL" />
          <el-option :label="t('system.user.stateDisabled')" :value="USER_STATE.DISABLED" />
          <el-option :label="t('system.user.stateLocked')" :value="USER_STATE.LOCKED" />
        </el-select>
        <LwButton variant="ghost" size="sm" @click="handleSearch">
          <template #icon><el-icon><Search /></el-icon></template>
          {{ t('common.search') }}
        </LwButton>
        <LwButton variant="text" size="sm" @click="handleReset">
          <template #icon><el-icon><Refresh /></el-icon></template>
          {{ t('common.reset') }}
        </LwButton>
      </template>

      <template #skeleton>
        <LwSkeleton :rows="6" />
      </template>

      <el-table :data="list" style="width: 100%">
        <el-table-column :label="t('system.user.username')" prop="username" min-width="160">
          <template #default="{ row }">
            <div class="lw-cell-title">{{ row.username }}</div>
            <div class="lw-cell-sub">{{ row.email || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.user.nickname')" prop="nickname" min-width="120" />
        <el-table-column :label="t('system.user.role')" min-width="180">
          <template #default="{ row }">
            <span class="text-sm">{{ roleNames(row.roleIds) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.user.state')" width="100">
          <template #default="{ row }">
            <LwTag :tone="stateTone(row.state)">{{ stateLabel(row.state) }}</LwTag>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.user.lastLoginAt')" width="160">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ formatTime(row.lastLoginAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="300" fixed="right">
          <template #default="{ row }">
            <div class="lw-rowacts">
              <LwButton v-if="hasPerm('system:user:update')" variant="text" size="sm" @click="openDialog(row)">
                {{ t('common.edit') }}
              </LwButton>
              <LwButton v-if="hasPerm('system:user:update')" variant="text" size="sm" @click="toggleState(row)">
                {{ row.state === USER_STATE.NORMAL ? t('common.disable') : t('common.enable') }}
              </LwButton>
              <!-- 改密与删除不可逆，用分隔线隔开并取错误语义色，降低误点概率 -->
              <span
                v-if="hasPerm('system:user:reset') || hasPerm('system:user:delete')"
                class="lw-rowacts__sep"
                aria-hidden="true"
              />
              <LwButton
                v-if="hasPerm('system:user:reset')"
                variant="text"
                size="sm"
                class="lw-text-danger"
                @click="openReset(row)"
              >
                {{ t('system.user.resetPassword') }}
              </LwButton>
              <LwButton
                v-if="hasPerm('system:user:delete')"
                variant="text"
                size="sm"
                class="lw-text-danger"
                @click="removeUser(row)"
              >
                {{ t('common.delete') }}
              </LwButton>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="py-6 text-sm" style="color: var(--lw-ink-muted)">{{ t('common.empty') }}</div>
        </template>
      </el-table>

      <template #pager>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="handleSearch"
        />
      </template>
    </LwTableCard>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('system.user.update') : t('system.user.create')"
      width="460px"
    >
      <form class="flex flex-col gap-4" @submit.prevent="submitDialog">
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.user.username') }}</span>
          <input v-model="form.username" class="lw-input" :disabled="isEdit" />
        </label>
        <label v-if="!isEdit" class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.user.initPassword') }}</span>
          <input
            v-model="form.password"
            type="password"
            class="lw-input"
            :placeholder="t('system.user.initPasswordTip')"
          />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.user.nickname') }}</span>
          <input v-model="form.nickname" class="lw-input" />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.user.email') }}</span>
          <input v-model="form.email" class="lw-input" />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.user.role') }}</span>
          <el-select v-model="form.roleIds" multiple style="width: 100%">
            <el-option v-for="role in roles" :key="role.roleId" :label="role.roleName" :value="role.roleId" />
          </el-select>
        </label>
      </form>
      <template #footer>
        <div class="flex justify-end gap-3">
          <LwButton variant="ghost" @click="dialogVisible = false">{{ t('common.cancel') }}</LwButton>
          <LwButton :loading="dialogLoading" @click="submitDialog">{{ t('common.save') }}</LwButton>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" :title="t('system.user.resetPassword')" width="420px">
      <p class="text-xs mb-3" style="color: var(--lw-ink-muted)">
        {{ t('system.user.resetTip', { name: resetTarget?.nickname ?? '' }) }}
      </p>
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('system.user.newPassword') }}</span>
        <input v-model="resetForm.newPassword" type="password" class="lw-input" />
      </label>
      <template #footer>
        <div class="flex justify-end gap-3">
          <LwButton variant="ghost" @click="resetVisible = false">{{ t('common.cancel') }}</LwButton>
          <LwButton :loading="resetLoading" @click="submitReset">{{ t('common.save') }}</LwButton>
        </div>
      </template>
    </el-dialog>
  </div>
</template>
