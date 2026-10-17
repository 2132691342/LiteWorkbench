/** 行操作：默认插槽直出，溢出插槽收进「更多」下拉；破坏性动作由调用方自带分隔线与错误色 */
<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

withDefaults(defineProps<{ more?: boolean }>(), { more: false })

const { t } = useI18n()
</script>

<template>
  <div class="lw-rowacts">
    <slot />
    <el-dropdown v-if="more" trigger="click" placement="bottom-end">
      <button type="button" class="lw-rowacts__more" :aria-label="t('table.more')">
        <span>{{ t('table.more') }}</span>
        <el-icon :size="12"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <slot name="overflow" />
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>
