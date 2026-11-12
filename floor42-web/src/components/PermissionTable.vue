<script setup lang="ts">
import { computed, ref } from 'vue'
import type { RuleBy, ToolDecision, ToolSettingView } from '../api/types'
import { t, tr } from '../i18n'
import { ruleFor } from '../ui/rules'
import { forgetApproval, office, setToolDecision } from '../state/office'

/** Columns of the table, from the most autonomous to the most restrictive. */
const COLUMNS = computed<{ decision: ToolDecision; label: string; hint: string }[]>(() =>
  (['ALLOW', 'ASK', 'ASK_ALWAYS', 'DENY'] as const).map((decision) => ({
    decision,
    label: t(`perm.${decision}`),
    hint: t(`perm.${decision}.hint`),
  })),
)

const busy = ref<string | null>(null)

/** A rule beyond a tool's line, in the screen's language, with who recorded it. */
const ruleText = (code: string, params: Record<string, string>, by: RuleBy) =>
  t('perm.rule', { rule: ruleFor(code, params), by: byWho(by) })
const byWho = (by: RuleBy) => tr(`perm.by.${by}`, by)
/** A ticket's own line outranks the row's: choosing the same setting again applies it to every ticket. */
const ticketLines = (row: ToolSettingView) => row.rules.some((r) => r.code === 'ruleTicket')
/** A line Floor42 set: choosing it again makes it the director's own choice, which an implementation ticket keeps. */
const floor42s = (row: ToolSettingView) => row.lineBy === 'FLOOR42' || row.lineBy === 'COMMITTEE_TABLE'

async function forget(question: string) {
  busy.value = question
  try {
    await forgetApproval(question)
  } finally {
    busy.value = null
  }
}

async function choose(tool: string, decision: ToolDecision) {
  busy.value = tool
  try {
    await setToolDecision(tool, decision)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div class="sub">{{ t('perm.title') }} · {{ office.mission?.id }}</div>
  <div v-if="(office.mission?.committees ?? []).length" class="hint">{{ t('perm.committeeTable') }}</div>
  <div v-if="office.toolSettings.length === 0" class="hint">{{ t('perm.none') }}</div>
  <table v-else class="permissions">
    <thead>
      <tr>
        <th>{{ t('perm.tool') }}</th>
        <th v-for="c in COLUMNS" :key="c.decision" :title="c.hint">{{ c.label }}</th>
      </tr>
    </thead>
    <tbody>
      <template v-for="row in office.toolSettings" :key="row.tool">
        <tr>
          <td class="tool">
            <b>{{ row.tool }}</b>
            <span class="category">{{ tr(`category.${row.category}`, row.category.toLowerCase()) }}</span>
          </td>
          <td v-for="c in COLUMNS" :key="c.decision" class="choice">
            <button
              class="radio"
              :class="{ on: row.decision === c.decision, [c.decision.toLowerCase()]: true }"
              :disabled="busy === row.tool"
              :title="c.hint"
              :aria-pressed="row.decision === c.decision"
              @click="(row.decision !== c.decision || ticketLines(row) || floor42s(row)) && choose(row.tool, c.decision)"
            ></button>
          </td>
        </tr>
        <tr class="details">
          <td colspan="5">
            <span class="source">{{ row.lineBy ? byWho(row.lineBy) : row.source }}</span>
            <span v-if="row.warning" class="warn"> · {{ row.warning }}</span>
            <span v-for="r in row.remembered" :key="r.question" class="remembered">
              · {{ t('perm.remembered') }} {{ r.agent }}<span v-if="r.command"> '{{ r.command }}'</span> {{ r.answer }}
              <button
                class="forget"
                :disabled="busy === r.question"
                :title="t('perm.forgetTitle', { question: r.question })"
                @click="forget(r.question)"
              >
                {{ t('perm.forget') }}
              </button>
            </span>
            <div v-for="(r, i) in row.rules" :key="i" class="rule">{{ ruleText(r.code, r.params, r.by) }}</div>
            <div v-if="ticketLines(row)" class="hint">{{ t('perm.reapply') }}</div>
            <div v-if="floor42s(row)" class="hint">{{ t('perm.makeYours') }}</div>
          </td>
        </tr>
      </template>
    </tbody>
  </table>
  <div v-if="office.mission?.origin === 'REAL'" class="hint">{{ t('perm.confined') }}</div>
</template>
