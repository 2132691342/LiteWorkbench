<script setup lang="ts">
import { computed } from 'vue'

/** 全站唯一按钮：五态由 .lw-btn 系列样式实现，hover 放大 / pressed 缩小 / loading 锁宽 */
interface Props {
  /** 变体：主/次/幽灵/危险/文字 */
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'text'
  /** 尺寸 */
  size?: 'sm' | 'md' | 'lg'
  /** 加载态：锁定宽度、屏蔽点击、显示旋转指示器 */
  loading?: boolean
  /** 禁用态 */
  disabled?: boolean
  /** 占满父容器宽度 */
  block?: boolean
  /** 原生 type */
  nativeType?: 'button' | 'submit' | 'reset'
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'primary',
  size: 'md',
  loading: false,
  disabled: false,
  block: false,
  nativeType: 'button',
})

defineEmits<{ (e: 'click', payload: MouseEvent): void }>()

const classes = computed(() => [
  'lw-btn',
  `lw-btn--${props.variant}`,
  `lw-btn--${props.size}`,
  {
    'is-loading': props.loading,
    'w-full': props.block,
  },
])

const isDisabled = computed(() => props.disabled || props.loading)
</script>

<template>
  <button
    :class="classes"
    :disabled="isDisabled"
    :type="nativeType"
    :aria-busy="loading"
    @click="$emit('click', $event)"
  >
    <span class="lw-btn__label inline-flex items-center gap-1.5">
      <slot name="icon" />
      <slot />
    </span>
    <span v-if="loading" class="lw-btn__spinner">
      <span class="lw-spinner" />
    </span>
  </button>
</template>
