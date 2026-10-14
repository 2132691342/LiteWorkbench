<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwTag from '@/components/ui/LwTag.vue'
import MemoryPanel from '@/components/settings/MemoryPanel.vue'
import ProviderPanel from '@/components/settings/ProviderPanel.vue'
import ToolPanel from '@/components/settings/ToolPanel.vue'
import { usePermission } from '@/composables/usePermission'
import { useUserStore } from '@/stores/user'
import PasswordDialog from './PasswordDialog.vue'

/** 设置：账户信息、安全操作，以及有权限时可见的平台级配置（供应方 / 工具） */
const { t } = useI18n()
const { hasPerm } = usePermission()
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
          {{ t('settings.securityDesc') }}
        </p>
        <div>
          <LwButton variant="ghost" @click="passwordVisible = true">{{ t('app.changePassword') }}</LwButton>
        </div>
      </LwCard>
    </div>

    <!-- 记忆是用户私有资产，所有登录用户都能管自己的 -->
    <MemoryPanel />

    <ProviderPanel v-if="hasPerm('wb:provider:list')" />
    <ToolPanel v-if="hasPerm('wb:tool:list')" />

    <PasswordDialog v-model="passwordVisible" />
  </div>
</template>
