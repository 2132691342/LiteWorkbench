<script setup lang="ts">
import { Document, MagicStick, Plus, Refresh } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import { i18nChangeLanguage } from '@wangeditor/editor'
import '@wangeditor/editor/dist/css/style.css'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwMarkdown from '@/components/ui/LwMarkdown.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwSegmented from '@/components/ui/LwSegmented.vue'
import { docApi, kbApi, openAssistStream } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { DocVO, DocVersionVO, KbVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 创作台：左列表 + 中编辑器 + 右 AI 辅助，保存即写版本并同步全文索引 */
const { t, locale } = useI18n()
const { hasPerm } = usePermission()

type DocType = 'markdown' | 'rich' | 'code'

const docs = ref<DocVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)
const error = ref('')

const current = ref<DocVO | null>(null)
const title = ref('')
const content = ref('')
const category = ref('')
const tags = ref('')
const summary = ref('')
const docType = ref<DocType>('markdown')
const sourceKbId = ref('')
const dirty = ref(false)
const saving = ref(false)
const opening = ref(false)
const mode = ref<'edit' | 'preview'>('edit')

/** 编辑 / 预览切换：离开编辑态时同步编辑器内容 */
function switchMode(next: string | number): void {
  const target = next === 'preview' ? 'preview' : 'edit'
  if (target === 'preview' && isRich.value) {
    content.value = editorRef.value?.getHtml() ?? content.value
  }
  mode.value = target
}

const versions = ref<DocVersionVO[]>([])
const versionDrawer = ref(false)
const versionLoading = ref(false)

const actions = ['continue', 'polish', 'expand', 'summary', 'outline', 'translate']
const assistAction = ref('continue')
const assistInstruction = ref('')
const assistKbId = ref('')
const assistText = ref('')
const assistRunning = ref(false)
const selectedText = ref('')

const kbOptions = ref<KbVO[]>([])

/** 富文本编辑器实例：非响应式，避免 Vue 代理内部状态 */
const editorRef = shallowRef<any>(null)
const editorConfig = { placeholder: t('writer.titlePlaceholder') }

const canEdit = computed(() => hasPerm('wb:doc:update') && current.value !== null)
const isRich = computed(() => docType.value === 'rich')
const wordCount = computed(() => content.value.replace(/\s+/g, '').length)

