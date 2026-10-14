<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { providerApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { ProviderPresetVO, ProviderREQ, ProviderVO } from '@/types/api'

/** 模型供应方管理：平台级配置，预设与 hutool-ai 对齐，密钥脱敏展示，编辑留空表示不修改 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const list = ref<ProviderVO[]>([])
const presets = ref<ProviderPresetVO[]>([])
const loading = ref(false)
const error = ref('')
const dialogVisible = ref(false)
const submitting = ref(false)
const testing = ref('')
const form = ref<ProviderREQ>(emptyForm())

const presetOptions = computed(() =>
  presets.value.map((preset) => ({
    value: preset.providerCode,
    label: t(`settings.provider.preset_${preset.providerCode}`),
  })))

function emptyForm(): ProviderREQ {
  return { providerCode: 'openai', providerName: '', baseUrl: '', apiKey: '', defaultModel: '', enabled: true }
}

function applyPreset(code: string): void {
  form.value.providerCode = code
  const preset = presets.value.find((item) => item.providerCode === code)
  if (preset) {
    form.value.baseUrl = preset.baseUrl ?? ''
    form.value.defaultModel = preset.defaultModel ?? ''
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [items, presetList] = await Promise.all([providerApi.list(), providerApi.presets()])
    list.value = items
    presets.value = presetList
  } catch (err) {
    error.value = err instanceof Error ? err.message : ''
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  form.value = emptyForm()
  dialogVisible.value = true
}

function openEdit(item: ProviderVO): void {
  form.value = {
    providerId: item.providerId,
    providerCode: item.providerCode,
    providerName: item.providerName,
    baseUrl: item.baseUrl ?? '',
    apiKey: '',
    defaultModel: item.defaultModel ?? '',
    enabled: item.enabled,
  }
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  submitting.value = true
  try {
    if (form.value.providerId) {
      await providerApi.update(form.value)
    } else {
      await providerApi.create(form.value)
    }
    ElMessage.success(t('common.saveSuccess'))
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function test(item: ProviderVO): Promise<void> {
  testing.value = item.providerId
  try {
    await providerApi.test(item.providerId)
    ElMessage.success(t('settings.provider.testOk'))
  } finally {
    testing.value = ''
  }
}

async function remove(item: ProviderVO): Promise<void> {
  await ElMessageBox.confirm(
    t('common.confirmDelete', { name: item.providerName }),
    t('common.tip'),
    { type: 'warning' },
  )
  await providerApi.remove(item.providerId)
  ElMessage.success(t('common.deleteSuccess'))
  await load()
}

onMounted(load)
</script>

<template>
  <LwCard :title="t('settings.provider.title')" :desc="t('settings.provider.desc')">
    <template #actions>
      <LwButton v-if="hasPerm('wb:provider:create')" size="sm" @click="openCreate">
        {{ t('common.create') }}
      </LwButton>
    </template>

    <LwPageState :loading="loading" :error="error" :empty="!list.length" @retry="load">
      <div class="flex flex-col gap-2">
        <div
          v-for="item in list"
          :key="item.providerId"
          class="px-3 py-2.5 rounded-lg border flex flex-wrap items-center gap-3 transition-all duration-200 hover:shadow-sm"
          style="border-color: var(--lw-border)"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-bold text-sm">{{ item.providerName }}</span>
              <LwTag :tone="item.enabled ? 'success' : 'muted'">
                {{ item.enabled ? t('common.enabled') : t('common.disabled') }}
              </LwTag>
              <LwTag tone="secondary">{{ item.providerCode }}</LwTag>
            </div>
            <div class="text-xs mt-1 truncate" style="color: var(--lw-ink-muted)">
              {{ item.baseUrl }} · {{ item.defaultModel }} · {{ item.apiKeyMasked || '—' }}
            </div>
          </div>
          <div class="flex items-center gap-2">
            <LwButton
              v-if="hasPerm('wb:provider:update')"
              variant="ghost"
              size="sm"
              :loading="testing === item.providerId"
              @click="test(item)"
            >
              {{ t('settings.provider.test') }}
            </LwButton>
            <LwButton v-if="hasPerm('wb:provider:update')" variant="text" size="sm" @click="openEdit(item)">
              {{ t('common.edit') }}
            </LwButton>
            <LwButton
              v-if="hasPerm('wb:provider:delete')"
              variant="text"
              size="sm"
              @click="remove(item)"
            >
              {{ t('common.delete') }}
            </LwButton>
          </div>
        </div>
      </div>
    </LwPageState>

    <el-dialog
      v-model="dialogVisible"
      :title="form.providerId ? t('common.edit') : t('common.create')"
      width="460px"
    >
      <el-form label-position="top">
        <el-form-item :label="t('settings.provider.preset')">
          <el-select
            v-model="form.providerCode"
            :disabled="Boolean(form.providerId)"
            class="w-full"
            @change="applyPreset"
          >
            <el-option
              v-for="option in presetOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <div class="text-xs mt-1" style="color: var(--lw-ink-muted); line-height: 1.5">
            {{ t('settings.provider.presetTip') }}
          </div>
        </el-form-item>
        <el-form-item :label="t('settings.provider.name')">
          <el-input v-model="form.providerName" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('settings.provider.baseUrl')">
          <el-input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item :label="t('settings.provider.apiKey')">
          <el-input v-model="form.apiKey" :placeholder="t('settings.provider.apiKeyTip')" show-password />
        </el-form-item>
        <el-form-item :label="t('settings.provider.defaultModel')">
          <el-input v-model="form.defaultModel" placeholder="gpt-4o-mini" />
        </el-form-item>
        <el-form-item>
          <el-switch v-model="form.enabled" :active-text="t('common.enabled')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LwButton variant="ghost" @click="dialogVisible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="submitting" @click="submit">{{ t('common.save') }}</LwButton>
      </template>
    </el-dialog>
  </LwCard>
</template>
