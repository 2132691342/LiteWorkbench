<script setup lang="ts">
import LwEmpty from './LwEmpty.vue'
import LwErrorBar from './LwErrorBar.vue'
import LwSkeleton from './LwSkeleton.vue'

/** 异步三态外壳：loading 骨架 / error 重试 / empty 空态，命中则接管内容 */
withDefaults(
  defineProps<{
    /** 加载中 */
    loading?: boolean
    /** 错误文案，非空即错误态 */
    error?: string
    /** 是否空数据 */
    empty?: boolean
    /** 骨架行数 */
    skeletonRows?: number
    /** 空态说明 */
    emptyText?: string
  }>(),
  { loading: false, error: '', empty: false, skeletonRows: 4, emptyText: '' },
)

defineEmits<{ (e: 'retry'): void }>()
</script>

<template>
  <LwSkeleton v-if="loading" :rows="skeletonRows" />
  <LwErrorBar v-else-if="error" :message="error" @retry="$emit('retry')" />
  <LwEmpty v-else-if="empty" :description="emptyText">
    <slot name="empty-action" />
  </LwEmpty>
  <slot v-else />
</template>
