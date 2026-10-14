<script setup lang="ts">
import LwEmpty from './LwEmpty.vue'
import LwErrorBar from './LwErrorBar.vue'
import LwSkeleton from './LwSkeleton.vue'

/** 异步三态外壳：loading 骨架 / error 重试 / empty 空态，命中则接管内容；三态间淡入过渡 */
withDefaults(
  defineProps<{
    loading?: boolean
    /** 错误文案，非空即错误态 */
    error?: string
    empty?: boolean
    skeletonRows?: number
    emptyText?: string
  }>(),
  { loading: false, error: '', empty: false, skeletonRows: 4, emptyText: '' },
)

defineEmits<{ (e: 'retry'): void }>()
</script>

<template>
  <!-- 内容直接透传不加包裹，避免破坏调用方的网格 / 弹性布局 -->
  <slot v-if="!loading && !error && !empty" />
  <transition v-else name="lw-fade" mode="out-in">
    <LwSkeleton v-if="loading" :rows="skeletonRows" />
    <LwErrorBar v-else-if="error" :message="error" @retry="$emit('retry')" />
    <LwEmpty v-else :description="emptyText">
      <slot name="empty-action" />
    </LwEmpty>
  </transition>
</template>
