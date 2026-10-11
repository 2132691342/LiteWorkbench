<script setup lang="ts">
import { useRouter } from 'vue-router'
import LwButton from '@/components/ui/LwButton.vue'

/** 404 兜底页 */
const router = useRouter()
</script>

<template>
  <div class="lw-page items-center justify-center" style="min-height: 60vh">
    <div class="text-6xl font-extrabold tracking-tight" style="color: var(--lw-primary)">404</div>
    <p class="text-sm" style="color: var(--lw-ink-muted)">页面不存在或没有访问权限</p>
    <LwButton @click="router.push('/dashboard')">返回仪表盘</LwButton>
  </div>
</template>
