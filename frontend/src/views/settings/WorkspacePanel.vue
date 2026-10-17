<script setup lang="ts">
import { Box, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
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
  await ElMessageBox.confirm(t('workspace.deleteConfirm', { name: item.workspaceName }), t('common.tip'), { type: 'warning' })
  await workspaceApi.remove(item.workspaceId)
  ElMessage.success(t('common.deleteSuccess'))
  await load()
}

onMounted(load)
</script>

<template>
  <div class="lw-page flex flex-col gap-4">
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

    <LwCard>
      <LwPageState :loading="loading" :error="error" :empty="!list.length" :skeleton-rows="3" @retry="load">
        <LwEmpty v-if="!list.length" :description="t('workspace.empty')" />
        <div v-else class="flex flex-col gap-3">
          <div
            v-for="item in list"
            :key="item.workspaceId"
            class="rounded-xl border p-4 flex flex-wrap items-center gap-4 transition-all duration-200 hover:lw-shadow-sm"
            style="border-color: var(--lw-border); background: var(--lw-surface-raised)"
          >
            <div
              class="w-10 h-10 rounded-lg flex items-center justify-center shrink-0"
              style="background: var(--lw-secondary-tint); color: var(--lw-primary-ink)"
            >
              <el-icon :size="18"><Box /></el-icon>
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-bold text-sm">{{ item.workspaceName }}</span>
                <LwTag v-if="item.isDefault" tone="secondary">{{ t('workspace.default') }}</LwTag>
              </div>
              <p class="text-xs mt-1 truncate" style="color: var(--lw-ink-muted)">
                {{ item.description || t('workspace.noDesc') }}
              </p>
              <p class="text-xs mt-1 font-mono" style="color: var(--lw-ink-disabled)">
                {{ item.bucketName }} · {{ item.objectCount ?? 0 }} {{ t('workspace.objects') }} · {{ formatSize(item.totalSize) }}
              </p>
            </div>
            <div class="flex items-center gap-2 shrink-0">
              <LwButton v-if="!item.isDefault" variant="ghost" size="sm" @click="setDefault(item)">
                {{ t('workspace.setDefault') }}
              </LwButton>
              <LwButton variant="text" size="sm" @click="openEdit(item)">{{ t('common.edit') }}</LwButton>
              <LwButton v-if="!item.isDefault" variant="text" size="sm" @click="remove(item)">
                {{ t('common.delete') }}
              </LwButton>
            </div>
          </div>
          <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">
            {{ t('workspace.total', { size: formatSize(totalSize) }) }}
          </p>
        </div>
      </LwPageState>
    </LwCard>

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
