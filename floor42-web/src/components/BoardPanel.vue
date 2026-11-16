<script setup lang="ts">
import { nowMs } from '../ui/clock'
import PutOnBoard from './PutOnBoard.vue'
import AbandonMissionButton from './AbandonMissionButton.vue'
import ArchiveMissionButton from './ArchiveMissionButton.vue'
import MissionAbandoned from './MissionAbandoned.vue'
import DeliveryHome from './DeliveryHome.vue'
import DelegationUntil from './DelegationUntil.vue'
import ObjectionsButton from './ObjectionsButton.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api/client'
import type { HeldDeveloper, TicketView } from '../api/types'
import { t, tr } from '../i18n'
import { defaultNote } from '../i18n/events'
import { confirmed } from '../state/confirm'
import { cssColor, uniformOf } from '../scene/identity'
import { approveTickets, completeMission, office, reviewStaffing } from '../state/office'
import { duration, ticketTiming } from '../ui/time'
import { atRisk } from '../ui/missionReport'
import { boardSummary } from '../ui/boardSummary'
import { waitingLabel } from '../ui/waiting'
import TicketDetail from './TicketDetail.vue'
import MissionGraph from './MissionGraph.vue'
import { agentName } from '../ui/ticketInfo'
const emit = defineEmits<{ close: []; agent: [id: string]; workshop: []; deliverables: []; report: [] }>()

