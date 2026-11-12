<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AgentView } from '../api/types'
import { t, tr } from '../i18n'
import { stripOf, tileState } from '../scene/remote'
import { remoteTiles } from '../state/remote'
import { drawTile, TILE, TILE_FPS, tileLook } from '../ui/webcam'

/**
 * "Remote today": a small video-call strip with one tile per agent drawn as working from home, who has work on a
 * mission in progress. Decor: the draw is the display choice of state/remote.ts, no event and no server fact. The
 * tile shows the same recorded state as the office figure (typing only while a tool executes, a sheet in hand
 * during a review, otherwise idle); a click selects the agent as a click in the office does.
 */
const emit = defineEmits<{ agent: [id: string] }>()

const KEY = 'floor42.remote.collapsed'
const stored = (): boolean => {
  try {
    return localStorage.getItem(KEY) === 'yes'
  } catch {
    return false
  }
}
const collapsed = ref(stored())
watch(collapsed, (c) => {
  try {
    localStorage.setItem(KEY, c ? 'yes' : 'no')
  } catch {
    // Not remembered: the strip just opens expanded next time.
  }
})

const strip = computed(() => stripOf<AgentView>(remoteTiles.value))
const canvases = new Map<string, HTMLCanvasElement>()
const looks = new Map<string, ReturnType<typeof tileLook>>()
const calm = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
let frame = 0
let timer: number | undefined

function bind(id: string, el: unknown) {
  if (el instanceof HTMLCanvasElement) canvases.set(id, el)
  else canvases.delete(id)
}

/** Draws every tile once; the colours of an agent are made the first time. */
function paint() {
  for (const a of strip.value.shown) {
    const ctx = canvases.get(a.id)?.getContext('2d')
    if (!ctx) continue
    if (!looks.has(a.id)) looks.set(a.id, tileLook(a.id))
    drawTile(ctx, looks.get(a.id)!, tileState(a.status), calm ? 0 : frame)
  }
}

onMounted(() => {
  paint()
  // Still under reduced motion; paused while the page is hidden.
  if (!calm) {
    timer = window.setInterval(() => {
      if (document.hidden || collapsed.value) return
      frame++
      paint()
    }, 1000 / TILE_FPS)
  }
})
onBeforeUnmount(() => window.clearInterval(timer))
watch(
  () => [collapsed.value, strip.value.shown.map((a) => `${a.id}:${a.status}`).join(',')],
  async () => {
    await nextTick()
    paint()
  },
)
</script>

<template>
  <div class="panel remote-strip" :class="{ collapsed }" :title="t('remote.tip')">
    <div class="title">
      {{ t('remote.title') }}
      <span v-if="strip.more" class="count">{{ t('remote.more', { count: strip.more }) }}</span>
      <button class="fold" :aria-expanded="!collapsed" :title="t(collapsed ? 'remote.expand' : 'remote.fold')" :aria-label="t(collapsed ? 'remote.expand' : 'remote.fold')" @click="collapsed = !collapsed">
        {{ collapsed ? '▸' : '▾' }}
      </button>
    </div>
    <div v-if="!collapsed" class="tiles">
      <button v-for="a in strip.shown" :key="a.id" class="tile" :data-agent="a.id" :data-state="tileState(a.status)" :title="t('remote.tip')" @click="emit('agent', a.id)">
        <canvas :ref="(el) => bind(a.id, el)" :width="TILE.width" :height="TILE.height"></canvas>
        <span class="tag">
          <b>{{ a.name }}</b>
          <i>{{ t('remote.tile', { status: tr(`status.${a.status}`, a.status.toLowerCase()) }) }}</i>
        </span>
      </button>
    </div>
  </div>
</template>
