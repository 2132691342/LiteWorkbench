<script setup lang="ts">
/** 根组件：只负责路由出口与切换过渡 */
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="lw-fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>
