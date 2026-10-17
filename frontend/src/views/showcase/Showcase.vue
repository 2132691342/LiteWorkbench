<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwAreaChart from '@/components/charts/LwAreaChart.vue'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwSegmented from '@/components/ui/LwSegmented.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'

/** 组件库：设计规范的可视化样例，五态与三态可直接比对 */
const { t } = useI18n()

/** 色值从令牌实时读取，令牌调整后样例页不会展示陈旧值 */
const tokenHex = (name: string): string =>
  getComputedStyle(document.documentElement).getPropertyValue(name).trim().toUpperCase()

const primaryHex = ref('')
const secondaryHex = ref('')
const surfaceHex = ref('')
const accentHex = ref('')
const inkHex = ref('')
const secondaryInkHex = ref('')

/** 三态互斥：一次只呈现 loading / empty / error 中的一种，刷新在两态间轮转 */
const demoState = ref<'loading' | 'empty' | 'error'>('loading')
const demoRound = ref(0)

const segmentedDemo = ref('all')
const segmentedOptions = computed(() => [
  { value: 'all', label: t('common.all') },
  { value: 'enabled', label: t('common.enabled') },
  { value: 'disabled', label: t('common.disabled'), count: 3 },
])

async function simulate(): Promise<void> {
  demoState.value = 'loading'
  await new Promise((resolve) => setTimeout(resolve, 1200))
  demoState.value = demoRound.value++ % 2 === 0 ? 'empty' : 'error'
}

onMounted(() => {
  primaryHex.value = tokenHex('--lw-primary')
  secondaryHex.value = tokenHex('--lw-secondary')
  surfaceHex.value = tokenHex('--lw-surface-subtle')
  accentHex.value = tokenHex('--lw-accent-soft')
  inkHex.value = tokenHex('--lw-ink')
  secondaryInkHex.value = tokenHex('--lw-primary-ink')
  void simulate()
})

/** 案例「交互控件」卡的输入演示：聚焦态可复现，用于对照 1.5px 强调边框 */
const demoText = ref('')

