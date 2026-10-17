<script setup lang="ts">
import { Folder, Refresh } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import ObservePanel from '@/components/monitor/ObservePanel.vue'
import { usePermission } from '@/composables/usePermission'
import { useMonitorStore } from '@/stores/monitor'

/** 健康监控：组件探测 + Python 运行时 + SQL/HTTP 调用观测 */
const { t } = useI18n()
const { hasPerm } = usePermission()
const router = useRouter()
const monitorStore = useMonitorStore()

const loading = computed(() => monitorStore.loading)
const error = computed(() => monitorStore.error)
const health = computed(() => monitorStore.health)
const python = computed(() => monitorStore.python)
const reprobing = ref(false)

const componentNames = computed<Record<string, string>>(() => ({
  postgres: t('monitor.componentPostgres'),
  redis: t('monitor.componentRedis'),
  manticore: t('monitor.componentManticore'),
  minio: t('monitor.componentMinio'),
  rabbitmq: t('monitor.componentRabbitmq'),
  python: t('monitor.componentPython'),
}))

const items = computed(() =>
  (health.value?.components ?? []).map((component) => ({
    name: componentNames.value[component.name] ?? component.name,
    status: component.status,
    detail: component.detail ?? '',
    costMs: component.costMs,
  })),
)

const aggregate = computed(() => health.value?.status ?? '')

async function load(): Promise<void> {
  await Promise.all([monitorStore.loadHealth(), monitorStore.loadPython()])
}

async function reprobe(): Promise<void> {
  reprobing.value = true
  try {
    await monitorStore.reprobe()
    ElMessage.success(t('common.success'))
  } finally {
    reprobing.value = false
  }
}

function toneOf(status: string): 'success' | 'warning' | 'error' {
  if (status === 'UP' || status === 'READY') return 'success'
  if (status === 'DEGRADED' || status === 'BAD_VERSION') return 'warning'
  return 'error'
}

onMounted(load)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('monitor.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('monitor.desc') }}</p>
      </div>
      <div class="flex items-center gap-3">
        <span class="h-10 inline-flex items-center">
          <LwTag v-if="aggregate" :tone="toneOf(aggregate) === 'success' ? 'success' : toneOf(aggregate) === 'error' ? 'error' : 'warning'">
            {{ aggregate }}
          </LwTag>
        </span>
        <LwButton v-if="hasPerm('monitor:storage:list')" variant="secondary" size="sm" @click="router.push('/storage')">
          <template #icon><el-icon><Folder /></el-icon></template>
          {{ t('menu.storageManage') }}
        </LwButton>
        <LwButton variant="ghost" @click="load">
          <template #icon><el-icon><Refresh /></el-icon></template>
          {{ t('common.refresh') }}
        </LwButton>
      </div>
    </div>

    <LwCard>
      <LwPageState :loading="loading" :error="error" :empty="!items.length" :skeleton-rows="5" @retry="load">
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          <div
            v-for="item in items"
            :key="item.name"
            class="p-4 rounded-card border flex items-center justify-between gap-3 transition-all duration-200 hover:lw-shadow-md"
            style="border-color: var(--lw-border)"
          >
            <div class="min-w-0">
              <div class="font-bold text-sm" style="color: var(--lw-ink)">{{ item.name }}</div>
              <div class="text-xs mt-1 truncate" style="color: var(--lw-ink-muted)">{{ item.detail }}</div>
            </div>
            <div class="flex flex-col items-end gap-1 shrink-0">
              <LwTag :tone="toneOf(item.status)">{{ item.status }}</LwTag>
              <span class="text-xs opacity-50">{{ t('monitor.costMs', { ms: item.costMs }) }}</span>
            </div>
          </div>
        </div>
      </LwPageState>
    </LwCard>

    <LwCard class="mt-6" :title="t('monitor.python')">
      <div class="flex items-center justify-between gap-4 flex-wrap">
        <div class="flex items-center gap-3">
          <LwTag :tone="toneOf(python?.status ?? '')">{{ python?.status ?? '-' }}</LwTag>
          <span class="text-sm" style="color: var(--lw-ink)">
            {{ python?.version ? `Python ${python.version}` : t('monitor.pythonMissing') }}
          </span>
          <span class="text-xs opacity-60 font-mono truncate max-w-md">{{ python?.source }}</span>
        </div>
        <LwButton
          v-if="hasPerm('monitor:python:reprobe')"
          variant="secondary"
          size="sm"
          :loading="reprobing"
          @click="reprobe"
        >
          {{ t('monitor.reprobe') }}
        </LwButton>
      </div>
    </LwCard>

    <ObservePanel />
  </div>
</template>
