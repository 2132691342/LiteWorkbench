<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { menuApi, roleApi } from '@/api'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { usePermission } from '@/composables/usePermission'
import type { MenuVO, RoleREQ, RoleVO } from '@/types/api'
import { menuLabel } from '@/utils/menu'

/** 角色管理：列表、增改删、菜单授权树 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const loading = ref(false)
const error = ref('')
const list = ref<RoleVO[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, size: 10 })

const dialogVisible = ref(false)
const dialogLoading = ref(false)
const isEdit = ref(false)
const form = reactive<RoleREQ>({ roleId: '', roleCode: '', roleName: '', sortNo: 0 })

const grantVisible = ref(false)
const grantLoading = ref(false)
const grantRole = ref<RoleVO | null>(null)
const menuTree = ref<MenuVO[]>([])
const checkedMenuIds = ref<string[]>([])
const treeRef = ref()

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await roleApi.page({ ...query })
    list.value = result.list
    total.value = result.total
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('common.failed')
  } finally {
    loading.value = false
  }
}

function openDialog(role?: RoleVO): void {
  isEdit.value = Boolean(role)
  form.roleId = role?.roleId ?? ''
  form.roleCode = role?.roleCode ?? ''
  form.roleName = role?.roleName ?? ''
  form.sortNo = role?.sortNo ?? 0
  dialogVisible.value = true
}

async function submitDialog(): Promise<void> {
  dialogLoading.value = true
  try {
    if (isEdit.value) {
      await roleApi.update({ ...form })
    } else {
      await roleApi.create({ ...form })
    }
    ElMessage.success(t('common.saveSuccess'))
    dialogVisible.value = false
    void load()
  } finally {
    dialogLoading.value = false
  }
}

async function openGrant(role: RoleVO): Promise<void> {
  grantRole.value = role
  grantVisible.value = true
  grantLoading.value = true
  try {
    const [tree, granted] = await Promise.all([menuApi.tree(), roleApi.menuIds(role.roleId)])
    menuTree.value = tree
    checkedMenuIds.value = granted
    await nextTick()
    treeRef.value?.setCheckedKeys(granted)
  } finally {
    grantLoading.value = false
  }
}

/** 提交授权：勾选父级时带出子级，半选父级也一并提交 */
async function submitGrant(): Promise<void> {
  if (!grantRole.value) return
  grantLoading.value = true
  try {
    const checked = treeRef.value?.getCheckedKeys() ?? []
    const halfChecked = treeRef.value?.getHalfCheckedKeys() ?? []
    await roleApi.grant({ roleId: grantRole.value.roleId, menuIds: [...checked, ...halfChecked] })
    ElMessage.success(t('common.saveSuccess'))
    grantVisible.value = false
  } finally {
    grantLoading.value = false
  }
}

/** 删除角色，内置角色入口已隐藏 */
async function removeRole(role: RoleVO): Promise<void> {
  await ElMessageBox.confirm(t('common.confirmDelete', { name: role.roleName }), t('common.delete'), {
    confirmButtonText: t('common.delete'),
    cancelButtonText: t('common.cancel'),
    type: 'error',
  })
  await roleApi.remove(role.roleId)
  ElMessage.success(t('common.deleteSuccess'))
  void load()
}

onMounted(load)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('system.role.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('system.role.desc') }}</p>
      </div>
      <LwButton v-if="hasPerm('system:role:create')" @click="openDialog()">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('common.create') }}
      </LwButton>
    </div>

    <LwCard>
      <div class="flex flex-wrap items-center gap-3">
        <el-input v-model="query.keyword" :placeholder="t('common.keyword')" clearable style="width: 220px" @keyup.enter="load" />
        <LwButton variant="ghost" size="sm" @click="load">{{ t('common.search') }}</LwButton>
      </div>
    </LwCard>

    <LwCard>
      <LwSkeleton v-if="loading" :rows="6" />
      <LwErrorBar v-else-if="error" :message="error" @retry="load" />
      <LwEmpty v-else-if="!list.length" />
      <template v-else>
        <el-table :data="list" style="width: 100%">
          <el-table-column :label="t('system.role.roleCode')" prop="roleCode" min-width="160" />
          <el-table-column :label="t('system.role.roleName')" prop="roleName" min-width="160" />
          <el-table-column :label="t('common.status')" width="120">
            <template #default="{ row }">
              <LwTag v-if="row.builtin" tone="secondary">{{ t('system.role.builtin') }}</LwTag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="260" fixed="right">
            <template #default="{ row }">
              <div class="flex gap-2">
                <LwButton v-if="hasPerm('system:role:update')" variant="text" size="sm" @click="openDialog(row)">
                  {{ t('common.edit') }}
                </LwButton>
                <LwButton v-if="hasPerm('system:role:grant')" variant="text" size="sm" @click="openGrant(row)">
                  {{ t('menu.systemRoleGrant') }}
                </LwButton>
                <LwButton
                  v-if="hasPerm('system:role:delete') && !row.builtin"
                  variant="text"
                  size="sm"
                  @click="removeRole(row)"
                >
                  {{ t('common.delete') }}
                </LwButton>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="flex justify-end mt-4">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="load"
            @size-change="load"
          />
        </div>
      </template>
    </LwCard>

    <el-dialog v-model="dialogVisible" :title="isEdit ? t('system.role.update') : t('system.role.create')" width="440px">
      <form class="flex flex-col gap-4" @submit.prevent="submitDialog">
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.role.roleCode') }}</span>
          <input v-model="form.roleCode" class="lw-input" :disabled="isEdit" />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.role.roleName') }}</span>
          <input v-model="form.roleName" class="lw-input" />
        </label>
      </form>
      <template #footer>
        <div class="flex justify-end gap-3">
          <LwButton variant="ghost" @click="dialogVisible = false">{{ t('common.cancel') }}</LwButton>
          <LwButton :loading="dialogLoading" @click="submitDialog">{{ t('common.save') }}</LwButton>
        </div>
      </template>
    </el-dialog>

    <el-drawer v-model="grantVisible" :title="t('system.role.grantTitle')" size="420px">
      <p class="text-xs mb-4" style="color: var(--lw-ink-muted)">
        {{ t('system.role.grantDesc') }} · {{ grantRole?.roleName }}
      </p>
      <el-tree
        ref="treeRef"
        :data="menuTree"
        node-key="menuId"
        show-checkbox
        default-expand-all
        :props="{ label: (data: MenuVO) => menuLabel(data.menuName), children: 'children' }"
      />
      <template #footer>
        <div class="flex justify-end gap-3">
          <LwButton variant="ghost" @click="grantVisible = false">{{ t('common.cancel') }}</LwButton>
          <LwButton :loading="grantLoading" @click="submitGrant">{{ t('common.save') }}</LwButton>
        </div>
      </template>
    </el-drawer>
  </div>
</template>
