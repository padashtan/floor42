<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { MissionReport } from '../api/types'
import { dateLocale, t, tr } from '../i18n'
import { office } from '../state/office'
import { useGuard } from '../ui/guard'
import { REPORT_SECTIONS, type ReportSectionId } from '../ui/missionReport'
import MissionEndCost from './MissionEndCost.vue'
import MissionEndDeliverables from './MissionEndDeliverables.vue'
import MissionEndNext from './MissionEndNext.vue'
import MissionEndProof from './MissionEndProof.vue'
import MissionEndRetro from './MissionEndRetro.vue'
import MissionEndReviews from './MissionEndReviews.vue'
import MissionEndSummary from './MissionEndSummary.vue'
import MissionEndTickets from './MissionEndTickets.vue'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * The end of a mission in one place (ADR-0039), on SectionedPanel: a short summary on top (outcome, cost, duration,
 * what no commit carries, the next step), then one light section at a time: deliverables, proof (ADR-0058), tickets,
 * reviews, cost, retrospective, next steps. Each figure shows its origin. Files are not shown here: the Deliverables panel does that.
 */
const props = defineProps<{ mission: string }>()
const emit = defineEmits<{ close: []; deliverables: []; archive: []; workshop: []; prove: [] }>()

const report = ref<MissionReport | null>(null)
const error = ref('')
const current = ref<ReportSectionId>('deliverables')
/** An action of the next steps is running: reading the report again waits for it. */
const { acting, guard } = useGuard()

async function load() {
  error.value = ''
  try {
    report.value = await api.report(props.mission)
  } catch (e) {
    error.value = e instanceof ApiError ? t('report.unreadable') : String(e)
  }
}
watch(() => props.mission, load, { immediate: true })
// A close, a directive or an acceptance changes the report: read it again when the mission's state moves.
watch(
  () => (office.mission?.id === props.mission ? `${office.mission.status}:${office.mission.tickets.map((x) => x.status).join()}` : ''),
  (now, before) => {
    if (now && before && now !== before) void load()
  },
)

const tabs = computed<SectionTab[]>(() => REPORT_SECTIONS.map((id) => ({ id, label: t(`report.s.${id}`) })))
</script>

<template>
  <SectionedPanel
    class="mission-end"
    id-prefix="report"
    :title="t('report.title')"
    :hint="t('report.intro')"
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="170px"
    @choose="(id) => (current = id as ReportSectionId)"
    @close="emit('close')"
  >
    <template #title-extra>
      <span v-if="report" class="hint">· {{ report.mission }} · {{ report.title }} · {{ tr(`mstatus.${report.status}`, report.status) }}</span>
      <button class="action" :disabled="acting" :title="t('report.refreshTitle')" @click="load">{{ t('report.refresh') }}</button>
    </template>
    <template #top>
      <div v-if="error" class="warn">{{ error }}</div>
      <div v-if="!report && !error" class="hint">…</div>
      <MissionEndSummary v-if="report" :report="report" @next="current = 'next'" @deliverables="emit('deliverables')" />
    </template>
    <template v-if="report" #default>
      <h3 class="me-title">{{ t(`report.s.${current}`) }}</h3>
      <MissionEndDeliverables v-if="current === 'deliverables'" :report="report" />
      <MissionEndProof v-else-if="current === 'proof'" :mission="report.mission" @prove="emit('prove')" />
      <MissionEndTickets v-else-if="current === 'tickets'" :report="report" />
      <MissionEndReviews v-else-if="current === 'reviews'" :report="report" />
      <MissionEndCost v-else-if="current === 'cost'" :report="report" />
      <MissionEndRetro v-else-if="current === 'retro'" :report="report" />
      <MissionEndNext v-else :report="report" :acting="acting" :guard="guard" @changed="load" @archive="emit('archive')" @workshop="emit('workshop')" />
      <div class="hint me-read">{{ t('report.readAt', { time: new Date(report.readAt).toLocaleTimeString(dateLocale()) }) }}</div>
    </template>
  </SectionedPanel>
</template>

<style>
/* The frame (section list, narrow screen) is SectionedPanel's; the lines below are shared by the section components. */
.me-title {
  margin: 0 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid #1d4a57;
  color: var(--cyan);
  font-size: 15px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.me-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
  line-height: 1.5;
}
.me-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 16px;
  padding: 8px 12px;
  background: #0a232c;
  border: 1px solid #1d4a57;
  border-left: 3px solid #2f7f94;
}
.me-row .main {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
  min-width: 0;
}
.me-row .side {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  justify-content: flex-end;
  align-items: baseline;
}
.me-row.total {
  border-left-color: var(--cyan);
}
.me-row code {
  word-break: break-all;
}
.me-sub {
  margin: 10px 0 0;
  color: var(--cyan);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.me-list {
  margin: 0;
  padding-left: 18px;
}
.mission-end .chip {
  font-size: 12px;
  border: 1px solid #1d4a57;
  color: var(--muted);
  padding: 0 6px;
}
.mission-end .chip.on {
  color: var(--green);
  border-color: var(--green);
}
.mission-end .chip.bad {
  color: #ff5a5a;
  border-color: #ff5a5a;
}
.me-ticket {
  border: 1px solid #1d4a57;
  border-left: 3px solid #1d4a57;
  padding: 6px 10px;
}
.me-ticket summary {
  display: flex;
  gap: 10px;
  align-items: baseline;
  cursor: pointer;
}
.me-ticket .title-text {
  flex: 1;
  min-width: 0;
}
.me-detail {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
  padding-left: 4px;
}
.mission-end .said {
  font-size: 0.9em;
}
.mission-end .verdict {
  font-size: 12px;
  border: 1px solid var(--muted);
  padding: 0 4px;
}
.mission-end .verdict.validated,
.mission-end .verdict-dot.validated {
  color: var(--green);
  border-color: var(--green);
}
.mission-end .verdict.validated_with_reservations,
.mission-end .verdict.unverifiable {
  color: var(--amber);
  border-color: var(--amber);
}
.mission-end .verdict.rejected {
  color: #ff5a5a;
  border-color: #ff5a5a;
}
.verdict-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--muted);
  align-self: center;
}
.verdict-dot.validated {
  background: var(--green);
}
.verdict-dot.validated_with_reservations,
.verdict-dot.unverifiable {
  background: var(--amber);
}
.verdict-dot.rejected {
  background: #ff5a5a;
}
.me-counts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 8px;
}
.me-counts .count {
  background: #0a232c;
  color: var(--text);
  border: 1px solid #1d4a57;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
}
.me-counts .count b {
  font-size: 22px;
}
.me-counts .count span {
  color: #9fc3cc;
  font-size: 12px;
}
.me-step {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid #1d4a57;
}
.me-step .action {
  margin: 0;
}
.me-step .merge code {
  margin-right: 6px;
  word-break: break-all;
}
.me-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-self: stretch;
}
.me-read {
  margin-top: 8px;
}
</style>
