<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'

/** 404 兜底页 */
const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <div class="lw-page items-center justify-center" style="min-height: 60vh">
    <div class="text-6xl font-extrabold tracking-tight" style="color: var(--lw-primary)">404</div>
    <p class="text-sm" style="color: var(--lw-ink-muted)">{{ t('notFound.title') }}</p>
    <LwButton @click="router.push('/dashboard')">{{ t('notFound.back') }}</LwButton>
  </div>
</template>
