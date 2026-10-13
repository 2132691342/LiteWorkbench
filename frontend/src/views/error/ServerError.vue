<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwErrorPage from '@/components/ui/LwErrorPage.vue'

/** 500：服务端异常兜底 */
const { t } = useI18n()
const router = useRouter()

function retry(): void {
  window.location.reload()
}
</script>

<template>
  <LwErrorPage code="500" :title="t('error.serverTitle')" :description="t('error.serverDesc')">
    <LwButton @click="retry">{{ t('error.retry') }}</LwButton>
    <LwButton variant="ghost" @click="router.push('/dashboard')">{{ t('error.backHome') }}</LwButton>
  </LwErrorPage>
</template>
