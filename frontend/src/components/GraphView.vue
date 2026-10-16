<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchKgGraph } from '../api/kgApi'

const props = defineProps({ workspace: { type: Object, default: null } })
const emit = defineEmits(['openWikiPage'])

const workspaceId = computed(() => props.workspace?.id || '')

const loading = ref(false)
const error = ref('')
const nodes = ref([])          // {id,name,type,sourceId,degree,x,y,vx,vy,r}
const edges = ref([])          // {source,target,type,weight}
const byId = new Map()         // id -> node 对象（与 nodes 同引用）
const adj = new Map()          // id -> Set(neighbor id)

const selectedId = ref(null)
const hoveredId = ref(null)
const termNeighbors = ref([])  // 选中 TERM 节点后的关联 wiki 页

const svgRef = ref(null)
const gRef = ref(null)
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })
const VIEW_W = 900
const VIEW_H = 620

let drag = null       // { node, moved, startX, startY }
let panning = false
let panStart = null
let rafId = null
let alpha = 0

const SIM = {
  repulsion: 7000,
  attraction: 0.03,
  restLength: 130,
  gravity: 0.02,
  damping: 0.86,
  maxV: 30
}

const nodeColor = (type) => (type === 'WIKI_PAGE' ? '#4f7c63' : '#c08a3e')
const edgeStroke = (type) => (type === 'WIKI_LINK' ? '#7fa88f' : type === 'PARENT_CHILD' ? '#a8b0a5' : '#d8a95c')

async function load() {
  if (!workspaceId.value) {
    nodes.value = []
    edges.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await fetchKgGraph(workspaceId.value)
    buildGraph(data.nodes || [], data.edges || [])
  } catch (e) {
    error.value = e?.message || '图谱加载失败'
    nodes.value = []
    edges.value = []
  } finally {
    loading.value = false
  }
}

function buildGraph(rawNodes, rawEdges) {
  byId.clear()
  adj.clear()
  const built = rawNodes.map((n) => ({
    id: n.id,
    name: n.name,
    type: n.type,
    sourceId: n.sourceId,
    degree: n.degree || 0,
    x: 0, y: 0, vx: 0, vy: 0,
    r: 10 + Math.min(20, Math.sqrt(n.degree || 0) * 4.5)
  }))
  built.forEach((n, i) => {
    const ang = (i / Math.max(1, built.length)) * Math.PI * 2
    n.x = VIEW_W / 2 + Math.cos(ang) * 220 + (Math.random() - 0.5) * 40
    n.y = VIEW_H / 2 + Math.sin(ang) * 220 + (Math.random() - 0.5) * 40
    byId.set(n.id, n)
  })
  const validEdges = rawEdges.filter((e) => byId.has(e.source) && byId.has(e.target))
  validEdges.forEach((e) => {
    if (!adj.has(e.source)) adj.set(e.source, new Set())
    if (!adj.has(e.target)) adj.set(e.target, new Set())
    adj.get(e.source).add(e.target)
    adj.get(e.target).add(e.source)
  })
  nodes.value = built
  edges.value = validEdges
  selectedId.value = null
  hoveredId.value = null
  termNeighbors.value = []
  startSimulation()
}

function startSimulation() {
  if (rafId) cancelAnimationFrame(rafId)
  alpha = 1
  const tick = () => {
    step()
    if (alpha > 0.02) {
      rafId = requestAnimationFrame(tick)
    } else {
      rafId = null
    }
  }
  rafId = requestAnimationFrame(tick)
}

