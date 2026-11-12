<script setup lang="ts">
import type { MissionReport } from '../api/types'
import { dateLocale, t } from '../i18n'
import { checksNewestFirst } from '../ui/newestFirst'

/** The reviews as four counts, then the checks Floor42 itself ran, each with its exit code and its origin. */
defineProps<{ report: MissionReport }>()
</script>

<template>
  <section class="me-section" data-report-section="reviews">
    <div class="me-counts">
      <div class="count validated"><b>{{ report.reviews.validated }}</b><span>{{ t('verdict.VALIDATED') }}</span></div>
      <div class="count reserved"><b>{{ report.reviews.withReservations }}</b><span>{{ t('verdict.VALIDATED_WITH_RESERVATIONS') }}</span></div>
      <div class="count rejected"><b>{{ report.reviews.rejected }}</b><span>{{ t('verdict.REJECTED') }}</span></div>
      <div class="count unverifiable"><b>{{ report.reviews.unverifiable }}</b><span>{{ t('verdict.UNVERIFIABLE') }}</span></div>
    </div>
    <div class="hint">{{ t('report.rv.total', { count: report.reviews.reviews }) }} · {{ t('report.basis.REPORTED') }}</div>

    <h4 class="me-sub">{{ t('report.checks') }}</h4>
    <div v-if="report.checks.length === 0" class="hint">{{ t('report.noChecks') }}</div>
    <div v-for="(c, i) in checksNewestFirst(report.checks)" :key="i" class="me-row">
      <div class="main">
        <span :class="c.passed ? 'ok' : 'warn'">{{ c.passed ? '✓' : '✗' }}</span>
        <code>{{ c.command }}</code>
      </div>
      <div class="side hint">
        {{ c.workspace }} · {{ c.timedOut ? t('report.timedOut') : t('report.exit', { code: c.exitCode ?? '?' }) }}
        · {{ new Date(c.at).toLocaleString(dateLocale()) }}
        <span class="chip" :title="t('report.basis.MEASURED')">{{ t('report.tag.MEASURED') }}</span>
      </div>
    </div>
    <template v-if="report.objections.length">
      <h4 class="me-sub">{{ t('report.objections') }}</h4>
      <div v-for="r in report.objections" :key="r.number" class="me-row">
        <div class="main">{{ t('report.objectionRound', { n: r.number }) }}</div>
        <div class="side hint">{{ t('report.objectionTickets', { targeted: r.targeted.join(', ') || '-', created: r.created.join(', ') || '-' }) }}</div>
      </div>
    </template>
  </section>
</template>
