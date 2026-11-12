<script setup lang="ts">
import { computed, ref } from 'vue'
import { api, MISSION_OBJECTIVE_MAX, newIdempotencyKey } from '../api/client'
import { keyPerContent } from '../ui/idempotency'
import type { MissionReport, NextStep } from '../api/types'
import { t } from '../i18n'
import { defaultNote } from '../i18n/events'
import { confirmed } from '../state/confirm'
import { why } from '../ui/why'
import { completeMission, createMission, workshopCall } from '../state/office'
import { useCopy } from '../ui/clipboard'
import { atRisk, nextSteps, offered, refusalKey } from '../ui/missionReport'
import PullRequestBlock from './PullRequestBlock.vue'
import UncommittedAlert from './UncommittedAlert.vue'
import { directiveMayGo } from '../ui/directiveWarning'

/**
 * What the director can do next, each step with the refusal it would meet (ADR-0039): close, reopen a ticket with a
 * directive, open a pull request, merge, follow up, archive. A step that changes the mission asks the panel to read
 * the report again.
 */
const props = defineProps<{ report: MissionReport; acting: boolean; guard: (work: () => Promise<unknown>) => Promise<boolean> }>()
const emit = defineEmits<{ changed: []; archive: []; workshop: [] }>()

const { copied, copy } = useCopy()
const busy = computed(() => props.acting)
const note = ref('')
const follow = ref<{ title: string; objective: string } | null>(null)
// One key per follow-up content (ui/idempotency.ts): sent again, no second mission; edited, a new key.
const followKeys = keyPerContent(newIdempotencyKey)
const reopen = ref<{ ticket: string; directive: string } | null>(null)
const prFor = ref<string | null>(null)

const steps = computed(() => nextSteps(props.report.next))
const risky = computed(() => atRisk(props.report.outputs))
const mergeable = computed(() => props.report.outputs.filter((o) => o.mergeCommand))
const withAccepted = computed(() => props.report.outputs.filter((o) => o.accepted > 0))
const reopenable = computed(() => props.report.tickets.filter((x) => x.kind !== 'RETRO' && x.reopenTo))

const step = (kind: NextStep['kind']) => steps.value.find((s) => s.kind === kind)
const can = (kind: NextStep['kind']) => !!step(kind)?.available
const offers = (kind: NextStep['kind']) => offered(kind, steps.value, props.report.status)
/** Why a step is not available, phrased in the screen's language (the server's message stays in its tooltip). */
const refusal = (kind: NextStep['kind']) => {
  const key = refusalKey(step(kind))
  return key ? t(key) : ''
}


async function close() {
  if (risky.value.length && !(await confirmed({ message: t('report.closeAtRisk', { count: risky.value.length }) }))) return
  const done = await props.guard(() => completeMission(props.report.mission, note.value.trim() || defaultNote('report.closeDefault')))
  if (done) emit('changed')
}

async function sendDirective() {
  const r = reopen.value
  if (!r || !r.directive.trim()) return
  if (!(await directiveMayGo(r.ticket, props.report.tickets.find((x) => x.id === r.ticket)?.environmentBlocker))) return
  const done = await props.guard(() => workshopCall(() => api.directive(props.report.mission, r.ticket, r.directive.trim())))
  if (done) {
    reopen.value = null
    emit('changed')
  }
}

async function createFollowUp() {
  const f = follow.value
  const repo = props.report.followUp.repository
  if (!f || !repo || !f.title.trim() || !f.objective.trim()) return
  const key = followKeys.keyFor([f.title.trim(), f.objective.trim(), repo])
  if (await props.guard(() => createMission(f.title.trim(), f.objective.trim(), repo, [], false, 'architecture', null, null, key))) {
    followKeys.done()
    follow.value = null
  }
}

function startFollowUp() {
  const f = props.report.followUp
  follow.value = { title: f.title, objective: f.objective }
}
</script>

