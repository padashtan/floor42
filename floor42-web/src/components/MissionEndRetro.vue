<script setup lang="ts">
import type { MissionReport } from '../api/types'
import { t, tr } from '../i18n'

/** The retrospective: where it stands, what the chair concluded, and the lessons with their status. */
defineProps<{ report: MissionReport }>()
</script>

<template>
  <section class="me-section" data-report-section="retro">
    <div>
      {{ t(`report.retro.${report.retro.state}`) }}<template v-if="report.retro.reason"> ({{ report.retro.reason }})</template>
    </div>
    <ul v-if="report.retro.conclusions.length" class="me-list">
      <li v-for="(c, i) in report.retro.conclusions" :key="i">{{ c }}</li>
    </ul>
    <h4 v-if="report.retro.lessons.length" class="me-sub">{{ t('report.retro.lessons') }}</h4>
    <div v-for="l in report.retro.lessons" :key="l.id" class="me-row">
      <div class="main">{{ l.text }}</div>
      <div class="side">
        <span class="chip" :class="{ on: l.status === 'ACCEPTED' }">{{ tr(`report.lesson.${l.status}`, l.status) }}</span>
        <span class="hint">{{ l.audience ?? t('report.everyone') }}</span>
      </div>
    </div>
  </section>
</template>
