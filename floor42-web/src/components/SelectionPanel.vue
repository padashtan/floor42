<script setup lang="ts">
import { computed } from 'vue'
import { t, tr } from '../i18n'
import type { SceneTarget } from '../scene/kit'
import { machineForExecution, type MachineId } from '../scene/mapping'
import { office } from '../state/office'
import { MACHINES } from './catalog'
import ExecutionCard from './ExecutionCard.vue'

const props = defineProps<{ target: SceneTarget | null }>()
const emit = defineEmits<{ desk: [] }>()

const agent = computed(() =>
  props.target?.kind === 'agent' ? office.agents.find((a) => a.id === props.target!.id) : undefined,
)
const agentTickets = computed(() => office.mission?.tickets.filter((x) => x.assignee === agent.value?.id) ?? [])
const agentSession = computed(() =>
  (office.mission?.sessions ?? []).filter((s) => s.agent === agent.value?.id).at(-1),
)
const agentActions = computed(() =>
  (office.mission?.actions.filter((a) => a.agent === agent.value?.id) ?? []).slice(-5).reverse(),
)

const machineId = computed(() =>
  props.target?.kind === 'machine' ? (props.target.id as MachineId) : undefined,
)
const machine = computed(() => (machineId.value ? MACHINES[machineId.value] : undefined))
const machineRuns = computed(() =>
  (office.mission?.actions ?? [])
    .filter((a) => a.ran && machineForExecution(a.category, a.summary) === machineId.value)
    .slice(-5)
    .reverse(),
)
const machinePermissions = computed(() =>
  (office.mission?.permissions ?? []).filter((p) => machine.value?.categories.some((c) => p.includes(c))),
)
</script>

<template>
  <div class="panel selection">
    <template v-if="!target">
      <div class="title">{{ t('sel.nothing') }}</div>
      <div class="hint">{{ t('sel.nothingHint') }}</div>
    </template>

    <template v-else-if="agent">
      <div class="title">{{ agent.name.toUpperCase() }} · {{ agent.role }}</div>
      <table role="presentation">
        <tr><td>{{ t('sel.status') }}</td><td>{{ tr(`status.${agent.status}`, agent.status) }}</td></tr>
        <tr><td>{{ t('agentSettings.model') }}</td><td>{{ agent.provider }} · {{ agent.model }}</td></tr>
        <tr><td>{{ t('agentSettings.effort') }}</td><td>{{ agent.effort ?? t('sel.notAvailable') }}</td></tr>
        <tr v-if="agent.suspended"><td>{{ t('sel.assignments') }}</td><td class="warn">{{ t('sel.suspended') }}</td></tr>
        <tr v-if="agentSession">
          <td>{{ t('sel.session') }}</td>
          <td>
            {{ agentSession.open ? t('sel.running') : agentSession.failed ? t('sel.failed') : t('sel.ended') }} · {{ agentSession.model }}
            <span v-if="agentSession.inputTokensProvided !== null">
              · {{ t('sel.tokens', { in: agentSession.inputTokensProvided, out: agentSession.outputTokensProvided ?? 0 }) }}
            </span>
            <span v-if="agentSession.costUsdEstimated !== null">
              · {{ t('board.cost', { usd: agentSession.costUsdEstimated.toFixed(4) }) }}
            </span>
          </td>
        </tr>
        <tr>
          <td>{{ t('sel.tickets') }}</td>
          <td>{{ agentTickets.map((x) => `${x.id} ${t(`board.col.${x.status}`)}`).join(', ') || t('sel.noTicket') }}</td>
        </tr>
      </table>
      <div v-if="agentActions.length" class="sub">{{ t('sel.recent') }}</div>
      <ExecutionCard v-for="a in agentActions" :key="a.id" :action="a" collapsed />
    </template>

    <template v-else-if="machine && machineId">
      <div class="title">{{ machine.name }} · {{ t('sel.machine') }}</div>
      <div class="hint">{{ t(`machine.${machineId}.stands`) }}. {{ t('machine.lightsUp') }}</div>
      <div class="sub">{{ t('sel.executions') }}</div>
      <div v-if="machineRuns.length === 0" class="hint">{{ t('sel.noneYet') }}</div>
      <ExecutionCard v-for="a in machineRuns" :key="a.id" :action="a" collapsed />
      <template v-if="machinePermissions.length">
        <div class="sub">{{ t('sel.permissions') }}</div>
        <div v-for="p in machinePermissions" :key="p" class="row"><code>{{ p }}</code></div>
      </template>
    </template>

    <template v-else-if="target.kind === 'board'">
      <div class="title">{{ t('sel.board') }}</div>
      <div v-if="!office.mission" class="hint">{{ t('sel.noMission') }}</div>
      <template v-else>
        <div class="hint">{{ office.mission.id }} · {{ office.mission.title }} · {{ tr(`mstatus.${office.mission.status}`, office.mission.status) }}</div>
        <div v-for="x in office.mission.tickets" :key="x.id" class="row">
          <b>{{ x.id }}</b> {{ x.title }} — <span>{{ t(`board.col.${x.status}`) }}</span> · {{ x.assignee ?? t('board.unassigned') }}
          <div v-for="r in x.reviews" :key="r.reviewer + r.verdict" class="basis">
            {{ t('board.reviewBy', { who: r.reviewer }) }}: {{ tr(`verdict.${r.verdict}`, r.verdict) }}
          </div>
        </div>
      </template>
    </template>

    <template v-else-if="target.kind === 'desk'">
      <div class="title">{{ t('desk.title') }}</div>
      <div class="hint">{{ t('sel.questionsWaiting', { count: office.questions.length }) }}</div>
      <button class="action" @click="emit('desk')">{{ t('sel.openDesk') }}</button>
    </template>

    <template v-else-if="target.kind === 'table'">
      <div class="title">{{ t('sel.table') }}</div>
      <div class="hint">{{ t('sel.tableHint') }}</div>
    </template>

    <template v-else>
      <div class="title">{{ t('sel.rack') }}</div>
      <div class="hint">{{ t('sel.rackHint') }}</div>
    </template>
  </div>
</template>
