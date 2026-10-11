<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api'
import LwButton from '@/components/ui/LwButton.vue'

/** 修改密码弹窗：两次新密码校验通过后才提交 */
const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false)
const errorMessage = ref('')

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
  if (form.newPassword !== form.confirmPassword) {
    errorMessage.value = t('settings.passwordMismatch')
    return
  }
  loading.value = true
  try {
    await authApi.changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success(t('common.saveSuccess'))
    visible.value = false
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
        <input v-model="form.oldPassword" type="password" class="lw-input" autocomplete="current-password" />
      </label>
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.newPassword') }}</span>
        <input v-model="form.newPassword" type="password" class="lw-input" autocomplete="new-password" />
      </label>
      <label class="flex flex-col gap-1.5">
        <span class="lw-label">{{ t('settings.confirmPassword') }}</span>
        <input v-model="form.confirmPassword" type="password" class="lw-input" autocomplete="new-password" />
      </label>
      <p v-if="errorMessage" class="text-xs px-3 py-2 rounded-control" style="color: var(--lw-error); background: rgba(212, 106, 106, 0.1)">
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
