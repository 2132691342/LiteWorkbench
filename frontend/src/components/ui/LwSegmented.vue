<script setup lang="ts">
/** 分段切换：视图 / 时间 / 视角的组内单选，全站统一，禁止各页自造 tab 或 pill */
interface SegmentedOption {
  value: string
  label: string
  count?: number
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  modelValue: string
  options: SegmentedOption[]
  /** 组内等宽（短标签视图切换用） */
  equal?: boolean
  size?: 'sm' | 'md'
}>(), { equal: false, size: 'md' })

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

function select(option: SegmentedOption): void {
  if (option.disabled || option.value === props.modelValue) {
    return
  }
  emit('update:modelValue', option.value)
}
</script>

<template>
  <div
    class="lw-segmented"
    :class="[`lw-segmented--${size}`, { 'is-equal': equal }]"
    role="tablist"
  >
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="tab"
      class="lw-segmented__item"
      :class="{ 'is-active': option.value === modelValue }"
      :disabled="option.disabled"
      :aria-selected="option.value === modelValue"
      :aria-disabled="option.disabled"
      @click="select(option)"
    >
      <span>{{ option.label }}</span>
      <span v-if="option.count !== undefined" class="lw-segmented__count">{{ option.count }}</span>
    </button>
  </div>
</template>
