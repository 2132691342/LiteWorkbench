<script setup lang="ts">
import { Hide, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api'
import LwButton from '@/components/ui/LwButton.vue'
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

/** 打开时重置表单与错误 */
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
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.oldPassword') }}</span>
        <input v-model="form.oldPassword" :type="showPassword ? 'text' : 'password'" class="lw-input" autocomplete="current-password" />
      </label>
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.newPassword') }}</span>
        <input v-model="form.newPassword" :type="showPassword ? 'text' : 'password'" class="lw-input" autocomplete="new-password" />
      </label>
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.confirmPassword') }}</span>
        <input v-model="form.confirmPassword" :type="showPassword ? 'text' : 'password'" class="lw-input" autocomplete="new-password" />
      </label>
      <button
        type="button"
        class="self-start inline-flex items-center gap-1.5 text-xs font-medium transition-opacity hover:opacity-80"
        style="color: var(--lw-ink-muted)"
        @click="showPassword = !showPassword"
      >
        <el-icon :size="14"><component :is="showPassword ? Hide : View" /></el-icon>
        {{ showPassword ? t('login.hidePassword') : t('login.showPassword') }}
      </button>
      <p v-if="errorMessage" class="text-xs px-3 py-2 rounded-control" style="color: var(--lw-error-ink); background: var(--lw-error-tint)">
        {{ errorMessage }}
      </p>
    </form>
    <template #footer>
      <div class="flex justify-end gap-3">
        <LwButton variant="ghost" @click="visible = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="loading" @click="submit">{{ t('common.save') }}</LwButton>
      </div>
    </template>
  </el-dialog>
</template>
