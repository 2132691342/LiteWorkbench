<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
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
  <div class="lw-page flex flex-col gap-4">
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

    <LwCard>
      <LwPageState :loading="loading" :error="error" :empty="!buckets.length" :skeleton-rows="4" @retry="load">
        <div class="flex flex-col gap-2">
          <div
            v-for="item in buckets"
            :key="item.bucketName"
            class="px-4 py-3 rounded-lg border flex flex-wrap items-center gap-3 transition-all duration-200 hover:lw-shadow-sm"
            style="border-color: var(--lw-border)"
          >
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-bold text-sm font-mono">{{ item.bucketName }}</span>
                <LwTag v-if="item.protectedBucket" tone="warning">{{ t('storage.protected') }}</LwTag>
              </div>
              <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">
                {{ item.objectCount ?? 0 }} {{ t('workspace.objects') }} · {{ formatSize(item.totalSize) }}
              </p>
            </div>
            <LwButton
              variant="text"
              size="sm"
              :disabled="item.protectedBucket"
              @click="remove(item)"
            >
              <template #icon><el-icon><Delete /></el-icon></template>
              {{ t('common.delete') }}
            </LwButton>
          </div>
        </div>
      </LwPageState>
    </LwCard>

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
