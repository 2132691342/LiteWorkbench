<script setup lang="ts">
/** 骨架屏：列表与卡片加载时占位，不做整页 loading */
withDefaults(defineProps<{ rows?: number }>(), { rows: 4 })

const widths = ['100%', '92%', '78%', '86%', '64%', '90%']
</script>

<template>
  <div class="flex flex-col gap-3" aria-hidden="true">
    <div
      v-for="row in rows"
      :key="row"
      class="lw-skeleton h-9"
      :style="{ width: widths[(row - 1) % widths.length] }"
    />
  </div>
</template>
