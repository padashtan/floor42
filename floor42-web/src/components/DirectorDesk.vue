<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed, nextTick, ref, watch } from 'vue'
import { t } from '../i18n'
import { wanted } from '../state/library'
import { office } from '../state/office'
import { waits } from '../state/waits'
import { elsewhere, todoRows } from '../ui/todo'
import { focusViewButton, takeOpener } from '../state/viewer'
import ImprovementChecklist from './ImprovementChecklist.vue'
import LessonList from './LessonList.vue'
import MissionArchives from './MissionArchives.vue'
import MissionControl from './MissionControl.vue'
import MissionResults from './MissionResults.vue'
import PermissionTable from './PermissionTable.vue'
import ProposedTickets from './ProposedTickets.vue'
import WaitsElsewhere from './WaitsElsewhere.vue'
import QuestionCard from './QuestionCard.vue'

const props = defineProps<{ focus: string | null }>()
const emit = defineEmits<{ close: []; agent: [id: string] }>()

type Tab = 'decide' | 'mission' | 'new' | 'permissions' | 'lessons' | 'notes' | 'archives'

const proposed = computed(() => (office.mission?.tickets ?? []).filter((t) => t.status === 'PROPOSED').length)
const lessonsProposed = computed(() => office.lessons.filter((l) => l.status === 'PROPOSED').length)
const elsewhereCount = computed(() => elsewhere(todoRows(waits.missions ?? [], [], nowMs()), office.mission?.id ?? null).length)
const toDecide = computed(() => office.questions.length + proposed.value + lessonsProposed.value + elsewhereCount.value)
const openNotes = computed(() => office.notes.filter((n) => !n.done).length)

// Back from the file viewer: the tab it was opened from, and the focus on the View button (ADR-0051).
const back = takeOpener('desk')
const root = ref<HTMLElement | null>(null)
// The list comes after git answered for the project: wait up to 3 s for its button.
if (back) void nextTick(() => focusViewButton(() => root.value, back.key, 60))
// Open where the director is needed: a question first, then the mission on the board, else a new mission.
const tab = ref<Tab>(
  back ? 'mission' : wanted.newMission ? 'new' : props.focus || toDecide.value ? 'decide' : office.mission ? 'mission' : 'new',
)
// The library chose a project: the desk shows the new-mission form (the form clears the request).
watch(
  () => wanted.newMission,
  (w) => {
    if (!w) return
    tab.value = 'new'
    void nextTick(() => (wanted.newMission = false))
  },
  { immediate: true },
)

// A plan waits for approval (To do, banner): its cap and resources are on the Mission tab.
watch(
  () => wanted.missionTab,
  (w) => {
    if (!w) return
    tab.value = 'mission'
    void nextTick(() => (wanted.missionTab = false))
  },
  { immediate: true },
)

// Opened again for a question while it is already open: show that question.
watch(
  () => props.focus,
  (f) => {
    if (f) tab.value = 'decide'
  },
)

const TABS = computed<{ key: Tab; label: string; count?: number; alert?: boolean }[]>(() => [
  { key: 'decide', label: t('desk.toDecide'), count: toDecide.value, alert: toDecide.value > 0 },
  { key: 'mission', label: t('desk.mission') },
  { key: 'new', label: t('desk.newMission') },
  { key: 'permissions', label: t('desk.permissions') },
  { key: 'lessons', label: t('desk.lessons'), count: lessonsProposed.value },
  { key: 'notes', label: t('desk.notes'), count: openNotes.value },
  { key: 'archives', label: t('desk.archives') },
])
</script>

<template>
  <div ref="root" class="panel desk-panel framed">
    <div class="title">
      {{ t('desk.title') }}
      <button class="close" :title="t('desk.close')" :aria-label="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="tabs">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="tab"
        :data-tab="t.key"
        :class="{ on: tab === t.key, alert: t.alert }"
        @click="tab = t.key"
      >
        {{ t.label }} <span v-if="t.count" class="count">{{ t.count }}</span>
      </button>
    </div>

    <div class="panel-scroll">
      <template v-if="tab === 'decide'">
        <div v-if="toDecide === 0" class="empty-state">{{ t('desk.nothing') }}</div>
        <template v-if="office.questions.length">
          <div class="sub">{{ t('desk.questions') }}</div>
          <QuestionCard
            v-for="q in office.questions"
            :key="q.id"
            :q="q"
            :focused="q.id === focus"
            locate
            @agent="(id) => emit('agent', id)"
          />
        </template>
        <ProposedTickets v-if="office.mission && proposed" :mission="office.mission" />
        <WaitsElsewhere />
        <LessonList v-if="lessonsProposed" only-proposed />
      </template>

      <template v-else-if="tab === 'mission'">
        <MissionControl section="board" />
        <MissionResults />
      </template>

      <MissionControl v-else-if="tab === 'new'" section="new" />

      <PermissionTable v-else-if="tab === 'permissions'" />

      <LessonList v-else-if="tab === 'lessons'" />

      <ImprovementChecklist v-else-if="tab === 'notes'" />

      <MissionArchives v-else-if="tab === 'archives'" />
    </div>
  </div>
</template>
