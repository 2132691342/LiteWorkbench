<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue'
import { defineAsyncComponent, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
/** 预览依赖较重，按需异步加载避免首屏体积膨胀 */
const VuePdfEmbed = defineAsyncComponent(() => import('vue-pdf-embed'))
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
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
const uploading = ref(false)
const uploadInput = ref<HTMLInputElement | null>(null)

/** 在线预览：图片直显，pdf / docx / 表格按类型渲染，其余只给下载 */
const previewOpen = ref(false)
const previewUrl = ref('')
const previewKind = ref<'image' | 'pdf' | 'docx' | 'sheet' | 'unknown'>('unknown')
const previewHtml = ref('')
const previewLoading = ref(false)
const docxEl = shallowRef<HTMLDivElement | null>(null)

function kindOf(name: string): 'image' | 'pdf' | 'docx' | 'sheet' | 'unknown' {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image'
  if (ext === 'pdf') return 'pdf'
  if (ext === 'docx') return 'docx'
  if (['xlsx', 'xls', 'csv'].includes(ext)) return 'sheet'
  return 'unknown'
}

async function preview(item: FileVO): Promise<void> {
  previewKind.value = kindOf(item.fileName)
  previewUrl.value = await fileApi.url(item.fileId)
  previewHtml.value = ''
  previewOpen.value = true
  if (previewKind.value === 'unknown') return
  if (previewKind.value === 'docx') {
    await renderDocx()
    return
  }
  if (previewKind.value === 'sheet') {
    await renderSheet()
  }
}

async function renderDocx(): Promise<void> {
  previewLoading.value = true
  try {
    const { renderAsync } = await import('docx-preview')
    const response = await fetch(previewUrl.value)
    const blob = await response.blob()
    await renderAsync(blob, docxEl.value as HTMLDivElement)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    previewLoading.value = false
  }
}

function openPreviewUrl(): void {
  window.open(previewUrl.value, '_blank')
}

async function renderSheet(): Promise<void> {
  previewLoading.value = true
  try {
    const XLSX = await import('xlsx')
    const response = await fetch(previewUrl.value)
    const buffer = await response.arrayBuffer()
    const workbook = XLSX.read(buffer, { type: 'array' })
    const sheet = workbook.Sheets[workbook.SheetNames[0]]
    previewHtml.value = XLSX.utils.sheet_to_html(sheet)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    previewLoading.value = false
  }
}

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
  uploading.value = true
  try {
    await fileApi.upload(file)
    ElMessage.success(t('common.success'))
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    uploading.value = false
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

function changeSize(next: number): void {
  size.value = next
  page.value = 1
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
      <LwButton :loading="uploading" @click="pickFile">
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
              <LwTag tone="muted">{{ row.category }}</LwTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('logs.time')" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column width="160" fixed="right">
            <template #default="{ row }">
              <LwButton variant="text" size="sm" @click="preview(row)">{{ t('files.preview') }}</LwButton>
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
            layout="total, sizes, prev, pager, next"
            :total="total"
            :page-size="size"
            :page-sizes="[10, 20, 50]"
            :current-page="page"
            @current-change="changePage"
            @size-change="changeSize"
          />
        </div>
      </LwPageState>
    </LwCard>

    <el-drawer v-model="previewOpen" :title="t('files.preview')" size="60%">
      <div class="flex flex-col gap-3 h-full">
        <img v-if="previewKind === 'image'" :src="previewUrl" class="max-h-full object-contain rounded-control" />
        <VuePdfEmbed v-else-if="previewKind === 'pdf'" :source="previewUrl" class="h-full" />
        <div v-else-if="previewKind === 'docx'" ref="docxEl" class="lw-md overflow-auto" />
        <div v-else-if="previewKind === 'sheet'" class="overflow-auto" v-html="previewHtml" />
        <div v-else class="flex flex-col items-center gap-3 py-16">
          <LwEmpty :description="t('files.previewUnsupported')" />
          <LwButton size="sm" variant="ghost" @click="openPreviewUrl">
            {{ t('files.download') }}
          </LwButton>
        </div>
        <div v-if="previewLoading" class="lw-subtitle">{{ t('common.loading') }}</div>
      </div>
    </el-drawer>
  </div>
</template>