async function loadKbOptions(): Promise<void> {
  try {
    const result = await kbApi.page({ page: 1, size: 100 })
    kbOptions.value = result.list
  } catch {
    kbOptions.value = []
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = keyword.value
      ? await docApi.search({ keyword: keyword.value, page: page.value, size: size.value })
      : await docApi.page({ page: page.value, size: size.value })
    docs.value = result.list
    total.value = result.total
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function open(doc: DocVO): Promise<void> {
  if (dirty.value && !(await confirmDiscard())) {
    return
  }
  opening.value = true
  try {
    const detail = await docApi.detail(doc.docId)
    current.value = detail
    title.value = detail.docTitle
    content.value = detail.content ?? ''
    category.value = detail.category ?? ''
    tags.value = detail.tags ?? ''
    summary.value = detail.summary ?? ''
    docType.value = (detail.docType ?? 'markdown') as DocType
    sourceKbId.value = detail.sourceKbId ?? ''
    dirty.value = false
    mode.value = 'edit'
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    opening.value = false
  }
}

async function confirmDiscard(): Promise<boolean> {
  try {
    await ElMessageBox.confirm(t('writer.unsaved'), t('common.confirm'), { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function createDoc(): Promise<void> {
  const docId = await docApi.create({ docTitle: t('writer.newDoc'), docType: 'markdown', content: '' })
  ElMessage.success(t('common.success'))
  await load()
  await open({ docId, docTitle: t('writer.newDoc') })
}

async function save(): Promise<void> {
  if (!current.value) return
  saving.value = true
  try {
    await docApi.update({
      docId: current.value.docId,
      docTitle: title.value,
      docType: docType.value,
      category: category.value,
      tags: tags.value,
      summary: summary.value,
      content: content.value,
      sourceKbId: sourceKbId.value,
    })
    dirty.value = false
    ElMessage.success(t('writer.saved'))
    await load()
    current.value = { ...current.value, docTitle: title.value }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    saving.value = false
  }
}

async function remove(): Promise<void> {
  if (!current.value) return
  await ElMessageBox.confirm(t('writer.confirmDelete', { name: title.value }), t('common.delete'), {
    type: 'warning',
  })
  await docApi.remove(current.value.docId)
  ElMessage.success(t('common.success'))
  current.value = null
  content.value = ''
  dirty.value = false
  await load()
}

async function openVersions(): Promise<void> {
  if (!current.value) return
  versionDrawer.value = true
  versionLoading.value = true
  try {
    const result = await docApi.versionPage(current.value.docId)
    versions.value = result.list
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    versionLoading.value = false
  }
}

async function restore(version: DocVersionVO): Promise<void> {
  await ElMessageBox.confirm(t('writer.restoreConfirm', { version: version.versionNo }), t('common.confirm'), {
    type: 'warning',
  })
  await docApi.restore(version.versionId)
  ElMessage.success(t('common.success'))
  versionDrawer.value = false
  await open(current.value as DocVO)
}

/** 取选区：富文本走编辑器 API，Markdown 与代码走 textarea */
function captureSelection(): string {
  if (isRich.value) {
    return editorRef.value?.getSelectionText?.() ?? ''
  }
  const el = document.getElementById('doc-editor') as HTMLTextAreaElement | null
  if (!el) return ''
  return content.value.slice(el.selectionStart, el.selectionEnd)
}

/** 流式辅助的中断句柄：断连即取消服务端模型调用 */
let assistAbort: ((reason?: string) => void) | null = null

function assist(): void {
  const selection = captureSelection()
  selectedText.value = selection
  if (!selection && !current.value) {
    ElMessage.warning(t('writer.assistEmpty'))
    return
  }
  assistRunning.value = true
  assistText.value = ''
  assistAbort = openAssistStream(
    {
      docId: current.value?.docId,
      action: assistAction.value,
      selection,
      instruction: assistInstruction.value,
      kbId: assistKbId.value || undefined,
    },
    {
      onDelta: (text) => {
        assistText.value += text
      },
      onEnd: (payload) => {
        assistRunning.value = false
        assistAbort = null
        if (payload.text) {
          assistText.value = payload.text
        }
      },
      onError: (message) => {
        assistRunning.value = false
        assistAbort = null
        error.value = message
      },
    },
  )
}

/** 停止生成：断开流式连接，服务端在下一检查点取消模型调用 */
function stopAssist(): void {
  assistAbort?.('user-stop')
  assistAbort = null
  assistRunning.value = false
}

function appendResult(): void {
  if (!assistText.value) return
  content.value = `${content.value}\n\n${assistText.value}`
  dirty.value = true
  if (isRich.value) {
    editorRef.value?.setHtml?.(content.value)
  }
}

function replaceSelection(): void {
  if (!assistText.value) return
  if (selectedText.value && content.value.includes(selectedText.value)) {
    content.value = content.value.replace(selectedText.value, assistText.value)
  } else {
    content.value = assistText.value
  }
  dirty.value = true
  if (isRich.value) {
    editorRef.value?.setHtml?.(content.value)
  }
}

async function copyResult(): Promise<void> {
  await navigator.clipboard.writeText(assistText.value)
  ElMessage.success(t('kb.copied'))
}

function onContentInput(): void {
  dirty.value = true
}

function onEditorCreated(editor: unknown): void {
  editorRef.value = editor
}

function changePage(next: number): void {
  page.value = next
  void load()
}

function search(): void {
  page.value = 1
  void load()
}

watch(locale, (value) => {
  i18nChangeLanguage(value === 'en-US' ? 'en' : 'zh-CN')
})

watch(content, () => {
  if (content.value !== (current.value?.content ?? '')) {
    dirty.value = true
  }
})

onBeforeUnmount(() => {
  assistAbort?.('unmount')
  assistAbort = null
  editorRef.value?.destroy?.()
  editorRef.value = null
})

i18nChangeLanguage(locale.value === 'en-US' ? 'en' : 'zh-CN')
void load()
void loadKbOptions()
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('writer.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('writer.desc') }}</p>
      </div>
      <div class="flex items-center gap-2">
        <el-input
          v-model="keyword"
          :placeholder="t('writer.searchPlaceholder')"
          clearable
          class="w-56"
          @keyup.enter="search"
          @clear="search"
        />
        <LwButton v-if="hasPerm('wb:doc:create')" @click="createDoc">
          <template #icon><el-icon><Plus /></el-icon></template>
          {{ t('writer.newDoc') }}
        </LwButton>
      </div>
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-[260px_minmax(0,1fr)_320px] gap-4">
      <!-- 文档列表 -->
      <LwCard class="p-4 flex flex-col gap-3 max-h-[calc(100vh-220px)] overflow-auto">
        <div class="lw-label">{{ t('writer.docList') }}</div>
        <LwPageState :loading="loading" :error="error" :skeleton-rows="5" @retry="load">
          <LwEmpty v-if="!docs.length" :description="t('writer.emptyResult')" />
          <button
            v-for="doc in docs"
            :key="doc.docId"
            class="w-full text-left px-3 py-2.5 rounded-control border transition-all duration-200"
            :class="current?.docId === doc.docId
              ? 'border-lw-secondary bg-lw-secondary-selected'
              : 'border-transparent hover:bg-lw-primary-tint'"
            @click="open(doc)"
          >
            <div class="flex items-center gap-2">
              <el-icon class="text-lw-secondary shrink-0"><Document /></el-icon>
              <span class="text-sm font-bold text-lw-ink truncate">{{ doc.docTitle }}</span>
            </div>
            <div class="flex items-center gap-2 mt-1 text-xs text-lw-muted">
              <span>v{{ doc.versionNo ?? 1 }}</span>
              <span>{{ doc.wordCount ?? 0 }} {{ t('writer.wordCount') }}</span>
              <span>{{ formatTime(doc.updatedAt) }}</span>
            </div>
          </button>
          <div v-if="total > size" class="flex justify-center mt-2">
            <el-pagination
              layout="prev, pager, next"
              small
              :total="total"
              :page-size="size"
              :current-page="page"
              @current-change="changePage"
            />
          </div>
        </LwPageState>
      </LwCard>

      <!-- 编辑器 -->
      <LwCard class="p-5 flex flex-col gap-4 min-h-[560px]">
        <template v-if="current">
          <div class="flex flex-wrap items-center gap-3">
            <input v-model="title" class="lw-input flex-1 min-w-48 font-bold" @input="onContentInput" />
            <el-select v-model="docType" class="w-32" @change="onContentInput">
              <el-option :label="t('writer.type_markdown')" value="markdown" />
              <el-option :label="t('writer.type_rich')" value="rich" />
              <el-option :label="t('writer.type_code')" value="code" />
            </el-select>
            <LwSegmented
              :model-value="mode"
              :options="[
                { value: 'edit', label: t('writer.edit') },
                { value: 'preview', label: t('writer.preview') },
              ]"
              @update:model-value="switchMode"
            />
            <LwButton variant="text" size="sm" @click="openVersions">
              <template #icon><el-icon><Refresh /></el-icon></template>
              {{ t('writer.versions') }}
            </LwButton>
            <LwButton
              v-if="canEdit"
              size="sm"
              :loading="saving"
              :disabled="!dirty"
              @click="save"
            >
              {{ saving ? t('writer.saving') : t('writer.save') }}
            </LwButton>
          </div>

          <div class="grid grid-cols-2 gap-3">
            <input v-model="category" class="lw-input" :placeholder="t('writer.category')" @input="onContentInput" />
            <input v-model="tags" class="lw-input" :placeholder="t('writer.tagsPlaceholder')" @input="onContentInput" />
          </div>

          <!-- 富文本模式 -->
          <div v-if="isRich && mode === 'edit'" class="flex-1 flex flex-col gap-2 min-h-96">
            <Toolbar :editor="editorRef" mode="default" class="border border-lw-border rounded-control" />
            <Editor
              :model-value="content"
              :default-config="editorConfig"
              mode="default"
              class="flex-1 min-h-80 border border-lw-border rounded-control overflow-hidden"
              @update:model-value="(html: string) => { content = html; dirty = true }"
              @on-created="onEditorCreated"
            />
          </div>

          <!-- Markdown / 代码模式 -->
          <textarea
            v-else-if="mode === 'edit'"
            id="doc-editor"
            v-model="content"
            class="lw-input flex-1 min-h-96 font-mono leading-6 resize-y"
            :placeholder="t('writer.titlePlaceholder')"
            @input="onContentInput"
          />

          <!-- 预览 -->
          <div v-else class="flex-1 min-h-96 overflow-auto pr-1">
            <LwMarkdown v-if="docType === 'markdown'" :content="content" />
            <div v-else-if="docType === 'rich'" class="lw-md" v-html="content"></div>
            <pre v-else class="lw-input font-mono whitespace-pre-wrap min-h-96">{{ content }}</pre>
          </div>

          <div class="flex items-center justify-between text-xs text-lw-muted">
            <span>{{ wordCount }} {{ t('writer.wordCount') }}</span>
            <span>{{ dirty ? t('writer.unsaved') : t('writer.saved') }}</span>
          </div>
        </template>
        <LwEmpty v-else :description="t('writer.emptyResult')">
          <template #empty-action>
            <LwButton v-if="hasPerm('wb:doc:create')" @click="createDoc">{{ t('writer.newDoc') }}</LwButton>
          </template>
        </LwEmpty>
      </LwCard>

      <!-- AI 辅助 -->
      <LwCard class="p-5 flex flex-col gap-4">
        <div class="flex items-center gap-2 pb-2 border-b border-lw-border">
          <el-icon class="text-lw-secondary"><MagicStick /></el-icon>
          <span class="text-sm font-bold text-lw-ink">{{ t('writer.assistTitle') }}</span>
        </div>

        <div class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('writer.assistAction') }}</span>
          <el-select v-model="assistAction" class="w-full">
            <el-option v-for="item in actions" :key="item" :label="t(`writer.assist_${item}`)" :value="item" />
          </el-select>
        </div>

        <div class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('writer.assistKb') }}</span>
          <el-select v-model="assistKbId" class="w-full" clearable :placeholder="t('writer.assistKbAll')">
            <el-option v-for="kb in kbOptions" :key="kb.kbId" :label="kb.kbName" :value="kb.kbId" />
          </el-select>
        </div>

        <div class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('writer.assistInstruction') }}</span>
          <el-input
            v-model="assistInstruction"
            type="textarea"
            :rows="2"
            :placeholder="t('writer.assistInstructionPlaceholder')"
          />
        </div>

        <LwButton v-if="!assistRunning" block @click="assist">
          {{ t('writer.assistRun') }}
        </LwButton>
        <LwButton v-else block variant="danger" @click="stopAssist">
          {{ t('writer.assistStop') }}
        </LwButton>

        <div v-if="assistText || assistRunning" class="flex flex-col gap-2">
          <div class="flex items-center gap-2">
            <div class="lw-label">{{ t('writer.assistResult') }}</div>
            <span v-if="assistRunning" class="text-xs" style="color: var(--lw-primary)">
              {{ t('writer.assistGenerating') }}
            </span>
          </div>
          <textarea v-model="assistText" class="lw-input min-h-48 font-mono text-xs leading-5" />
          <div class="flex flex-wrap gap-2">
            <LwButton v-if="canEdit" size="sm" variant="secondary" @click="replaceSelection">
              {{ t('writer.assistReplace') }}
            </LwButton>
            <LwButton v-if="canEdit" size="sm" variant="ghost" @click="appendResult">
              {{ t('writer.assistInsert') }}
            </LwButton>
            <LwButton size="sm" variant="text" @click="copyResult">{{ t('writer.assistCopy') }}</LwButton>
          </div>
        </div>

        <div v-if="canEdit && current" class="mt-auto pt-4 border-t border-lw-border">
          <LwButton
            v-if="hasPerm('wb:doc:delete')"
            variant="danger"
            size="sm"
            block
            @click="remove"
          >
            {{ t('common.delete') }}
          </LwButton>
        </div>
      </LwCard>
    </div>

    <!-- 版本历史 -->
    <el-drawer v-model="versionDrawer" :title="t('writer.versions')" size="420px">
      <LwPageState :loading="versionLoading" :skeleton-rows="4">
        <LwEmpty v-if="!versions.length" :description="t('writer.emptyResult')" />
        <div v-for="version in versions" :key="version.versionId" class="flex items-start justify-between gap-3 py-3 border-b border-lw-border">
          <div class="min-w-0">
            <div class="text-sm font-bold text-lw-ink">v{{ version.versionNo }} · {{ version.docTitle }}</div>
            <div class="text-xs text-lw-muted mt-0.5">{{ version.changeNote }}</div>
            <div class="text-xs text-lw-muted mt-0.5">{{ formatTime(version.createdAt) }}</div>
          </div>
          <LwButton v-if="canEdit" size="sm" variant="text" @click="restore(version)">
            {{ t('writer.restore') }}
          </LwButton>
        </div>
      </LwPageState>
    </el-drawer>
  </div>
</template>