function step() {
  const ns = nodes.value
  const n = ns.length
  if (n === 0) return
  // 斥力（所有节点两两）
  for (let i = 0; i < n; i++) {
    const a = ns[i]
    for (let j = i + 1; j < n; j++) {
      const b = ns[j]
      let dx = a.x - b.x
      let dy = a.y - b.y
      let d2 = dx * dx + dy * dy
      if (d2 < 0.01) { d2 = 0.01; dx = Math.random() - 0.5 }
      const d = Math.sqrt(d2)
      const f = SIM.repulsion / d2
      const fx = (dx / d) * f
      const fy = (dy / d) * f
      a.vx += fx; a.vy += fy
      b.vx -= fx; b.vy -= fy
    }
  }
  // 引力（边上的弹簧）
  for (const e of edges.value) {
    const s = byId.get(e.source)
    const t = byId.get(e.target)
    if (!s || !t) continue
    let dx = t.x - s.x
    let dy = t.y - s.y
    const d = Math.sqrt(dx * dx + dy * dy) || 0.01
    const f = SIM.attraction * (d - SIM.restLength)
    const fx = (dx / d) * f
    const fy = (dy / d) * f
    s.vx += fx; s.vy += fy
    t.vx -= fx; t.vy -= fy
  }
  // 向中心的微弱引力 + 积分
  const cx = VIEW_W / 2
  const cy = VIEW_H / 2
  for (let i = 0; i < n; i++) {
    const a = ns[i]
    a.vx += (cx - a.x) * SIM.gravity
    a.vy += (cy - a.y) * SIM.gravity
    a.vx *= SIM.damping
    a.vy *= SIM.damping
    const sp = Math.sqrt(a.vx * a.vx + a.vy * a.vy)
    if (sp > SIM.maxV) { a.vx = (a.vx / sp) * SIM.maxV; a.vy = (a.vy / sp) * SIM.maxV }
    if (drag && drag.node === a) continue // 拖拽中的节点不自动移动
    a.x += a.vx * alpha
    a.y += a.vy * alpha
  }
  alpha *= 0.98
}

function relayout() { startSimulation() }
function resetView() { zoom.value = 1; pan.value = { x: 0, y: 0 } }

function toLocal(evt) {
  const ctm = gRef.value.getScreenCTM()
  const pt = new DOMPoint(evt.clientX, evt.clientY)
  const local = pt.matrixTransform(ctm.inverse())
  return { x: local.x, y: local.y }
}

function nodeDown(node, evt) {
  evt.stopPropagation()
  const p = toLocal(evt)
  drag = { node, moved: false, startX: p.x, startY: p.y }
}
function onMove(evt) {
  if (drag) {
    const p = toLocal(evt)
    if (Math.abs(p.x - drag.startX) > 3 || Math.abs(p.y - drag.startY) > 3) drag.moved = true
    drag.node.x = p.x
    drag.node.y = p.y
    drag.node.vx = 0
    drag.node.vy = 0
    if (!rafId) startSimulation()
    return
  }
  if (panning && panStart) {
    const dx = evt.clientX - panStart.x
    const dy = evt.clientY - panStart.y
    pan.value = { x: panStart.panX + dx, y: panStart.panY + dy }
  }
}
function onUp() {
  if (drag) {
    if (!drag.moved) handleClick(drag.node)
    drag = null
  }
  panning = false
  panStart = null
}
function bgDown(evt) {
  panning = true
  panStart = { x: evt.clientX, y: evt.clientY, panX: pan.value.x, panY: pan.value.y }
}
function onWheel(evt) {
  evt.preventDefault()
  const factor = evt.deltaY < 0 ? 1.1 : 0.9
  zoom.value = Math.min(3, Math.max(0.25, zoom.value * factor))
}

function handleClick(node) {
  if (node.type === 'WIKI_PAGE') {
    if (node.sourceId) emit('openWikiPage', node.sourceId)
    return
  }
  // TERM：选中并列出关联 wiki 页
  selectedId.value = node.id
  const nb = adj.get(node.id) || new Set()
  const pages = []
  nb.forEach((id) => {
    const n = byId.get(id)
    if (n && n.type === 'WIKI_PAGE') pages.push({ id: n.id, name: n.name, sourceId: n.sourceId })
  })
  termNeighbors.value = pages
}
function openNeighbor(sourceId) {
  if (sourceId) emit('openWikiPage', sourceId)
}

const activeSet = computed(() => {
  const id = hoveredId.value || selectedId.value
  if (!id) return null
  const set = new Set([id])
  ;(adj.get(id) || new Set()).forEach((x) => set.add(x))
  return set
})
const dimNode = (id) => activeSet.value && !activeSet.value.has(id)
const dimEdge = (e) => activeSet.value && !(activeSet.value.has(e.source) && activeSet.value.has(e.target))

