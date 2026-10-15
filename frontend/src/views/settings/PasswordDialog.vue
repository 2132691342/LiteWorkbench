<script setup lang="ts">
import { Hide, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api'
import LwButton from '@/components/ui/LwButton.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import { useUserStore } from '@/stores/user'
import { encryptSecret, resetPublicKey } from '@/utils/crypto'

/** 修改密码弹窗：两次新密码校验通过后才提交 */
const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()
const userStore = useUserStore()
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false)
const errorMessage = ref('')
/** 口令框共用显隐开关，与强制改密页保持一致 */
const showPassword = ref(false)

watch(visible, (value) => {
  if (value) {
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    errorMessage.value = ''
  }
})

async function submit(): Promise<void> {
  errorMessage.value = ''
  if (!form.oldPassword || !form.newPassword) {
    errorMessage.value = t('settings.passwordRequired')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    errorMessage.value = t('settings.passwordMismatch')
    return
  }
  loading.value = true
  try {
    // 两段密码分别加密；改密成功后服务端作废会话，需重新登录
    const oldSecret = await encryptSecret(form.oldPassword)
    const newSecret = await encryptSecret(form.newPassword)
    await authApi.changePassword({
      oldPassword: oldSecret.cipher,
      newPassword: newSecret.cipher,
      keyId: newSecret.keyId,
    })
    resetPublicKey()
    ElMessage.success(t('settings.passwordChanged'))
    visible.value = false
    userStore.reset()
    window.location.href = '/login'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('common.failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="t('app.changePassword')" width="420px" append-to-body>
    <form class="flex flex-col gap-4" @submit.prevent="submit">
      <!-- 显隐开关内嵌进输入框：裸 <button> 会被浏览器画出默认灰框，且独立开关与字段脱节 -->
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.oldPassword') }}</span>
        <div
          class="lw-field flex items-center h-10 px-3 rounded-control border transition-all duration-200 focus-within:border-lw-primary focus-within:lw-shadow-sm"
          style="border-color: var(--lw-border-strong)"
        >
          <input
            v-model="form.oldPassword" :type="showPassword ? 'text' : 'password'"
            class="w-full px-2 bg-transparent text-sm" :placeholder="t('settings.fieldPlaceholder')"
            autocomplete="current-password"
          >
          <button
            type="button" class="lw-icon-btn p-1 rounded-md shrink-0 transition-all duration-200 hover:bg-black/5 active:scale-90"
            :aria-label="showPassword ? t('login.hidePassword') : t('login.showPassword')"
            @click="showPassword = !showPassword"
          >
            <el-icon :size="14" style="color: var(--lw-ink-muted)"><component :is="showPassword ? Hide : View" /></el-icon>
          </button>
        </div>
      </label>
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.newPassword') }}</span>
        <div
          class="lw-field flex items-center h-10 px-3 rounded-control border transition-all duration-200 focus-within:border-lw-primary focus-within:lw-shadow-sm"
          style="border-color: var(--lw-border-strong)"
        >
          <input
            v-model="form.newPassword" :type="showPassword ? 'text' : 'password'"
            class="w-full px-2 bg-transparent text-sm" :placeholder="t('settings.fieldPlaceholder')"
            autocomplete="new-password" minlength="8"
          >
          <button
            type="button" class="lw-icon-btn p-1 rounded-md shrink-0 transition-all duration-200 hover:bg-black/5 active:scale-90"
            :aria-label="showPassword ? t('login.hidePassword') : t('login.showPassword')"
            @click="showPassword = !showPassword"
          >
            <el-icon :size="14" style="color: var(--lw-ink-muted)"><component :is="showPassword ? Hide : View" /></el-icon>
          </button>
        </div>
        <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('settings.passwordRule') }}</span>
      </label>
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.confirmPassword') }}</span>
        <div
          class="lw-field flex items-center h-10 px-3 rounded-control border transition-all duration-200 focus-within:border-lw-primary focus-within:lw-shadow-sm"
          style="border-color: var(--lw-border-strong)"
        >
          <input
            v-model="form.confirmPassword" :type="showPassword ? 'text' : 'password'"
            class="w-full px-2 bg-transparent text-sm" :placeholder="t('settings.fieldPlaceholder')"
            autocomplete="new-password" minlength="8"
          >
          <button
            type="button" class="lw-icon-btn p-1 rounded-md shrink-0 transition-all duration-200 hover:bg-black/5 active:scale-90"
            :aria-label="showPassword ? t('login.hidePassword') : t('login.showPassword')"
            @click="showPassword = !showPassword"
          >
            <el-icon :size="14" style="color: var(--lw-ink-muted)"><component :is="showPassword ? Hide : View" /></el-icon>
          </button>
        </div>
      </label>
      <LwErrorBar v-if="errorMessage" :message="errorMessage" :retryable="false" />
    </form>
    <template #footer>
      <div class="flex justify-end gap-3">
        <LwButton variant="ghost" @click="visible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="loading" @click="submit">{{ t('common.save') }}</LwButton>
      </div>
    </template>
  </el-dialog>
</template>
