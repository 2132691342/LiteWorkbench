<script setup lang="ts">
import { Folder, Plus, RefreshRight, Search, Upload } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwRowActions from '@/components/ui/LwRowActions.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTableCard from '@/components/ui/LwTableCard.vue'
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

function changeDocSize(next: number): void {
  docSize.value = next
  docPage.value = 1
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
  if (value < 1024) return t('kb.sizeB', { n: value })
  if (value < 1024 * 1024) return t('kb.sizeKB', { n: (value / 1024).toFixed(1) })
  return t('kb.sizeMB', { n: (value / 1024 / 1024).toFixed(1) })
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

function changeSearchSize(next: number): void {
  searchSize.value = next
  searchPage.value = 1
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
      <LwPageState :loading="kbLoading" :error="kbError" @retry="loadKbs">
        <!-- 窄列空态用行内提示而非整块 LwEmpty：280px 侧列里大图标空态只会显得空洞 -->
        <div
          v-if="!kbList.length"
          class="rounded-card border p-4 flex items-center gap-2 text-xs leading-relaxed"
          style="border-color: var(--lw-border); color: var(--lw-ink-muted)"
        >
          <el-icon :size="14" class="shrink-0"><Folder /></el-icon>
          <span>{{ t('kb.listEmpty') }}</span>
        </div>
        <div v-else class="flex flex-col gap-2">
          <div
            v-for="kb in kbList"
            :key="kb.kbId"
            class="text-left rounded-card border p-4 transition-all duration-200 cursor-pointer hover:-translate-y-0.5 hover:lw-shadow-md"
            :class="kb.kbId === selectedKbId
              ? 'border-lw-primary bg-lw-secondarySelected lw-shadow-sm'
              : 'border-lw-border bg-lw-surface'"
            role="button"
            tabindex="0"
            :aria-selected="kb.kbId === selectedKbId"
            @click="selectKb(kb)"
            @keydown.enter="selectKb(kb)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-bold text-sm truncate" :style="{ color: 'var(--lw-primary-ink)' }">{{ kb.kbName }}</span>
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
          </div>
        </div>
      </LwPageState>

      <div class="flex flex-col gap-4 min-w-0">
        <LwTableCard
          :title="selectedKb ? `${t('kb.docs')} · ${selectedKb.kbName}` : t('kb.docs')"
          :total="docTotal"
          :loading="docLoading"
          :error="docError"
          :empty="!docs.length"
          :empty-text="t('kb.emptyResult')"
          @retry="loadDocs"
        >
          <template #toolbar>
            <span v-if="hasPerm('wb:kb:upload') && selectedKbId" class="text-xs mr-1" style="color: var(--lw-ink-muted)">
              {{ t('kb.uploadTip') }}
            </span>
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

          <template #filter>
            <el-input v-model="docKeyword" style="width: 220px" :placeholder="t('common.search')" clearable @keyup.enter="searchDocs" />
            <el-select v-model="docStateFilter" clearable style="width: 140px" :placeholder="t('kb.state')" @change="searchDocs">
              <el-option
                v-for="state in DOC_STATES" :key="state" :value="state"
                :label="t(`kb.state_${state}`)"
              />
            </el-select>
            <LwButton variant="ghost" size="sm" @click="searchDocs">{{ t('common.search') }}</LwButton>
          </template>

          <template #skeleton>
            <LwSkeleton :rows="5" />
          </template>

          <el-table :data="docs" style="width: 100%">
            <el-table-column :label="t('kb.docName')" prop="docName" min-width="200" show-overflow-tooltip />
            <el-table-column :label="t('kb.size')" width="100">
              <template #default="{ row }"><span class="lw-cell-mono">{{ formatSize(row.fileSize) }}</span></template>
            </el-table-column>
            <el-table-column :label="t('kb.chunkCount')" width="90">
              <template #default="{ row }"><span class="lw-cell-mono">{{ row.chunkCount ?? 0 }}</span></template>
            </el-table-column>
            <el-table-column :label="t('kb.state')" width="110">
              <template #default="{ row }">
                <LwTag :tone="DOC_STATE_TONE[row.state] ?? 'muted'">{{ t(`kb.state_${row.state}`) }}</LwTag>
              </template>
            </el-table-column>
            <el-table-column :label="t('kb.errorMessage')" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.state === 'failed'" class="lw-cell-sub">{{ row.errorMessage }}</span>
                <span v-else class="lw-cell-sub">-</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="170" fixed="right">
              <template #default="{ row }">
                <LwRowActions>
                  <LwButton
                    v-if="hasPerm('wb:kb:upload') && (row.state === 'failed' || row.state === 'ready')"
                    variant="text" size="sm" @click="reparseDoc(row)"
                  >
                    <template #icon><el-icon><RefreshRight /></el-icon></template>
                    {{ t('kb.reparse') }}
                  </LwButton>
                  <template v-if="hasPerm('wb:kb:delete')">
                    <span class="lw-rowacts__sep" aria-hidden="true" />
                    <LwButton variant="text" size="sm" class="lw-text-danger" @click="removeDoc(row)">
                      {{ t('common.delete') }}
                    </LwButton>
                  </template>
                </LwRowActions>
              </template>
            </el-table-column>
          </el-table>

          <template #pager>
            <el-pagination
              layout="total, sizes, prev, pager, next"
              :total="docTotal"
              :page-size="docSize"
              :page-sizes="[10, 20, 50]"
              :current-page="docPage"
              @current-change="changeDocPage"
              @size-change="changeDocSize"
            />
          </template>
        </LwTableCard>

        <LwCard :title="t('kb.searchTitle')">
          <div class="flex flex-wrap items-center gap-2 mb-4">
            <el-select v-model="searchScope" class="w-44 shrink-0" :placeholder="t('kb.scopeAll')">
              <el-option :label="t('kb.scopeAll')" value="" />
              <el-option
                v-for="kb in kbList" :key="kb.kbId" :label="kb.kbName" :value="kb.kbId"
              />
            </el-select>
            <el-input
              v-model="searchKeyword" class="flex-1 min-w-40" :placeholder="t('kb.searchPlaceholder')" clearable
              data-test="kb-search-input" @keyup.enter="searchPage = 1; doSearch()"
            />
            <LwButton class="shrink-0" data-test="kb-search" :loading="searchLoading" @click="searchPage = 1; doSearch()">
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
                class="rounded-card border p-4 transition-colors border-lw-border bg-lw-surface hover:border-lw-primary"
              >
                <div class="flex items-center justify-between gap-3 mb-2">
                  <span class="text-xs font-bold truncate" :style="{ color: 'var(--lw-primary-ink)' }">
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
                layout="total, sizes, prev, pager, next"
                :total="searchTotal"
                :page-size="searchSize"
                :page-sizes="[10, 20, 50]"
                :current-page="searchPage"
                @current-change="changeSearchPage"
                @size-change="changeSearchSize"
              />
            </div>
          </LwPageState>
          <LwEmpty v-if="!searched" :description="t('kb.searchPlaceholder')" />
        </LwCard>
      </div>
    </div>

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
  color: var(--lw-primary-ink);
  border-radius: 3px;
  padding: 0 2px;
  font-weight: 600;
}
</style>
