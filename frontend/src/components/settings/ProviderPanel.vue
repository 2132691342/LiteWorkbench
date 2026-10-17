<script setup lang="ts">
import { Picture, QuestionFilled, Setting, Tools } from '@element-plus/icons-vue'
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

/** 模型供应方管理：平台级配置；模型参数（上下文 / 最大输出 / 采样 / 能力位）随供应方落库并下发运行链路 */
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

const presetCodes = computed(() => presets.value.map((preset) => preset.providerCode))

/** 非预设协议（不在白名单内）打标，引导删除重建，不再静默展示 */
function isUnsupported(code: string): boolean {
  return presetCodes.value.length > 0 && !presetCodes.value.includes(code)
}

function emptyForm(): ProviderREQ {
  return {
    providerCode: 'openai',
    providerName: '',
    baseUrl: '',
    apiKey: '',
    defaultModel: '',
    contextWindow: undefined,
    maxOutputTokens: undefined,
    temperature: undefined,
    topP: undefined,
    capVision: false,
    capTool: true,
    capReasoning: false,
    enabled: true,
  }
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
    contextWindow: item.contextWindow,
    maxOutputTokens: item.maxOutputTokens,
    temperature: item.temperature,
    topP: item.topP,
    capVision: item.capVision ?? false,
    capTool: item.capTool ?? true,
    capReasoning: item.capReasoning ?? false,
    enabled: item.enabled,
  }
  dialogVisible.value = true
}

/** 数值字段归一：空串与非法值一律 undefined，交给后端按"未配置"处理 */
function num(value: unknown): number | undefined {
  if (value === '' || value === null || value === undefined) return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

async function submit(): Promise<void> {
  submitting.value = true
  try {
    const payload: ProviderREQ = {
      ...form.value,
      contextWindow: num(form.value.contextWindow),
      maxOutputTokens: num(form.value.maxOutputTokens),
      temperature: num(form.value.temperature),
      topP: num(form.value.topP),
    }
    if (form.value.providerId) {
      await providerApi.update(payload)
    } else {
      await providerApi.create(payload)
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
      <p v-if="!list.length" class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">
        {{ t('settings.provider.emptyTip') }}
      </p>

      <div class="flex flex-col gap-2">
        <div
          v-for="item in list"
          :key="item.providerId"
          class="px-3 py-2.5 rounded-lg border flex flex-wrap items-center gap-3 transition-all duration-200 hover:lw-shadow-sm"
          style="border-color: var(--lw-border)"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 flex-wrap">
              <span class="font-bold text-sm">{{ item.providerName }}</span>
              <LwTag :tone="item.enabled ? 'success' : 'muted'">
                {{ item.enabled ? t('common.enabled') : t('common.disabled') }}
              </LwTag>
              <LwTag tone="secondary">{{ item.providerCode }}</LwTag>
              <el-tooltip v-if="isUnsupported(item.providerCode)" :content="t('settings.provider.unsupportedTip')" placement="top">
                <LwTag tone="error">{{ t('settings.provider.unsupported') }}</LwTag>
              </el-tooltip>
              <!-- 模型能力位：一眼看出该模型能看图片、能调工具、能出思维链 -->
              <el-tooltip v-if="item.capVision" :content="t('settings.provider.capVision')" placement="top">
                <LwTag tone="secondary"><el-icon><Picture /></el-icon></LwTag>
              </el-tooltip>
              <el-tooltip v-if="item.capTool" :content="t('settings.provider.capTool')" placement="top">
                <LwTag tone="secondary"><el-icon><Tools /></el-icon></LwTag>
              </el-tooltip>
              <el-tooltip v-if="item.capReasoning" :content="t('settings.provider.capReasoning')" placement="top">
                <LwTag tone="secondary"><el-icon><Setting /></el-icon></LwTag>
              </el-tooltip>
            </div>
            <div class="text-xs mt-1 truncate" style="color: var(--lw-ink-muted)">
              {{ item.baseUrl }} · {{ item.defaultModel }} · {{ item.apiKeyMasked || '—' }}
            </div>
            <div class="text-xs mt-0.5 font-mono" style="color: var(--lw-ink-disabled)">
              {{ t('settings.provider.metaContext', { v: item.contextWindow ?? t('settings.provider.fallbackGlobal') }) }}
              · {{ t('settings.provider.metaMax', { v: item.maxOutputTokens ?? t('settings.provider.unlimited') }) }}
              · {{ t('settings.provider.metaTemp', { v: item.temperature ?? t('settings.provider.fallbackGlobal') }) }}
              · {{ t('settings.provider.metaTopP', { v: item.topP ?? t('settings.provider.unset') }) }}
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
      width="560px"
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

        <!-- 模型参数：留空即回落全局配置，不强制填 -->
        <div class="grid grid-cols-2 gap-x-4">
          <el-form-item :label="t('settings.provider.contextWindow')">
            <el-input v-model="form.contextWindow" placeholder="32000" type="number" />
          </el-form-item>
          <el-form-item :label="t('settings.provider.maxOutputTokens')">
            <el-input v-model="form.maxOutputTokens" :placeholder="t('settings.provider.unlimited')" type="number" />
          </el-form-item>
          <el-form-item :label="t('settings.provider.temperature')">
            <el-input v-model="form.temperature" placeholder="0.3" type="number" step="0.1" min="0" max="2" />
          </el-form-item>
          <el-form-item :label="t('settings.provider.topP')">
            <el-input v-model="form.topP" :placeholder="t('settings.provider.unset')" type="number" step="0.05" min="0" max="1" />
          </el-form-item>
        </div>

        <div class="flex flex-wrap items-center gap-6 py-1">
          <div class="flex items-center gap-2">
            <el-switch v-model="form.capVision" />
            <span class="text-sm font-bold" style="color: var(--lw-ink-muted)">{{ t('settings.provider.capVision') }}</span>
            <el-tooltip :content="t('settings.provider.capVisionTip')" placement="top">
              <el-icon style="color: var(--lw-ink-disabled)"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
          <div class="flex items-center gap-2">
            <el-switch v-model="form.capTool" />
            <span class="text-sm font-bold" style="color: var(--lw-ink-muted)">{{ t('settings.provider.capTool') }}</span>
            <el-tooltip :content="t('settings.provider.capToolTip')" placement="top">
              <el-icon style="color: var(--lw-ink-disabled)"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
          <div class="flex items-center gap-2">
            <el-switch v-model="form.capReasoning" />
            <span class="text-sm font-bold" style="color: var(--lw-ink-muted)">{{ t('settings.provider.capReasoning') }}</span>
            <el-tooltip :content="t('settings.provider.capReasoningTip')" placement="top">
              <el-icon style="color: var(--lw-ink-disabled)"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </div>

        <el-form-item class="mt-2">
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
