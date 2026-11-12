<script setup lang="ts">
import { computed, provide, ref } from 'vue'
import { normalize } from '../ui/sectioned'
import { t } from '../i18n'
import { office } from '../state/office'
import { setPanel } from '../state/openPanels'
import { MEETING_BUSY } from '../ui/meetingBusy'
import CommitteeDebate from './CommitteeDebate.vue'
import CommitteeDecision from './CommitteeDecision.vue'
import CommitteeOverview from './CommitteeOverview.vue'
import RetroSection from './RetroSection.vue'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * The meeting room on SectionedPanel: the committee (its state and what the director does about it), its debate
 * (newest first), the mission's earlier committees, and the retrospective. One section at a time, chosen on the left.
 */
const emit = defineEmits<{ close: [] }>()

const mission = computed(() => office.mission)
/** The director's meeting open now (ADR-0049): this room leads to its panel. */
const directorMeeting = computed(() => office.meetings.find((m) => m.open) ?? null)
const committee = computed(() => mission.value?.committee ?? null)
/** The mission's earlier committees, newest first: the latest one is shown in full (ADR-0046). */
const earlier = computed(() => (mission.value?.committees ?? []).filter((k) => k.number !== committee.value?.number).reverse())
const committeeCount = computed(() => mission.value?.committees.length ?? 0)
const retro = computed(() => mission.value?.retro ?? null)
/** One write at a time in the room, whichever part started it (the parts inject it). */
const busy = ref(false)
provide(MEETING_BUSY, busy)

/** The director has something to do: a ruling or the analyst's questions. */
const waiting = computed(() => ['AWAITING_DIRECTOR', 'AWAITING_ANSWERS'].includes(committee.value?.stage ?? ''))
const tabs = computed<SectionTab[]>(() => {
  const list: SectionTab[] = []
  if (committee.value) {
    list.push({ id: 'committee', label: t('meeting.s.committee'), badge: waiting.value ? { text: '!', tone: 'changed' } : null })
    list.push({ id: 'debate', label: t('meeting.s.debate'), badge: { text: String(committee.value.contributions.length) } })
  }
  if (earlier.value.length) list.push({ id: 'history', label: t('meeting.s.history'), badge: { text: String(earlier.value.length) } })
  if (retro.value) list.push({ id: 'retro', label: t('meeting.s.retro') })
  return list
})
const query = ref('')
/** The earlier committees whose topic or recommendation holds the search. */
const foundEarlier = computed(() =>
  earlier.value.filter((k) => !query.value.trim() || normalize(`${k.topic} ${k.recommendation ?? ''} ${k.ticket}`).includes(normalize(query.value))),
)
const chosen = ref('committee')
const current = computed(() => (tabs.value.some((s) => s.id === chosen.value) ? chosen.value : (tabs.value[0]?.id ?? '')))
const empty = computed(() => !committee.value && !retro.value && !directorMeeting.value)
</script>

<template>
  <div v-if="empty" class="panel meeting framed meeting-empty">
    <div class="title">
      {{ t('meeting.title') }}<span v-if="mission">&nbsp;· {{ mission.id }}</span>
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="empty-state">
      <span>{{ t('meeting.empty') }}</span>
      <button class="action recommended" @click="setPanel('callMeeting', true)">{{ t('meeting.emptyCall') }}</button>
    </div>
  </div>
  <SectionedPanel
    v-else
    v-model:query="query"
    class="meeting"
    :hint="t('meeting.intro')"
    searchable
    id-prefix="meeting"
    :title="t('meeting.title') + (mission ? ` · ${mission.id}` : '')"
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="170px"
    @choose="(id) => (chosen = id)"
    @close="emit('close')"
  >
    <template #top>
      <div v-if="directorMeeting" class="hint director-meeting-open">
        {{ t('meetv.openMeeting', { meeting: directorMeeting.id }) }}
        <button class="action small meeting-open-it" @click="setPanel('callMeeting', true)">{{ t('meetv.openIt') }}</button>
      </div>
    </template>
    <template v-if="mission" #default="{ searching }">
      <div v-if="!tabs.length" class="hint">{{ t('meeting.noCommittee') }}</div>
      <div v-else-if="searching" class="mr-stack">
        <CommitteeDebate v-if="committee" :committee="committee" :query="query" />
        <div v-for="k in foundEarlier" :key="k.number" class="mr-card">
          <div class="card-label">{{ t('meeting.committeeNo', { n: k.number, count: committeeCount }) }} · {{ k.ticket }}</div>
          <div>{{ k.topic }}</div>
          <div class="hint">{{ k.recommendation ?? t('meeting.historyNone') }}</div>
        </div>
      </div>
      <div v-else-if="current === 'committee' && committee" class="mr-stack committee">
        <CommitteeOverview :committee="committee" :mission-id="mission.id" :count="committeeCount" />
        <CommitteeDecision :committee="committee" :mission="mission" />
      </div>
      <CommitteeDebate v-else-if="current === 'debate' && committee" :committee="committee" />
      <section v-else-if="current === 'history'" class="mr-stack history">
        <div v-for="k in earlier" :key="k.number" class="mr-card">
          <div class="card-label">
            {{ t('meeting.committeeNo', { n: k.number, count: committeeCount }) }} · {{ t(`meeting.stage.${k.stage as 'ANALYSIS'}`) }} · {{ k.ticket }}
          </div>
          <div>{{ k.topic }}</div>
          <div class="hint">{{ k.recommendation ?? t('meeting.historyNone') }}</div>
        </div>
      </section>
      <RetroSection v-else-if="current === 'retro' && retro" :retro="retro" :mission-id="mission.id" />
    </template>
  </SectionedPanel>
</template>
