<script setup lang="ts">
import type { MissionView } from '../api/types'
import { isGraph } from '../ui/taskGraph'
import ExtendGraph from './ExtendGraph.vue'
import TaskGraph from './TaskGraph.vue'

/** The task graph on the board, for a graph-mode mission only (ADR-0062); a click opens the ticket. */
defineProps<{ mission: MissionView; selected?: string | null }>()
const emit = defineEmits<{ pick: [id: string] }>()
</script>

<template>
  <template v-if="isGraph(mission)">
    <TaskGraph :mission="mission" :selected="selected" @pick="emit('pick', $event)" />
    <ExtendGraph :mission="mission" />
  </template>
</template>
