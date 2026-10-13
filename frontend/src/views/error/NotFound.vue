<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwErrorPage from '@/components/ui/LwErrorPage.vue'

/** 404：页面不存在或已被移动 */
const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <LwErrorPage code="404" :title="t('notFound.title')" :description="t('error.notFoundDesc')">
    <LwButton @click="router.push('/dashboard')">{{ t('notFound.back') }}</LwButton>
    <LwButton variant="ghost" @click="router.back()">{{ t('error.backPrev') }}</LwButton>
  </LwErrorPage>
</template>
