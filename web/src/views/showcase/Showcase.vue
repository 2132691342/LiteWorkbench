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
      <LwCard :title="t('showcase.buttons')" desc="hover 放大 · pressed 缩小 · loading 锁宽">
        <div class="flex flex-col gap-4">
          <div class="flex flex-wrap gap-3">
            <LwButton>主要按钮</LwButton>
            <LwButton variant="secondary">次要按钮</LwButton>
            <LwButton variant="ghost">幽灵按钮</LwButton>
            <LwButton variant="danger">危险按钮</LwButton>
            <LwButton variant="text">文字按钮</LwButton>
          </div>
          <div class="flex flex-wrap gap-3 items-center">
            <LwButton size="sm">小尺寸</LwButton>
            <LwButton>默认尺寸</LwButton>
            <LwButton size="lg">大尺寸</LwButton>
            <LwButton :loading="demoLoading">加载态</LwButton>
            <LwButton disabled>禁用态</LwButton>
          </div>
          <p class="text-xs" style="color: var(--lw-ink-muted)">
            悬停按钮观察放大与阴影，按下观察缩小，加载态宽度锁定不跳动。
          </p>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.colors')" desc="四色令牌走完全站，语义色低饱和">
        <div class="flex flex-col gap-4">
          <div class="flex gap-4">
            <div class="flex-1 space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm shadow-sm" style="background: var(--lw-primary); color: var(--lw-on-primary)">
                主色
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>#2563EB</span><span>Primary</span>
              </div>
            </div>
            <div class="flex-1 space-y-2">
              <div class="h-16 rounded-lg flex items-center justify-center font-bold text-sm shadow-sm" style="background: var(--lw-secondary); color: var(--lw-on-primary)">
                辅助色
              </div>
              <div class="flex justify-between text-xs font-mono" style="color: var(--lw-ink-muted)">
                <span>#9333EA</span><span>Secondary</span>
              </div>
            </div>
          </div>
          <div class="flex flex-wrap gap-2">
            <LwTag tone="primary">主色</LwTag>
            <LwTag tone="secondary">辅助</LwTag>
            <LwTag tone="success">成功</LwTag>
            <LwTag tone="warning">警告</LwTag>
            <LwTag tone="error">错误</LwTag>
            <LwTag tone="muted">中性</LwTag>
          </div>
          <div class="p-3 rounded-lg border text-xs flex items-center justify-between" style="background: var(--lw-surface); border-color: var(--lw-border)">
            <span class="font-bold">浅底 Surface / 深字 Ink</span>
            <span class="font-mono" style="color: var(--lw-ink-muted)">#F8FAFC / #334155</span>
          </div>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.states')" desc="加载中 / 失败重试 / 空态">
        <div class="flex flex-col gap-4">
          <LwPageState :loading="demoLoading" :skeleton-rows="3" />

          <LwErrorBar v-if="!demoLoading" message="示例错误：模型调用超时（4003）" @retry="simulate" />

          <LwEmpty v-if="!demoLoading" description="示例空态：还没有会话，点击下方按钮开始" />

          <div v-if="!demoLoading" class="flex justify-center">
            <LwButton variant="ghost" size="sm" @click="simulate">{{ t('common.refresh') }}</LwButton>
          </div>
        </div>
      </LwCard>

      <LwCard :title="t('showcase.charts')" desc="面积图：主色序列 + 渐变填充 + 虚线网格">
        <LwAreaChart :categories="chartCategories" :values="chartValues" name="demo" :height="240" />
      </LwCard>
    </div>
  </div>
</template>
