<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TicketView } from '../api/types'
import { t, tr } from '../i18n'
import { noteReason } from '../i18n/events'
import { askReason } from '../state/confirm'
import { approveTickets, dropTicket, editTicket, office } from '../state/office'
import { agentName } from '../ui/ticketInfo'
import { duration, ticketTiming } from '../ui/time'
import NodeFacts from './NodeFacts.vue'
import ReviewItems from './ReviewItems.vue'
import TerminalBlock from './TerminalBlock.vue'

/** One ticket of the board opened: its timing, cost, deliverable, reviews and directives, and the director's choices. */
const props = defineProps<{
  ticket: TicketView
  missionId: string
  now: number
  busy: boolean
  /** The board's busy guard: runs the action and says whether it went through. */
  guard: (action: () => Promise<boolean>) => Promise<boolean>
}>()
const emit = defineEmits<{ close: []; agent: [id: string]; workshop: [] }>()

const sessions = computed(() => office.mission?.sessions ?? [])
const editing = ref(false)
const draft = ref({ title: '', description: '', criteria: '' })

function timing(x: TicketView) {
  return ticketTiming(x, sessions.value, props.now)
}

/** Sessions spent on a ticket and their estimated cost, from the runtime's figures. */
function cost(x: TicketView) {
  const own = sessions.value.filter((s) => s.ticket === x.id)
  const usd = own.map((s) => s.costUsdEstimated).filter((c): c is number => c !== null)
  return { sessions: own.length, usd: usd.length ? usd.reduce((a, b) => a + b, 0) : null }
}

async function run(action: () => Promise<boolean>) {
  if (await props.guard(action)) editing.value = false
}

function startEdit(x: TicketView) {
  draft.value = { title: x.title, description: x.description, criteria: x.acceptanceCriteria.join('\n') }
  editing.value = true
}

function save(x: TicketView) {
  const criteria = draft.value.criteria
    .split('\n')
    .map((c) => c.trim())
    .filter((c) => c)
  return run(() => editTicket(x.id, draft.value.title, draft.value.description, criteria))
}

async function drop(x: TicketView) {
  const reason = await askReason({
    message: t('board.dropPrompt', { id: x.id, title: x.title }),
    confirmLabel: t('confirm.drop'),
    danger: true,
    reason: noteReason('board.dropDefault'),
  })
  if (reason !== null) void run(() => dropTicket(x.id, reason))
}
</script>

