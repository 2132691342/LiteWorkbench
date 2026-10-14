<script setup lang="ts">
import { Hide, Lock, User, View } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import LwButton from '@/components/ui/LwButton.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import { useUserStore } from '@/stores/user'
import { fetchPublicKey } from '@/utils/crypto'

/** 登录页：左品牌 hero + 右凭证卡，进入即预热公钥，取不到公钥不降级明文 */
const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 登录表单：不预填任何账号，避免把默认凭据暗示给访问者 */
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errorMessage = ref('')
const keyReady = ref(false)
const keyError = ref(false)
const showPassword = ref(false)

/** 公钥预热：安全组件取不到时不降级明文，内联错误条 + 重试 */
async function loadPublicKey(): Promise<void> {
  keyError.value = false
  try {
    await fetchPublicKey()
    keyReady.value = true
  } catch {
    keyReady.value = false
    keyError.value = true
  }
}

onMounted(loadPublicKey)

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
    // 地球：外圈 + 经线 + 赤道，三段子路径合成一条 d
    icon: 'M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20M2 12h20',
  },
])

/** 回跳地址只接受站内绝对路径，防止被伪造成外部地址 */
function safeRedirect(): string {
  const raw = route.query.redirect as string | undefined
  return raw && raw.startsWith('/') && !raw.startsWith('//') ? raw : '/dashboard'
}

async function handleSubmit(): Promise<void> {
  if (loading.value) return
  errorMessage.value = ''
  if (!form.username) {
    errorMessage.value = t('login.usernameRequired')
    return
  }
  if (!form.password) {
    errorMessage.value = t('login.passwordRequired')
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
    router.push(safeRedirect())
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('common.failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-full flex items-center justify-center p-6 relative overflow-hidden" style="background-color: var(--lw-surface)">
    <div class="absolute top-[-20%] left-[-10%] w-[500px] h-[500px] rounded-full blur-[100px] opacity-20 pointer-events-none"
         style="background-color: var(--lw-secondary)" />
    <div class="absolute bottom-[-10%] right-[-10%] w-[400px] h-[400px] rounded-full blur-[100px] opacity-20 pointer-events-none"
         style="background-color: var(--lw-surface-subtle)" />

    <div class="w-full max-w-[1080px] flex flex-col lg:flex-row items-center gap-10 lg:gap-16 relative z-10">
      <section class="flex-1 flex flex-col items-center lg:items-start text-center lg:text-left">
        <div class="flex items-center gap-3">
          <div class="w-12 h-12 rounded-card flex items-center justify-center lw-shadow-sm transition-transform duration-200 hover:scale-105"
               style="background: var(--lw-secondary)">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round" style="color: var(--lw-on-primary)">
              <rect width="18" height="18" x="3" y="3" rx="2" ry="2" />
              <line x1="3" x2="21" y1="9" y2="9" />
              <line x1="9" x2="9" y1="21" y2="9" />
            </svg>
          </div>
          <span class="text-2xl font-bold tracking-tight" style="color: var(--lw-ink)">{{ t('app.title') }}</span>
        </div>

        <div class="inline-flex items-center gap-2 px-3 py-1.5 rounded-full border mt-8 text-xs font-bold transition-all duration-200 hover:lw-shadow-sm"
             style="background: var(--lw-surface-raised); border-color: var(--lw-border); color: var(--lw-secondary)">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z" />
          </svg>
          <span>{{ t('login.badge') }}</span>
        </div>

        <h1 class="text-4xl lg:text-5xl font-extrabold tracking-tight mt-6 leading-tight" style="color: var(--lw-ink)">
          {{ t('login.heroTitle1') }}<br />
          <span style="color: var(--lw-secondary)">{{ t('login.heroTitle2') }}</span>
        </h1>

        <p class="text-base mt-5 leading-relaxed max-w-[480px]" style="color: var(--lw-ink-muted)">
          {{ t('login.heroDesc') }}
        </p>

        <div class="hidden lg:flex flex-col gap-3 mt-10 w-full max-w-[460px]">
          <div v-for="feature in features" :key="feature.title"
               class="flex items-start gap-3 p-3 rounded-card border transition-all duration-200 hover:-translate-y-0.5 hover:lw-shadow-md"
               style="background: var(--lw-surface-raised); border-color: var(--lw-border)">
            <div class="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
                 style="background: var(--lw-primary-tint); color: var(--lw-primary)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                   stroke-linecap="round" stroke-linejoin="round">
                <path :d="feature.icon" />
              </svg>
            </div>
            <div class="text-left">
              <h3 class="font-bold text-sm" style="color: var(--lw-ink)">{{ feature.title }}</h3>
              <p class="text-xs mt-0.5 leading-relaxed" style="color: var(--lw-ink-muted)">{{ feature.desc }}</p>
            </div>
          </div>
        </div>
      </section>

      <section class="w-full max-w-[400px] lw-card p-6 flex flex-col gap-5">
        <div>
          <h2 class="text-lg font-bold tracking-tight" style="color: var(--lw-ink)">{{ t('login.welcome') }}</h2>
          <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">{{ t('login.title') }}</p>
        </div>

        <LwErrorBar v-if="keyError" :message="t('login.keyFailed')" @retry="loadPublicKey" />

        <form class="flex flex-col gap-4" @submit.prevent="handleSubmit">
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('login.username') }}</span>
            <div class="flex items-center h-10 px-3 rounded-control border transition-all duration-200 focus-within:border-lw-primary focus-within:lw-shadow-sm"
                 style="border-color: var(--lw-border)">
              <el-icon :size="15" style="color: var(--lw-ink-muted)"><User /></el-icon>
              <input
                v-model="form.username"
                class="w-full px-2 bg-transparent outline-none text-sm"
                :placeholder="t('login.usernamePlaceholder')"
                autocomplete="username"
              />
            </div>
          </label>

          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('login.password') }}</span>
            <div class="flex items-center h-10 px-3 rounded-control border transition-all duration-200 focus-within:border-lw-primary focus-within:lw-shadow-sm"
                 style="border-color: var(--lw-border)">
              <el-icon :size="15" style="color: var(--lw-ink-muted)"><Lock /></el-icon>
              <input
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                class="w-full px-2 bg-transparent outline-none text-sm"
                :placeholder="t('login.passwordPlaceholder')"
                autocomplete="current-password"
                @keydown.enter.prevent="handleSubmit"
              />
              <el-tooltip
                :content="showPassword ? t('login.hidePassword') : t('login.showPassword')"
                placement="top"
                :open-delay="300"
              >
                <button
                  type="button"
                  class="p-1 rounded-md transition-all duration-200 hover:bg-black/5 active:scale-90 focus:outline-none"
                  :aria-label="showPassword ? t('login.hidePassword') : t('login.showPassword')"
                  @click="showPassword = !showPassword"
                >
                  <el-icon :size="15" style="color: var(--lw-ink-muted)">
                    <component :is="showPassword ? Hide : View" />
                  </el-icon>
                </button>
              </el-tooltip>
            </div>
          </label>

          <p v-if="errorMessage" class="text-xs font-medium px-3 py-2 rounded-control"
             style="color: var(--lw-error-ink); background: var(--lw-error-tint)">
            {{ errorMessage }}
          </p>

          <LwButton native-type="submit" size="lg" block :loading="loading" :disabled="!keyReady" class="mt-2">
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
      </section>
    </div>
  </div>
</template>
