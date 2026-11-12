<script setup lang="ts">
import { computed } from 'vue'
import type { MissionReport } from '../api/types'
import { t, tr } from '../i18n'
import { useCopy } from '../ui/clipboard'
import { atRisk, recommendedStep, warnings } from '../ui/missionReport'
import CostBoundNote from './CostBoundNote.vue'
import MeasureFigure from './MeasureFigure.vue'

/**
 * The top of the end-of-mission panel: the outcome, the three figures that matter (cost, duration, time at work) each
 * with its origin, what no commit carries (always loud, with its path), and the step to take next. Everything else is
 * in the sections below.
 */
const props = defineProps<{ report: MissionReport }>()
const emit = defineEmits<{ next: []; deliverables: [] }>()

const { copied, copy } = useCopy()
const risky = computed(() => atRisk(props.report.outputs))
const alerts = computed(() => warnings(props.report.warnings).filter((w) => w.level !== 'info'))
const others = computed(() => warnings(props.report.warnings).filter((w) => w.level === 'info'))
const recommended = computed(() => recommendedStep(props.report.next, props.report.status))
const closed = computed(() => props.report.status === 'COMPLETED' || props.report.status === 'CANCELLED')
</script>

<template>
  <div class="me-summary" data-report-summary>
    <div class="me-outcome">
      <b>{{ tr(`mstatus.${report.status}`, report.status) }}</b>
      <span>{{ t('report.tickets', { done: report.totals.done, dropped: report.totals.dropped, open: report.totals.open }) }}</span>
      <span v-if="report.outcome" class="note">{{ t('report.outcome') }} {{ report.outcome }}</span>
    </div>
    <div class="me-figures">
      <div>
        <span class="label">{{ t('report.sum.cost') }}</span>
        <MeasureFigure :measure="report.totals.costUsd" kind="money" />
        <CostBoundNote :bound="report.totals.costBound" />
        <span v-if="report.totals.budgetUsd !== null" class="hint">{{ t('report.budget', { usd: report.totals.budgetUsd }) }}</span>
      </div>
      <div>
        <span class="label">{{ t('report.sum.duration') }}</span>
        <MeasureFigure :measure="report.totals.elapsedMs" kind="time" />
      </div>
      <div>
        <span class="label">{{ t('report.sum.work') }}</span>
        <MeasureFigure :measure="report.totals.workMs" kind="time" />
      </div>
    </div>

    <!-- Never a silent end: what no commit, merge or pull request carries comes first, with its path. -->
    <div v-for="(w, i) in alerts" :key="i" class="warning" :class="w.level">{{ t(w.key as never, w.params) }}</div>
    <div v-for="o in risky" :key="o.agent" class="at-risk">
      <b>{{ o.agent }}</b> · <code>{{ o.path }}</code>
      <button class="link" @click="copy(o.path)">{{ copied === o.path ? t('report.copied') : t('report.copyPath') }}</button>
      <div v-if="o.ignored.length" class="files">
        {{ t('report.ignoredFiles', { count: o.ignoredCount }) }} <code v-for="f in o.ignored" :key="f">{{ f }}</code>
      </div>
      <div v-if="o.uncommitted.length" class="files">
        {{ t('report.uncommittedFiles', { count: o.uncommittedCount }) }} <code v-for="f in o.uncommitted" :key="f">{{ f }}</code>
      </div>
    </div>
    <details v-if="others.length" class="me-notes">
      <summary>{{ t('report.sum.notes', { count: others.length }) }}</summary>
      <div v-for="(w, i) in others" :key="i" class="warning" :class="w.level">{{ t(w.key as never, w.params) }}</div>
    </details>

    <div class="me-next">
      <button class="action recommended" @click="emit('deliverables')">{{ t('report.deliverables') }}</button>
      <button class="action" @click="emit('next')">
        {{ recommended ? t('report.sum.next', { step: t(`report.do.${recommended}`) }) : t('report.sum.seeNext') }}
      </button>
      <span v-if="closed" class="hint">{{ t('report.sum.closed') }}</span>
    </div>
  </div>
</template>

<style>
.me-summary {
  border: 1px solid #1d4a57;
  border-left: 3px solid var(--cyan);
  padding: 10px 14px;
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.me-outcome {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  align-items: baseline;
}
.me-outcome b {
  color: var(--cyan);
  letter-spacing: 0.06em;
}
.me-outcome .note {
  flex-basis: 100%;
  color: var(--muted);
}
.me-figures {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 8px 16px;
}
.me-figures > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.me-figures .label {
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.me-summary .warning {
  padding: 4px 8px;
  border-left: 3px solid var(--cyan);
}
.me-summary .warning.loud {
  border-left-color: #ff5a5a;
  background: rgba(255, 90, 90, 0.14);
  font-weight: bold;
}
.me-summary .warning.warn {
  border-left-color: #f2a93b;
  background: rgba(242, 169, 59, 0.1);
}
.me-summary .at-risk {
  margin-left: 12px;
  font-size: 0.9em;
}
.me-summary .at-risk code {
  margin-right: 6px;
  word-break: break-all;
}
.me-notes summary {
  cursor: pointer;
  color: var(--muted);
}
.me-next {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.me-next .action {
  margin: 0;
}
</style>
