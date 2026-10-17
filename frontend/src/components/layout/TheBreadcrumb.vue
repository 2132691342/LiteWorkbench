/** 面包屑：两级以上才渲染，末级不可点；分隔符用 / 而非箭头图标 */
<script setup lang="ts">
import { useBreadcrumb } from '@/composables/useBreadcrumb'

const { trail } = useBreadcrumb()
</script>

<template>
  <nav v-if="trail.length > 1" class="lw-crumb" :aria-label="'breadcrumb'">
    <template v-for="(item, index) in trail" :key="item.path || item.label">
      <span v-if="index > 0" class="lw-crumb__sep" aria-hidden="true">/</span>
      <span v-if="index === trail.length - 1" class="lw-crumb__current">{{ item.label }}</span>
      <router-link v-else-if="item.path" :to="item.path" class="lw-crumb__link">{{ item.label }}</router-link>
      <span v-else class="lw-crumb__current">{{ item.label }}</span>
    </template>
  </nav>
</template>
