<script setup lang="ts">
import { Plus, RefreshRight, Search, Upload } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { kbApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { KbChunkVO, KbDocVO, KbVO } from '@/types/api'

/** 知识库工作台：左库列表，右文档管理与分块检索 */

const DOC_STATE_TONE: Record<string, 'success' | 'warning' | 'muted' | 'error'> = {
  ready: 'success',
  processing: 'warning',
  pending: 'muted',
  failed: 'error',
}

const { t } = useI18n()
const { hasPerm } = usePermission()

/* ===== 知识库列表 ===== */

const kbList = ref<KbVO[]>([])
const kbLoading = ref(false)
const kbError = ref('')
const selectedKbId = ref('')
const selectedKb = computed(() => kbList.value.find(item => item.kbId === selectedKbId.value))

async function loadKbs(): Promise<void> {
  kbLoading.value = true
  kbError.value = ''
  try {
    const result = await kbApi.page({ page: 1, size: 100 })
    kbList.value = result.list
    if (!selectedKbId.value && kbList.value.length) {
      selectedKbId.value = kbList.value[0].kbId
    }
    if (selectedKbId.value) {
      await loadDocs()
    }
  } catch (e) {
    kbError.value = (e as Error).message
  } finally {
    kbLoading.value = false
  }
}

function selectKb(kb: KbVO): void {
  selectedKbId.value = kb.kbId
  docPage.value = 1
  void loadDocs()
}

/* ===== 知识库新建 / 编辑 ===== */

const kbDialogVisible = ref(false)
const kbSaving = ref(false)
const kbForm = reactive({ kbId: '', kbName: '', description: '' })

function openKbCreate(): void {
  Object.assign(kbForm, { kbId: '', kbName: '', description: '' })
  kbDialogVisible.value = true
}

function openKbEdit(kb: KbVO): void {
  Object.assign(kbForm, { kbId: kb.kbId, kbName: kb.kbName, description: kb.description ?? '' })
  kbDialogVisible.value = true
}

async function saveKb(): Promise<void> {
  kbSaving.value = true
  try {
    if (kbForm.kbId) {
      await kbApi.update({ ...kbForm })
    } else {
      await kbApi.create({ ...kbForm })
    }
    ElMessage.success(t('common.success'))
    kbDialogVisible.value = false
    await loadKbs()
  } finally {
    kbSaving.value = false
  }
}

async function removeKb(kb: KbVO): Promise<void> {
  await ElMessageBox.confirm(t('kb.confirmDeleteKb', { name: kb.kbName }), t('common.delete'), {
    type: 'warning',
  })
  await kbApi.remove(kb.kbId)
  if (selectedKbId.value === kb.kbId) {
    selectedKbId.value = ''
  }
  ElMessage.success(t('common.success'))
  await loadKbs()
}

/* ===== 文档管理 ===== */

const docs = ref<KbDocVO[]>([])
const docLoading = ref(false)
const docError = ref('')
const docPage = ref(1)
const docSize = ref(20)
const docTotal = ref(0)
const docKeyword = ref('')
/** 轮询句柄：存在待解析文档时定时刷新 */
let pollTimer: number | undefined

const docStateFilter = ref('')
const DOC_STATES = ['pending', 'processing', 'ready', 'failed'] as const

async function loadDocs(): Promise<void> {
  if (!selectedKbId.value) {
    docs.value = []
    docTotal.value = 0
    return
  }
  docLoading.value = true
  docError.value = ''
  try {
    const result = await kbApi.docPage({
      kbId: selectedKbId.value,
      page: docPage.value,
      size: docSize.value,
      keyword: docKeyword.value || undefined,
      state: docStateFilter.value || undefined,
    })
    docs.value = result.list
    docTotal.value = result.total
    schedulePoll()
  } catch (e) {
    docError.value = (e as Error).message
  } finally {
    docLoading.value = false
  }
}

/** 有 pending / processing 文档时轮询刷新状态，全部到终态即停 */
function schedulePoll(): void {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
  const parsing = docs.value.some(doc => doc.state === 'pending' || doc.state === 'processing')
  if (parsing) {
    pollTimer = window.setInterval(() => void loadDocs(), 3000)
  }
}

function searchDocs(): void {
  docPage.value = 1
  void loadDocs()
}

function changeDocPage(next: number): void {
  docPage.value = next
  void loadDocs()
}

const fileInput = ref<HTMLInputElement>()

function openUpload(): void {
  fileInput.value?.click()
}

async function onFileChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !selectedKbId.value) {
    return
  }
  try {
    await kbApi.upload(selectedKbId.value, file)
    ElMessage.success(t('common.success'))
    docPage.value = 1
    await loadDocs()
  } finally {
    // 错误提示由请求拦截器统一处理
  }
}

