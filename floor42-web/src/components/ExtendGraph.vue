<script setup lang="ts">
import { computed, ref } from 'vue'
import type { MissionView } from '../api/types'
import { t } from '../i18n'
import { extendGraph } from '../state/office'

/**
 * "Extend the graph" (ADR-0062): one read-only planner session reads the graph as it stands and proposes new nodes. It
 * is paid, so the screen says so before the click; the nodes arrive as proposals to approve, nothing done is reopened.
 */
const props = defineProps<{ mission: MissionView }>()

const goal = ref('')
const busy = ref(false)

/** The extension's own planning ticket is open: its planner is at work, a second is not started. */
const running = computed(() =>
  props.mission.tickets.some(
    (x) => x.kind === 'PLANNING' && x.title.startsWith('Extend the graph') && x.status !== 'DONE' && x.status !== 'CANCELLED',
  ),
)
const offered = computed(() => props.mission.origin === 'REAL' && props.mission.status === 'IN_PROGRESS')

async function go() {
  busy.value = true
  try {
    if (await extendGraph(props.mission.id, goal.value.trim())) goal.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form v-if="offered" class="extend-graph" @submit.prevent="go">
    <div class="sub">{{ t('graph.extend.title') }}</div>
    <div class="hint">{{ t('graph.extend.hint') }}</div>
    <input v-model="goal" maxlength="1000" :placeholder="t('graph.extend.goal')" :aria-label="t('graph.extend.goal')" />
    <button class="action" type="submit" :disabled="busy || running">{{ t('graph.extend.go') }}</button>
    <div v-if="running" class="hint">{{ t('graph.extend.asked') }}</div>
  </form>
</template>

<style scoped>
.extend-graph {
  margin: 6px 0;
}
.extend-graph input {
  width: 100%;
  box-sizing: border-box;
  margin: 4px 0;
}
</style>
