<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { toolApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { ToolVO } from '@/types/api'

/** 工具管理：平台级启停与手工调用，confirm 风险工具需显式确认 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const list = ref<ToolVO[]>([])
const loading = ref(false)
const error = ref('')
const toggling = ref('')
const invokeVisible = ref(false)
const invoking = ref(false)
const invokeForm = ref({ toolName: '', args: '{}', confirmed: false })
const invokeRisk = ref<'safe' | 'confirm' | 'danger'>('safe')
const invokeOutput = ref('')

/** 非 safe 工具必须显式确认后才能提交 */
const needsConfirm = computed(() => invokeRisk.value !== 'safe')

const RISK_TONE: Record<string, 'success' | 'warning' | 'error'> = {
  safe: 'success',
  confirm: 'warning',
  danger: 'error',
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    list.value = await toolApi.list()
  } catch (err) {
    error.value = err instanceof Error ? err.message : ''
  } finally {
    loading.value = false
  }
}

async function toggle(item: ToolVO): Promise<void> {
  toggling.value = item.toolId
  try {
    await toolApi.toggle(item.toolId, !item.enabled)
    item.enabled = !item.enabled
    ElMessage.success(t('common.saveSuccess'))
  } finally {
    toggling.value = ''
  }
}

function openInvoke(item: ToolVO): void {
  invokeForm.value = { toolName: item.toolName, args: '{}', confirmed: false }
  invokeRisk.value = item.riskLevel
  invokeOutput.value = ''
  invokeVisible.value = true
}

async function submitInvoke(): Promise<void> {
  invoking.value = true
  try {
    const args = JSON.parse(invokeForm.value.args || '{}') as Record<string, unknown>
    invokeOutput.value = await toolApi.invoke(invokeForm.value.toolName, {
      args,
      confirmed: invokeForm.value.confirmed,
    })
  } catch (err) {
    invokeOutput.value = err instanceof Error ? err.message : String(err)
  } finally {
    invoking.value = false
  }
}

onMounted(load)
</script>

<template>
  <LwCard :title="t('settings.tool.title')" :desc="t('settings.tool.desc')">
    <LwPageState :loading="loading" :error="error" :empty="!list.length" @retry="load">
      <div class="flex flex-col gap-2">
        <div
          v-for="item in list"
          :key="item.toolId"
          class="px-3 py-2.5 rounded-lg border flex flex-wrap items-center gap-3 transition-all duration-200 hover:shadow-sm"
          style="border-color: var(--lw-border)"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-bold text-sm">{{ item.toolLabel }}</span>
              <LwTag :tone="RISK_TONE[item.riskLevel]">{{ t(`settings.tool.risk_${item.riskLevel}`) }}</LwTag>
              <LwTag tone="muted">{{ item.category }}</LwTag>
            </div>
            <div class="text-xs mt-1 font-mono truncate" style="color: var(--lw-ink-muted)">
              {{ item.toolName }}
            </div>
          </div>
          <div class="flex items-center gap-2">
            <LwButton v-if="hasPerm('wb:tool:invoke')" variant="ghost" size="sm" @click="openInvoke(item)">
              {{ t('settings.tool.invoke') }}
            </LwButton>
            <el-switch
              v-if="hasPerm('wb:tool:toggle')"
              :model-value="item.enabled"
              :loading="toggling === item.toolId"
              @change="toggle(item)"
            />
          </div>
        </div>
      </div>
    </LwPageState>

    <el-dialog v-model="invokeVisible" :title="t('settings.tool.invokeTitle')" width="520px">
      <el-form label-position="top">
        <el-form-item :label="t('settings.tool.args')">
          <el-input v-model="invokeForm.args" type="textarea" :rows="5" spellcheck="false" />
        </el-form-item>
        <el-form-item v-if="needsConfirm">
          <div class="w-full flex flex-col gap-1">
            <el-checkbox v-model="invokeForm.confirmed">{{ t('settings.tool.confirmed') }}</el-checkbox>
            <span class="text-xs" style="color: var(--lw-warning-ink)">{{ t('settings.tool.confirmWarn') }}</span>
          </div>
        </el-form-item>
        <div
          v-if="invokeOutput"
          class="px-3 py-2 rounded-lg text-xs whitespace-pre-wrap break-all max-h-56 overflow-auto"
          style="background: var(--lw-surface)"
        >
          {{ invokeOutput }}
        </div>
      </el-form>
      <template #footer>
        <LwButton variant="ghost" @click="invokeVisible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="invoking" :disabled="needsConfirm && !invokeForm.confirmed" @click="submitInvoke">
          {{ t('common.submit') }}
        </LwButton>
      </template>
    </el-dialog>
  </LwCard>
</template>
