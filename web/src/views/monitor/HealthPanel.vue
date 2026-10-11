<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { monitorApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { HealthRESP, PythonStatusVO } from '@/types/api'

/** 健康监控：PostgreSQL / Redis / MinIO / RabbitMQ / Python 逐项探测 + 运行时状态 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const loading = ref(false)
const error = ref('')
const health = ref<HealthRESP | null>(null)
const python = ref<PythonStatusVO | null>(null)
const reprobing = ref(false)

const componentNames: Record<string, string> = {
  postgres: 'PostgreSQL',
  redis: 'Redisearch',
  minio: 'MinIO',
  rabbitmq: 'RabbitMQ',
  python: 'Python Runtime',
}

const items = computed(() =>
  (health.value?.components ?? []).map((component) => ({
    name: componentNames[component.name] ?? component.name,
    status: component.status,
    detail: component.detail ?? '',
    costMs: component.costMs,
  })),
)

const aggregate = computed(() => health.value?.status ?? '')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [healthResult, pythonResult] = await Promise.all([
      monitorApi.health(),
      monitorApi.pythonStatus(),
    ])
    health.value = healthResult
    python.value = pythonResult
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function reprobe(): Promise<void> {
  reprobing.value = true
  try {
    python.value = await monitorApi.pythonReprobe()
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
        <LwTag v-if="aggregate" :tone="toneOf(aggregate) === 'success' ? 'success' : toneOf(aggregate) === 'error' ? 'error' : 'warning'">
          {{ aggregate }}
        </LwTag>
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
            class="p-4 rounded-xl border flex items-center justify-between gap-3 transition-all duration-200 hover:shadow-md"
            style="border-color: var(--lw-border)"
          >
            <div class="min-w-0">
              <div class="font-bold text-sm" style="color: var(--lw-ink)">{{ item.name }}</div>
              <div class="text-xs mt-1 truncate" style="color: var(--lw-ink-muted)">{{ item.detail }}</div>
            </div>
            <div class="flex flex-col items-end gap-1 shrink-0">
              <LwTag :tone="toneOf(item.status)">{{ item.status }}</LwTag>
              <span class="text-xs opacity-50">{{ item.costMs }}ms</span>
            </div>
          </div>
        </div>
      </LwPageState>
    </LwCard>

    <LwCard class="mt-6">
      <template #title>{{ t('monitor.python') }}</template>
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
  </div>
</template>