async function reparseDoc(doc: KbDocVO): Promise<void> {
  await kbApi.reparse(doc.docId)
  ElMessage.success(t('common.success'))
  await loadDocs()
}

async function removeDoc(doc: KbDocVO): Promise<void> {
  await ElMessageBox.confirm(t('kb.confirmDeleteDoc', { name: doc.docName }), t('common.delete'), {
    type: 'warning',
  })
  await kbApi.removeDoc(doc.docId)
  ElMessage.success(t('common.success'))
  await Promise.all([loadDocs(), loadKbs()])
}

function formatSize(value?: number): string {
  if (!value) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

/* ===== 分块检索 ===== */

const chunks = ref<KbChunkVO[]>([])
const searchLoading = ref(false)
const searchError = ref('')
const searchKeyword = ref('')
const searchScope = ref('')
const searchPage = ref(1)
const searchSize = ref(10)
const searchTotal = ref(0)
const searched = ref(false)

async function doSearch(): Promise<void> {
  if (!searchKeyword.value.trim()) {
    return
  }
  searchLoading.value = true
  searchError.value = ''
  searched.value = true
  try {
    const result = await kbApi.search({
      keyword: searchKeyword.value.trim(),
      kbId: searchScope.value || undefined,
      page: searchPage.value,
      size: searchSize.value,
    })
    chunks.value = result.list
    searchTotal.value = result.total
  } catch (e) {
    searchError.value = (e as Error).message
  } finally {
    searchLoading.value = false
  }
}

function changeSearchPage(next: number): void {
  searchPage.value = next
  void doSearch()
}

interface Segment {
  text: string
  hit: boolean
}

/** 按关键词把正文切成 [普通, 命中] 片段，纯文本渲染无 XSS 面 */
function highlight(content: string): Segment[] {
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    return [{ text: content, hit: false }]
  }
  const segments: Segment[] = []
  const lower = content.toLowerCase()
  const needle = keyword.toLowerCase()
  let cursor = 0
  for (let index = lower.indexOf(needle); index >= 0; index = lower.indexOf(needle, cursor)) {
    if (index > cursor) {
      segments.push({ text: content.slice(cursor, index), hit: false })
    }
    segments.push({ text: content.slice(index, index + needle.length), hit: true })
    cursor = index + needle.length
  }
  if (cursor < content.length) {
    segments.push({ text: content.slice(cursor), hit: false })
  }
  return segments
}

onMounted(() => {
  void loadKbs()
})