<template>
  <section class="me-section" data-report-section="next">
    <div v-if="offers('CLOSE')" class="me-step">
      <input v-model="note" :placeholder="t('board.deliveryNote')" maxlength="500" :disabled="!can('CLOSE')" :aria-label="t('board.deliveryNote')" />
      <button class="action recommended" :disabled="busy || !can('CLOSE')" :title="refusal('CLOSE')" @click="close">
        {{ t('report.do.CLOSE') }}
      </button>
      <div v-if="!can('CLOSE')" class="hint">{{ refusal('CLOSE') }}</div>
      <UncommittedAlert v-else :mission="report.mission" />
    </div>

    <div v-if="offers('REOPEN')" class="me-step">
      <button
        class="action"
        :disabled="busy || !can('REOPEN')"
        :title="refusal('REOPEN')"
        @click="reopen = { ticket: reopenable[0]?.id ?? '', directive: '' }"
      >
        {{ t('report.do.REOPEN') }}
      </button>
      <div v-if="!can('REOPEN')" class="hint">{{ refusal('REOPEN') }}</div>
      <div v-if="reopen" class="me-form">
        <select v-model="reopen.ticket" :aria-label="t('report.reopenTicket')">
          <option v-for="x in reopenable" :key="x.id" :value="x.id">
            {{ x.id }} {{ x.title }}<template v-if="x.reopenTo !== x.id"> → {{ x.reopenTo }}</template>
          </option>
        </select>
        <textarea v-model="reopen.directive" rows="3" :placeholder="t('report.directivePlaceholder')" :aria-label="t('report.directivePlaceholder')"></textarea>
        <div>
          <button
            class="action"
            :disabled="busy || !reopen.directive.trim()"
            :title="why([!reopen.directive.trim(), t('ux.why.needDirective')])"
            @click="sendDirective"
          >{{ t('report.sendDirective') }}</button>
          <button class="link" @click="reopen = null">{{ t('report.cancel') }}</button>
        </div>
      </div>
    </div>

    <div class="me-step">
      <button class="action" :disabled="!can('PULL_REQUEST')" :title="refusal('PULL_REQUEST')" @click="prFor = prFor ? null : (withAccepted[0]?.agent ?? null)">
        {{ t('report.do.PULL_REQUEST') }}
      </button>
      <div v-if="!can('PULL_REQUEST')" class="hint">{{ refusal('PULL_REQUEST') }}</div>
      <template v-if="prFor">
        <select v-if="withAccepted.length > 1" v-model="prFor" :aria-label="t('report.prAgent')">
          <option v-for="o in withAccepted" :key="o.agent" :value="o.agent">{{ o.agent }} · {{ o.branch }}</option>
        </select>
        <PullRequestBlock :key="prFor" :mission="report.mission" :workspace="prFor" :accepted="true" />
      </template>
    </div>

    <div class="me-step">
      <b>{{ t('report.do.MERGE') }}</b>
      <div v-if="!can('MERGE')" class="hint">{{ refusal('MERGE') }}</div>
      <div v-for="o in mergeable" :key="o.agent" class="merge">
        <span class="hint">{{ t('report.mergeHint', { agent: o.agent, count: o.accepted, branch: report.projectBranch ?? '' }) }}</span>
        <code>{{ o.mergeCommand }}</code>
        <button class="link" @click="copy(o.mergeCommand!)">{{ copied === o.mergeCommand ? t('report.copied') : t('report.copy') }}</button>
      </div>
    </div>

    <div class="me-step">
      <button class="action" :disabled="busy || !can('FOLLOW_UP')" :title="refusal('FOLLOW_UP')" @click="startFollowUp">
        {{ t('report.do.FOLLOW_UP') }}
      </button>
      <div v-if="!can('FOLLOW_UP')" class="hint">{{ refusal('FOLLOW_UP') }}</div>
      <div v-if="follow" class="me-form">
        <input v-model="follow.title" maxlength="120" :aria-label="t('report.followUpTitle')" />
        <textarea v-model="follow.objective" rows="8" :aria-label="t('report.followUpObjective')" :maxlength="MISSION_OBJECTIVE_MAX"></textarea>
        <div class="hint">{{ t('report.followUpHint', { repository: report.followUp.repository ?? '' }) }}</div>
        <div>
          <button class="action recommended" :disabled="busy" @click="createFollowUp">{{ t('report.createFollowUp') }}</button>
          <button class="link" @click="follow = null">{{ t('report.cancel') }}</button>
        </div>
      </div>
    </div>

    <div class="me-step">
      <button class="action" @click="emit('archive')">{{ t('report.do.ARCHIVE') }}</button>
      <span class="hint">{{ t('report.archiveHint') }}</span>
    </div>
    <div class="me-step">
      <button class="action" @click="emit('workshop')">{{ t('board.openWorkshop') }}</button>
    </div>
  </section>
</template>
