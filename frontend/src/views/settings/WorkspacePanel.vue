<script setup lang="ts">
import { Box, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwRowActions from '@/components/ui/LwRowActions.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTableCard from '@/components/ui/LwTableCard.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { workspaceApi } from '@/api'
import type { WorkspaceREQ, WorkspaceVO } from '@/types/api'

/** 工作区管理：每个工作区绑定一个独立 MinIO 桶，会话与上传按工作区隔离资产 */
const { t } = useI18n()

const list = ref<WorkspaceVO[]>([])
const loading = ref(false)
const error = ref('')
const dialogVisible = ref(false)
const submitting = ref(false)
const editing = ref(false)
const form = ref<WorkspaceREQ>({ workspaceName: '', description: '' })

const totalSize = computed(() => list.value.reduce((sum, item) => sum + (item.totalSize ?? 0), 0))

/** 字节数人类可读：按 1024 进制降档，保留一位小数 */
function formatSize(bytes?: number): string {
  const value = bytes ?? 0
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    list.value = await workspaceApi.list()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  form.value = { workspaceName: '', description: '' }
  editing.value = false
  dialogVisible.value = true
}

function openEdit(item: WorkspaceVO): void {
  form.value = { workspaceId: item.workspaceId, workspaceName: item.workspaceName, description: item.description ?? '' }
  editing.value = true
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.value.workspaceName.trim()) return
  submitting.value = true
  try {
    if (editing.value) {
      await workspaceApi.update(form.value)
      ElMessage.success(t('common.saveSuccess'))
    } else {
      await workspaceApi.create(form.value)
      ElMessage.success(t('workspace.createOk'))
    }
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function setDefault(item: WorkspaceVO): Promise<void> {
  await workspaceApi.setDefault(item.workspaceId)
  ElMessage.success(t('workspace.defaultOk'))
  await load()
}

async function remove(item: WorkspaceVO): Promise<void> {
  await ElMessageBox.confirm(t('workspace.deleteConfirm', { name: item.workspaceName }), t('common.tip'), {
    confirmButtonText: t('common.delete'),
    cancelButtonText: t('common.cancel'),
    type: 'error',
  })
  await workspaceApi.remove(item.workspaceId)
  ElMessage.success(t('common.deleteSuccess'))
  await load()
}

onMounted(load)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('workspace.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('workspace.desc') }}</p>
      </div>
      <LwButton size="sm" @click="openCreate">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('workspace.create') }}
      </LwButton>
    </div>

    <LwTableCard
      :title="t('workspace.title')"
      :total="list.length"
      :loading="loading"
      :error="error"
      :empty="!list.length"
      :empty-text="t('workspace.empty')"
      @retry="load"
    >
      <template #skeleton>
        <LwSkeleton :rows="4" />
      </template>

      <el-table :data="list" style="width: 100%">
        <el-table-column :label="t('workspace.name')" min-width="220">
          <template #default="{ row }">
            <div class="flex items-center gap-2">
              <span
                class="w-6 h-6 rounded-md flex items-center justify-center shrink-0"
                style="background: var(--lw-secondary-tint); color: var(--lw-primary-ink)"
              >
                <el-icon :size="13"><Box /></el-icon>
              </span>
              <div class="min-w-0">
                <div class="lw-cell-title truncate">{{ row.workspaceName }}</div>
                <div class="lw-cell-sub truncate">{{ row.description || t('workspace.noDesc') }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('workspace.bucket')" min-width="200">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ row.bucketName }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('workspace.objects')" width="110">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ row.objectCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('files.size')" width="130">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ formatSize(row.totalSize) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('workspace.default')" width="110">
          <template #default="{ row }">
            <LwTag v-if="row.isDefault" tone="secondary">{{ t('workspace.default') }}</LwTag>
            <span v-else class="lw-cell-sub">-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="220" fixed="right">
          <template #default="{ row }">
            <!-- 默认工作区不可删除：删掉会把当前会话的资产桶一起带走 -->
            <LwRowActions>
              <LwButton v-if="!row.isDefault" variant="text" size="sm" @click="setDefault(row)">
                {{ t('workspace.setDefault') }}
              </LwButton>
              <LwButton variant="text" size="sm" @click="openEdit(row)">{{ t('common.edit') }}</LwButton>
              <template v-if="!row.isDefault">
                <span class="lw-rowacts__sep" aria-hidden="true" />
                <LwButton variant="text" size="sm" class="lw-text-danger" @click="remove(row)">
                  {{ t('common.delete') }}
                </LwButton>
              </template>
            </LwRowActions>
          </template>
        </el-table-column>
      </el-table>

      <template #pager>
        <span class="text-xs" style="color: var(--lw-ink-muted)">
          {{ t('workspace.total', { size: formatSize(totalSize) }) }}
        </span>
      </template>
    </LwTableCard>

    <el-dialog v-model="dialogVisible" :title="editing ? t('workspace.edit') : t('workspace.create')" width="440px">
      <el-form label-position="top">
        <el-form-item :label="t('workspace.name')">
          <el-input v-model="form.workspaceName" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('workspace.descField')">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="512" />
        </el-form-item>
        <p class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">
          {{ t('workspace.bucketTip') }}
        </p>
      </el-form>
      <template #footer>
        <LwButton variant="ghost" @click="dialogVisible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="submitting" @click="submit">{{ t('common.save') }}</LwButton>
      </template>
    </el-dialog>
  </div>
</template>