<template>
  <div class="ticket-detail">
    <div class="detail-head">
      <b>{{ ticket.id }}</b> · {{ t(`board.col.${ticket.status === 'CANCELLED' ? 'CANCELLED' : ticket.status}`) }} ·
      <a href="#" @click.prevent="ticket.assignee && emit('agent', ticket.assignee)">{{ agentName(ticket.assignee) }}</a>
      <span v-if="cost(ticket).sessions" class="hint">
        · {{ t('board.sessions', { count: cost(ticket).sessions }) }}<span v-if="cost(ticket).usd !== null">
          · {{ t('board.cost', { usd: cost(ticket).usd!.toFixed(4) }) }}</span
        >
      </span>
      <button class="close" :title="t('board.closeCard')" @click="emit('close')">×</button>
    </div>
    <template v-if="!editing">
      <div class="detail-title">{{ ticket.title }}</div>
      <div class="timing">
        <template v-if="timing(ticket).elapsedMs !== null">
          <b>{{ duration(timing(ticket).elapsedMs!) }}</b>
          {{ timing(ticket).open ? t('board.timingOpen') : t('board.timingDone') }}
        </template>
        <template v-else>{{ t('board.notStarted') }}</template>
        <span v-for="w in timing(ticket).work" :key="w.agent" class="work">
          · {{ agentName(w.agent) }} <b>{{ duration(w.ms) }}</b><span v-if="w.running" class="ok"> ●</span>
        </span>
        <div class="hint">{{ t('board.timingBasis') }}</div>
      </div>
      <div v-if="ticket.description" class="context">{{ ticket.description }}</div>
      <div v-if="ticket.acceptanceCriteria.length" class="sub">{{ t('board.doneWhen') }}</div>
      <ul class="criteria">
        <li v-for="c in ticket.acceptanceCriteria" :key="c">{{ c }}</li>
      </ul>
      <div v-if="ticket.dependsOn.length" class="context">{{ t('board.after', { tickets: ticket.dependsOn.join(', ') }) }}</div>
      <NodeFacts :ticket="ticket" />
      <template v-if="ticket.deliverable">
        <div class="sub">{{ t('board.deliverable') }}</div>
        <TerminalBlock :text="ticket.deliverable" :name="`${ticket.id}-deliverable`" height="320px" format="markdown" />
      </template>
      <template v-if="ticket.consultations.length">
        <div class="sub">{{ t('board.helpAsked') }}</div>
        <div v-for="(c, i) in ticket.consultations" :key="i" class="context">
          {{ c.kind === 'CONSULT' ? t('board.consulted', { who: c.reviewer, role: c.role ?? '' }) : t('board.researched', { who: c.reviewer }) }}
          {{ c.question }}<br />
          <i>{{ c.answer === null ? t('board.waiting') : `${c.answeredBy ?? 'Floor42'}: ${c.answer}` }}</i>
        </div>
      </template>
      <template v-if="ticket.reviews.length">
        <div class="sub">{{ t('board.reviews') }}</div>
        <div v-for="(r, i) in ticket.reviews" :key="i" class="review">
          <div>
            <span class="verdict" :class="r.verdict.toLowerCase()">{{ tr(`verdict.${r.verdict}`, r.verdict) }}</span>
            {{ t('board.reviewBy', { who: agentName(r.reviewer) }) }}
          </div>
          <ReviewItems :items="r.items" />
          <TerminalBlock :text="r.findings" :name="`${ticket.id}-review-${i + 1}`" height="260px" format="markdown" />
        </div>
      </template>
      <template v-if="ticket.directives.length">
        <div class="sub">{{ t('board.directives') }}</div>
        <div v-for="(d, i) in ticket.directives" :key="i" class="context">{{ d }}</div>
      </template>
      <div class="options">
        <button
          v-if="ticket.status === 'PROPOSED'"
          class="action recommended"
          :disabled="busy"
          @click="run(() => approveTickets(missionId, [ticket.id]))"
        >
          {{ t('board.approve') }}
        </button>
        <button
          v-if="ticket.status === 'PROPOSED' || ticket.status === 'TODO'"
          class="action"
          :disabled="busy"
          @click="startEdit(ticket)"
        >
          {{ t('board.edit') }}
        </button>
        <button
          v-if="!['DONE', 'CANCELLED'].includes(ticket.status)"
          class="action"
          :disabled="busy"
          @click="drop(ticket)"
        >
          {{ t('board.drop') }}
        </button>
        <button v-if="ticket.status !== 'PROPOSED'" class="action" @click="emit('workshop')">{{ t('board.openWorkshop') }}</button>
      </div>
    </template>
    <form v-else class="edit-ticket" @submit.prevent="save(ticket)">
      <input v-model="draft.title" :placeholder="t('board.fieldTitle')" required :aria-label="t('board.fieldTitle')" />
      <textarea v-model="draft.description" rows="3" :placeholder="t('board.fieldWhy')" :aria-label="t('board.fieldWhy')" />
      <textarea v-model="draft.criteria" rows="4" :placeholder="t('board.fieldCriteria')" :aria-label="t('board.fieldCriteria')" />
      <div class="options">
        <button class="action recommended" type="submit" :disabled="busy || !draft.title.trim()">{{ t('common.save') }}</button>
        <button class="action" type="button" :disabled="busy" @click="editing = false">{{ t('common.cancel') }}</button>
      </div>
    </form>
  </div>
</template>
