<script setup lang="ts">
import { Hide, Lock, User, View } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import LwButton from '@/components/ui/LwButton.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import { useUserStore } from '@/stores/user'
import { fetchPublicKey } from '@/utils/crypto'

/** 登录页：居中单卡（DESIGN.md §12.3），进入即预热公钥，失败内联提示不降级明文 */
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

/** 品牌卡三条能力说明，文案走 i18n */
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
    icon: 'M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0 20M2 12h20',
  },
])

/** 回跳地址只接受站内绝对路径，防止被伪造成外部地址 */
function safeRedirect(): string {
  const raw = route.query.redirect as string | undefined
  return raw && raw.startsWith('/') && !raw.startsWith('//') ? raw : '/dashboard'
}

/** 提交登录，成功后回到来源页或仪表盘 */
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
  <div class="min-h-full flex items-center justify-center p-6" style="background-color: var(--lw-surface)">
    <div class="w-full max-w-[400px] flex flex-col gap-6">
      <!-- 品牌区：方块图标 + 产品名 + 一句定位 -->
      <div class="flex flex-col items-center text-center gap-3">
        <div
          class="w-12 h-12 rounded-card flex items-center justify-center shadow-sm"
          style="background: var(--lw-secondary)"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round" style="color: #ffffff">
            <rect width="18" height="18" x="3" y="3" rx="2" ry="2" />
            <line x1="3" x2="21" y1="9" y2="9" />
            <line x1="9" x2="9" y1="21" y2="9" />
          </svg>
        </div>
        <div>
          <h1 class="text-2xl font-bold tracking-tight" style="color: var(--lw-ink)">{{ t('app.title') }}</h1>
          <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">{{ t('login.welcomeDesc') }}</p>
        </div>
      </div>

      <!-- 凭证表单卡 -->
      <section class="lw-card p-6 flex flex-col gap-5">
        <div>
          <h2 class="text-lg font-bold tracking-tight" style="color: var(--lw-ink)">{{ t('login.welcome') }}</h2>
          <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">{{ t('login.title') }}</p>
        </div>

        <LwErrorBar v-if="keyError" :message="t('login.keyFailed')" @retry="loadPublicKey" />

        <form class="flex flex-col gap-4" @submit.prevent="handleSubmit">
          <label class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('login.username') }}</span>
            <div class="flex items-center h-10 px-3 rounded-control border transition-all duration-200 focus-within:shadow-sm"
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
            <div class="flex items-center h-10 px-3 rounded-control border transition-all duration-200 focus-within:shadow-sm"
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
                  class="p-1 rounded-md transition-colors hover:bg-black/5 focus:outline-none"
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

      <!-- 能力说明：三张小卡，登录页唯一的信息补充 -->
      <div class="grid grid-cols-3 gap-3">
        <div
          v-for="feature in features"
          :key="feature.title"
          class="p-3 rounded-card border text-left flex flex-col gap-2 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-md"
          style="background: var(--lw-surface-raised); border-color: var(--lw-border)"
        >
          <div
            class="w-8 h-8 rounded-lg flex items-center justify-center"
            style="background: var(--lw-primary-tint); color: var(--lw-primary)"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path :d="feature.icon" />
            </svg>
          </div>
          <h3 class="font-bold text-xs" style="color: var(--lw-ink)">{{ feature.title }}</h3>
          <p class="text-[10px] leading-relaxed" style="color: var(--lw-ink-muted)">{{ feature.desc }}</p>
        </div>
      </div>
    </div>
  </div>
</template>
