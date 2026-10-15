<script setup lang="ts">
import { DataLine, Stopwatch, Timer, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwMetricCard from '@/components/ui/LwMetricCard.vue'
import LwSegmented from '@/components/ui/LwSegmented.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import { usePermission } from '@/composables/usePermission'
import { useMonitorStore } from '@/stores/monitor'
import { formatTime } from '@/utils/const'

/** 调用观测：窗口内 SQL 与 HTTP 的汇总指标与慢调用榜，可定时刷新与重置窗口 */
const { t } = useI18n()
const { hasPerm } = usePermission()
const monitorStore = useMonitorStore()

const view = computed(() => monitorStore.observe)
const loading = computed(() => monitorStore.observeLoading)
const error = computed(() => monitorStore.observeError)
const resetting = ref(false)

/** 自动刷新档位：0 表示关闭，其余为秒 */
const interval = ref('30')

const intervalOptions = computed(() => [
  { value: '0', label: t('monitor.refreshOff') },
  { value: '10', label: t('monitor.refresh10s') },
  { value: '30', label: t('monitor.refresh30s') },
])

const isEmpty = computed(() => {
  const data = view.value
  if (!data) return true
  return (data.sql?.count ?? 0) === 0 && (data.http?.count ?? 0) === 0
})

const windowLabel = computed(() => {
  const data = view.value
  if (!data?.windowStartMs) return ''
  return t('monitor.observeWindow', { time: formatTime(data.windowStartMs) })
})

let timer: ReturnType<typeof setInterval> | null = null

function stopTimer(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

function startTimer(): void {
  stopTimer()
  const seconds = Number(interval.value)
  if (!Number.isFinite(seconds) || seconds <= 0) return
  timer = setInterval(() => {
    void monitorStore.loadObserve()
  }, seconds * 1000)
}

watch(interval, startTimer)

async function reset(): Promise<void> {
  try {
    await ElMessageBox.confirm(t('monitor.observeResetTip'), t('monitor.observeReset'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  resetting.value = true
  try {
    await monitorStore.resetObserve()
    ElMessage.success(t('common.success'))
  } finally {
    resetting.value = false
  }
}

onMounted(() => {
  void monitorStore.loadObserve()
  startTimer()
})

onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="flex flex-col gap-4">
    <LwCard :title="t('monitor.observe')" :desc="t('monitor.observeDesc')">
      <template #actions>
        <div class="flex items-center gap-3">
          <LwSegmented v-model="interval" :options="intervalOptions" size="sm" />
          <LwButton
            v-if="hasPerm('monitor:observe:reset')"
            variant="text"
            size="sm"
            class="lw-text-danger"
            :loading="resetting"
            @click="reset"
          >
            {{ t('monitor.observeReset') }}
          </LwButton>
        </div>
      </template>

      <LwErrorBar v-if="error" :message="error" @retry="monitorStore.loadObserve()" />
      <LwSkeleton v-else-if="loading && !view" :rows="4" />

      <template v-else-if="view">
        <p v-if="windowLabel" class="text-xs" style="color: var(--lw-ink-muted)">{{ windowLabel }}</p>

        <div class="flex flex-col gap-2">
          <span class="lw-label">{{ t('monitor.observeSql') }}</span>
          <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
            <LwMetricCard
              :label="t('monitor.observeSqlTotal')"
              :value="String(view.sql?.count ?? 0)"
              :icon="DataLine"
            />
            <LwMetricCard
              :label="t('monitor.observeSlow')"
              :value="String(view.sql?.slowCount ?? 0)"
              :hint-tone="(view.sql?.slowCount ?? 0) > 0 ? 'warning' : 'success'"
              tone="secondary"
              :icon="WarningFilled"
            />
            <LwMetricCard
              :label="t('monitor.observeAvg')"
              :value="`${view.sql?.avgMs ?? 0}ms`"
              tone="ink"
              :icon="Timer"
            />
            <LwMetricCard
              :label="t('monitor.observeMax')"
              :value="`${view.sql?.maxMs ?? 0}ms`"
              :icon="Stopwatch"
            />
          </div>

          <div v-if="view.sql?.top?.length" class="lw-table mt-1">
            <el-table :data="view.sql.top" size="small">
              <el-table-column prop="key" :label="t('monitor.colSql')" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="font-mono text-xs">{{ row.key }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="count" :label="t('monitor.colCount')" width="80" align="right" />
              <el-table-column :label="t('monitor.colAvg')" width="90" align="right">
                <template #default="{ row }">{{ row.avgMs }}ms</template>
              </el-table-column>
              <el-table-column :label="t('monitor.colMax')" width="90" align="right">
                <template #default="{ row }">{{ row.maxMs }}ms</template>
              </el-table-column>
              <el-table-column :label="t('monitor.colLast')" width="150" align="right">
                <template #default="{ row }">
                  <span class="font-mono text-xs">{{ formatTime(row.lastTs) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="sample" :label="t('monitor.colMethod')" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="font-mono text-xs">{{ row.sample || '-' }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="flex flex-col gap-2">
          <span class="lw-label">{{ t('monitor.observeHttp') }}</span>
          <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
            <LwMetricCard
              :label="t('monitor.observeHttpTotal')"
              :value="String(view.http?.count ?? 0)"
              :icon="DataLine"
            />
            <LwMetricCard
              :label="t('monitor.observeError')"
              :value="String(view.http?.errorCount ?? 0)"
              :hint-tone="(view.http?.errorCount ?? 0) > 0 ? 'error' : 'success'"
              tone="secondary"
              :icon="WarningFilled"
            />
            <LwMetricCard
              :label="t('monitor.observeAvg')"
              :value="`${view.http?.avgMs ?? 0}ms`"
              tone="ink"
              :icon="Timer"
            />
            <LwMetricCard
              :label="t('monitor.observeMax')"
              :value="`${view.http?.maxMs ?? 0}ms`"
              :icon="Stopwatch"
            />
          </div>

          <div v-if="view.http?.top?.length" class="lw-table mt-1">
            <el-table :data="view.http.top" size="small">
              <el-table-column prop="key" :label="t('monitor.colRoute')" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="font-mono text-xs">{{ row.key }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="count" :label="t('monitor.colCount')" width="80" align="right" />
              <el-table-column :label="t('monitor.colAvg')" width="90" align="right">
                <template #default="{ row }">{{ row.avgMs }}ms</template>
              </el-table-column>
              <el-table-column :label="t('monitor.colMax')" width="90" align="right">
                <template #default="{ row }">{{ row.maxMs }}ms</template>
              </el-table-column>
              <el-table-column :label="t('monitor.colLast')" width="150" align="right">
                <template #default="{ row }">
                  <span class="font-mono text-xs">{{ formatTime(row.lastTs) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="sample" :label="t('monitor.colQuery')" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="font-mono text-xs">{{ row.sample || '-' }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <p class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">{{ t('monitor.observeLogTip') }}</p>
      </template>

      <LwEmpty v-else-if="isEmpty" :description="t('monitor.observeEmpty')" />
    </LwCard>
  </div>
</template>
