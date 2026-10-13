<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwErrorPage from '@/components/ui/LwErrorPage.vue'

/** 403：无权限访问 */
const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <LwErrorPage code="403" :title="t('error.forbiddenTitle')" :description="t('error.forbiddenDesc')">
    <LwButton @click="router.push('/dashboard')">{{ t('error.backHome') }}</LwButton>
    <LwButton variant="ghost" @click="router.back()">{{ t('error.backPrev') }}</LwButton>
  </LwErrorPage>
</template>
