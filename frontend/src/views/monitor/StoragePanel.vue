<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwRowActions from '@/components/ui/LwRowActions.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTableCard from '@/components/ui/LwTableCard.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { storageApi } from '@/api'
import type { BucketAdminVO } from '@/types/api'

/** 存储桶管理（管理员）：全平台桶清单、新建与删除；平台业务桶标记保护 */
const { t } = useI18n()

const buckets = ref<BucketAdminVO[]>([])
const loading = ref(false)
const error = ref('')
const createVisible = ref(false)
const creating = ref(false)
const newBucket = ref('')

/** 桶名校验：3~63 位小写字母数字连字符，不能以连字符开头结尾 */
const nameInvalid = (name: string): boolean => !/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(name)

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
    buckets.value = await storageApi.buckets()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  if (nameInvalid(newBucket.value.trim())) {
    ElMessage.warning(t('storage.nameRule'))
    return
  }
  creating.value = true
  try {
    await storageApi.createBucket(newBucket.value.trim())
    ElMessage.success(t('storage.createOk'))
    createVisible.value = false
    newBucket.value = ''
    await load()
  } finally {
    creating.value = false
  }
}

async function remove(item: BucketAdminVO): Promise<void> {
  if (item.protectedBucket) {
    ElMessage.warning(t('storage.protectedTip'))
    return
  }
  await ElMessageBox.confirm(t('storage.deleteConfirm', { name: item.bucketName }), t('common.tip'), { type: 'warning' })
  await storageApi.removeBucket(item.bucketName)
  ElMessage.success(t('common.deleteSuccess'))
  await load()
}

onMounted(load)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('storage.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('storage.desc') }}</p>
      </div>
      <LwButton size="sm" @click="createVisible = true">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('storage.create') }}
      </LwButton>
    </div>

    <LwTableCard
      :title="t('storage.title')"
      :total="buckets.length"
      :loading="loading"
      :error="error"
      :empty="!buckets.length"
      :empty-text="t('common.empty')"
      @retry="load"
    >
      <template #skeleton>
        <LwSkeleton :rows="4" />
      </template>

      <el-table :data="buckets" style="width: 100%">
        <el-table-column :label="t('storage.name')" min-width="240">
          <template #default="{ row }">
            <div class="lw-cell-title font-mono">{{ row.bucketName }}</div>
            <div class="lw-cell-sub">{{ t('storage.scope') }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('workspace.objects')" width="120">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ row.objectCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('files.size')" width="140">
          <template #default="{ row }">
            <span class="lw-cell-mono">{{ formatSize(row.totalSize) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('storage.protected')" width="130">
          <template #default="{ row }">
            <LwTag v-if="row.protectedBucket" tone="warning">{{ t('storage.protected') }}</LwTag>
            <span v-else class="lw-cell-sub">-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="130" fixed="right">
          <template #default="{ row }">
            <LwRowActions>
              <LwButton
                variant="text"
                size="sm"
                class="lw-text-danger"
                :disabled="row.protectedBucket"
                @click="remove(row)"
              >
                <template #icon><el-icon><Delete /></el-icon></template>
                {{ t('common.delete') }}
              </LwButton>
            </LwRowActions>
          </template>
        </el-table-column>
      </el-table>
    </LwTableCard>

    <el-dialog v-model="createVisible" :title="t('storage.create')" width="440px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('storage.name')">
          <el-input v-model="newBucket" maxlength="63" placeholder="my-bucket" />
        </el-form-item>
        <p class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">
          {{ t('storage.nameRule') }}
        </p>
      </el-form>
      <template #footer>
        <LwButton variant="ghost" @click="createVisible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="creating" :disabled="nameInvalid(newBucket.trim())" @click="create">
          {{ t('common.save') }}
        </LwButton>
      </template>
    </el-dialog>
  </div>
</template>
