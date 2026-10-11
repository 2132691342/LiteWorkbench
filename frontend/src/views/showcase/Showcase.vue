<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwAreaChart from '@/components/charts/LwAreaChart.vue'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'

/** 组件库：设计规范的可视化样例，五态与三态可直接比对 */
const { t } = useI18n()

const demoLoading = ref(false)
const demoDone = ref(false)

/** 按钮加载态演示：1.2s 后结束 */
async function simulate(): Promise<void> {
  demoLoading.value = true
  demoDone.value = false
  await new Promise((resolve) => setTimeout(resolve, 1200))
  demoLoading.value = false
  demoDone.value = true
}

onMounted(() => {
  // 让首屏直接看到骨架效果
  demoLoading.value = true
  void simulate()
})

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
      <LwTag v-if="demoDone" tone="success">{{ t('common.success') }}</LwTag>
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
            <LwButton :loading="demoLoading">{{ t('showcase.loading') }}</LwButton>
            <LwButton disabled>{{ t('showcase.disabled') }}</LwButton>
          </div>
          <p class="text-xs" style="color: var(--lw-ink-muted)">
            {{ t('showcase.buttonsHint') }}
          </p>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.colors')" :desc="t('showcase.colorsDesc')">
        <div class="flex flex-col gap-4">
          <div class="flex gap-4">
            <div class="flex-1 space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm shadow-sm" style="background: var(--lw-primary); color: var(--lw-on-primary)">
                {{ t('showcase.tagPrimary') }}
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>#2563EB</span><span>Primary</span>
              </div>
            </div>
            <div class="flex-1 space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm shadow-sm" style="background: var(--lw-secondary); color: var(--lw-on-primary)">
                {{ t('showcase.tagSecondary') }}
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>#9333EA</span><span>Secondary</span>
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
            <span class="font-mono" style="color: var(--lw-ink-muted)">#F8FAFC / #334155</span>
          </div>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.states')" :desc="t('showcase.statesDesc')">
        <div class="flex flex-col gap-4">
          <LwPageState :loading="demoLoading" :skeleton-rows="3" />

          <LwErrorBar v-if="!demoLoading" :message="t('showcase.demoError')" @retry="simulate" />

          <LwEmpty v-if="!demoLoading" :description="t('showcase.demoEmpty')" />

          <div v-if="!demoLoading" class="flex justify-center">
            <LwButton variant="ghost" size="sm" @click="simulate">{{ t('common.refresh') }}</LwButton>
          </div>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.charts')" :desc="t('showcase.chartsDesc')">
        <LwAreaChart :categories="chartCategories" :values="chartValues" name="demo" :height="240" />
      </LwCard>
    </div>
  </div>
</template>
