<script setup lang="ts">
import { Hide, View } from '@element-plus/icons-vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { authApi } from '@/api'
import LwButton from '@/components/ui/LwButton.vue'
import { useUserStore } from '@/stores/user'
import { encryptSecret, resetPublicKey } from '@/utils/crypto'

/** 强制改密页：种子账号首登唯一可达页，改密成功即作废会话要求重新登录 */
const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false)
const errorMessage = ref('')
/** 三个口令框共用一个显隐开关，避免逐框加眼睛图标把表单撑乱 */
const showPassword = ref(false)

/** 强度提示：长度与字母数字组合 */
const strength = computed(() => {
  const value = form.newPassword
  if (!value) return 0
  let score = value.length >= 8 ? 1 : 0
  if (/[A-Za-z]/.test(value) && /\d/.test(value)) score += 1
  if (value.length >= 12) score += 1
  return score
})

async function handleSubmit(): Promise<void> {
  if (loading.value) return
  errorMessage.value = ''
  if (!form.oldPassword || !form.newPassword) {
    errorMessage.value = t('forcePassword.required')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    errorMessage.value = t('settings.passwordMismatch')
    return
  }
  if (strength.value < 2) {
    errorMessage.value = t('forcePassword.weak')
    return
  }
  loading.value = true
  try {
    const oldSecret = await encryptSecret(form.oldPassword)
    const newSecret = await encryptSecret(form.newPassword)
    await authApi.changePassword({
      oldPassword: oldSecret.cipher,
      newPassword: newSecret.cipher,
      keyId: newSecret.keyId,
    })
    resetPublicKey()
    userStore.reset()
    router.push('/login')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('common.failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-full flex items-center justify-center p-6" style="background-color: var(--lw-surface)">
    <div class="w-full max-w-[400px] rounded-card border p-8 flex flex-col gap-5"
         style="background-color: var(--lw-surface-raised); border-color: var(--lw-border)">
      <div>
        <h1 class="text-xl font-bold tracking-tight" style="color: var(--lw-ink)">
          {{ t('forcePassword.title') }}
        </h1>
        <p class="text-sm mt-1.5 leading-relaxed" style="color: var(--lw-ink-muted)">
          {{ t('forcePassword.desc') }}
        </p>
      </div>

      <form class="flex flex-col gap-4" @submit.prevent="handleSubmit">
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('settings.oldPassword') }}</span>
          <input v-model="form.oldPassword" :type="showPassword ? 'text' : 'password'" autocomplete="current-password"
                 class="lw-input" :placeholder="t('settings.oldPassword')" />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('settings.newPassword') }}</span>
          <input v-model="form.newPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password"
                 class="lw-input" :placeholder="t('forcePassword.newPlaceholder')" />
          <span class="flex items-center gap-1.5 mt-1">
            <span v-for="index in 3" :key="index" class="h-1 flex-1 rounded-full transition-all duration-200"
                  :style="{ backgroundColor: strength >= index ? 'var(--lw-primary)' : 'var(--lw-border)' }" />
          </span>
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="lw-label">{{ t('settings.confirmPassword') }}</span>
          <input v-model="form.confirmPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password"
                 class="lw-input" :placeholder="t('settings.confirmPassword')" />
        </label>

        <button
          type="button"
          class="self-start inline-flex items-center gap-1.5 text-xs font-medium border-none bg-transparent transition-opacity hover:opacity-80"
          style="color: var(--lw-ink-muted)"
          @click="showPassword = !showPassword"
        >
          <el-icon :size="14"><component :is="showPassword ? Hide : View" /></el-icon>
          {{ showPassword ? t('login.hidePassword') : t('login.showPassword') }}
        </button>

        <p v-if="errorMessage" class="text-xs font-medium px-3 py-2 rounded-control"
           style="color: var(--lw-error-ink); background: var(--lw-error-tint)">
          {{ errorMessage }}
        </p>

        <LwButton native-type="submit" size="lg" block :loading="loading">
          {{ loading ? t('forcePassword.submitting') : t('forcePassword.submit') }}
        </LwButton>
      </form>
    </div>
  </div>
</template>
