<script setup lang="ts">
import { computed } from 'vue'
import type { MissionView } from '../api/types'
import { t } from '../i18n'
import { layoutGraph, NODE_H, NODE_W } from '../ui/taskGraph'

/**
 * The mission's task graph (ADR-0062): one box per ticket, an arrow from a ticket to each ticket that waits for it.
 * Drawn from the tickets as they are; nothing is stored for it.
 */
const props = defineProps<{ mission: MissionView; selected?: string | null }>()
const emit = defineEmits<{ pick: [id: string] }>()

const graph = computed(() => layoutGraph(props.mission))
const clip = (s: string) => (s.length > 24 ? `${s.slice(0, 23)}…` : s)
</script>

<template>
  <div v-if="graph.nodes.length" class="task-graph" role="group" :aria-label="t('graph.title')">
    <div class="sub">{{ t('graph.title') }} <span class="hint">· {{ t('graph.hint') }}</span></div>
    <div class="task-graph-scroll">
      <svg :width="graph.width" :height="graph.height" :viewBox="`0 0 ${graph.width} ${graph.height}`" role="list">
        <defs>
          <marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 z" class="graph-arrow" />
          </marker>
        </defs>
        <path
          v-for="e in graph.edges"
          :key="`${e.from}-${e.to}`"
          class="graph-edge"
          :d="`M${e.x1},${e.y1} C${e.x1},${e.y1 + 22} ${e.x2},${e.y2 - 22} ${e.x2},${e.y2}`"
          marker-end="url(#graph-arrow)"
        />
        <g
          v-for="n in graph.nodes"
          :key="n.id"
          class="graph-node"
          :class="[`st-${n.status}`, { picked: selected === n.id, waiting: n.waiting }]"
          role="listitem"
          tabindex="0"
          :aria-label="t('graph.node', { id: n.id, title: n.title, status: t(`board.col.${n.status}`) }) + (n.toRecheck ? ', ' + t('graph.toRecheck') : '')"
          @click="emit('pick', n.id)"
          @keydown.enter="emit('pick', n.id)"
        >
          <rect :x="n.x" :y="n.y" :width="NODE_W" :height="NODE_H" rx="4" />
          <text :x="n.x + 8" :y="n.y + 17" class="graph-id">{{ n.id }} · {{ t(`board.col.${n.status}`) }}</text>
          <text :x="n.x + 8" :y="n.y + 33">{{ clip(n.title) }}</text>
          <text :x="n.x + 8" :y="n.y + 47" class="graph-marks">
            <tspan v-if="n.risk" :class="{ bad: n.risk === 'high' }">{{ t(`graph.risk.${n.risk}`) }}</tspan>
            <tspan v-if="n.tier"> · {{ t(`graph.tier.${n.tier}`) }}</tspan>
            <tspan v-if="n.rejected" class="warn"> · {{ t('graph.rejected', { count: n.rejected }) }}</tspan>
          </text>
          <text v-if="n.tests || n.toRecheck" :x="n.x + 8" :y="n.y + 61" class="graph-marks">
            <tspan v-if="n.tests" :class="{ bad: n.tests.failed > 0 }">
              {{ t('graph.tests', { ok: n.tests.passed, total: n.tests.total }) }}
            </tspan>
            <tspan v-if="n.toRecheck" class="warn recheck">{{ n.tests ? ' · ' : '' }}{{ t('graph.toRecheck') }}</tspan>
          </text>
          <title>{{ n.id }} {{ n.title }}{{ n.toRecheck ? ` (${t('graph.toRecheckHint')})` : '' }}</title>
        </g>
      </svg>
    </div>
  </div>
</template>

<style scoped>
.task-graph-scroll {
  max-height: 46vh;
  overflow: auto;
}
svg {
  flex: none;
  font-size: 12px;
}
.graph-edge {
  fill: none;
  stroke: var(--line);
  stroke-width: 1.5;
}
.graph-arrow {
  fill: var(--line);
}
.graph-node {
  cursor: pointer;
}
.graph-node rect {
  fill: var(--panel);
  stroke: var(--line);
  stroke-width: 1.5;
}
.graph-node text {
  fill: var(--text);
}
.graph-node .graph-id,
.graph-node .graph-marks {
  fill: var(--muted);
}
.graph-node .bad {
  fill: var(--red);
}
.graph-node .warn {
  fill: var(--amber);
}
.graph-node:focus-visible rect,
.graph-node.picked rect {
  stroke: var(--cyan);
  stroke-width: 2.5;
}
.st-PROPOSED rect {
  stroke-dasharray: 4 3;
}
.st-IN_PROGRESS rect {
  stroke: var(--cyan);
}
.st-IN_REVIEW rect {
  stroke: var(--amber);
}
.st-DONE rect {
  stroke: var(--green);
}
.st-CANCELLED {
  opacity: 0.5;
}
.waiting rect {
  stroke: var(--amber);
  stroke-dasharray: 2 2;
}
</style>
