<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import { fileApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { FileVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 文件空间：MinIO 对象按用户前缀隔离，上传直传 / 下载走预签名 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const files = ref<FileVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const uploadInput = ref<HTMLInputElement | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await fileApi.page({ page: page.value, size: size.value })
    files.value = result.list
    total.value = result.total
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function pickFile(): void {
  uploadInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 100 * 1024 * 1024) {
    ElMessage.error(t('files.tooLarge'))
    return
  }
  loading.value = true
  try {
    await fileApi.upload(file)
    ElMessage.success(t('common.success'))
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function download(item: FileVO): Promise<void> {
  const url = await fileApi.url(item.fileId)
  window.open(url, '_blank')
}

async function remove(item: FileVO): Promise<void> {
  await ElMessageBox.confirm(t('files.confirmDelete', { name: item.fileName }), t('common.delete'), {
    type: 'warning',
  })
  await fileApi.remove(item.fileId)
  ElMessage.success(t('common.success'))
  await load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

onMounted(load)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('files.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('files.desc') }}</p>
      </div>
      <LwButton :loading="loading" @click="pickFile">
        <template #icon><el-icon><Upload /></el-icon></template>
        {{ t('files.upload') }}
      </LwButton>
      <input ref="uploadInput" type="file" class="hidden" @change="onFileChange" />
    </div>
    <LwCard>
      <LwPageState :loading="loading" :error="error" :empty="!files.length" @retry="load">
        <el-table :data="files" class="lw-table">
          <el-table-column :label="t('files.fileName')" prop="fileName" min-width="220" show-overflow-tooltip />
          <el-table-column :label="t('files.size')" width="120">
            <template #default="{ row }">{{ (row.fileSize / 1024).toFixed(1) }} KB</template>
          </el-table-column>
          <el-table-column :label="t('files.category')" width="110">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.category }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('logs.time')" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column width="160" fixed="right">
            <template #default="{ row }">
              <LwButton variant="text" size="sm" @click="download(row)">{{ t('files.download') }}</LwButton>
              <LwButton v-if="hasPerm('wb:file:delete')" variant="text" size="sm" @click="remove(row)">
                {{ t('common.delete') }}
              </LwButton>
            </template>
          </el-table-column>
          <template #empty><span /></template>
        </el-table>
        <div v-if="total > size" class="flex justify-end mt-4">
          <el-pagination
            layout="prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="page"
            @current-change="changePage"
          />
        </div>
      </LwPageState>
    </LwCard>
  </div>
</template>
