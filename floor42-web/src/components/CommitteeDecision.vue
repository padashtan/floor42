<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import type { CommitteeView, MissionView } from '../api/types'
import { optionLabel, t } from '../i18n'
import { answer, contributeInPerson, office, sendBackCommittee } from '../state/office'
import { MEETING_BUSY } from '../ui/meetingBusy'
import { why } from '../ui/why'
import QuestionCard from './QuestionCard.vue'
import TerminalBlock from './TerminalBlock.vue'

/**
 * What the director does for a committee: speaks in person while it analyses or debates, answers the analyst's
 * questions, rules when it asks, or reads that it was adopted.
 */
const props = defineProps<{ committee: CommitteeView; mission: MissionView }>()

const busy = inject(MEETING_BUSY, ref(false))
const constraint = ref('')

// The director may speak in person in their double's seat while an idea committee analyses or debates.
const mine = ref({ text: '', proposal: '', position: '', objection: '' })
const canSpeak = computed(
  () => !!props.committee.directorSeat && ['ANALYSIS', 'DEBATE'].includes(props.committee.stage) && props.mission.status === 'IN_PROGRESS',
)
/** Something here waits for the director: to speak, to answer, to rule, or the adoption to read. */
const actionable = computed(
  () => canSpeak.value || ['CLARIFICATION', 'AWAITING_ANSWERS', 'AWAITING_DIRECTOR', 'ADOPTED'].includes(props.committee.stage),
)
async function speak() {
  const v = mine.value
  if (!v.text.trim()) return
  busy.value = true
  try {
    const blank = (s: string) => (s.trim() ? s.trim() : null)
    if (await contributeInPerson(props.mission.id, props.committee.number, v.text.trim(), blank(v.proposal), blank(v.position), blank(v.objection))) {
      mine.value = { text: '', proposal: '', position: '', objection: '' }
    }
  } finally {
    busy.value = false
  }
}

/** The business analyst's questions still open: answered here, they go back to the committee. */
const clarifying = computed(() =>
  office.questions.filter((q) => q.kind === 'CLARIFICATION' && q.mission === props.mission.id && q.status === 'OPEN'),
)
const ruling = computed(() =>
  office.questions.find(
    (q) => q.kind === 'COMMITTEE_ARBITRATION' && q.mission === props.mission.id && q.ticket === props.committee.ticket,
  ),
)

async function rule(option: string) {
  if (!ruling.value) return
  busy.value = true
  try {
    await answer(ruling.value.id, option)
  } finally {
    busy.value = false
  }
}

async function sendBack() {
  if (!constraint.value.trim()) return
  busy.value = true
  try {
    if (await sendBackCommittee(props.mission.id, props.committee.number, constraint.value.trim())) constraint.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="committee-decision mr-card">
    <div class="card-label">{{ t('meeting.card.actions') }}</div>
    <div v-if="!actionable" class="hint">{{ t('meeting.card.nothing') }}</div>
    <form v-if="canSpeak" class="in-person" @submit.prevent="speak">
      <div class="sub">{{ t('meeting.speakTitle') }}</div>
      <div class="hint">{{ t('meeting.speakHint', { round: committee.round }) }}</div>
      <textarea v-model="mine.text" rows="4" :placeholder="t('meeting.speakText')" required :aria-label="t('meeting.speakText')" />
      <input v-model="mine.proposal" :placeholder="t('meeting.speakProposal')" :aria-label="t('meeting.speakProposal')" />
      <input v-model="mine.position" :placeholder="t('meeting.speakPosition')" :aria-label="t('meeting.speakPosition')" />
      <input v-model="mine.objection" :placeholder="t('meeting.speakObjection')" :aria-label="t('meeting.speakObjection')" />
      <button class="action" type="submit" :disabled="busy || !mine.text.trim()" :title="why([!mine.text.trim(), t('why.needText')])">{{ t('meeting.speak') }}</button>
    </form>

    <div v-if="committee.stage === 'CLARIFICATION' || committee.stage === 'AWAITING_ANSWERS'" class="clarifying">
      <div class="sub">{{ t('meeting.clarifyTitle', { analyst: committee.analyst ?? '' }) }}</div>
      <div class="hint">
        {{ committee.stage === 'CLARIFICATION' ? t('meeting.clarifyWorking') : t('meeting.clarifyAsk') }}
      </div>
      <TerminalBlock v-if="committee.clarificationNote" :text="committee.clarificationNote" format="markdown" height="160px" />
      <QuestionCard v-for="q in clarifying" :key="q.id" :q="q" />
    </div>

    <div v-if="committee.stage === 'AWAITING_DIRECTOR'" class="ruling">
      <div v-if="committee.standingCriticalObjections.length" class="standing">
        <b>{{ t('meeting.standing') }}</b> {{ t('meeting.standingHint') }}
        <div v-for="o in committee.standingCriticalObjections" :key="o.member">
          · {{ o.member }} ({{ o.role }}): {{ o.criticalObjection }}
        </div>
      </div>
      <div class="row">
        <button
          v-for="o in ruling?.options ?? []"
          :key="o"
          class="action"
          :class="{ recommended: o.startsWith('Adopt') && !committee.standingCriticalObjections.length }"
          :disabled="busy"
          @click="rule(o)"
        >
          {{ optionLabel(o) }}
        </button>
      </div>
      <div class="row">
        <input v-model="constraint" :placeholder="t('meeting.constraintPlaceholder')" :aria-label="t('meeting.constraintPlaceholder')" />
        <button class="action" :disabled="busy || !constraint.trim()" :title="why([!constraint.trim(), t('why.needText')])" @click="sendBack">{{ t('meeting.sendBack') }}</button>
      </div>
    </div>
    <div v-else-if="committee.stage === 'ADOPTED'" class="hint">
      {{ t('meeting.adopted') }}<span v-if="committee.implement">{{ t('meeting.implementPlanned') }}</span>.
    </div>
  </div>
</template>
