<script setup lang="ts">
import { Graph } from '@antv/x6'
import { Delete, Plus, VideoPlay } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import { flowApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { FlowNodeResult, FlowRunVO, FlowVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 工作流画布：X6 渲染顺序链，节点配置在右侧编辑，执行结果按节点回放 */
const { t } = useI18n()
const { hasPerm } = usePermission()

interface FlowNodeConfig {
  prompt?: string
  script?: string
  toolName?: string
  params?: string
  keyword?: string
  kbId?: string
  topK?: number
  template?: string
  outputVar?: string
  timeoutSeconds?: number
  inputs?: string
}

interface FlowNode {
  id: string
  type: string
  label: string
  config: FlowNodeConfig
  x: number
  y: number
}

interface FlowEdge {
  source: string
  target: string
}

const NODE_TYPES = ['llm', 'knowledge', 'python', 'tool']

const flows = ref<FlowVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const listLoading = ref(false)
const error = ref('')

const current = ref<FlowVO | null>(null)
const flowName = ref('')
const description = ref('')
const nodes = ref<FlowNode[]>([])
const edges = ref<FlowEdge[]>([])
const selectedId = ref('')
const saving = ref(false)
const running = ref(false)
const runResult = ref<FlowRunVO | null>(null)
const inputsText = ref('{}')

const canvasEl = ref<HTMLDivElement | null>(null)
const graph = shallowRef<Graph | null>(null)

const selectedNode = computed(() => nodes.value.find((item) => item.id === selectedId.value) ?? null)
const canEdit = computed(() => hasPerm('wb:flow:update') && current.value !== null)

/** 画布渲染：节点位置随拖拽更新，连线按节点顺序串联 */
function renderGraph(): void {
  if (!graph.value) return
  graph.value.clearCells()
  for (const node of nodes.value) {
    graph.value.addNode({
      id: node.id,
      x: node.x,
      y: node.y,
      width: 168,
      height: 56,
      label: node.label || t(`flow.node_${node.type}`),
      attrs: {
        body: { stroke: '#CBC9E2', strokeWidth: 1, fill: '#F2F0F7', rx: 8, ry: 8 },
        label: { fill: '#6A51A3', fontSize: 13, fontWeight: 600 },
      },
    })
  }
  for (const edge of edges.value) {
    graph.value.addEdge({
      source: edge.source,
      target: edge.target,
      attrs: { line: { stroke: '#9E9AC8', strokeWidth: 2, targetMarker: 'classic' } },
    })
  }
  highlightSelected()
}

/** 选中态：主色描边加粗，未选中回归辅助色 */
function highlightSelected(): void {
  for (const node of graph.value?.getNodes() ?? []) {
    const active = node.id === selectedId.value
    node.attr('body/stroke', active ? '#6A51A3' : '#CBC9E2')
    node.attr('body/strokeWidth', active ? 2 : 1)
  }
}

function addNode(type: string): void {
  if (!current.value) return
  const id = `n${Date.now().toString(36)}`
  const previous = nodes.value[nodes.value.length - 1]
  const node: FlowNode = {
    id,
    type,
    label: t(`flow.node_${type}`),
    config: {},
    x: 80 + (nodes.value.length % 4) * 200,
    y: 60 + Math.floor(nodes.value.length / 4) * 100,
  }
  nodes.value.push(node)
  if (previous && previous.type !== 'end') {
    edges.value.push({ source: previous.id, target: id })
  }
  selectedId.value = id
  renderGraph()
}

function removeNode(id: string): void {
  const index = nodes.value.findIndex((item) => item.id === id)
  if (index < 0) return
  const incoming = edges.value.filter((edge) => edge.target === id)
  const outgoing = edges.value.filter((edge) => edge.source === id)
  edges.value = edges.value.filter((edge) => edge.source !== id && edge.target !== id)
  // 删除后把前驱直接接到后继，保持链条不断
  for (const edge of incoming) {
    for (const next of outgoing) {
      edges.value.push({ source: edge.source, target: next.target })
    }
  }
  nodes.value.splice(index, 1)
  if (selectedId.value === id) {
    selectedId.value = nodes.value[0]?.id ?? ''
  }
  renderGraph()
}

/** 组装后端画布结构：start 与 end 由前端补齐，业务节点按列表顺序串联 */
function buildGraphJson(): string {
  const startNode = { id: 'start', type: 'start', label: t('flow.node_start'), config: { inputs: parseInputs() } }
  const endNode = { id: 'end', type: 'end', label: t('flow.node_end'), config: { template: '{{output}}' } }
  const business = nodes.value.map((node) => ({
    id: node.id,
    type: node.type,
    label: node.label,
    config: node.config,
    x: node.x,
    y: node.y,
  }))
  const list = [startNode, ...business, endNode]
  const links: FlowEdge[] = []
  for (let i = 0; i + 1 < list.length; i++) {
    links.push({ source: list[i].id, target: list[i + 1].id })
  }
  return JSON.stringify({ nodes: list, edges: links })
}

/** 初始变量：JSON 文本解析失败按空对象处理 */
function parseInputs(): Record<string, string> {
  try {
    return JSON.parse(inputsText.value || '{}') as Record<string, string>
  } catch {
    return {}
  }
}

async function load(): Promise<void> {
  listLoading.value = true
  error.value = ''
  try {
    const result = await flowApi.page({ keyword: keyword.value, page: page.value, size: size.value })
    flows.value = result.list
    total.value = result.total
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    listLoading.value = false
  }
}

async function createFlow(): Promise<void> {
  const flowId = await flowApi.create({ flowName: t('flow.newFlow'), graphJson: JSON.stringify({ nodes: [], edges: [] }) })
  ElMessage.success(t('common.success'))
  await load()
  await open({ flowId, flowName: t('flow.newFlow') } as FlowVO)
}

async function open(flow: FlowVO): Promise<void> {
  const detail = await flowApi.detail(flow.flowId)
  current.value = detail
  flowName.value = detail.flowName
  description.value = detail.description ?? ''
  const parsed = JSON.parse(detail.graphJson ?? '{"nodes":[],"edges":[]}') as {
    nodes?: FlowNode[]
    edges?: FlowEdge[]
  }
  nodes.value = (parsed.nodes ?? []).filter((node) => node.type !== 'start' && node.type !== 'end')
  edges.value = parsed.edges ?? []
  selectedId.value = nodes.value[0]?.id ?? ''
  runResult.value = null
  renderGraph()
}

async function save(): Promise<void> {
  if (!current.value) return
  saving.value = true
  try {
    await flowApi.update({
      flowId: current.value.flowId,
      flowName: flowName.value,
      description: description.value,
      graphJson: buildGraphJson(),
    })
    ElMessage.success(t('common.saveSuccess'))
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    saving.value = false
  }
}

async function run(): Promise<void> {
  if (!current.value) return
  running.value = true
  runResult.value = null
  try {
    let inputs: Record<string, string> = {}
    try {
      inputs = JSON.parse(inputsText.value || '{}') as Record<string, string>
    } catch {
      inputs = {}
    }
    runResult.value = await flowApi.run({ flowId: current.value.flowId, inputs })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    running.value = false
  }
}

async function remove(): Promise<void> {
  if (!current.value) return
  await ElMessageBox.confirm(t('flow.confirmDelete', { name: flowName.value }), t('common.delete'), {
    type: 'warning',
  })
  await flowApi.remove(current.value.flowId)
  ElMessage.success(t('common.success'))
  current.value = null
  nodes.value = []
  edges.value = []
  renderGraph()
  await load()
}

function nodeStatusType(status?: string): string {
  return status === 'success' ? 'success' : 'danger'
}

function nodeRows(): FlowNodeResult[] {
  return runResult.value?.nodes ?? []
}

function changePage(next: number): void {
  page.value = next
  void load()
}

onMounted(() => {
  if (canvasEl.value) {
    graph.value = new Graph({
      container: canvasEl.value,
      background: { color: '#FFFFFF' },
      grid: { visible: true, type: 'doubleMesh', args: [{ color: '#E2E8F0' }, { color: '#F2F0F7' }] },
      connecting: { allowBlank: false, allowLoop: false },
    })
    graph.value.on('node:click', ({ node }) => {
      selectedId.value = node.id
      highlightSelected()
    })
    graph.value.on('node:moved', ({ node }) => {
      const target = nodes.value.find((item) => item.id === node.id)
      if (target) {
        const position = node.position()
        target.x = position.x
        target.y = position.y
      }
    })
  }
  void load()
})

onBeforeUnmount(() => {
  graph.value?.dispose()
  graph.value = null
})
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('flow.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('flow.desc') }}</p>
      </div>
      <LwButton v-if="hasPerm('wb:flow:create')" @click="createFlow">
        <template #icon><el-icon><Plus /></el-icon></template>
        {{ t('flow.newFlow') }}
      </LwButton>
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-[240px_minmax(0,1fr)_320px] gap-4">
      <!-- 工作流列表 -->
      <LwCard class="p-4 flex flex-col gap-3 max-h-[calc(100vh-220px)] overflow-auto">
        <el-input v-model="keyword" :placeholder="t('common.keyword')" clearable @keyup.enter="load" @clear="load" />
        <LwPageState :loading="listLoading" :error="error" :skeleton-rows="4" @retry="load">
          <LwEmpty v-if="!flows.length" :description="t('common.empty')" />
          <button
            v-for="flow in flows"
            :key="flow.flowId"
            class="w-full text-left px-3 py-2.5 rounded-control border transition-all duration-200"
            :class="current?.flowId === flow.flowId
              ? 'border-lw-secondary bg-lw-secondary-selected'
              : 'border-transparent hover:bg-lw-primary-tint'"
            @click="open(flow)"
          >
            <div class="text-sm font-bold text-lw-ink truncate">{{ flow.flowName }}</div>
            <div class="text-xs text-lw-muted mt-0.5">
              {{ flow.nodeCount ?? 0 }} {{ t('flow.nodeCount') }} · {{ formatTime(flow.updatedAt) }}
            </div>
          </button>
          <div v-if="total > size" class="flex justify-center mt-2">
            <el-pagination
              layout="prev, pager, next"
              small
              :total="total"
              :page-size="size"
              :current-page="page"
              @current-change="changePage"
            />
          </div>
        </LwPageState>
      </LwCard>

      <!-- 画布 -->
      <LwCard class="p-5 flex flex-col gap-4">
        <template v-if="current">
          <div class="flex flex-wrap items-center gap-3">
            <input v-model="flowName" class="lw-input flex-1 min-w-48 font-bold" />
            <el-select
              v-if="canEdit"
              class="w-36"
              :placeholder="t('flow.addNode')"
              @change="(value: string) => addNode(value)"
            >
              <el-option v-for="type in NODE_TYPES" :key="type" :label="t(`flow.node_${type}`)" :value="type" />
            </el-select>
            <LwButton v-if="canEdit" size="sm" :loading="saving" @click="save">
              {{ saving ? t('flow.saving') : t('flow.save') }}
            </LwButton>
            <LwButton v-if="hasPerm('wb:flow:run')" size="sm" variant="secondary" :loading="running" @click="run">
              <template #icon><el-icon><VideoPlay /></el-icon></template>
              {{ running ? t('flow.running') : t('flow.run') }}
            </LwButton>
          </div>

          <div ref="canvasEl" class="w-full h-96 rounded-card border border-lw-border overflow-hidden" />

          <LwEmpty v-if="!nodes.length" :description="t('flow.emptyCanvas')" />

          <div class="flex flex-col gap-2">
            <span class="lw-label">{{ t('flow.runResult') }}</span>
            <el-input
              v-model="inputsText"
              type="textarea"
              :rows="2"
              :placeholder="t('flow.inputsPlaceholder')"
            />
            <span class="lw-subtitle">{{ t('flow.inputsTip') }}</span>
            <div v-if="runResult" class="flex flex-col gap-2">
              <div class="flex items-center gap-2 text-xs text-lw-muted">
                <span>{{ t('flow.status') }}：{{ runResult.status }}</span>
                <span>{{ t('flow.duration') }}：{{ runResult.durationMs }} ms</span>
              </div>
              <div
                v-for="node in nodeRows()"
                :key="node.nodeId"
                class="rounded-control border border-lw-border p-3 flex flex-col gap-1"
              >
                <div class="flex items-center justify-between">
                  <span class="text-sm font-bold text-lw-ink">{{ node.nodeLabel || node.nodeId }}</span>
                  <el-tag size="small" :type="nodeStatusType(node.status)">
                    {{ node.status === 'success' ? t('flow.status_success') : t('flow.status_failed') }}
                  </el-tag>
                </div>
                <div v-if="node.errorMessage" class="text-xs text-lw-errorInk">{{ node.errorMessage }}</div>
                <pre v-else class="text-xs text-lw-muted whitespace-pre-wrap">{{ node.output }}</pre>
              </div>
              <div v-if="runResult.output" class="rounded-control bg-lw-subtle p-3 text-sm text-lw-ink whitespace-pre-wrap">
                {{ runResult.output }}
              </div>
            </div>
          </div>
        </template>
        <LwEmpty v-else :description="t('flow.emptyCanvas')">
          <template #empty-action>
            <LwButton v-if="hasPerm('wb:flow:create')" @click="createFlow">{{ t('flow.newFlow') }}</LwButton>
          </template>
        </LwEmpty>
      </LwCard>

      <!-- 节点配置 -->
      <LwCard class="p-5 flex flex-col gap-4">
        <div class="lw-label">{{ t('flow.nodeLabel') }}</div>
        <template v-if="selectedNode">
          <input v-model="selectedNode.label" class="lw-input" />
          <template v-if="selectedNode.type === 'llm'">
            <div class="flex flex-col gap-1.5">
              <span class="lw-label">{{ t('flow.prompt') }}</span>
              <el-input
                v-model="selectedNode.config.prompt"
                type="textarea"
                :rows="6"
                :placeholder="t('flow.promptPlaceholder')"
              />
            </div>
          </template>
          <template v-else-if="selectedNode.type === 'python'">
            <div class="flex flex-col gap-1.5">
              <span class="lw-label">{{ t('flow.script') }}</span>
              <el-input
                v-model="selectedNode.config.script"
                type="textarea"
                :rows="6"
                :placeholder="t('flow.scriptPlaceholder')"
              />
            </div>
            <div class="flex flex-col gap-1.5">
              <span class="lw-label">{{ t('flow.timeoutSeconds') }}</span>
              <el-input-number v-model="selectedNode.config.timeoutSeconds" :min="1" :max="600" class="w-full" />
            </div>
          </template>
          <template v-else-if="selectedNode.type === 'knowledge'">
            <div class="flex flex-col gap-1.5">
              <span class="lw-label">{{ t('flow.keyword') }}</span>
              <input v-model="selectedNode.config.keyword" class="lw-input" />
            </div>
            <div class="flex flex-col gap-1.5">
              <span class="lw-label">{{ t('flow.node_knowledge') }}</span>
              <input v-model="selectedNode.config.kbId" class="lw-input" />
            </div>
            <div class="flex flex-col gap-1.5">
              <span class="lw-label">{{ t('flow.topK') }}</span>
              <el-input-number v-model="selectedNode.config.topK" :min="1" :max="10" class="w-full" />
            </div>
          </template>
          <template v-else-if="selectedNode.type === 'tool'">
            <div class="flex flex-col gap-1.5">
              <span class="lw-label">{{ t('flow.toolName') }}</span>
              <input v-model="selectedNode.config.toolName" class="lw-input" placeholder="python_exec" />
            </div>
          </template>
          <div class="flex flex-col gap-1.5">
            <span class="lw-label">{{ t('flow.outputVar') }}</span>
            <input v-model="selectedNode.config.outputVar" class="lw-input" placeholder="output" />
          </div>
          <LwButton v-if="canEdit" variant="danger" size="sm" block @click="removeNode(selectedNode.id)">
            <template #icon><el-icon><Delete /></el-icon></template>
            {{ t('flow.deleteNode') }}
          </LwButton>
        </template>
        <p v-else class="lw-subtitle">{{ t('flow.emptyCanvas') }}</p>

        <div v-if="canEdit && current" class="mt-auto pt-4 border-t border-lw-border flex flex-col gap-2">
          <input v-model="description" class="lw-input" :placeholder="t('flow.description')" />
          <LwButton v-if="hasPerm('wb:flow:delete')" variant="danger" size="sm" block @click="remove">
            {{ t('common.delete') }}
          </LwButton>
        </div>
      </LwCard>
    </div>
  </div>
</template>
