/** 管理列表卡：卡头（资源名 + 条数 + 工具栏）+ 筛选条 + 表体 + 分页同属一张白卡 */
<script setup lang="ts">
import { FullScreen, Refresh, Sort } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwEmpty from './LwEmpty.vue'
import LwErrorBar from './LwErrorBar.vue'

type Density = 'default' | 'medium' | 'compact'

const props = withDefaults(
  defineProps<{
    /** 数据为空：由调用方按数据源长度给出，不能用 emptyText 代替判空 */
    empty?: boolean
    /** 空态说明文案 */
    emptyText?: string
    /** 加载中：表体位置换成骨架 */
    loading?: boolean
    /** 错误文案，非空即错误态 */
    error?: string
    /** 分页总数，为 0 或不足一页时不渲染分页条 */
    total?: number
    /** 卡头资源名，缺省不渲染卡头 */
    title?: string
  }>(),
  { empty: false, emptyText: '', loading: false, error: '', total: 0, title: '' },
)

defineEmits<{ (e: 'retry'): void }>()

const { t } = useI18n()
const DENSITIES: Density[] = ['default', 'medium', 'compact']

/** 密度是跨页面的操作习惯，持久化到 localStorage 而非各页各存一份 */
const density = ref<Density>((localStorage.getItem('lw-table-density') as Density) ?? 'default')
const fullscreen = ref(false)

const densityLabel = computed(() => t(`table.density_${density.value}`))
const countText = computed(() => (props.total > 0 ? t('table.count', { n: props.total }) : ''))

function cycleDensity(): void {
  density.value = DENSITIES[(DENSITIES.indexOf(density.value) + 1) % DENSITIES.length]
  localStorage.setItem('lw-table-density', density.value)
}
</script>

<template>
  <section class="lw-tablecard" :data-density="density" :class="{ 'is-fullscreen': fullscreen }">
    <div v-if="title || $slots.toolbar" class="lw-tablecard__head">
      <div class="lw-tablecard__title">
        <span>{{ title }}</span>
        <span v-if="countText" class="lw-tablecard__count">{{ countText }}</span>
      </div>
      <div class="lw-toolbar">
        <slot name="toolbar" />
        <el-tooltip :content="t('table.reload')" placement="top" :show-after="300">
          <button
            type="button"
            class="lw-toolbar__btn"
            :aria-label="t('table.reload')"
            :disabled="loading"
            @click="$emit('retry')"
          >
            <el-icon :size="14" :class="{ 'lw-spin-icon': loading }"><Refresh /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip :content="densityLabel" placement="top" :show-after="300">
          <button
            type="button"
            class="lw-toolbar__btn"
            :class="{ 'is-active': density !== 'default' }"
            :aria-label="densityLabel"
            @click="cycleDensity"
          >
            <el-icon :size="14"><Sort /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip
          :content="fullscreen ? t('table.exitFull') : t('table.fullscreen')"
          placement="top"
          :show-after="300"
        >
          <button
            type="button"
            class="lw-toolbar__btn"
            :class="{ 'is-active': fullscreen }"
            :aria-label="t('table.fullscreen')"
            @click="fullscreen = !fullscreen"
          >
            <el-icon :size="14"><FullScreen /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </div>

    <div v-if="$slots.filter" class="lw-filterbar">
      <slot name="filter" />
    </div>

    <!-- 批量条占筛选条位置：选中时换行，未选中不占位 -->
    <slot name="batch" />

    <div v-if="loading" class="lw-tablecard__body">
      <slot name="skeleton" />
    </div>

    <div v-else-if="error" class="lw-tablecard__body">
      <LwErrorBar :message="error" @retry="$emit('retry')" />
    </div>

    <template v-else>
      <LwEmpty v-if="empty" :description="emptyText">
        <slot name="empty-action" />
      </LwEmpty>
      <slot v-else />
    </template>

    <div v-if="!loading && !error && !empty && total > 0 && $slots.pager" class="lw-pager">
      <slot name="pager" />
    </div>
  </section>
</template>
