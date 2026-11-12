<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MissionView, TicketView } from '../api/types'
import { t as tt } from '../i18n'
import { noteReason } from '../i18n/events'
import { askReason } from '../state/confirm'
import { why } from '../ui/why'
import { isGraph, plainDescription, riskOf, tierOf } from '../ui/taskGraph'
import TaskGraph from './TaskGraph.vue'
import { approveTickets, dropTicket, editTicket } from '../state/office'

const props = defineProps<{ mission: MissionView }>()

const graph = computed(() => isGraph(props.mission))
const proposed = computed(() => props.mission.tickets.filter((t) => t.status === 'PROPOSED'))
const selected = ref<string[]>([])
const editing = ref<string | null>(null)
const draft = ref({ title: '', description: '', criteria: '' })
const busy = ref(false)
// Graph mode (ADR-0062): the director's risk for a node, where it differs from the planner's; final once approved.
const risks = ref<Record<string, 'low' | 'high'>>({})
const riskNow = (t: TicketView): 'low' | 'high' => risks.value[t.id] ?? riskOf(t.description)?.risk ?? 'high'
function flip(t: TicketView) {
  risks.value = { ...risks.value, [t.id]: riskNow(t) === 'high' ? 'low' : 'high' }
}
/** Only the risks the director changed, for the nodes about to be approved. */
function chosen(ids: string[]): Record<string, 'low' | 'high'> {
  const out: Record<string, 'low' | 'high'> = {}
  for (const id of ids) {
    const t = props.mission.tickets.find((x) => x.id === id)
    if (t && risks.value[id] && risks.value[id] !== (riskOf(t.description)?.risk ?? 'high')) out[id] = risks.value[id]
  }
  return out
}

// Every newly proposed ticket starts selected; the director unticks what should wait.
const seen = new Set<string>()
watch(
  proposed,
  (list) => {
    for (const t of list) {
      if (!seen.has(t.id)) {
        seen.add(t.id)
        selected.value.push(t.id)
      }
    }
    selected.value = selected.value.filter((id) => list.some((t) => t.id === id))
  },
  { immediate: true },
)

function titleOf(id: string) {
  return props.mission.tickets.find((t) => t.id === id)?.title ?? id
}

function startEdit(t: TicketView) {
  editing.value = t.id
  draft.value = { title: t.title, description: t.description, criteria: t.acceptanceCriteria.join('\n') }
}

async function run(action: () => Promise<boolean>) {
  busy.value = true
  try {
    if (await action()) editing.value = null
  } finally {
    busy.value = false
  }
}

function save(id: string) {
  const criteria = draft.value.criteria.split('\n').map((c) => c.trim()).filter((c) => c)
  return run(() => editTicket(id, draft.value.title, draft.value.description, criteria))
}

async function drop(t: TicketView) {
  const reason = await askReason({
    message: tt('board.dropPrompt', { id: t.id, title: t.title }),
    confirmLabel: tt('confirm.drop'),
    danger: true,
    reason: noteReason('board.dropDefault'),
  })
  if (reason !== null) run(() => dropTicket(t.id, reason))
}
</script>

<template>
  <div v-if="proposed.length" class="proposed">
    <div class="sub">
      {{ tt('proposed.title') }} <span class="hint">· {{ tt('proposed.hint') }}</span>
    </div>
    <TaskGraph v-if="graph" :mission="mission" />
    <div v-for="t in proposed" :key="t.id" class="proposal">
      <template v-if="editing !== t.id">
        <label class="head">
          <input v-model="selected" type="checkbox" :value="t.id" :disabled="busy" />
          <b>{{ t.id }}</b> {{ t.title }}
        </label>
        <div v-if="plainDescription(t.description)" class="context">{{ plainDescription(t.description) }}</div>
        <div v-if="graph" class="context proposal-marks">
          <span v-if="riskOf(t.description)" :class="{ bad: riskNow(t) === 'high' }" :title="riskOf(t.description)?.why">
            {{ tt(`graph.risk.${riskNow(t)}`) }}<template v-if="riskOf(t.description)?.why && !risks[t.id]"> : {{ riskOf(t.description)!.why }}</template>
          </span>
          <span v-if="tierOf(t.description)"> · {{ tt(`graph.tier.${tierOf(t.description)!}`) }}</span>
          <button
            class="action risk-flip"
            type="button"
            :disabled="busy"
            :title="tt('graph.riskChoiceHint')"
            @click="flip(t)"
          >
            {{ tt('graph.riskChoice', { risk: tt(`graph.risk.${riskNow(t)}`) }) }}
          </button>
        </div>
        <div v-if="t.acceptanceCriteria.length" class="context">
          {{ tt('board.doneWhen') }} :
          <ul class="criteria">
            <li v-for="c in t.acceptanceCriteria" :key="c">{{ c }}</li>
          </ul>
        </div>
        <div v-if="t.dependsOn.length" class="context">
          {{ tt('board.after', { tickets: t.dependsOn.map((d) => `${d} ${titleOf(d)}`).join(', ') }) }}
        </div>
        <div class="options">
          <button class="action" :disabled="busy" @click="startEdit(t)">{{ tt('board.edit') }}</button>
          <button class="action" :disabled="busy" @click="drop(t)">{{ tt('board.drop') }}</button>
        </div>
      </template>
      <form v-else class="edit-ticket" @submit.prevent="save(t.id)">
        <input v-model="draft.title" :placeholder="tt('board.fieldTitle')" required :aria-label="tt('board.fieldTitle')" />
        <textarea v-model="draft.description" rows="2" :placeholder="tt('board.fieldWhy')" :aria-label="tt('board.fieldWhy')" />
        <textarea v-model="draft.criteria" rows="2" :placeholder="tt('board.fieldCriteria')" :aria-label="tt('board.fieldCriteria')" />
        <div class="options">
          <button class="action recommended" type="submit" :disabled="busy || !draft.title.trim()">{{ tt('common.save') }}</button>
          <button class="action" type="button" :disabled="busy" @click="editing = null">{{ tt('common.cancel') }}</button>
        </div>
      </form>
    </div>
    <button
      class="action recommended"
      :disabled="busy || selected.length === 0"
      :title="why([selected.length === 0, tt('why.noSelection')])"
      @click="
        run(() => {
          const ids = proposed.filter((t) => selected.includes(t.id)).map((t) => t.id)
          return approveTickets(mission.id, ids, chosen(ids))
        })
      "
    >
      {{ tt('proposed.approve', { count: selected.length }) }}
    </button>
  </div>
</template>
