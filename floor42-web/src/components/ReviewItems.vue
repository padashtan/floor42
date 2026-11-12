<script setup lang="ts">
import { computed } from 'vue'
import type { ReviewItemsView } from '../api/types'
import { t, tr } from '../i18n'

/**
 * A review's reservations and notes, apart (ADR-0053): a reservation cites a criterion of the ticket and says what to
 * change, a note blocks nothing. Nothing for a review recorded before 1.13.0, whose findings are shown as written.
 */
const props = defineProps<{ items: ReviewItemsView | null }>()
/** The reviewer declared reservations Floor42 could not read: the findings below are the review as written. */
const unparsed = computed(() => props.items?.notes.some((n) => n.why === 'UNPARSED') ?? false)
const notes = computed(() => props.items?.notes.filter((n) => n.why !== 'UNPARSED') ?? [])
</script>

<template>
  <div v-if="items" class="review-items">
    <div v-if="items.reservations.length" class="reservations">
      <div class="sub">{{ t('review.reservations') }}</div>
      <ul>
        <li v-for="(r, i) in items.reservations" :key="i" class="reservation">
          <b>{{ t('review.criterion', { n: r.criterion }) }}</b> <i>{{ r.criterionText }}</i><br />
          {{ r.gap }}<br />
          <span class="change">{{ t('review.change', { change: r.change }) }}</span>
        </li>
      </ul>
    </div>
    <div v-if="unparsed" class="hint unparsed">{{ t('review.unparsed') }}</div>
    <div v-if="notes.length" class="notes">
      <div class="sub">{{ t('review.notes') }}</div>
      <ul>
        <li v-for="(n, i) in notes" :key="i" class="note">
          {{ n.text }}
          <span v-if="n.why !== 'NOTE'" class="hint">({{ tr(`review.why.${n.why}`, n.why) }})</span>
        </li>
      </ul>
    </div>
    <div v-if="!unparsed && !items.reservations.length && !notes.length" class="hint">{{ t('review.none') }}</div>
  </div>
</template>

<style scoped>
.review-items ul {
  margin: 0.2rem 0 0.4rem;
  padding-left: 1.2rem;
}
.reservation .change {
  font-weight: 600;
}
</style>
