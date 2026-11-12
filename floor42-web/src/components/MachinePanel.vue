<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../i18n'
import { machineForExecution, type MachineId } from '../scene/mapping'
import { office } from '../state/office'
import { MACHINES } from './catalog'
import ExecutionCard from './ExecutionCard.vue'

/** Everything a machine really ran on the mission on the board, newest first, with its full recorded output. */
const props = defineProps<{ machine: MachineId }>()
const emit = defineEmits<{ close: [] }>()

const filter = ref('')
const runs = computed(() => {
  const f = filter.value.trim().toLowerCase()
  return (office.mission?.actions ?? [])
    .filter((a) => a.ran && machineForExecution(a.category, a.summary) === props.machine)
    .filter((a) => !f || `${a.agent} ${a.tool} ${a.summary} ${a.ticket ?? ''}`.toLowerCase().includes(f))
    .slice()
    .reverse()
})
const others = computed(() =>
  (Object.keys(MACHINES) as MachineId[])
    .filter((m) => m !== props.machine)
    .map((m) => ({
      id: m,
      count: (office.mission?.actions ?? []).filter((a) => a.ran && machineForExecution(a.category, a.summary) === m)
        .length,
    })),
)
</script>

<template>
  <div class="panel machine-panel framed">
    <div class="title">
      {{ MACHINES[machine].name }} · {{ t('machine.outputs') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div class="hint">
        {{ t(`machine.${machine}.stands`) }}.
        <template v-if="office.mission?.origin === 'SIMULATED'">
          <b class="warn">{{ t('machine.simulated') }}</b>
        </template>
        <template v-else>{{ t('machine.lightsUp') }}</template>
      </div>
      <div v-if="!office.mission" class="empty-state">{{ t('board.noMission') }}</div>
      <template v-else>
        <div class="machine-tools">
          <span class="sub">
            {{ t('machine.count', { count: runs.length, mission: office.mission.id }) }} ·
            <span :class="office.mission.origin === 'REAL' ? 'bad' : 'warn'">{{ t(`log.${office.mission.origin}`) }}</span>
          </span>
          <input v-model="filter" :placeholder="t('machine.filter')" :aria-label="t('machine.filter')" />
        </div>
        <div v-if="runs.length === 0" class="empty-state">
          {{ t('machine.none') }}
          <div class="hint">
            {{ t('machine.elsewhere') }}
            <span v-for="o in others" :key="o.id">{{ MACHINES[o.id].name }} {{ o.count }} · </span>
            {{ t('machine.where') }}
          </div>
        </div>
        <ExecutionCard v-for="(a, i) in runs" :key="a.id" :action="a" :collapsed="i > 2" height="220px" />
      </template>
    </div>
  </div>
</template>
