<script setup lang="ts">
import { Expand, Fold, SwitchButton, User } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import LwButton from '@/components/ui/LwButton.vue'
import { switchLocale } from '@/locales'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { roleLabel } from '@/utils/role'
import PasswordDialog from '@/views/settings/PasswordDialog.vue'

/** 顶栏：品牌区 + 语言切换 + 用户菜单，退出登录前二次确认 */
const { t } = useI18n()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

const passwordVisible = ref(false)
const localeText = computed(() => (appStore.locale === 'zh-CN' ? 'EN' : '中'))
const initials = computed(() => userStore.nickname.slice(0, 2).toUpperCase() || 'LW')
const currentRoleLabel = computed(() => roleLabel(userStore.roleCodes, t))

function toggleLocale(): void {
  switchLocale(appStore.locale === 'zh-CN' ? 'en-US' : 'zh-CN')
}

async function handleLogout(): Promise<void> {
  await ElMessageBox.confirm(t('app.logoutConfirm'), t('app.logout'), {
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel'),
    type: 'warning',
  })
  await userStore.logout()
  router.push('/login')
}

function handleCommand(command: string): void {
  if (command === 'password') {
    passwordVisible.value = true
  } else if (command === 'logout') {
    void handleLogout()
  }
}
</script>

<template>
  <header
    class="h-16 flex items-center justify-between px-4 md:px-6 border-b shrink-0 z-20"
    style="background-color: var(--lw-surface-raised); border-color: var(--lw-border)"
  >
    <div class="flex items-center gap-4 md:gap-8">
      <!-- 移动端唤出抽屉；品牌块在侧栏内，顶栏只留当前页上下文与用户区 -->
      <div class="flex items-center gap-3">
        <LwButton
          variant="text"
          size="sm"
          class="lg:hidden"
          :aria-label="t('app.expand')"
          @click="appStore.openSidebar()"
        >
          <template #icon>
            <el-icon><component :is="appStore.sidebarCollapsed ? Expand : Fold" /></el-icon>
          </template>
        </LwButton>
        <span class="font-bold text-base tracking-tight lg:hidden">{{ t('app.title') }}</span>
      </div>
    </div>

    <div class="flex items-center gap-2 md:gap-3">
      <span class="hidden md:block text-xs font-medium" style="color: var(--lw-ink-muted)">{{ t('app.greeting', { name: userStore.nickname }) }}</span>
      <LwButton variant="ghost" size="sm" @click="toggleLocale">{{ localeText }}</LwButton>
      <div class="hidden sm:flex flex-col items-end mr-1 leading-tight">
        <span class="text-xs font-bold" style="color: var(--lw-ink)">{{ userStore.nickname }}</span>
        <span class="text-[10px]" style="color: var(--lw-ink-muted)">
          {{ currentRoleLabel }}
        </span>
      </div>
      <el-dropdown trigger="click" @command="handleCommand">
        <div
          class="w-9 h-9 rounded-full flex items-center justify-center font-bold text-xs lw-shadow-sm cursor-pointer transition-transform duration-200 hover:scale-105"
          style="background-color: var(--lw-surface-subtle); color: var(--lw-primary-ink)"
        >
          {{ initials }}
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="password">
              <el-icon class="mr-1"><User /></el-icon>{{ t('app.changePassword') }}
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon class="mr-1"><SwitchButton /></el-icon>{{ t('app.logout') }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <PasswordDialog v-model="passwordVisible" />
  </header>
</template>
