<script setup lang="ts">
import { computed } from 'vue'
import type { GraphHead, GraphView } from '../api/types'
import { t, dateLocale } from '../i18n'
import { cssColor, uniformOf } from '../scene/identity'
import { office } from '../state/office'
import { layout } from './gitGraph'

/** The git graph of the mission (W6): one lane per branch, commits as dots, merges as lines joining lanes. */
const props = defineProps<{ graph: GraphView }>()

const ROW = 24
const LANE = 16
const PAD = 10

/** The director's branch keeps one colour; an agent's lane takes the agent's uniform; older lines are neutral. */
const DIRECTOR = '#f2a93b'
const OTHERS = ['#8a8f98', '#6d7f99', '#99806d', '#7d996d']

const graph = computed(() =>
  layout(
    props.graph.commits,
    props.graph.heads.map((h) => h.commit),
  ),
)
const rowOf = computed(() => new Map(props.graph.commits.map((c, i) => [c.hash, i])))

const laneColors = computed(() => {
  const colors = new Map<number, string>()
  for (const h of props.graph.heads) {
    const row = rowOf.value.get(h.commit)
    if (row === undefined) continue
    const lane = graph.value.lanes[row]
    if (!colors.has(lane)) colors.set(lane, h.agent ? cssColor(uniformOf(h.agent)) : DIRECTOR)
  }
  return colors
})
const color = (lane: number) => laneColors.value.get(lane) ?? OTHERS[lane % OTHERS.length]

const x = (lane: number) => PAD + lane * LANE
const y = (row: number) => ROW / 2 + row * ROW
const width = computed(() => PAD * 2 + Math.max(0, graph.value.width - 1) * LANE)
const height = computed(() => Math.max(1, props.graph.commits.length) * ROW)

/** A straight line along a lane, a soft curve where a line forks out of a commit or joins one. */
function path(s: { fromRow: number; fromLane: number; toRow: number; toLane: number }): string {
  const x1 = x(s.fromLane)
  const y1 = y(s.fromRow)
  const x2 = x(s.toLane)
  const y2 = Math.min(y(s.toRow), height.value)
  if (x1 === x2) return `M ${x1} ${y1} L ${x2} ${y2}`
  const mid = (y1 + y2) / 2
  return `M ${x1} ${y1} C ${x1} ${mid}, ${x2} ${mid}, ${x2} ${y2}`
}

const name = (id: string) => office.agents.find((a) => a.id === id)?.name ?? id
const labels = computed(() => {
  const at = new Map<string, GraphHead[]>()
  for (const h of props.graph.heads) at.set(h.commit, [...(at.get(h.commit) ?? []), h])
  return at
})
const short = (hash: string) => hash.slice(0, 8)
const when = (at: string) => new Date(at).toLocaleString(dateLocale(), { dateStyle: 'short', timeStyle: 'short' })
</script>

<template>
  <div class="git-graph">
    <div v-if="props.graph.commits.length === 0" class="hint">{{ t('archive.noGraph') }}</div>
    <div v-else class="scroll">
      <div class="grid" :style="{ height: `${height}px` }">
        <svg :width="width" :height="height" :viewBox="`0 0 ${width} ${height}`" role="img" :aria-label="t('archive.graph')">
          <path
            v-for="(s, i) in graph.segments"
            :key="i"
            :d="path(s)"
            :stroke="color(s.color)"
            stroke-width="2"
            fill="none"
          />
          <circle
            v-for="(c, i) in props.graph.commits"
            :key="c.hash"
            :cx="x(graph.lanes[i])"
            :cy="y(i)"
            :r="c.parents.length > 1 ? 3.5 : 4.5"
            :style="{ fill: c.parents.length > 1 ? 'var(--panel)' : color(graph.lanes[i]) }"
            :stroke="color(graph.lanes[i])"
            stroke-width="2"
          >
            <title>{{ c.hash }}</title>
          </circle>
        </svg>
        <div class="rows">
          <div v-for="c in props.graph.commits" :key="c.hash" class="row" :style="{ height: `${ROW}px` }">
            <span
              v-for="h in labels.get(c.hash) ?? []"
              :key="(h.agent ?? '') + h.branch"
              class="label"
              :style="{ borderColor: color(graph.lanes[rowOf.get(c.hash)!]) }"
            >
              {{ h.branch || t('project.detached') }}
              <span class="who">· {{ h.agent ? name(h.agent) : t('archive.director') }}</span>
              <span v-if="h.uncommitted > 0" class="who">· {{ t('archive.uncommitted', { count: h.uncommitted }) }}</span>
            </span>
            <code>{{ short(c.hash) }}</code>
            <span class="subject">{{ c.subject }}</span>
            <span class="hint">· {{ c.author }} · {{ when(c.at) }}</span>
          </div>
        </div>
      </div>
    </div>
    <div v-if="props.graph.truncated" class="hint">{{ t('archive.truncated', { count: props.graph.commits.length }) }}</div>
  </div>
</template>

<style scoped>
.scroll {
  max-height: 50vh;
  overflow: auto;
}
.grid {
  display: flex;
  align-items: flex-start;
  min-width: max-content;
}
svg {
  flex: none;
}
.rows {
  font-size: 0.85em;
}
.row {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.label {
  border: 1.5px solid;
  border-radius: 9px;
  padding: 0 6px;
  font-size: max(12px, 0.9em);
}
.who {
  opacity: 0.75;
}
</style>
