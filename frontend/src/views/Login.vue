<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import LwButton from '@/components/ui/LwButton.vue'
import { useUserStore } from '@/stores/user'

/** 登录页：左侧品牌区 + 右侧凭证表单，错误内联展示不弹遮罩 */
const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 登录表单：不预填任何账号，避免把默认凭据暗示给访问者 */
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errorMessage = ref('')

/** 品牌区三条能力说明，文案走 i18n */
const features = computed(() => [
  {
    title: t('login.featureObserve'),
    desc: t('login.featureObserveDesc'),
    icon: 'M13 2 3 14h9l-1 8 10-12h-9l1-8z',
  },
  {
    title: t('login.featureRbac'),
    desc: t('login.featureRbacDesc'),
    icon: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z',
  },
  {
    title: t('login.featureModel'),
    desc: t('login.featureModelDesc'),
    icon: 'M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20M2 12h20',
  },
])

/** 提交登录，成功后回到来源页或仪表盘 */
async function handleSubmit(): Promise<void> {
  if (loading.value) return
  errorMessage.value = ''
  if (!form.username || !form.password) {
    errorMessage.value = t('common.failed')
    return
  }
  loading.value = true
  const start = Date.now()
  try {
    // 登录过快时保持 loading 至少 400ms，避免闪烁
    await userStore.login({ ...form })
    const remain = 400 - (Date.now() - start)
    if (remain > 0) {
      await new Promise((resolve) => setTimeout(resolve, remain))
    }
    // 首登强制改密：直接进改密页，其余入口在改密前不可达
    if (userStore.pwdResetRequired) {
      router.push('/force-password')
      return
    }
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('common.failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-full flex flex-col lg:flex-row" style="background-color: var(--lw-surface)">
    <!-- 品牌区 -->
    <section class="relative flex-1 overflow-hidden flex items-center justify-center px-8 py-12">
      <div
        class="absolute w-[480px] h-[480px] rounded-full pointer-events-none"
        style="top: -18%; left: -12%; background: var(--lw-primary); filter: blur(100px); opacity: 0.18"
      />
      <div
        class="absolute w-[400px] h-[400px] rounded-full pointer-events-none"
        style="bottom: -12%; right: -10%; background: var(--lw-secondary); filter: blur(100px); opacity: 0.18"
      />
      <div class="relative z-10 max-w-xl w-full flex flex-col items-center text-center lg:items-start lg:text-left">
        <div
          class="inline-flex items-center gap-2 px-3 py-1.5 rounded-full border text-xs font-bold mb-6"
          style="background: var(--lw-surface-raised); border-color: var(--lw-border); color: var(--lw-primary)"
        >
          <span class="w-1.5 h-1.5 rounded-full" style="background: var(--lw-primary)" />
          {{ t('login.subtitle') }}
        </div>
        <h1 class="text-4xl lg:text-5xl font-extrabold tracking-tight leading-tight mb-5" style="color: var(--lw-ink)">
          {{ t('app.title') }}<br />
          <span style="color: var(--lw-primary)">{{ t('app.slogan') }}</span>
        </h1>
        <p class="text-base mb-10 leading-relaxed max-w-md" style="color: var(--lw-ink-muted)">
          {{ t('login.welcomeDesc') }}
        </p>
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-4 w-full">
          <div
            v-for="feature in features"
            :key="feature.title"
            class="p-5 rounded-2xl border text-left flex flex-col gap-3 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-md"
            style="background: var(--lw-surface-raised); border-color: var(--lw-border)"
          >
            <div
              class="w-10 h-10 rounded-lg flex items-center justify-center"
              style="background: var(--lw-primary-tint); color: var(--lw-primary)"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path :d="feature.icon" />
              </svg>
            </div>
            <h3 class="font-bold text-sm" style="color: var(--lw-ink)">{{ feature.title }}</h3>
            <p class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">{{ feature.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- 表单区 -->
    <section class="lg:w-[460px] shrink-0 flex items-center justify-center px-6 py-10 border-t lg:border-t-0 lg:border-l"
             style="border-color: var(--lw-border); background: var(--lw-surface-raised)">
      <div class="w-full max-w-sm flex flex-col gap-6">
        <div>
          <h2 class="text-2xl font-bold tracking-tight" style="color: var(--lw-ink)">{{ t('login.welcome') }}</h2>
          <p class="text-sm mt-1" style="color: var(--lw-ink-muted)">{{ t('login.title') }}</p>
        </div>

        <form class="flex flex-col gap-4" @submit.prevent="handleSubmit">
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('login.username') }}</span>
            <div class="flex items-center px-3 rounded-control border transition-all duration-200 focus-within:shadow-sm"
                 style="border-color: var(--lw-border)">
              <el-icon :size="15" style="color: var(--lw-ink-muted)"><User /></el-icon>
              <input
                v-model="form.username"
                class="w-full px-2 py-2.5 bg-transparent outline-none text-sm"
                :placeholder="t('login.usernamePlaceholder')"
                autocomplete="username"
              />
            </div>
          </label>

          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('login.password') }}</span>
            <div class="flex items-center px-3 rounded-control border transition-all duration-200 focus-within:shadow-sm"
                 style="border-color: var(--lw-border)">
              <el-icon :size="15" style="color: var(--lw-ink-muted)"><Lock /></el-icon>
              <input
                v-model="form.password"
                type="password"
                class="w-full px-2 py-2.5 bg-transparent outline-none text-sm"
                :placeholder="t('login.passwordPlaceholder')"
                autocomplete="current-password"
              />
            </div>
          </label>

          <p v-if="errorMessage" class="text-xs font-medium px-3 py-2 rounded-control"
             style="color: var(--lw-error-ink); background: var(--lw-error-tint)">
            {{ errorMessage }}
          </p>

          <LwButton native-type="submit" size="lg" block :loading="loading" class="mt-2">
            {{ loading ? t('login.submitting') : t('login.submit') }}
          </LwButton>
        </form>

        <p class="text-xs leading-relaxed flex items-start gap-1.5" style="color: var(--lw-ink-muted)">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round" class="mt-0.5 shrink-0"
               style="color: var(--lw-secondary)">
            <rect width="18" height="11" x="3" y="11" rx="2" ry="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
          <span>{{ t('login.securityTip') }}</span>
        </p>
      </div>
    </section>
  </div>
</template>
