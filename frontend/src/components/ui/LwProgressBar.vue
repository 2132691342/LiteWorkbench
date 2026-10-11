<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

/** 路由切换顶部进度条：跳转开始缓慢推进，加载完成收满后淡出 */
const router = useRouter()
const visible = ref(false)
const progress = ref(0)

let timer: number | undefined
let hideTimer: number | undefined

function start(): void {
  window.clearInterval(timer)
  window.clearTimeout(hideTimer)
  visible.value = true
  progress.value = 12
  timer = window.setInterval(() => {
    progress.value = Math.min(progress.value + Math.random() * 12, 88)
  }, 180)
}

function done(): void {
  window.clearInterval(timer)
  progress.value = 100
  hideTimer = window.setTimeout(() => {
    visible.value = false
    progress.value = 0
  }, 240)
}

onMounted(() => {
  router.beforeEach(() => {
    start()
  })
  router.afterEach(() => {
    done()
  })
})

onBeforeUnmount(() => {
  window.clearInterval(timer)
  window.clearTimeout(hideTimer)
})
</script>

<template>
  <div class="lw-progress" :style="{ opacity: visible ? 1 : 0, width: `${progress}%` }" />
</template>
