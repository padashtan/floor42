<script setup lang="ts">
import { computed } from 'vue'
import type { MissionReport } from '../api/types'
import { t, tr } from '../i18n'
import { latestFirst } from '../ui/newestFirst'

/**
 * One line per ticket: id, title, state, who produced it, the verdicts of its reviews. A click opens what the producer
 * and the reviewers said, the commit and the directives. The cost and the time of a ticket are in the Cost section.
 */
const props = defineProps<{ report: MissionReport }>()
const work = computed(() => props.report.tickets.filter((x) => x.kind !== 'RETRO'))
</script>

<template>
  <section class="me-section" data-report-section="tickets">
    <div v-if="!work.length" class="hint">{{ t('report.tk.none') }}</div>
    <details v-for="x in work" :key="x.id" class="me-ticket">
      <summary>
        <b>{{ x.id }}</b>
        <span class="title-text">{{ x.title }}</span>
        <span class="chip">{{ tr(`board.col.${x.status}`, x.status) }}</span>
        <span v-for="(r, i) in x.reviews" :key="i" class="verdict-dot" :class="r.verdict.toLowerCase()" :title="tr(`verdict.${r.verdict}`, r.verdict)"></span>
      </summary>
      <div class="me-detail">
        <div class="hint">
          {{ x.producer ?? t('board.unassigned') }}
          <template v-if="x.commit"> · {{ t('report.commit', { commit: x.commit.slice(0, 7) }) }}</template>
          · {{ t('report.sessions', { count: x.sessions, failed: x.failedSessions }) }}
          <template v-if="x.directives"> · {{ t('report.directives', { count: x.directives }) }}</template>
          <template v-if="x.rejections"> · {{ t('report.rejections', { count: x.rejections }) }}</template>
        </div>
        <div v-if="x.producerVerdict" class="said">{{ t('report.producerSaid', { text: x.producerVerdict }) }}</div>
        <div v-for="(r, i) in latestFirst(x.reviews)" :key="i" class="said">
          <span class="verdict" :class="r.verdict.toLowerCase()">{{ tr(`verdict.${r.verdict}`, r.verdict) }}</span>
          {{ r.reviewer }}<template v-if="r.firstLine">: « {{ r.firstLine }} »</template>
        </div>
      </div>
    </details>
    <template v-if="(report.askedBack ?? []).length">
      <h4 class="me-sub">{{ t('report.askedBack', { count: report.askedBack.length }) }}</h4>
      <div v-for="a in report.askedBack" :key="a.question" class="me-row" data-report-asked-back>
        <div class="main">{{ a.question }} · {{ a.subject }}</div>
        <div class="side hint">{{ t('report.askedBack.line', { agent: a.agent, round: a.round }) }}</div>
      </div>
    </template>
  </section>
</template>
