/** 表格卡：筛选条 + 表体 + 分页同属一张白卡，是管理列表页的统一骨架 */
<script setup lang="ts">
import LwEmpty from './LwEmpty.vue'
import LwErrorBar from './LwErrorBar.vue'

withDefaults(
  defineProps<{
    /** 列表数据为空时显示的说明 */
    emptyText?: string
    loading?: boolean
    error?: string
    /** 分页总数，为 0 或不足一页时不渲染分页条 */
    total?: number
  }>(),
  { emptyText: '', loading: false, error: '', total: 0 },
)

defineEmits<{ (e: 'retry'): void }>()
</script>

<template>
  <section class="lw-tablecard">
    <div v-if="$slots.filter" class="lw-filterbar">
      <slot name="filter" />
    </div>

    <div v-if="loading" class="lw-tablecard__body">
      <slot name="skeleton" />
    </div>

    <div v-else-if="error" class="lw-tablecard__body">
      <LwErrorBar :message="error" @retry="$emit('retry')" />
    </div>

    <template v-else>
      <LwEmpty v-if="emptyText" :description="emptyText" />
      <slot v-else />
    </template>

    <div v-if="!loading && !error && total > 0 && $slots.pager" class="lw-pager">
      <slot name="pager" />
    </div>
  </section>
</template>
