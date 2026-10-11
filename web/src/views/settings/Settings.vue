<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwTag from '@/components/ui/LwTag.vue'
import PasswordDialog from './PasswordDialog.vue'
import { useUserStore } from '@/stores/user'

/** 设置：账户信息展示与安全操作 */
const { t } = useI18n()
const userStore = useUserStore()

const passwordVisible = ref(false)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('settings.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('settings.desc') }}</p>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <LwCard :title="t('settings.account')">
        <div class="flex flex-col gap-3 text-sm">
          <div class="flex justify-between">
            <span style="color: var(--lw-ink-muted)">{{ t('system.user.username') }}</span>
            <span class="font-bold">{{ userStore.user?.username }}</span>
          </div>
          <div class="flex justify-between">
            <span style="color: var(--lw-ink-muted)">{{ t('system.user.nickname') }}</span>
            <span class="font-bold">{{ userStore.user?.nickname }}</span>
          </div>
          <div class="flex justify-between items-center">
            <span style="color: var(--lw-ink-muted)">{{ t('system.user.role') }}</span>
            <span class="flex gap-1">
              <LwTag v-for="code in userStore.roleCodes" :key="code" tone="primary">{{ code }}</LwTag>
            </span>
          </div>
        </div>
      </LwCard>

      <LwCard :title="t('settings.security')">
        <p class="text-sm" style="color: var(--lw-ink-muted)">
          登录态由 Sa-Token 管理，令牌存于 Redis 并可随时作废。
        </p>
        <div>
          <LwButton variant="ghost" @click="passwordVisible = true">{{ t('app.changePassword') }}</LwButton>
        </div>
      </LwCard>
    </div>

    <PasswordDialog v-model="passwordVisible" />
  </div>
</template>