const truncate = (s) => (s && s.length > 8 ? s.slice(0, 8) + '…' : s)

watch(workspaceId, load)
onMounted(load)
onBeforeUnmount(() => { if (rafId) cancelAnimationFrame(rafId) })
</script>

<template>
  <div class="graph-view">
    <div class="graph-toolbar">
      <strong>知识图谱</strong>
      <span class="graph-stat">节点 {{ nodes.length }} · 边 {{ edges.length }}</span>
      <span class="graph-hint">拖拽节点 / 滚轮缩放 / 空白处平移 · 点页面节点打开 · 点概念节点看关联页</span>
      <span class="graph-spacer" />
      <button type="button" class="graph-btn" @click="relayout">重新布局</button>
      <button type="button" class="graph-btn" @click="resetView">重置视图</button>
    </div>

    <div v-if="error" class="graph-error">{{ error }}</div>
    <div v-else-if="loading" class="graph-loading">图谱加载中…</div>
    <div v-else-if="nodes.length === 0" class="graph-empty">
      <div class="graph-empty-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
          <circle cx="12" cy="5" r="2.5" /><circle cx="5" cy="17" r="2.5" /><circle cx="19" cy="17" r="2.5" />
          <path d="M12 7.5v3" /><path d="M10.6 12.2 6.8 15" /><path d="M13.4 12.2l3.8 2.8" />
        </svg>
      </div>
      <strong>暂无图谱数据</strong>
      <span>在「知识库」切换到「Wiki 页面」，用 <code>[[页面或概念名]]</code> 串联页面，即可生成知识图谱。</span>
    </div>

    <svg
      v-else
      ref="svgRef"
      class="graph-svg"
      :viewBox="`0 0 ${VIEW_W} ${VIEW_H}`"
      @mousemove="onMove"
      @mouseup="onUp"
      @mouseleave="onUp"
      @wheel="onWheel"
      @mousedown="bgDown"
    >
      <g ref="gRef" :transform="`translate(${pan.x},${pan.y}) scale(${zoom})`">
        <line
          v-for="(e, i) in edges"
          :key="'e' + i"
          :x1="byId.get(e.source)?.x"
          :y1="byId.get(e.source)?.y"
          :x2="byId.get(e.target)?.x"
          :y2="byId.get(e.target)?.y"
          :stroke="edgeStroke(e.type)"
          :stroke-width="e.type === 'MENTIONS' ? 1 : 1.6"
          :stroke-dasharray="e.type === 'MENTIONS' ? '4 3' : ''"
          :opacity="dimEdge(e) ? 0.12 : 0.65"
        />
        <g
          v-for="n in nodes"
          :key="n.id"
          :transform="`translate(${n.x},${n.y})`"
          class="graph-node"
          :opacity="dimNode(n.id) ? 0.25 : 1"
          @mousedown="nodeDown(n, $event)"
          @mouseenter="hoveredId = n.id"
          @mouseleave="hoveredId = null"
        >
          <circle
            :r="n.r"
            :fill="nodeColor(n.type)"
            :stroke="(selectedId === n.id || hoveredId === n.id) ? '#1b1b1b' : '#ffffff'"
            stroke-width="2"
          />
          <text class="graph-label" :y="n.r + 12" text-anchor="middle">{{ truncate(n.name) }}</text>
        </g>
      </g>
    </svg>

    <aside v-if="selectedId && termNeighbors.length" class="graph-panel">
      <h4>「{{ byId.get(selectedId)?.name }}」关联页面</h4>
      <ul>
        <li v-for="p in termNeighbors" :key="p.id">
          <button type="button" class="graph-link" @click="openNeighbor(p.sourceId)">{{ p.name }} →</button>
        </li>
      </ul>
      <button type="button" class="graph-btn" @click="selectedId = null; termNeighbors = []">关闭</button>
    </aside>

    <div class="graph-legend">
      <span><i class="dot" style="background:#4f7c63" /> wiki 页</span>
      <span><i class="dot" style="background:#c08a3e" /> 概念</span>
      <span><i class="line" style="background:#7fa88f" /> 页间链接</span>
      <span><i class="line" style="background:#a8b0a5" /> 层级</span>
      <span><i class="line dash" style="background:#d8a95c" /> 提及</span>
    </div>
  </div>
