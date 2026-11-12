<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CommitteeView, ContributionView } from '../api/types'
import { t } from '../i18n'
import { roundsNewestFirst } from '../ui/newestFirst'
import { normalize } from '../ui/sectioned'
import TerminalBlock from './TerminalBlock.vue'

/**
 * The debate, newest first: the decision record on top, then each round from the latest to the first; the chosen
 * contribution is read in full beside the list.
 */
const props = withDefaults(defineProps<{ committee: CommitteeView; query?: string }>(), { query: '' })

const opened = ref<ContributionView | null>(null)
/** The contributions holding every word of the search (all of them when it is empty). */
const found = computed(() => {
  const words = normalize(props.query).split(' ').filter(Boolean)
  return props.committee.contributions.filter((c) => {
    const text = normalize(`${c.member} ${c.role} ${c.proposal ?? ''} ${c.position ?? ''} ${c.criticalObjection ?? ''} ${c.text}`)
    return words.every((w) => text.includes(w))
  })
})
const rounds = computed(() => roundsNewestFirst(found.value))
const record = computed(() => found.value.filter((c) => c.kind === 'SYNTHESIS').at(-1) ?? null)
</script>

<template>
  <section class="mr-block meeting-body">
    <div class="rounds">
      <div v-if="record" class="round">
        <div class="sub">{{ t('meeting.record', { who: record.member }) }}</div>
        <button class="contribution record" :class="{ on: opened === record }" @click="opened = record">
          <div class="proposal">{{ record.proposal ?? t('meeting.noRecommendation') }}</div>
        </button>
      </div>
      <div v-for="r in rounds" :key="r.round" class="round">
        <div class="sub">{{ r.round === 1 ? t('meeting.round1') : t('meeting.roundN', { n: r.round }) }}</div>
        <button
          v-for="c in r.items"
          :key="c.member + c.round"
          class="contribution"
          :class="{ on: opened === c, critical: c.criticalObjection }"
          @click="opened = c"
        >
          <div class="who">
            <b>{{ c.member }}</b> <span class="hint">{{ c.role }}</span>
            <span v-if="c.byDirector" class="by-director">{{ t('meeting.byDirector') }}</span>
            <span v-if="c.position" class="position">{{ c.position }}</span>
          </div>
          <div class="proposal">{{ c.proposal ?? t('meeting.noProposal') }}</div>
          <div v-if="c.criticalObjection" class="objection">{{ t('meeting.objection', { text: c.criticalObjection }) }}</div>
        </button>
      </div>
      <div v-if="!record && !rounds.length" class="hint">{{ t('meeting.noDebate') }}</div>
    </div>

    <div class="reader">
      <div v-if="!opened" class="hint">{{ t('meeting.pick') }}</div>
      <template v-else>
        <div class="sub">{{ opened.member }} · {{ opened.kind.toLowerCase() }} · {{ t('meeting.round', { n: opened.round }) }}</div>
        <TerminalBlock class="fill" :text="opened.text" :name="`${opened.member}-round-${opened.round}`" height="360px" format="markdown" />
      </template>
    </div>
  </section>
</template>
