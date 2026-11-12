<script setup lang="ts">
import { computed, ref } from 'vue'
import type { QuestionView } from '../api/types'
import { optionLabel, t, tr } from '../i18n'
import { questionContext, questionText } from '../i18n/events'
import { answer, office } from '../state/office'
import { ruleFor } from '../ui/rules'
import { why } from '../ui/why'
import { askAgainLabel } from '../ui/waiting'
import AskBackDialog from './AskBackDialog.vue'

const props = defineProps<{ q: QuestionView; focused?: boolean; locate?: boolean }>()
const emit = defineEmits<{ agent: [id: string] }>()

/** The agent a question is about: who asked it, or whose action waits for approval. */
const agent = computed(() => agentOfQuestion(props.q))
const action = computed(() => office.mission?.actions.find((a) => a.id === props.q.action) ?? null)
const busy = ref(false)
/** An open question (no options) takes a free answer, sent back to the waiting agent as written. */
const free = ref('')
/** The remembered refusal the director chose to be asked again for, sent with the answer; null for none. */
const askAgain = ref<string | null>(null)
/** A question recorded before ask-back existed (the online demo's tape) carries none: the card still shows. */
const ask = computed(() => (props.q as Partial<QuestionView>).askBack ?? { rounds: 0, possible: false })
/** The ask-back form is open (ADR-0059). */
const asking = ref(false)

/** The rule that makes the call ask, and who recorded it (the committee's table, the director, Floor42). */
const cause = (code: string, params: Record<string, string>, by: string) =>
  t('question.askedBecause', {
    rule: ruleFor(code, params),
    by: tr(`perm.by.${by}`, by),
  })

async function choose(option: string) {
  if (!option.trim()) return
  busy.value = true
  try {
    await answer(props.q.id, option, askAgain.value)
  } finally {
    busy.value = false
  }
}
</script>

<script lang="ts">
import { office as state } from '../state/office'

export function agentOfQuestion(q: { askedBy: string; action: string | null }): string | null {
  if (state.agents.some((a) => a.id === q.askedBy)) return q.askedBy
  const action = state.mission?.actions.find((a) => a.id === q.action)
  return action && state.agents.some((a) => a.id === action.agent) ? action.agent : null
}
</script>

<template>
  <div class="question" :class="{ focused, blocking: q.blocking }">
    <div class="head">
      <b>{{ q.id }}</b> · {{ tr(`qkind.${q.kind}`, q.kind) }} · {{ t('question.askedBy', { who: q.askedBy }) }} ·
      <span :class="q.blocking ? 'warn' : ''">{{ q.blocking ? t('question.blocking') : t('question.notBlocking') }}</span> ·
      {{ tr(`criticality.${q.criticality}`, q.criticality.toLowerCase()) }}
      <button v-if="locate && agent" class="locate" :title="t('question.locateTitle', { agent })" @click="emit('agent', agent)">
        {{ t('question.locate', { agent }) }}
      </button>
    </div>
    <div class="subject">{{ questionText(q.phraseCode, q.phraseParams, 'subject', q.subject) }}</div>
    <div v-if="action" class="context">
      {{ t('question.wantsToRun', { agent: action.agent }) }} <b>{{ action.tool }}</b>: <code>{{ action.summary }}</code>
    </div>
    <div v-if="q.context" class="context">{{ questionContext(q) }}</div>
    <div v-if="q.causeCode && q.causeParams && q.causeBy" class="context">{{ cause(q.causeCode, q.causeParams, q.causeBy) }}</div>
    <div v-if="q.writesNow && q.writesAsked && q.writesNow !== q.writesAsked" class="context warn">
      {{ t('question.writesNow', { writes: tr(`question.writes.${q.writesNow}`, q.writesNow) }) }}
    </div>
    <div v-if="q.recommendation" class="context">{{ t('question.recommendation', { option: optionLabel(q.recommendation) }) }}</div>
    <div v-if="q.status === 'OPEN' && (q.askAgain ?? []).length" class="ask-again">
      <label v-for="a in q.askAgain" :key="a.question">
        <input
          type="checkbox"
          :checked="askAgain === a.question"
          :disabled="busy"
          @change="askAgain = ($event.target as HTMLInputElement).checked ? a.question : null"
        />
        {{ askAgainLabel(a) }}
      </label>
      <div class="hint">{{ t('question.askAgainHint') }}</div>
    </div>
    <template v-if="q.status === 'OPEN' && ask.rounds > 0">
      <div class="hint" data-ask-back-rounds>
        {{ ask.possible ? t('askBack.rounds', { n: ask.rounds }) : t('askBack.capped', { n: ask.rounds }) }}
      </div>
    </template>
    <template v-if="q.status === 'OPEN' && ask.possible">
      <button v-if="!asking" class="link" data-ask-back-open :title="t('askBack.title', { agent: q.askedBy })" @click="asking = true">
        {{ t('askBack.button') }}
      </button>
      <AskBackDialog v-else :question="q.id" :agent="q.askedBy" @close="asking = false" />
    </template>
    <div class="options">
      <button
        v-for="o in q.options"
        :key="o"
        class="action"
        :class="{ recommended: o === q.recommendation }"
        :disabled="busy"
        @click="choose(o)"
      >
        {{ optionLabel(o) }}
      </button>
    </div>
    <form v-if="q.options.length === 0" class="free-answer" @submit.prevent="choose(free)">
      <button
        v-if="q.recommendation && free !== q.recommendation"
        type="button"
        class="link"
        :disabled="busy"
        @click="free = q.recommendation"
      >
        {{ t('question.useRecommendation') }}
      </button>
      <textarea
        v-model="free"
        rows="3"
        maxlength="4000"
        :placeholder="t('question.freePlaceholder', { who: q.askedBy })"
        :disabled="busy"
        @keydown.ctrl.enter.prevent="choose(free)" :aria-label="t('question.freePlaceholder', { who: q.askedBy })"></textarea>
      <button class="action" type="submit" :disabled="busy || !free.trim()" :title="why([!free.trim(), t('why.needText')])">{{ t('question.send') }}</button>
    </form>
  </div>
</template>
