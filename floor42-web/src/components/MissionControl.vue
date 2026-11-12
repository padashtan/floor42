<script setup lang="ts">
import { computed, ref, toRef, type Ref } from 'vue'
import { api, ApiError, newIdempotencyKey } from '../api/client'
import type { CommitteePanel, InitPreview } from '../api/types'
import {
  approvePlan,
  cancelMission,
  completeMission,
  configureChecks,
  createMission,
  office,
  rejectPlan,
  resumeMission,
  startDemo,
} from '../state/office'
import { t, tr } from '../i18n'
import { defaultNote, saidText } from '../i18n/events'
import { confirmed } from '../state/confirm'
import { drafts, resetMissionDraft } from '../state/drafts'
import ArchiveMissionButton from './ArchiveMissionButton.vue'
import ConveneRetroButton from './ConveneRetroButton.vue'
import MissionFiles from './MissionFiles.vue'
import MissionCommittee from './MissionCommittee.vue'
import DelegationConsent from './DelegationConsent.vue'
import PutOnBoard from './PutOnBoard.vue'
import PlanCap from './PlanCap.vue'
import PracticeMission from './PracticeMission.vue'
import UnfamiliarLines from './UnfamiliarLines.vue'
import WorkflowLine from './WorkflowLine.vue'
import { checksAdded, tasksAdded } from '../ui/workflowChecks'
import WorkflowPicker from './WorkflowPicker.vue'
import TeamMode from './TeamMode.vue'
import MissionBasics from './MissionBasics.vue'
import MissionProject from './MissionProject.vue'
import { why } from '../ui/why'
import { keyPerContent } from '../ui/idempotency'

/** The desk shows one part at a time: the mission on the board, or the form for a new one. */
const props = withDefaults(defineProps<{ section?: 'board' | 'new' }>(), { section: 'board' })
// The fields live in the drafts store (state/drafts.ts): another panel taking this one's place loses nothing.
const title = toRef(drafts.mission, 'title')
const objective = toRef(drafts.mission, 'objective')
const repository = toRef(drafts.mission, 'repository')
const checks = toRef(drafts.mission, 'checks')
/** The director's explicit choice, made before the folder: a repository that exists, or a new empty project. */
const kind = ref<'existing' | 'new'>('existing')
/** Set when the chosen folder has no git (or no commit yet): creating the mission initialises it first. */
const initPreview = ref<InitPreview | null>(null)
/** Creating the mission would initialise the folder; a folder meant as an existing project must not be made one by surprise. */
const creating = computed(() => !!initPreview.value && !initPreview.value.refused)
const mismatch = computed(() => creating.value && kind.value === 'existing')
/** No committee, an architecture committee, or an idea committee before a new project. */
const panel = toRef(drafts.mission, 'panel') as Ref<'none' | CommitteePanel>
const withCommittee = computed(() => panel.value !== 'none')
const letGeoffreyPlan = toRef(drafts.mission, 'letGeoffreyPlan')
/** The company workflow the new mission pins (ADR-0050); null for Floor42's own. */
const workflow = ref<string | null>(null)
const mode = ref<'loop' | 'graph'>('loop')
const seated = toRef(drafts.mission, 'seated')
const deliverTo = toRef(drafts.mission, 'deliverTo')

const busy = ref(false)
// One key per content proposed (ui/idempotency.ts): sent again, it creates no second mission; edited, it is new.
const missionKeys = keyPerContent(newIdempotencyKey)

const runtime = computed(() => office.status?.runtime)
const mission = computed(() => office.mission)
const awaiting = computed(() => mission.value?.status === 'AWAITING_APPROVAL')
const closable = computed(
  () =>
    mission.value?.status === 'IN_PROGRESS' &&
    mission.value.tickets.length > 0 &&
    mission.value.tickets.every((t) => t.status === 'DONE' || t.status === 'CANCELLED'),
)
const waiting = computed(() =>
  (mission.value?.tickets ?? []).filter((t) => t.status === 'TODO' || t.status === 'IN_PROGRESS' || t.status === 'IN_REVIEW'),
)
const cancellable = computed(() => mission.value && !['COMPLETED', 'CANCELLED'].includes(mission.value.status))
const cost = computed(() => {
  const costs = (mission.value?.sessions ?? []).map((s) => s.costUsdEstimated).filter((c): c is number => c !== null)
  return costs.length ? costs.reduce((a, b) => a + b, 0) : null
})

