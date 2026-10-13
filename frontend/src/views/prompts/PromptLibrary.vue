<script setup lang="ts">
import { CopyDocument, Plus } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { promptApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { PromptREQ, PromptVO, PromptVariable } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 提示词库：内置模板只读，变量渲染后可直接复制到工作台 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const prompts = ref<PromptVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)
const error = ref('')

const dialog = ref(false)
const editingId = ref('')
const submitting = ref(false)
const form = ref<PromptREQ>({ promptTitle: '', category: '', content: '', variables: '' })

const renderDialog = ref(false)
const renderTarget = ref<PromptVO | null>(null)
const renderValues = ref<Record<string, string>>({})
const rendered = ref('')
const rendering = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await promptApi.page({ keyword: keyword.value, page: page.value, size: size.value })
    prompts.value = result.list
    total.value = result.total
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = ''
  form.value = { promptTitle: '', category: '', content: '', variables: '' }
  dialog.value = true
}

async function openEdit(item: PromptVO): Promise<void> {
  if (item.builtin) {
    ElMessage.warning(t('prompt.builtinReadonly'))
    return
  }
  const detail = await promptApi.detail(item.promptId)
  editingId.value = detail.promptId
  form.value = {
    promptId: detail.promptId,
    promptTitle: detail.promptTitle,
    category: detail.category ?? '',
    content: detail.content,
    variables: detail.variables?.length ? JSON.stringify(detail.variables) : '',
  }
  dialog.value = true
}

async function submit(): Promise<void> {
  if (submitting.value) return
  if (form.value.variables && !isJsonArray(form.value.variables)) {
    ElMessage.error(t('prompt.invalidJson'))
    return
  }
  submitting.value = true
  try {
    if (editingId.value) {
      await promptApi.update({ ...form.value, promptId: editingId.value })
    } else {
      await promptApi.create(form.value)
    }
    dialog.value = false
    ElMessage.success(t('common.success'))
    await load()
  } catch {
    // 拦截器已提示，保留弹窗供修正
  } finally {
    submitting.value = false
  }
}

function isJsonArray(raw: string): boolean {
  try {
    return Array.isArray(JSON.parse(raw))
  } catch {
    return false
  }
}

async function remove(item: PromptVO): Promise<void> {
  await ElMessageBox.confirm(t('prompt.confirmDelete', { name: item.promptTitle }), t('common.delete'), {
    type: 'warning',
  })
  await promptApi.remove(item.promptId)
  ElMessage.success(t('common.success'))
  await load()
}

async function copy(item: PromptVO): Promise<void> {
  const promptId = await promptApi.copy(item.promptId)
  ElMessage.success(t('common.success'))
  await load()
  await openEdit({ ...item, promptId, builtin: false })
}

function openRender(item: PromptVO): void {
  renderTarget.value = item
  renderValues.value = {}
  for (const variable of item.variables ?? []) {
    renderValues.value[variable.name] = variable.defaultValue ?? ''
  }
  rendered.value = ''
  renderDialog.value = true
}

async function render(): Promise<void> {
  if (!renderTarget.value) return
  rendering.value = true
  try {
    rendered.value = await promptApi.render({
      promptId: renderTarget.value.promptId,
      variables: renderValues.value,
    })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    rendering.value = false
  }
}

async function copyRendered(): Promise<void> {
  await navigator.clipboard.writeText(rendered.value)
  ElMessage.success(t('kb.copied'))
}