onBeforeUnmount(() => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
  }
})
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('kb.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('kb.desc') }}</p>
      </div>
      <LwButton v-if="hasPerm('wb:kb:create')" data-test="kb-create" @click="openKbCreate">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('common.create') }}
      </LwButton>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4 items-start">
      <!-- 左：知识库列表 -->
      <LwPageState :loading="kbLoading" :error="kbError" :empty="!kbList.length" @retry="loadKbs">
        <div class="flex flex-col gap-2">
          <button
            v-for="kb in kbList"
            :key="kb.kbId"
            type="button"
            class="text-left rounded-xl border p-4 transition-all duration-200 cursor-pointer hover:-translate-y-0.5 hover:shadow-md"
            :class="kb.kbId === selectedKbId
              ? 'border-[var(--lw-primary)] bg-[var(--lw-secondary-selected)] shadow-sm'
              : 'border-[var(--lw-border)] bg-[var(--lw-surface)]'"
            @click="selectKb(kb)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-bold text-sm truncate" :style="{ color: 'var(--lw-primary)' }">{{ kb.kbName }}</span>
              <span class="flex items-center gap-1 shrink-0" @click.stop>
                <LwButton
                  v-if="hasPerm('wb:kb:update')"
                  variant="text" size="sm" @click="openKbEdit(kb)"
                >{{ t('common.edit') }}</LwButton>
                <LwButton
                  v-if="hasPerm('wb:kb:delete')"
                  variant="text" size="sm" @click="removeKb(kb)"
                >{{ t('common.delete') }}</LwButton>
              </span>
            </div>
            <p v-if="kb.description" class="text-xs mt-1 opacity-70 line-clamp-2">{{ kb.description }}</p>
            <div class="flex gap-3 mt-2 text-xs opacity-60">
              <span>{{ t('kb.docCount') }} {{ kb.docCount ?? 0 }}</span>
              <span>{{ t('kb.chunkCount') }} {{ kb.chunkCount ?? 0 }}</span>
            </div>
          </button>
        </div>
      </LwPageState>

      <!-- 右：文档 + 检索 -->
      <div class="flex flex-col gap-4 min-w-0">
        <LwCard :title="selectedKb ? `${t('kb.docs')} · ${selectedKb.kbName}` : t('kb.docs')">
          <template #actions>
            <span
              v-if="hasPerm('wb:kb:upload') && selectedKbId"
              class="inline-flex items-center gap-2 text-xs opacity-60"
            >{{ t('kb.uploadTip') }}</span>
            <LwButton
              v-if="hasPerm('wb:kb:upload') && selectedKbId"
              variant="secondary" size="sm" data-test="kb-upload" @click="openUpload"
            >
              <template #icon><el-icon><Upload /></el-icon></template>
              {{ t('kb.upload') }}
            </LwButton>
            <input
              ref="fileInput" type="file" hidden
              accept=".txt,.md,.csv,.json,.html,.xml,.log,.docx,.pdf"
              @change="onFileChosen"
            >
          </template>
          <div class="flex gap-2 mb-4 max-w-lg">
            <el-input v-model="docKeyword" :placeholder="t('common.search')" clearable @keyup.enter="searchDocs" />
            <el-select v-model="docStateFilter" clearable class="w-32" :placeholder="t('kb.state')" @change="searchDocs">
              <el-option
                v-for="state in DOC_STATES" :key="state" :value="state"
                :label="t(`kb.state_${state}`)"
              />
            </el-select>
            <LwButton variant="secondary" @click="searchDocs">{{ t('common.search') }}</LwButton>
          </div>
          <LwPageState :loading="docLoading" :error="docError" :empty="!docs.length" @retry="loadDocs">
            <el-table :data="docs" class="lw-table">
              <el-table-column :label="t('kb.docName')" prop="docName" min-width="200" show-overflow-tooltip />
              <el-table-column :label="t('kb.size')" width="100">
                <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
              </el-table-column>
              <el-table-column :label="t('kb.chunkCount')" width="80">
                <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column :label="t('kb.state')" width="110">
                <template #default="{ row }">
                  <LwTag :tone="DOC_STATE_TONE[row.state] ?? 'muted'">{{ t(`kb.state_${row.state}`) }}</LwTag>
                </template>
              </el-table-column>
              <el-table-column :label="t('kb.errorMessage')" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <span v-if="row.state === 'failed'" class="text-xs">{{ row.errorMessage }}</span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column width="150" fixed="right">
                <template #default="{ row }">
                  <LwButton
                    v-if="hasPerm('wb:kb:upload') && (row.state === 'failed' || row.state === 'ready')"
                    variant="text" size="sm" @click="reparseDoc(row)"
                  >
                    <template #icon><el-icon><RefreshRight /></el-icon></template>
                    {{ t('kb.reparse') }}
                  </LwButton>
                  <LwButton
                    v-if="hasPerm('wb:kb:delete')"
                    variant="text" size="sm" @click="removeDoc(row)"
                  >{{ t('common.delete') }}</LwButton>
                </template>
              </el-table-column>
              <template #empty><span /></template>
            </el-table>
            <div v-if="docTotal > docSize" class="flex justify-end mt-4">
              <el-pagination
                layout="prev, pager, next"
                :total="docTotal"
                :page-size="docSize"
                :current-page="docPage"
                @current-change="changeDocPage"
              />
            </div>
          </LwPageState>
        </LwCard>

        <LwCard :title="t('kb.searchTitle')">
          <div class="flex gap-2 mb-4 max-w-xl">
            <el-select v-model="searchScope" class="w-44" :placeholder="t('kb.scopeAll')">
              <el-option :label="t('kb.scopeAll')" value="" />
              <el-option
                v-for="kb in kbList" :key="kb.kbId" :label="kb.kbName" :value="kb.kbId"
              />
            </el-select>
            <el-input
              v-model="searchKeyword" :placeholder="t('kb.searchPlaceholder')" clearable
              data-test="kb-search-input" @keyup.enter="searchPage = 1; doSearch()"
            />
            <LwButton data-test="kb-search" :loading="searchLoading" @click="searchPage = 1; doSearch()">
              <template #icon><el-icon><Search /></el-icon></template>
              {{ t('common.search') }}
            </LwButton>
          </div>
          <LwPageState
            :loading="searchLoading" :error="searchError"
            :empty="searched && !chunks.length" :empty-text="t('kb.emptyResult')" @retry="doSearch"
          >
            <div class="flex flex-col gap-3">
              <article
                v-for="chunk in chunks" :key="chunk.chunkId"
                class="rounded-xl border p-4 transition-colors border-[var(--lw-border)] bg-[var(--lw-surface)] hover:border-[var(--lw-primary)]"
              >
                <div class="flex items-center justify-between gap-3 mb-2">
                  <span class="text-xs font-bold truncate" :style="{ color: 'var(--lw-primary)' }">
                    {{ chunk.docName }} · {{ t('kb.seq') }} {{ chunk.seq }}
                  </span>
                  <LwTag tone="muted">{{ t('kb.score') }} {{ (chunk.score ?? 0).toFixed(3) }}</LwTag>
                </div>
                <p class="text-sm leading-relaxed whitespace-pre-wrap break-words m-0">
                  <template v-for="(seg, i) in highlight(chunk.content)" :key="i">
                    <mark v-if="seg.hit" class="lw-kb-hit">{{ seg.text }}</mark>
                    <template v-else>{{ seg.text }}</template>
                  </template>
                </p>
              </article>
            </div>
            <div v-if="searchTotal > searchSize" class="flex justify-end mt-4">
              <el-pagination
                layout="prev, pager, next"
                :total="searchTotal"
                :page-size="searchSize"
                :current-page="searchPage"
                @current-change="changeSearchPage"
              />
            </div>
          </LwPageState>
          <LwEmpty v-if="!searched" :description="t('kb.searchPlaceholder')" />
        </LwCard>
      </div>
    </div>

    <!-- 新建 / 编辑知识库 -->
    <el-dialog v-model="kbDialogVisible" :title="kbForm.kbId ? t('kb.updateTitle') : t('kb.createTitle')" width="520">
      <el-form :model="kbForm" label-width="90">
        <el-form-item :label="t('kb.kbName')" required>
          <el-input v-model="kbForm.kbName" :placeholder="t('kb.namePlaceholder')" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('kb.description')">
          <el-input v-model="kbForm.description" type="textarea" :rows="3" maxlength="512" show-word-limit
                    :placeholder="t('kb.descPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LwButton variant="secondary" @click="kbDialogVisible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="kbSaving" @click="saveKb">{{ t('common.save') }}</LwButton>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lw-kb-hit {
  background: var(--lw-secondary-tint);
  color: var(--lw-primary);
  border-radius: 3px;
  padding: 0 2px;
  font-weight: 600;
}
</style>
