<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { menuApi } from '@/api'
import LwButton from '@/components/ui/LwButton.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTableCard from '@/components/ui/LwTableCard.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { usePermission } from '@/composables/usePermission'
import type { MenuREQ, MenuVO } from '@/types/api'
import { MENU_TYPE } from '@/utils/const'
import { menuLabel } from '@/utils/menu'

/** 菜单管理：树形表格维护目录、路由页与按钮权限点 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const loading = ref(false)
const error = ref('')
const tree = ref<MenuVO[]>([])

const dialogVisible = ref(false)
const dialogLoading = ref(false)
const isEdit = ref(false)
const form = reactive<MenuREQ>({
  menuId: '',
  parentId: '0',
  menuName: '',
  menuType: MENU_TYPE.MENU,
  routePath: '',
  component: '',
  icon: '',
  permCode: '',
  sortNo: 0,
  visible: true,
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    tree.value = await menuApi.tree()
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('common.failed')
  } finally {
    loading.value = false
  }
}

/** 打开新建（可指定父级）或编辑弹窗 */
function openDialog(menu?: MenuVO, parent?: MenuVO): void {
  isEdit.value = Boolean(menu)
  form.menuId = menu?.menuId ?? ''
  form.parentId = menu?.parentId ?? parent?.menuId ?? '0'
  form.menuName = menu?.menuName ?? ''
  form.menuType = menu?.menuType ?? (parent ? MENU_TYPE.MENU : MENU_TYPE.DIR)
  form.routePath = menu?.routePath ?? ''
  form.component = menu?.component ?? ''
  form.icon = menu?.icon ?? ''
  form.permCode = menu?.permCode ?? ''
  form.sortNo = menu?.sortNo ?? 0
  form.visible = menu?.visible ?? true
  dialogVisible.value = true
}

async function submitDialog(): Promise<void> {
  if (!form.menuName?.trim()) {
    ElMessage.warning(t('system.menu.nameRequired'))
    return
  }
  dialogLoading.value = true
  try {
    if (isEdit.value) {
      await menuApi.update({ ...form })
    } else {
      await menuApi.create({ ...form })
    }
    ElMessage.success(t('common.saveSuccess'))
    dialogVisible.value = false
    void load()
  } finally {
    dialogLoading.value = false
  }
}

/** 删除菜单，存在子级时后端会拒绝 */
async function removeMenu(menu: MenuVO): Promise<void> {
  try {
    await ElMessageBox.confirm(t('common.confirmDelete', { name: menuLabel(menu.menuName) }), t('common.delete'), {
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
      type: 'error',
    })
  } catch {
    return
  }
  await menuApi.remove(menu.menuId)
  ElMessage.success(t('common.deleteSuccess'))
  void load()
}

function typeLabel(type: string): string {
  if (type === MENU_TYPE.DIR) return t('system.menu.typeDir')
  if (type === MENU_TYPE.BUTTON) return t('system.menu.typeButton')
  return t('system.menu.typeMenu')
}

function typeTone(type: string): 'primary' | 'secondary' | 'muted' {
  if (type === MENU_TYPE.DIR) return 'secondary'
  if (type === MENU_TYPE.BUTTON) return 'muted'
  return 'primary'
}