/** Initialises git in the chosen folder when it needs it, then proposes the mission. */
async function propose(): Promise<boolean> {
  const init = initPreview.value
  if (init && !init.refused) {
    try {
      await api.initRepository(init.path)
    } catch (e) {
      office.error = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
      return false
    }
  }
  const committee = withCommittee.value ? seated.value : []
  const plan = !withCommittee.value && letGeoffreyPlan.value
  const fields = [title.value, objective.value, repository.value] as const
  const key = missionKeys.keyFor([...fields, committee, plan, panel.value, workflow.value, deliverTo.value, mode.value])
  const committeeKind = panel.value === 'idea' ? 'idea' : 'architecture'
  const created = await createMission(...fields, committee, plan, committeeKind, workflow.value, deliverTo.value, key, plan ? mode.value : 'loop')
  // The board shows the mission just created; its checks go through the call of the desk's Checks section.
  const made = office.mission
  if (created && checks.value.trim() && made?.title === title.value) await configureChecks(made.id, checks.value.trim(), false)
  return created
}

/** The way out of the new mission form before proposing: nothing was sent, so nothing needs undoing. */
function clearForm() {
  resetMissionDraft()
  initPreview.value = null
}

async function cancel() {
  const m = mission.value
  if (!m || !(await confirmed({ message: t('mission.abandonConfirm', { id: m.id, title: m.title }), confirmLabel: t('confirm.abandon'), danger: true }))) return
  run(() => cancelMission(m.id, defaultNote('note.abandoned')))
}

