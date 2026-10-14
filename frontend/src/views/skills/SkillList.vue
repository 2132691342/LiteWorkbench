<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { skillApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { SkillVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 技能：摘要常驻提示词，命中触发词才展开正文 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const skills = ref<SkillVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)
const error = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  skillId: '',
  skillCode: '',
  skillName: '',
  summary: '',
  content: '',
  triggerWords: '',
  enabled: true,
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await skillApi.page({ page: page.value, size: size.value, keyword: keyword.value })
    skills.value = result.list
    total.value = result.total
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  Object.assign(form, { skillId: '', skillCode: '', skillName: '', summary: '', content: '', triggerWords: '', enabled: true })
  dialogVisible.value = true
}

function openEdit(item: SkillVO): void {
  Object.assign(form, {
    skillId: item.skillId,
    skillCode: item.skillCode,
    skillName: item.skillName,
    summary: item.summary ?? '',
    content: item.content ?? '',
    triggerWords: item.triggerWords ?? '',
    enabled: item.enabled,
  })
  dialogVisible.value = true
}

async function save(): Promise<void> {
  saving.value = true
  try {
    if (form.skillId) {
      await skillApi.update({ ...form })
    } else {
      await skillApi.create({ ...form })
    }
    ElMessage.success(t('common.success'))
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(item: SkillVO): Promise<void> {
  await ElMessageBox.confirm(t('skills.confirmDelete', { name: item.skillName }), t('common.delete'), {
    type: 'warning',
  })
  await skillApi.remove(item.skillId)
  ElMessage.success(t('common.success'))
  await load()
}

/** 恢复内置技能为种子版本：保留用户自己的启用状态 */
async function restore(item: SkillVO): Promise<void> {
  await ElMessageBox.confirm(t('skills.confirmRestore', { name: item.skillName }), t('skills.restore'), {
    type: 'warning',
  })
  await skillApi.restore(item.skillId)
  ElMessage.success(t('common.success'))
  await load()
}

function search(): void {
  page.value = 1
  void load()
}

function changeSize(next: number): void {
  size.value = next
  page.value = 1
  void load()
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
        <h2 class="lw-title">{{ t('skills.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('skills.desc') }}</p>
      </div>
      <LwButton v-if="hasPerm('wb:skill:create')" @click="openCreate">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('common.create') }}
      </LwButton>
    </div>
    <LwCard>
      <div class="flex flex-wrap items-center gap-2 mb-4">
        <el-input v-model="keyword" class="flex-1 min-w-40" :placeholder="t('common.search')" clearable @keyup.enter="search" />
        <LwButton variant="secondary" class="shrink-0" @click="search">{{ t('common.search') }}</LwButton>
      </div>
      <LwPageState :loading="loading" :error="error" :empty="!skills.length" @retry="load">
        <el-table :data="skills" class="lw-table">
          <el-table-column :label="t('skills.skillCode')" width="190">
            <template #default="{ row }">
              <span class="inline-flex items-center gap-1.5">
                <span>{{ row.skillCode }}</span>
                <LwTag v-if="row.builtin" tone="secondary">{{ t('skills.builtin') }}</LwTag>
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('skills.skillName')" prop="skillName" min-width="140" />
          <el-table-column :label="t('skills.summary')" prop="summary" min-width="200" show-overflow-tooltip />
          <el-table-column :label="t('skills.triggerWords')" prop="triggerWords" min-width="160" show-overflow-tooltip />
          <el-table-column :label="t('common.status')" width="90">
            <template #default="{ row }">
              <LwTag :tone="row.enabled ? 'success' : 'muted'">
                {{ row.enabled ? t('common.enabled') : t('common.disabled') }}
              </LwTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('logs.time')" width="180">
            <template #default="{ row }">{{ formatTime(row.updatedAt ?? row.createdAt) }}</template>
          </el-table-column>
          <el-table-column width="150" fixed="right">
            <template #default="{ row }">
              <LwButton v-if="hasPerm('wb:skill:update')" variant="text" size="sm" @click="openEdit(row)">
                {{ t('common.edit') }}
              </LwButton>
              <LwButton v-if="row.builtin && hasPerm('wb:skill:update')" variant="text" size="sm" @click="restore(row)">
                {{ t('skills.restore') }}
              </LwButton>
              <LwButton v-if="hasPerm('wb:skill:delete')" variant="text" size="sm" @click="remove(row)">
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

    <el-dialog v-model="dialogVisible" :title="form.skillId ? t('common.edit') : t('common.create')" width="640">
      <el-form :model="form" label-width="90">
        <el-form-item :label="t('skills.skillCode')" required>
          <el-input v-model="form.skillCode" :disabled="!!form.skillId" :placeholder="t('skills.skillCodePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('skills.skillName')" required>
          <el-input v-model="form.skillName" />
        </el-form-item>
        <el-form-item :label="t('skills.summary')">
          <el-input v-model="form.summary" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('skills.content')">
          <el-input v-model="form.content" type="textarea" :rows="6" maxlength="8000" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('skills.triggerWords')">
          <el-input v-model="form.triggerWords" :placeholder="t('skills.triggerWordsPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LwButton variant="secondary" @click="dialogVisible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="saving" @click="save">{{ t('common.save') }}</LwButton>
      </template>
    </el-dialog>
  </div>
</template>
