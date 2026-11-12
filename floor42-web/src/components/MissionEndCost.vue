<script setup lang="ts">
import { computed } from 'vue'
import type { MissionReport } from '../api/types'
import { t } from '../i18n'
import CostBoundNote from './CostBoundNote.vue'
import MeasureFigure from './MeasureFigure.vue'

/** What the mission took: the totals, then the same figures ticket by ticket. Each figure carries its origin. */
const props = defineProps<{ report: MissionReport }>()
const work = computed(() => props.report.tickets.filter((x) => x.kind !== 'RETRO'))
</script>

<template>
  <section class="me-section" data-report-section="cost">
    <div class="me-row total">
      <div class="main">{{ t('report.sum.cost') }}</div>
      <div class="side">
        <MeasureFigure :measure="report.totals.costUsd" kind="money" />
        <CostBoundNote :bound="report.totals.costBound" />
        <span v-if="report.totals.budgetUsd !== null" class="hint">{{ t('report.budget', { usd: report.totals.budgetUsd }) }}</span>
      </div>
    </div>
    <div class="me-row total">
      <div class="main">{{ t('report.elapsed') }}</div>
      <div class="side"><MeasureFigure :measure="report.totals.elapsedMs" kind="time" /></div>
    </div>
    <div class="me-row total">
      <div class="main">{{ t('report.work') }}</div>
      <div class="side"><MeasureFigure :measure="report.totals.workMs" kind="time" /></div>
    </div>
    <div class="hint">
      {{ t('report.sessions', { count: report.totals.sessions, failed: report.totals.failedSessions }) }} · {{ t('report.basisHint') }}
    </div>

    <h4 class="me-sub">{{ t('report.perTicket') }}</h4>
    <div v-for="x in work" :key="x.id" class="me-row">
      <div class="main">
        <b>{{ x.id }}</b>
        <span class="title-text">{{ x.title }}</span>
      </div>
      <div class="side">
        <MeasureFigure :measure="x.workMs" kind="time" />
        <MeasureFigure :measure="x.costUsd" kind="money" />
        <CostBoundNote :bound="x.costBound" />
      </div>
    </div>
  </section>
</template>