const chartCategories = ['1', '2', '3', '4', '5', '6', '7', '8']
const chartValues = [8, 16, 12, 24, 20, 32, 28, 40]
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('showcase.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('showcase.desc') }}</p>
      </div>
      <LwTag tone="success">{{ t('common.success') }}</LwTag>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <LwCard :title="t('showcase.buttons')" :desc="t('showcase.buttonsDesc')">
        <div class="flex flex-col gap-4">
          <div class="flex flex-wrap gap-3">
            <LwButton>{{ t('showcase.primary') }}</LwButton>
            <LwButton variant="secondary">{{ t('showcase.secondary') }}</LwButton>
            <LwButton variant="ghost">{{ t('showcase.ghost') }}</LwButton>
            <LwButton variant="danger">{{ t('showcase.danger') }}</LwButton>
            <LwButton variant="text">{{ t('showcase.text') }}</LwButton>
          </div>
          <div class="flex flex-wrap gap-3 items-center">
            <LwButton size="sm">{{ t('showcase.sizeSm') }}</LwButton>
            <LwButton>{{ t('showcase.sizeMd') }}</LwButton>
            <LwButton size="lg">{{ t('showcase.sizeLg') }}</LwButton>
            <LwButton :loading="demoState === 'loading'">{{ t('showcase.loading') }}</LwButton>
            <LwButton disabled>{{ t('showcase.disabled') }}</LwButton>
          </div>
          <div class="flex flex-col gap-2 pt-3 border-t" style="border-color: var(--lw-border)">
            <span class="lw-label">{{ t('showcase.segmented') }}</span>
            <LwSegmented v-model="segmentedDemo" :options="segmentedOptions" />
          </div>
          <p class="text-xs" style="color: var(--lw-ink-muted)">
            {{ t('showcase.buttonsHint') }}
          </p>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.colors')" :desc="t('showcase.colorsDesc')">
        <div class="flex flex-col gap-4">
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm lw-shadow-sm" style="background: var(--lw-primary); color: var(--lw-on-primary)">
                {{ t('showcase.tagPrimary') }}
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>{{ primaryHex }}</span><span>{{ t('showcase.tagPrimary') }}</span>
              </div>
            </div>
            <div class="space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm lw-shadow-sm" style="background: var(--lw-secondary); color: var(--lw-on-secondary)">
                {{ t('showcase.tagSecondary') }}
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>{{ secondaryHex }}</span><span>{{ t('showcase.tagSecondary') }}</span>
              </div>
            </div>
            <div class="space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm lw-shadow-sm" style="background: var(--lw-accent-soft); color: var(--lw-ink)">
                {{ t('showcase.tagSoft') }}
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>{{ accentHex }}</span><span>{{ t('showcase.tagSoft') }}</span>
              </div>
            </div>
            <div class="space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm lw-shadow-sm" style="background: var(--lw-ink); color: var(--lw-on-primary)">
                {{ t('showcase.tagInk') }}
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>{{ inkHex }}</span><span>{{ t('showcase.tagInk') }}</span>
              </div>
            </div>
          </div>
          <div class="flex flex-wrap gap-2">
            <LwTag tone="primary">{{ t('showcase.tagPrimary') }}</LwTag>
            <LwTag tone="secondary">{{ t('showcase.tagSecondary') }}</LwTag>
            <LwTag tone="success">{{ t('showcase.tagSuccess') }}</LwTag>
            <LwTag tone="warning">{{ t('showcase.tagWarning') }}</LwTag>
            <LwTag tone="error">{{ t('showcase.tagError') }}</LwTag>
            <LwTag tone="muted">{{ t('showcase.tagMuted') }}</LwTag>
          </div>
          <div class="p-3 rounded-lg border text-xs flex items-center justify-between" style="background: var(--lw-surface); border-color: var(--lw-border)">
            <span class="font-bold">{{ t('showcase.surfaceLabel') }}</span>
            <span class="font-mono" style="color: var(--lw-ink-muted)">{{ surfaceHex }} / {{ secondaryInkHex }}</span>
          </div>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.inputs')" :desc="t('showcase.inputsDesc')">
        <div class="flex flex-col gap-4">
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('showcase.fieldLabel') }}</span>
            <div class="lw-field">
              <input v-model="demoText" class="w-full bg-transparent outline-none text-sm" :placeholder="t('showcase.fieldPlaceholder')" />
            </div>
          </label>
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('showcase.fieldLabel') }}</span>
            <el-input :placeholder="t('showcase.fieldPlaceholder')" clearable />
          </label>
          <div class="flex flex-wrap items-center gap-6 pt-3 border-t" style="border-color: var(--lw-border)">
            <el-checkbox :model-value="true">{{ t('showcase.checked') }}</el-checkbox>
            <el-radio-group :model-value="true">
              <el-radio :value="true">{{ t('showcase.radio') }}</el-radio>
            </el-radio-group>
            <span class="flex items-center gap-2">
              <el-switch :model-value="true" />
              <span class="text-xs font-medium">{{ t('showcase.switchOn') }}</span>
            </span>
          </div>
          <p class="text-xs" style="color: var(--lw-ink-muted)">{{ t('showcase.inputsHint') }}</p>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.states')" :desc="t('showcase.statesDesc')">
        <div class="flex flex-col gap-4">
          <LwPageState :loading="demoState === 'loading'" :skeleton-rows="3" />

          <LwErrorBar v-if="demoState === 'error'" :message="t('showcase.demoError')" @retry="simulate" />

          <LwEmpty v-if="demoState === 'empty'" :description="t('showcase.demoEmpty')" />

          <div class="flex justify-center">
            <LwButton variant="ghost" size="sm" :loading="demoState === 'loading'" @click="simulate">
              {{ t('common.refresh') }}
            </LwButton>
          </div>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.charts')" :desc="t('showcase.chartsDesc')">
        <LwAreaChart :categories="chartCategories" :values="chartValues" name="demo" :height="240" />
      </LwCard>
    </div>
  </div>
</template>
