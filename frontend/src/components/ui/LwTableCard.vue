/** 管理列表卡：筛选条 + 表体 + 分页同属一张白卡，是增删改查页的统一骨架 */
<script setup lang="ts">
import LwEmpty from './LwEmpty.vue'
import LwErrorBar from './LwErrorBar.vue'

withDefaults(
  defineProps<{
    /** 数据为空：由调用方按数据源长度给出，不能用 emptyText 代替判空 */
    empty?: boolean
    /** 空态说明文案 */
    emptyText?: string
    loading?: boolean
    error?: string
    /** 分页总数，为 0 或不足一页时不渲染分页条 */
    total?: number
  }>(),
  { empty: false, emptyText: '', loading: false, error: '', total: 0 },
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