onMounted(load)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('system.menu.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('system.menu.desc') }}</p>
      </div>
      <LwButton v-if="hasPerm('system:menu:create')" @click="openDialog()">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('system.menu.create') }}
      </LwButton>
    </div>

    <LwTableCard
      :title="t('system.menu.title')"
      :total="tree.length"
      :loading="loading"
      :error="error"
      :empty="!tree.length"
      :empty-text="t('common.empty')"
      @retry="load"
    >

      <template #skeleton>
        <LwSkeleton :rows="8" />
      </template>
      <el-table :data="tree" row-key="menuId" default-expand-all style="width: 100%">
        <el-table-column :label="t('system.menu.menuName')" min-width="240">
          <template #default="{ row }">
            <div class="lw-cell-title">{{ menuLabel(row.menuName) }}</div>
            <div class="lw-cell-mono">{{ row.menuName }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.menu.menuType')" width="110">
          <template #default="{ row }">
            <LwTag :tone="typeTone(row.menuType)">{{ typeLabel(row.menuType) }}</LwTag>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.menu.permCode')" min-width="180">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ row.permCode || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.menu.routePath')" min-width="140">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ row.routePath || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('system.menu.sortNo')" prop="sortNo" width="90">
          <template #default="{ row }"><span class="lw-cell-mono">{{ row.sortNo }}</span></template>
        </el-table-column>
        <el-table-column
          v-if="hasPerm('system:menu:update') || hasPerm('system:menu:delete')"
          :label="t('common.actions')"
          width="230"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="lw-rowacts">
              <LwButton v-if="hasPerm('system:menu:create')" variant="text" size="sm" @click="openDialog(undefined, row)">
                {{ t('system.menu.addChild') }}
              </LwButton>
              <LwButton v-if="hasPerm('system:menu:update')" variant="text" size="sm" @click="openDialog(row)">
                {{ t('common.edit') }}
              </LwButton>
              <template v-if="hasPerm('system:menu:delete')">
                <span class="lw-rowacts__sep" aria-hidden="true" />
                <LwButton variant="text" size="sm" class="lw-text-danger" @click="removeMenu(row)">
                  {{ t('system.menu.remove') }}
                </LwButton>
              </template>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </LwTableCard>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('system.menu.update') : t('system.menu.create')"
      width="520px"
    >
      <form class="flex flex-col gap-4" @submit.prevent="submitDialog">
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.menu.menuName') }}</span>
          <input v-model="form.menuName" class="lw-input" :placeholder="t('system.menu.namePlaceholder')" />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('system.menu.menuType') }}</span>
          <el-select v-model="form.menuType" style="width: 100%">
            <el-option :label="t('system.menu.typeDir')" :value="MENU_TYPE.DIR" />
            <el-option :label="t('system.menu.typeMenu')" :value="MENU_TYPE.MENU" />
            <el-option :label="t('system.menu.typeButton')" :value="MENU_TYPE.BUTTON" />
          </el-select>
        </label>
        <div class="grid grid-cols-2 gap-4">
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('system.menu.routePath') }}</span>
            <input v-model="form.routePath" class="lw-input" :placeholder="t('system.menu.routePathPlaceholder')" />
          </label>
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('system.menu.component') }}</span>
            <input v-model="form.component" class="lw-input" :placeholder="t('system.menu.componentPlaceholder')" />
          </label>
        </div>
        <div class="grid grid-cols-2 gap-4">
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('system.menu.icon') }}</span>
            <input v-model="form.icon" class="lw-input" :placeholder="t('system.menu.iconPlaceholder')" />
          </label>
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('system.menu.permCode') }}</span>
            <input v-model="form.permCode" class="lw-input" :placeholder="t('system.menu.permCodePlaceholder')" />
          </label>
        </div>
        <div class="grid grid-cols-2 gap-4 items-end">
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('system.menu.sortNo') }}</span>
            <el-input-number v-model="form.sortNo" :min="0" style="width: 100%" />
          </label>
          <label class="flex items-center gap-2 pb-2">
            <el-switch v-model="form.visible" />
            <span class="text-sm" style="color: var(--lw-ink)">{{ t('system.menu.visible') }}</span>
          </label>
        </div>
      </form>
      <template #footer>
        <div class="flex justify-end gap-3">
          <LwButton variant="ghost" @click="dialogVisible = false">{{ t('common.cancel') }}</LwButton>
          <LwButton :loading="dialogLoading" @click="submitDialog">{{ t('common.save') }}</LwButton>
        </div>
      </template>
    </el-dialog>
  </div>
</template>