function variablesOf(item: PromptVO): PromptVariable[] {
  return item.variables ?? []
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
        <h2 class="lw-title">{{ t('prompt.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('prompt.desc') }}</p>
      </div>
      <div class="flex items-center gap-2">
        <el-input
          v-model="keyword"
          :placeholder="t('common.keyword')"
          clearable
          class="w-56"
          @keyup.enter="load"
          @clear="load"
        />
        <LwButton v-if="hasPerm('wb:prompt:create')" @click="openCreate">
          <template #icon><el-icon><Plus /></el-icon></template>
          {{ t('prompt.newPrompt') }}
        </LwButton>
      </div>
    </div>

    <LwCard class="p-5">
      <LwPageState :loading="loading" :error="error" :empty="!prompts.length" @retry="load">
        <el-table :data="prompts" class="lw-table">
          <el-table-column :label="t('prompt.promptTitle')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <span class="font-bold text-lw-ink">{{ row.promptTitle }}</span>
                <LwTag v-if="row.builtin" tone="secondary">{{ t('prompt.builtin') }}</LwTag>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('prompt.category')" prop="category" width="120" />
          <el-table-column :label="t('prompt.content')" prop="content" min-width="260" show-overflow-tooltip />
          <el-table-column :label="t('prompt.useCount')" prop="useCount" width="100" />
          <el-table-column :label="t('logs.time')" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column width="240" fixed="right">
            <template #default="{ row }">
              <LwButton size="sm" variant="text" @click="openRender(row)">{{ t('prompt.render') }}</LwButton>
              <LwButton v-if="hasPerm('wb:prompt:create')" size="sm" variant="text" @click="copy(row)">
                {{ t('prompt.copy') }}
              </LwButton>
              <LwButton
                v-if="hasPerm('wb:prompt:update') && !row.builtin"
                size="sm"
                variant="text"
                @click="openEdit(row)"
              >
                {{ t('common.edit') }}
              </LwButton>
              <LwButton
                v-if="hasPerm('wb:prompt:delete') && !row.builtin"
                size="sm"
                variant="text"
                @click="remove(row)"
              >
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

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dialog" :title="editingId ? t('common.edit') : t('prompt.newPrompt')" width="640px">
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('prompt.promptTitle') }}</span>
          <input v-model="form.promptTitle" class="lw-input" :placeholder="t('prompt.titlePlaceholder')" />
        </div>
        <div class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('prompt.category') }}</span>
          <input v-model="form.category" class="lw-input" />
        </div>
        <div class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('prompt.content') }}</span>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            :placeholder="t('prompt.contentPlaceholder')"
          />
        </div>
        <div class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('prompt.variables') }}</span>
          <el-input
            v-model="form.variables"
            type="textarea"
            :rows="3"
            :placeholder="t('prompt.variablesPlaceholder')"
          />
        </div>
      </div>
      <template #footer>
        <LwButton variant="ghost" @click="dialog = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="submitting" @click="submit">{{ t('common.save') }}</LwButton>
      </template>
    </el-dialog>

    <!-- 变量渲染 -->
    <el-dialog v-model="renderDialog" :title="t('prompt.render')" width="640px">
      <div class="flex flex-col gap-4">
        <div class="lw-label">{{ t('prompt.fillVariables') }}</div>
        <div v-for="variable in variablesOf(renderTarget as PromptVO)" :key="variable.name" class="flex flex-col gap-1.5">
          <span class="text-xs font-bold text-lw-ink">{{ variable.label || variable.name }}</span>
          <input v-model="renderValues[variable.name]" class="lw-input" />
        </div>
        <div v-if="!variablesOf(renderTarget as PromptVO).length" class="lw-subtitle">
          {{ renderTarget?.content }}
        </div>
        <LwButton block :loading="rendering" @click="render">
          {{ rendering ? t('prompt.rendering') : t('prompt.render') }}
        </LwButton>
        <div v-if="rendered" class="flex flex-col gap-2">
          <div class="lw-label">{{ t('prompt.rendered') }}</div>
          <textarea v-model="rendered" class="lw-input min-h-40 font-mono text-xs leading-5" readonly />
          <LwButton size="sm" variant="ghost" block @click="copyRendered">
            <template #icon><el-icon><CopyDocument /></el-icon></template>
            {{ t('kb.copied') }}
          </LwButton>
        </div>
      </div>
    </el-dialog>
  </div>
</template>