/** The mission's tickets as a board, straight from the persisted state: columns are ticket statuses, nothing else. */
const COLUMNS = ['PROPOSED', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'] as const

const mission = computed(() => office.mission)
const tickets = computed(() => mission.value?.tickets ?? [])
const showCancelled = ref(false)
const cancelled = computed(() => tickets.value.filter((x) => x.status === 'CANCELLED'))
const openId = ref<string | null>(null)
const open = computed(() => tickets.value.find((x) => x.id === openId.value) ?? null)
const busy = ref(false)
const closing = ref('')

// Open tickets and running sessions count up to now: the board ticks once a second.
const now = ref(nowMs())
const tick = window.setInterval(() => (now.value = nowMs()), 1000)
onBeforeUnmount(() => window.clearInterval(tick))

function column(key: TicketView['status']) {
  return tickets.value.filter((x) => x.status === key)
}

function lastVerdict(x: TicketView) {
  return x.reviews.length ? x.reviews[x.reviews.length - 1].verdict : null
}

function timing(x: TicketView) {
  return ticketTiming(x, mission.value?.sessions ?? [], now.value)
}

function waiting(x: TicketView) {
  return !!x.waitingFor || (mission.value?.questions ?? []).some((q) => q.status === 'OPEN' && q.ticket === x.id)
}

function blockedBy(x: TicketView) {
  return x.dependsOn.filter((d) => tickets.value.find((y) => y.id === d)?.status !== 'DONE')
}

/** Every ticket is done or dropped: the director reads the deliverables and accepts the delivery from here. */
const deliverable = computed(
  () =>
    mission.value?.status === 'IN_PROGRESS' &&
    tickets.value.length > 0 &&
    tickets.value.every((x) => x.status === 'DONE' || x.status === 'CANCELLED'),
)
const summary = computed(() => boardSummary(tickets.value, mission.value?.sessions ?? [], now.value))

async function run(action: () => Promise<boolean>): Promise<boolean> {
  busy.value = true
  try {
    return await action()
  } finally {
    busy.value = false
  }
}

/** Also copy the documents git ignores (build outputs) into the destination (ADR-0051). */
const withIgnored = ref(false)

function closeMission() {
  const m = mission.value
  if (!m) return
  void run(() => completeMission(m.id, closing.value.trim() || defaultNote('board.closeDefault'), withIgnored.value))
}

// Reinforcing the team: Geoffrey judges from measured facts, then the director answers yes or no. Offered on a real
// mission in progress; put forward when more tickets are ready than there are developers free to take them.
const ready = computed(() =>
  tickets.value.filter(
    (x) =>
      x.status === 'TODO' &&
      x.dependsOn.every((d) => tickets.value.find((y) => y.id === d)?.status === 'DONE'),
  ),
)
const freeDevelopers = computed(
  () => office.agents.filter((a) => a.workRole === 'DEVELOPER' && !a.suspended && a.status === 'AVAILABLE').length,
)
const canReinforce = computed(() => mission.value?.origin === 'REAL' && mission.value.status === 'IN_PROGRESS')
const backlog = computed(() => ready.value.length > Math.max(1, freeDevelopers.value))

async function reinforce() {
  const m = mission.value
  if (!m || !(await confirmed({ message: t('board.reinforceConfirm') }))) return
  void run(() => reviewStaffing(m.id))
}

// A developer whose worktree still holds a finished ticket takes no new one until the director accepts or rejects it
// in the workshop: measured by Floor42 on the worktree, read again whenever a ticket moves.
const held = ref<HeldDeveloper[]>([])
async function loadHeld() {
  const m = mission.value
  if (!m || m.origin !== 'REAL' || m.status !== 'IN_PROGRESS') {
    held.value = []
    return
  }
  try {
    held.value = await api.held(m.id)
  } catch {
    held.value = []
  }
}
watch(() => [mission.value?.id, mission.value?.status, tickets.value.map((x) => x.status).join()], loadHeld, {
  immediate: true,
})
const heldPoll = window.setInterval(loadHeld, 15_000)
onBeforeUnmount(() => window.clearInterval(heldPoll))

// The end-of-mission report says what no commit carries (ADR-0039): read once the delivery block shows.
const riskCount = ref(0)
async function loadRisk() {
  const m = mission.value
  if (!m || m.origin !== 'REAL' || !(deliverable.value || m.status === 'COMPLETED')) {
    riskCount.value = 0
    return
  }
  try {
    riskCount.value = atRisk((await api.report(m.id)).outputs).length
  } catch {
    riskCount.value = 0
  }
}
watch(() => [mission.value?.id, mission.value?.status, deliverable.value], loadRisk, { immediate: true })
</script>

<template>
  <div class="panel board-panel framed" :class="{ split: open }">
    <div class="title">
      {{ t('board.title') }}
      <span v-if="mission" class="hint">· {{ mission.id }} · {{ mission.title }} · {{ tr(`mstatus.${mission.status}`, mission.status) }}</span>
      <button
        v-if="canReinforce"
        class="action reinforce"
        :class="{ urgent: backlog && !mission?.staffingReview }"
        :disabled="busy || !!mission?.staffingReview"
        :title="mission?.staffingReview ? t('board.reinforceRunning') : t('board.reinforceTitle', { ready: ready.length, free: freeDevelopers })"
        @click="reinforce"
      >
        {{ mission?.staffingReview ? t('board.reinforcing') : t('board.reinforce') }}
      </button>
      <button v-if="mission" class="action" :title="t('deliverables.topTitle')" @click="emit('deliverables')">
        {{ t('deliverables.open') }}
      </button>
      <DelegationUntil v-if="mission" :mission="mission.id" />
      <AbandonMissionButton v-if="mission" :mission="mission" />
      <ArchiveMissionButton v-if="mission" :mission="mission" />
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <PutOnBoard v-if="!mission"><div class="hint">{{ t('board.noMission') }}</div></PutOnBoard>
    <div v-else class="board-body panel-scroll">
      <!-- The open ticket reads in a pane of its own beside the board, never below the columns. -->
      <div class="board-main">
      <MissionAbandoned :mission="mission" />
      <MissionGraph :mission="mission" :selected="openId" @pick="openId = $event" />
      <div v-for="h in held" :key="h.agent" class="held">
        <span class="warn">{{ t('board.held', { who: agentName(h.agent), ticket: h.ticket }) }}</span>
        <button class="action" @click="emit('workshop')">{{ t('board.openWorkshop') }}</button>
      </div>
      <div v-if="deliverable" class="delivery">
        <div class="delivery-head">{{ t('board.deliveryTitle') }}</div>
        <div class="delivery-facts">
          {{ t('board.deliveryTickets', { done: summary.done, dropped: summary.dropped }) }}
          <template v-if="summary.elapsed !== null"> · {{ t('board.deliveryElapsed', { time: duration(summary.elapsed) }) }}</template>
          · {{ t('board.deliveryWork', { time: duration(summary.work), sessions: summary.sessions }) }}
          <template v-if="summary.usd !== null"> · {{ t('board.cost', { usd: summary.usd.toFixed(4) }) }}</template>
        </div>
        <div class="hint">{{ t('board.deliveryHow') }}</div>
        <div v-if="riskCount" class="warn">{{ t('board.reportAtRisk', { count: riskCount }) }}</div>
        <div class="row">
          <button class="action recommended" @click="emit('report')">{{ t('board.openReport') }}</button>
        </div>
        <div v-if="mission.delivery" class="home">
          {{ t('home.willCopy', { folder: mission.delivery.destination, mission: mission.id }) }}
          <label class="hint"><input v-model="withIgnored" type="checkbox" /> {{ t('home.withIgnored') }}</label>
        </div>
        <div v-else class="hint">{{ t('home.noneSet') }}</div>
        <div class="row">
          <input v-model="closing" :placeholder="t('board.deliveryNote')" maxlength="500" :aria-label="t('board.deliveryNote')" />
          <button class="action" :disabled="busy" @click="closeMission">{{ t('board.deliveryAccept') }}</button>
          <button class="action" @click="emit('workshop')">{{ t('board.openWorkshop') }}</button>
          <ObjectionsButton :disabled="busy" />
        </div>
      </div>
      <div v-else-if="mission.status === 'COMPLETED'" class="delivery done">
        <div class="delivery-head">{{ t('board.closedTitle') }}</div>
        <div class="hint">{{ mission.outcome }}</div>
        <DeliveryHome v-if="mission.delivery?.copy" :copy="mission.delivery.copy" />
        <div v-if="riskCount" class="warn">{{ t('board.reportAtRisk', { count: riskCount }) }}</div>
        <button class="action recommended" @click="emit('report')">{{ t('board.openReport') }}</button>
      </div>

      <div class="columns">
        <div v-for="c in COLUMNS" :key="c" class="column" :class="c.toLowerCase()">
          <div class="column-head">
            {{ t(`board.col.${c}`) }} <span class="count-chip">{{ column(c).length }}</span>
            <div v-if="t(`board.col.${c}.hint`)" class="hint">{{ t(`board.col.${c}.hint`) }}</div>
          </div>
          <div
            v-for="x in column(c)"
            :key="x.id"
            class="ticket-card"
            :class="{ selected: openId === x.id, waiting: waiting(x), dropped: mission.status === 'CANCELLED' && c !== 'DONE' }"
            @click="openId = openId === x.id ? null : x.id"
          >
            <div class="card-top">
              <b>{{ x.id }}</b>
              <span v-if="lastVerdict(x)" class="verdict" :class="lastVerdict(x)!.toLowerCase()">{{
                tr(`verdict.${lastVerdict(x)}`, lastVerdict(x)!)
              }}</span>
              <span v-if="waiting(x)" class="verdict waits">{{
                waitingLabel(x) ?? t('board.waitsForYou')
              }}</span>
            </div>
            <div class="card-title">{{ x.title }}</div>
            <div class="card-meta">
              <span
                v-if="x.assignee"
                class="avatar"
                :style="{ background: `color-mix(in srgb, ${cssColor(uniformOf(x.assignee))} 55%, #000)` }"
                :title="agentName(x.assignee)"
                >{{ agentName(x.assignee)[0] }}</span
              >
              <span v-if="timing(x).elapsedMs !== null" class="clock" :class="{ running: timing(x).open }" :title="t('board.elapsedTitle')">
                ⏱ {{ duration(timing(x).elapsedMs!) }}
              </span>
              <span v-if="x.acceptanceCriteria.length" :title="t('board.criteriaTitle')">✓ {{ x.acceptanceCriteria.length }}</span>
              <span v-if="x.reviews.length" :title="t('board.reviewsTitle')">◎ {{ x.reviews.length }}</span>
              <span v-if="x.consultations.length" :title="t('board.consultationsTitle')">? {{ x.consultations.length }}</span>
              <span v-if="blockedBy(x).length && x.status !== 'DONE'" class="blocked" :title="t('board.blockedTitle')">
                {{ t('board.after', { tickets: blockedBy(x).join(', ') }) }}
              </span>
            </div>
          </div>
          <div v-if="column(c).length === 0" class="empty-col">—</div>
          <button
            v-if="c === 'PROPOSED' && column('PROPOSED').length"
            class="action recommended"
            :disabled="busy"
            @click="run(() => approveTickets(mission!.id, column('PROPOSED').map((x) => x.id)))"
          >
            {{ t('board.approveAll', { count: column('PROPOSED').length }) }}
          </button>
        </div>
      </div>

      <div v-if="cancelled.length" class="hint">
        <label><input v-model="showCancelled" type="checkbox" /> {{ t('board.showDropped', { count: cancelled.length }) }}</label>
        <span v-if="showCancelled"> · {{ cancelled.map((x) => `${x.id} ${x.title}`).join(' · ') }}</span>
      </div>
      </div>

      <TicketDetail
        v-if="open"
        :key="open.id"
        :ticket="open"
        :mission-id="mission.id"
        :now="now"
        :busy="busy"
        :guard="run"
        @close="openId = null"
        @agent="(id) => emit('agent', id)"
        @workshop="emit('workshop')"
      />
    </div>
  </div>
</template>
