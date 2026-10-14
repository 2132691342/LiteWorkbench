<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { memoryApi } from '@/api'
import type { MemoryVO } from '@/types/api'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { formatTime } from '@/utils/const'

/** 长期记忆：Agent 反复用到的偏好与事实在此沉淀，可手工增删改；同 key 覆盖 */
const { t } = useI18n()

const list = ref<MemoryVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const saving = ref(false)

const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ memoryId: '', memoryKey: '', memoryValue: '', category: 'fact', weight: 0 })

const categoryOptions = computed(() => [
  { value: 'fact', label: t('settings.memoryCatFact') },
  { value: 'preference', label: t('settings.memoryCatPreference') },
  { value: 'constraint', label: t('settings.memoryCatConstraint') },
  { value: 'profile', label: t('settings.memoryCatProfile') },
])

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await memoryApi.page({ page: page.value, size: size.value, keyword: keyword.value })
    list.value = result.list
    total.value = result.total
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function openCreate(): void {
  editing.value = false
  Object.assign(form, { memoryId: '', memoryKey: '', memoryValue: '', category: 'fact', weight: 0 })
  dialogVisible.value = true
}

function openEdit(item: MemoryVO): void {
  editing.value = true
  Object.assign(form, {
    memoryId: item.memoryId,
    memoryKey: item.memoryKey,
    memoryValue: item.memoryValue,
    category: item.category,
    weight: item.weight ?? 0,
  })
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.memoryValue.trim() || (!editing.value && !form.memoryKey.trim())) {
    ElMessage.warning(t('settings.memoryRequired'))
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await memoryApi.update({ ...form })
    } else {
      await memoryApi.create({ ...form })
    }
    dialogVisible.value = false
    ElMessage.success(t('common.success'))
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message)
  } finally {
    saving.value = false
  }
}

async function remove(item: MemoryVO): Promise<void> {
  await ElMessageBox.confirm(t('settings.memoryDeleteConfirm'), t('common.tip'), { type: 'warning' })
  await memoryApi.remove(item.memoryId)
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
  <LwCard :title="t('settings.memory')" :desc="t('settings.memoryDesc')">
    <template #actions>
      <LwButton size="sm" @click="openCreate">{{ t('settings.memoryAdd') }}</LwButton>
    </template>

    <div class="flex items-center gap-2">
      <el-input
        v-model="keyword"
        :placeholder="t('settings.memorySearchPlaceholder')"
        clearable
        @keydown.enter="search"
        @clear="search"
      />
      <LwButton variant="ghost" :loading="loading" @click="search">{{ t('common.search') }}</LwButton>
    </div>

    <LwErrorBar v-if="error" :message="error" @retry="load" />

    <LwSkeleton v-if="loading" :rows="3" />
    <LwEmpty v-else-if="list.length === 0" :description="t('settings.memoryEmpty')" />
    <div v-else class="flex flex-col gap-2">
      <div
        v-for="item in list"
        :key="item.memoryId"
        class="memory-row"
      >
        <div class="flex items-center gap-2 flex-wrap">
          <span class="text-sm font-bold" style="color: var(--lw-ink)">{{ item.memoryKey }}</span>
          <LwTag tone="secondary">{{ item.category }}</LwTag>
          <LwTag v-if="item.weight" tone="primary">W{{ item.weight }}</LwTag>
          <span class="text-[10px] font-mono ml-auto" style="color: var(--lw-ink-muted)">
            {{ formatTime(item.updatedAt) }}
          </span>
        </div>
        <p class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">{{ item.memoryValue }}</p>
        <div class="flex justify-end gap-2">
          <LwButton variant="ghost" size="sm" @click="openEdit(item)">{{ t('common.edit') }}</LwButton>
          <LwButton variant="danger" size="sm" @click="remove(item)">{{ t('common.delete') }}</LwButton>
        </div>
      </div>
    </div>

    <div v-if="total > size" class="flex justify-end">
      <el-pagination
        :current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="changePage"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? t('common.edit') : t('settings.memoryAdd')" width="480px">
      <div class="flex flex-col gap-3">
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('settings.memoryKey') }}</span>
          <el-input v-model="form.memoryKey" :disabled="editing" maxlength="128" />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('settings.memoryValue') }}</span>
          <el-input v-model="form.memoryValue" type="textarea" :rows="3" maxlength="2000" show-word-limit />
        </label>
        <div class="flex gap-3">
          <label class="flex flex-col gap-1.5 flex-1">
            <span class="lw-label">{{ t('settings.memoryCategory') }}</span>
            <el-select v-model="form.category">
              <el-option v-for="opt in categoryOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </label>
          <label class="flex flex-col gap-1.5 w-32">
            <span class="lw-label">{{ t('settings.memoryWeight') }}</span>
            <el-input-number v-model="form.weight" :min="0" :max="9" controls-position="right" />
          </label>
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <LwButton variant="ghost" @click="dialogVisible = false">{{ t('common.cancel') }}</LwButton>
          <LwButton :loading="saving" @click="save">{{ t('common.save') }}</LwButton>
        </div>
      </template>
    </el-dialog>
  </LwCard>
</template>

<style scoped>
.memory-row {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  padding: 0.625rem 0.75rem;
  border-radius: var(--lw-radius-control);
  border: 1px solid var(--lw-border);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.memory-row:hover {
  border-color: var(--lw-secondary);
  box-shadow: var(--lw-shadow-sm);
}
</style>
