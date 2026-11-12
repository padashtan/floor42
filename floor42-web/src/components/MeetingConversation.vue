<script setup lang="ts">
import { computed } from 'vue'
import type { MeetingView, TurnView } from '../api/types'
import { dateLocale, t, type Key } from '../i18n'
import { addressee } from '../ui/meeting'
import { conversation } from '../ui/meetingTurns'

/**
 * The director's meeting as a conversation (ADR-0049): who spoke, in which role, what they said, what it cost as
 * Claude Code estimated it, and who still waits or speaks; the director's own words between. Not the work log: no tool
 * call here, only what was said at the table.
 */
const props = defineProps<{ meeting: MeetingView }>()
const said = computed(() => conversation(props.meeting))

function at(iso: string | null): string {
  return iso ? new Date(iso).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' }) : ''
}

function cost(turn: TurnView): string | null {
  if (turn.costUsd === null || turn.costBasis === null) return null
  const usd = turn.costUsd.toFixed(turn.costUsd < 0.1 ? 4 : 2)
  return t(turn.costBasis === 'ESTIMATED_BY_CLAUDE_CODE' ? 'meetv.costEstimated' : 'meetv.costCharged', { usd })
}
</script>

<template>
  <div class="conversation" aria-live="polite">
    <div v-if="said.length === 0" class="hint">{{ t('meetv.nothingSaid') }}</div>
    <div v-for="(s, i) in said" :key="i" class="said" :class="s.kind === 'note' ? 'director' : s.turn.state.toLowerCase()">
      <template v-if="s.kind === 'note'">
        <div class="who">
          <b>{{ s.note.attendee ? t('meetv.youTo', { who: addressee(meeting, s.note.attendee) ?? '' }) : t('meetv.you') }}</b>
          <span class="when">{{ at(s.note.at) }}</span>
        </div>
        <div class="words">{{ s.note.text }}</div>
      </template>
      <template v-else>
        <div class="who">
          <b>{{ s.turn.name }}</b> <span class="role">{{ s.turn.role }}</span>
          <span class="state">{{ t(`meetv.state.${s.turn.state}` as Key) }}</span>
          <span class="when">{{ t('meetv.round', { n: s.round }) }} {{ at(s.turn.at) }}</span>
        </div>
        <div v-if="s.turn.answer" class="words">{{ s.turn.answer }}</div>
        <div v-else-if="s.turn.why" class="why">{{ t('meetv.notHeard', { why: s.turn.why }) }}</div>
        <div v-if="cost(s.turn)" class="cost">{{ cost(s.turn) }}</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.conversation {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 6px 0;
}
.said {
  border: 1px solid var(--muted);
  border-radius: 10px;
  padding: 6px 8px;
  max-width: 92%;
}
.said.director {
  align-self: flex-end;
  border-color: var(--amber);
}
.said.waiting {
  opacity: 0.6;
  border-style: dashed;
}
.said.speaking {
  border-color: var(--amber);
}
.said.done {
  border-color: var(--green);
}
.said.not_heard {
  border-style: dotted;
}
.who {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: baseline;
}
.role,
.when,
.cost,
.why {
  color: var(--muted);
}
.state {
  color: var(--amber);
}
.words {
  white-space: pre-wrap;
  margin-top: 2px;
}
</style>
