<script setup lang="ts">
import { useElementPlusLocale } from '@/plugins/element-plus'

/** 根组件：路由出口与切换过渡；Element Plus 语言随应用语言联动 */
const { locale: epLocale } = useElementPlusLocale()
</script>

<template>
  <el-config-provider :locale="epLocale">
    <router-view v-slot="{ Component }">
      <transition name="lw-fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </el-config-provider>
</template>
