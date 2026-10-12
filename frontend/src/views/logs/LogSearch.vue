<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { logApi } from '@/api'
import type { LogDocVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 日志中心：Manticore 全文检索，按时间倒序分页 */
const { t } = useI18n()

const loading = ref(false)
const error = ref('')
const docs = ref<LogDocVO[]>([])
const total = ref(0)
const expanded = ref('')
const query = reactive({
  keyword: '',
  level: '',
  from: undefined as number | undefined,
  to: undefined as number | undefined,
  page: 1,
  size: 20,
})

async function search(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await logApi.search({
      keyword: query.keyword || undefined,
      level: query.level || undefined,
      from: query.from,
      to: query.to,
      page: query.page,
      size: query.size,
    })
    docs.value = result.list
    total.value = result.total
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

/** 条件变更后回到第一页 */
function handleSearch(): void {
  query.page = 1
  void search()
}

function levelTone(level?: string): 'error' | 'warning' | 'primary' {
  if (level === 'ERROR') return 'error'
  if (level === 'WARN') return 'warning'
  return 'primary'
}

onMounted(search)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('logs.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('logs.desc') }}</p>
      </div>
    </div>

    <LwCard>
      <div class="flex flex-wrap items-center gap-3">
        <el-input v-model="query.keyword" :placeholder="t('common.keyword')" clearable style="width: 260px" @keyup.enter="handleSearch" />
        <el-select v-model="query.level" :placeholder="t('logs.level')" clearable style="width: 140px">
          <el-option label="ERROR" value="ERROR" />
          <el-option label="WARN" value="WARN" />
          <el-option label="INFO" value="INFO" />
        </el-select>
        <el-date-picker
          v-model="query.from"
          type="datetime"
          value-format="x"
          :placeholder="t('logs.from')"
          style="width: 200px"
        />
        <el-date-picker
          v-model="query.to"
          type="datetime"
          value-format="x"
          :placeholder="t('logs.to')"
          style="width: 200px"
        />
        <LwButton variant="ghost" size="sm" @click="handleSearch">
          <template #icon><el-icon><Search /></el-icon></template>
          {{ t('common.search') }}
        </LwButton>
      </div>
    </LwCard>

    <LwCard>
      <LwPageState :loading="loading" :error="error" :empty="!docs.length" :skeleton-rows="8" @retry="search">
        <div class="flex flex-col gap-2">
          <div
            v-for="doc in docs"
            :key="doc.logId"
            class="rounded-lg border px-4 py-3 cursor-pointer transition-all duration-200 hover:lw-shadow-sm"
            style="border-color: var(--lw-border)"
            @click="expanded = expanded === doc.logId ? '' : doc.logId"
          >
            <div class="flex items-center gap-3">
              <LwTag :tone="levelTone(doc.level)">{{ doc.level }}</LwTag>
              <span class="text-xs font-mono opacity-70 shrink-0">{{ formatTime(doc.ts) }}</span>
              <span class="text-sm truncate flex-1" style="color: var(--lw-ink)">{{ doc.message }}</span>
              <span class="text-xs opacity-50 shrink-0 hidden md:block">{{ doc.logger }}</span>
            </div>
            <div v-if="expanded === doc.logId" class="mt-3 text-xs">
              <div class="opacity-60 mb-1">
                {{ t('logs.traceId') }}: {{ doc.traceId || '-' }} · {{ t('logs.logger') }}: {{ doc.logger }}
              </div>
              <pre
                v-if="doc.exception"
                class="whitespace-pre-wrap break-all max-h-64 overflow-auto p-3 rounded-lg"
                style="background: var(--lw-error-tint); color: var(--lw-error-ink)"
              >{{ doc.exception }}</pre>
            </div>
          </div>
        </div>
        <div class="flex justify-end mt-4">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="search"
            @size-change="handleSearch"
          />
        </div>
      </LwPageState>
    </LwCard>
  </div>
</template>