</template>

<style scoped>
.graph-view {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--site-line, #dce5f2);
  border-radius: 18px;
  background: var(--site-paper, rgba(255, 254, 249, 0.88));
  box-shadow: var(--site-shadow, 0 10px 28px rgba(48, 67, 105, 0.055));
}

.graph-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--site-line, #dce5f2);
  background: rgba(255, 255, 255, 0.62);
  flex-wrap: wrap;
}

.graph-toolbar strong {
  color: var(--site-ink, #263552);
  font-size: 15px;
  letter-spacing: -0.01em;
}

.graph-stat {
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--site-green-soft, #eaf3e7);
  color: var(--site-green, #6f9b78);
  font-size: 12px;
  font-weight: 700;
}

.graph-hint { color: var(--site-muted, #738097); font-size: 12px; }
.graph-spacer { flex: 1; }

.graph-btn {
  border: 1px solid var(--site-line, #dce5f2);
  background: #fff;
  border-radius: 9px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
  color: var(--site-ink-soft, #525f75);
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease;
}

.graph-btn:hover {
  border-color: #80a388;
  color: var(--site-green, #6f9b78);
  background: var(--site-green-soft, #eaf3e7);
}

.graph-svg {
  flex: 1;
  width: 100%;
  background-color: #fffdf8;
  background-image: radial-gradient(circle, rgba(80, 110, 90, 0.09) 1px, transparent 1px);
  background-size: 24px 24px;
  cursor: grab;
  user-select: none;
}

.graph-svg:active { cursor: grabbing; }
.graph-node { cursor: pointer; }

.graph-label {
  font-size: 11px;
  fill: var(--site-ink-soft, #525f75);
  paint-order: stroke;
  stroke: rgba(255, 253, 248, 0.95);
  stroke-width: 3px;
  stroke-linejoin: round;
  pointer-events: none;
}

.graph-error,
.graph-loading,
.graph-empty {
  padding: 44px 24px;
  color: var(--site-muted, #738097);
  text-align: center;
}

.graph-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.graph-empty-icon {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--site-green-soft, #eaf3e7);
  color: var(--site-green, #6f9b78);
}

.graph-empty-icon svg { width: 28px; height: 28px; }
.graph-empty strong { color: var(--site-ink, #263552); font-size: 15px; }
.graph-empty span { font-size: 13px; line-height: 1.6; max-width: 420px; }
.graph-empty code { background: #eef1ea; padding: 2px 6px; border-radius: 5px; }

.graph-panel {
  position: absolute;
  top: 66px;
  right: 14px;
  width: 250px;
  background: #fffdf8;
  border: 1px solid var(--site-line, #dce5f2);
  border-radius: 14px;
  padding: 14px;
  box-shadow: 0 12px 30px rgba(48, 67, 105, 0.12);
}

.graph-panel h4 { margin: 0 0 10px; color: var(--site-ink, #263552); font-size: 14px; }
.graph-panel ul { list-style: none; margin: 0 0 10px; padding: 0; max-height: 320px; overflow: auto; }
.graph-panel li { padding: 3px 0; }

.graph-link {
  background: none;
  border: none;
  color: var(--site-green, #6f9b78);
  cursor: pointer;
  padding: 0;
  font-size: 13px;
}

.graph-link:hover { text-decoration: underline; }

.graph-legend {
  position: absolute;
  left: 14px;
  bottom: 14px;
  display: flex;
  gap: 14px;
  padding: 8px 12px;
  border: 1px solid var(--site-line, #dce5f2);
  border-radius: 12px;
  background: rgba(255, 253, 248, 0.92);
  box-shadow: 0 4px 14px rgba(48, 67, 105, 0.06);
  font-size: 12px;
  color: var(--site-muted, #738097);
}

.graph-legend .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 5px; vertical-align: middle; }
.graph-legend .line { display: inline-block; width: 16px; height: 0; border-top: 2px solid; margin-right: 5px; vertical-align: middle; }
.graph-legend .line.dash { border-top-style: dashed; }
</style>