async function run(action: () => Promise<boolean>, reset = false) {
  busy.value = true
  try {
    // A proposed mission empties the whole form, choices and folder included: the next one starts blank.
    if ((await action()) && reset) {
      missionKeys.done()
      resetMissionDraft()
      initPreview.value = null
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <template v-if="props.section !== 'new'">
  <div class="sub">{{ t('mission.onBoard') }}</div>
  <PutOnBoard v-if="!mission"><div class="empty-state">{{ t('mission.none') }}</div></PutOnBoard>
  <template v-else>
    <div class="hint">
      <b>{{ mission.id }}</b> · {{ mission.title }} · {{ tr(`mstatus.${mission.status}`, mission.status) }} ·
      <span :class="mission.origin === 'REAL' ? 'bad' : 'warn'">{{ t(`log.${mission.origin}`) }}</span>
      <span v-if="mission.workspace"> · {{ mission.workspace }}</span>
    </div>
    <WorkflowLine :workflow="mission.workflow" />
    <div v-if="mission.sessions.length" class="hint">
      {{ t('board.sessions', { count: mission.sessions.length }) }}
      <span v-if="cost !== null && mission.origin === 'REAL'"> · {{ t('board.cost', { usd: cost.toFixed(4) }) }}</span>
    </div>
    <div v-if="awaiting" class="plan">
      {{ mission.tickets[0]?.kind === 'PLANNING' ? t('mission.geoffreyPlans') : t('mission.officePlan') }}
      <div v-for="x in mission.tickets" :key="x.id">— {{ x.id }} {{ x.title }} ({{ x.assignee ?? t('board.unassigned') }})</div>
      <div v-if="tasksAdded(mission.workflow)" class="hint tasks-added">{{ t('workflows.task.approvalCount', tasksAdded(mission.workflow)!) }}</div>
      <div v-if="checksAdded(mission.workflow, mission.tickets)" class="hint checks-added">{{ t('workflows.chk.approvalCount', checksAdded(mission.workflow, mission.tickets)!) }}</div>
      {{ t('mission.checkPermissions') }}
      <SandboxReadiness v-if="mission.workspace" :project="mission.workspace" :check="mission.checkCommand" />
      <!-- A mission on a repository, real or the guided rehearsal on the sample, is approved with its cap. -->
      <PlanCap
        :key="mission.id"
        v-if="mission.origin === 'REAL' || mission.workspace"
        :mission="mission"
        :busy="busy"
        @approve="(cap) => run(() => approvePlan(mission!.id, cap.budgetUsd, !!cap.noCap, cap.resources ?? []))"
      />
      <div>
        <button v-if="mission.origin !== 'REAL' && !mission.workspace" class="action recommended" :disabled="busy" @click="run(() => approvePlan(mission!.id))">
          {{ t('mission.approvePlan') }}
        </button>
        <button
          class="action"
          :disabled="busy"
          @click="run(() => rejectPlan(mission!.id, defaultNote('note.planRejected')))"
        >
          {{ t('lessons.reject') }}
        </button>
      </div>
    </div>
    <DelegationConsent v-if="mission.origin === 'REAL' && !awaiting" :mission="mission.id" />
    <div v-if="mission.status === 'SUSPENDED'" class="plan">
      {{ t('mission.suspended') }}<span v-if="mission.incidents.length">:
        {{ saidText(mission.incidents.at(-1)!.descriptionCode, mission.incidents.at(-1)!.descriptionParams, mission.incidents.at(-1)!.description) }}</span>. {{ t('mission.suspendedThen') }}
      <div>
        <button class="action" :disabled="busy" @click="run(() => resumeMission(mission!.id))">{{ t('mission.resume') }}</button>
      </div>
    </div>
    <div v-if="!awaiting && waiting.length" class="hint">
      {{ t('mission.inOrder') }} {{ waiting.map((x) => `${x.id} ${x.title} (${t(`board.col.${x.status}`)})`).join(' → ') }}
    </div>
    <button
      v-if="closable"
      class="action"
      :disabled="busy"
      @click="run(() => completeMission(mission!.id, defaultNote('note.closedAfterReview')))"
    >
      {{ t('board.deliveryAccept') }}
    </button>
    <button v-if="cancellable" class="action" :disabled="busy" @click="cancel">{{ t('mission.abandon') }}</button>
    <ArchiveMissionButton :mission="mission" />
    <ConveneRetroButton :mission="mission" />
    <MissionFiles v-if="cancellable && mission.workspace" :mission="mission.id" />
  </template>
  </template>

  <template v-if="props.section !== 'board'">
  <div class="sub">{{ t('mission.new') }}</div>
  <div v-if="!runtime?.available" class="hint warn">
    {{ t('mission.unavailable', { reason: runtime?.reason ?? t('top.unknown') }) }}
  </div>
  <form
    v-else
    class="new-mission"
    @submit.prevent="run(propose, true)"
  >
    <MissionBasics v-model:title="title" v-model:objective="objective" :idea="panel === 'idea'" />
    <MissionProject
      v-model:repository="repository"
      v-model:preview="initPreview"
      v-model:kind="kind"
      :objective="objective"
      :optional="panel === 'idea'"
    />
    <label class="field">
      <span class="hint">{{ t('mission.labelChecks') }}</span>
      <input v-model="checks" name="checks" :placeholder="t('results.checksPlaceholder')" />
    </label>
    <label class="deliver-to">
      {{ t('home.field') }}
      <input v-model="deliverTo" name="deliverTo" :placeholder="t('home.placeholder')" aria-describedby="home-hint" />
    </label>
    <div id="home-hint" class="hint">{{ deliverTo.trim() ? t('home.hint') : t('home.none') }}</div>
    <WorkflowPicker v-model="workflow" />
    <label v-if="!withCommittee" class="hint"><input v-model="letGeoffreyPlan" type="checkbox" /> {{ t('mission.letGeoffreyPlan') }}</label>
    <TeamMode v-if="!withCommittee" v-model="mode" :plan="letGeoffreyPlan" />
    <MissionCommittee v-model:panel="panel" v-model:seated="seated" />
    <div v-if="mismatch" class="warn notice">{{ t('mission.notRepository') }}</div>
    <div v-else-if="creating" class="warn notice new-project">{{ t('mission.willCreate', { folder: initPreview!.path }) }}</div>
    <div class="form-actions">
      <button
        class="action"
        type="submit"
        :disabled="busy || !!initPreview?.refused || mismatch || (withCommittee && seated.length < (panel === 'idea' ? 2 : 1))"
        :title="
          why(
            [!!initPreview?.refused, t('why.initRefused')],
            [mismatch, t('mission.notRepository')],
            [withCommittee && seated.length < (panel === 'idea' ? 2 : 1), t('why.seats', { count: panel === 'idea' ? 2 : 1 })],
          )
        "
      >
        {{ initPreview && !initPreview.refused ? t('mission.initAndPropose') : t('mission.propose') }}
      </button>
      <button
        v-if="title || objective || repository || checks"
        class="action clear-form"
        type="button"
        :disabled="busy"
        :title="t('ux.clearFormTitle')"
        @click="clearForm"
      >
        {{ t('ux.clearForm') }}
      </button>
    </div>
    <span class="hint">{{ t('mission.runtime', { version: runtime.version ?? '' }) }}</span>
  </form>
  <UnfamiliarLines />

  <PracticeMission />

  <div class="sub">{{ t('mission.demo') }}</div>
  <button class="action" :disabled="busy" @click="run(startDemo)">{{ t('mission.demoStart') }}</button>
  <div class="hint">{{ t('mission.demoHint') }}</div>
  </template>
</template>
