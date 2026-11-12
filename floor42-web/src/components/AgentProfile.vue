<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { AgentProfileView } from '../api/types'
import { cssColor, inkOn, uniformOf } from '../scene/identity'
import { t, tr, dateLocale } from '../i18n'
import { phrased } from '../i18n/events'
import { markCore, office } from '../state/office'
import { contexts } from '../state/context'
import { contextTip, gauge, tokens } from '../ui/contextGauge'
import { duration } from '../ui/time'
import { currentTicket, dismissal } from '../ui/dismissal'
import AgentIdentity from './AgentIdentity.vue'
import ExecutionCard from './ExecutionCard.vue'

/** An agent's profile: declared when hired, measured from the journal. Opened from the agent's right-click menu or by
 * double-clicking its crew card; offers the menu's staff actions (suspend, resume, dismiss). */
const props = defineProps<{ agent: string }>()
const emit = defineEmits<{ close: []; settings: []; follow: []; suspend: []; resume: []; dismiss: [] }>()

const profile = ref<AgentProfileView | null>(null)
const error = ref<string | null>(null)

async function load() {
  const asked = props.agent
  try {
    const p = await api.agentProfile(asked)
    // A late answer for an agent no longer shown is dropped.
    if (asked !== props.agent) return
    profile.value = p
    error.value = null
  } catch (e) {
    if (asked !== props.agent) return
    error.value = e instanceof Error ? e.message : String(e)
  }
}
onMounted(load)
watch(
  () => props.agent,
  () => {
    profile.value = null
    error.value = null
    void load()
  },
)
// The record follows the journal: refresh when this agent's status changes.
watch(() => office.agents.find((a) => a.id === props.agent)?.status, load)

// Its terminal: every tool call it made on the mission on the board, newest first, as Floor42 recorded it.
const filter = ref('')
const calls = computed(() => {
  const f = filter.value.trim().toLowerCase()
  return (office.mission?.actions ?? [])
    .filter((a) => a.agent === props.agent)
    .filter((a) => !f || `${a.tool} ${a.summary} ${a.ticket ?? ''}`.toLowerCase().includes(f))
    .slice()
    .reverse()
})
const shown = ref(10)
watch(() => props.agent, () => (shown.value = 10))

// Its context in use (W18): measured tokens, a share estimated against the model's documented window.
const reading = computed(() => contexts[props.agent] ?? null)
const meter = computed(() => (reading.value ? gauge(reading.value) : null))
const atWork = computed(() => office.agents.find((a) => a.id === props.agent)?.atWork ?? false)
// The director's star: a core member stays when everyone else is dismissed. Read from the staff list (journaled).
const core = computed(() => office.agents.find((a) => a.id === props.agent)?.core ?? false)
const present = computed(() => office.agents.some((a) => a.id === props.agent))

// The same staff actions as the agent's right-click menu, under the same rule (ui/dismissal.ts). An agent no longer
// on staff (dismissed) shows its record only.
const staff = computed(() => office.agents.find((a) => a.id === props.agent) ?? null)
const leaving = computed(() =>
  staff.value ? dismissal(staff.value, currentTicket(props.agent, office.mission?.tickets ?? [])) : null,
)

function words(m: Record<string, number>) {
  const e = Object.entries(m)
  return e.length ? e.map(([k, v]) => `${v} ${tr(`word.${k}`, k.replaceAll('_', ' ').toLowerCase())}`).join(' · ') : t('profile.none')
}
</script>

<template>
  <div class="panel profile-panel framed">
    <div class="title">
      {{ t('profile.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div v-if="error" class="bad">{{ error }}</div>
      <template v-else-if="profile">
        <div class="profile-head">
          <div class="portrait" :style="{ background: cssColor(uniformOf(agent)), color: inkOn(uniformOf(agent)) }">{{ profile.name[0] }}</div>
          <div>
            <div class="profile-name">
              {{ profile.name }}
              <button
                v-if="present"
                class="core-toggle"
                :class="{ on: core }"
                :aria-pressed="core"
                :title="core ? t('core.unstar') : t('core.star')"
                @click="markCore(agent, !core)"
              >
                {{ core ? '★' : '☆' }}
              </button>
            </div>
            <div>{{ profile.role }}</div>
            <div class="hint">
              {{ tr(`status.${profile.status}`, profile.status) }}
              <span v-if="profile.suspended" class="warn"> · {{ t('sel.suspended') }}</span>
            </div>
          </div>
          <div class="profile-actions">
            <button class="action" @click="emit('follow')">{{ t('profile.follow') }}</button>
            <button class="action" @click="emit('settings')">{{ t('profile.settings') }}</button>
            <template v-if="staff && leaving">
              <button v-if="staff.suspended" class="action" @click="emit('resume')">{{ t('profile.resume') }}</button>
              <button v-else class="action" :title="t('menu.suspendHint')" @click="emit('suspend')">
                {{ t('profile.suspend') }}
              </button>
              <button
                class="action"
                :disabled="!!leaving.refused"
                :title="leaving.refused ?? leaving.hint"
                @click="emit('dismiss')"
              >
                {{ t('menu.dismiss') }}
              </button>
            </template>
          </div>
        </div>
        <div v-if="leaving?.refused" class="hint warn">{{ leaving.refused }}</div>

        <div class="sub">{{ t('ctx.title') }}</div>
        <div class="context-gauge" :title="reading ? contextTip(reading) : ''">
          <template v-if="reading && meter">
            <div class="ctx-line">
              <b>{{ t('ctx.used', { used: tokens(meter.usedTokens) }) }}</b>
              <span v-if="meter.percent !== null" class="hint">
                · {{ t('ctx.ofWindow', { percent: meter.percent, window: tokens(meter.windowTokens ?? 0) }) }}</span
              >
              <span v-else class="hint"> · {{ t('ctx.unknownWindow', { model: reading.model }) }}</span>
            </div>
            <div class="ctx-track" :class="meter.level">
              <div v-if="meter.share !== null" class="ctx-fill" :style="{ width: `${meter.share * 100}%` }"></div>
            </div>
            <div class="hint">
              {{ t('ctx.read', { time: new Date(reading.at).toLocaleTimeString(dateLocale()), model: reading.model }) }}
              <span v-if="!atWork"> · {{ t('ctx.ended') }}</span>
            </div>
          </template>
          <div v-else class="hint">{{ t('ctx.none') }}</div>
          <div class="hint">{{ t('ctx.noCategories') }}</div>
        </div>

        <AgentIdentity :agent="agent" />

        <details class="profile-terminal" open>
          <summary class="sub">
            {{ t('profile.terminal') }}
            <span class="hint">· {{ office.mission ? t('profile.terminalOn', { count: calls.length, mission: office.mission.id }) : t('board.noMission') }}</span>
          </summary>
          <template v-if="office.mission">
            <input v-model="filter" class="terminal-filter" :placeholder="t('machine.filter')" :aria-label="t('machine.filter')" />
            <div v-if="!calls.length" class="hint">{{ t('profile.terminalNone') }}</div>
            <ExecutionCard v-for="(a, i) in calls.slice(0, shown)" :key="a.id" :action="a" :collapsed="i > 1" height="200px" />
            <button v-if="calls.length > shown" class="link" @click="shown += 20">
              {{ t('profile.terminalMore', { count: calls.length - shown }) }}
            </button>
          </template>
        </details>

        <div class="sub">{{ t('profile.declared') }} <span class="hint">· {{ t('profile.whenHired') }}</span></div>
        <table role="presentation" class="kv">
          <tr><td>{{ t('agentSettings.model') }}</td><td>{{ profile.provider }} · {{ profile.model }}</td></tr>
          <tr><td>{{ t('agentSettings.effort') }}</td><td>{{ profile.effort ?? t('menu.default') }}</td></tr>
          <tr v-if="profile.duties.length">
            <td>{{ t('profile.job') }}</td>
            <td>
              <div v-for="d in profile.duties" :key="d">{{ d }}</div>
            </td>
          </tr>
          <tr>
            <td>{{ t('profile.skills') }}</td>
            <td>
              <span v-for="s in profile.skills" :key="s" class="chip">{{ s }}</span>
              <span v-if="!profile.skills.length" class="hint">{{ t('profile.noSkills') }}</span>
            </td>
          </tr>
          <tr v-if="profile.focus"><td>{{ t('profile.focus') }}</td><td>{{ profile.focus }}</td></tr>
          <tr v-if="profile.modelTrait">
            <td>{{ t('profile.family') }}</td>
            <td>{{ profile.modelTrait }} <span class="hint">({{ t('profile.notMeasured') }})</span></td>
          </tr>
        </table>

        <div class="sub">{{ t('profile.observed') }} <span class="hint">· {{ t('profile.observedHint') }}</span></div>
        <div v-for="(f, i) in profile.facts" :key="i" class="fact" :class="f.kind.toLowerCase()">
          <span class="fact-kind">{{ t(`profile.fact.${f.kind === 'STRENGTH' || f.kind === 'WATCH' ? f.kind : 'INFO'}`) }}</span>
          {{ phrased('fact', f.code, f.params, f.text) }} <span class="hint">· {{ tr(`basis.${f.basis}`, f.basis) }}</span>
        </div>

        <div class="sub">{{ t('profile.record') }}</div>
        <table role="presentation" class="kv">
          <tr><td>{{ t('profile.missions') }}</td><td>{{ profile.record.missions }}</td></tr>
          <tr>
            <td>{{ t('profile.sessions') }}</td>
            <td>
              {{ profile.record.sessions }}<span v-if="profile.record.failedSessions">
                ({{ t('profile.failed', { count: profile.record.failedSessions }) }})</span
              >
              · {{ t('sel.tokens', { in: profile.record.inputTokensProvided, out: profile.record.outputTokensProvided }) }}
            </td>
          </tr>
          <tr>
            <td>{{ t('profile.workTime') }}</td>
            <td>{{ duration(profile.record.workMs) }} <span class="hint">({{ t('profile.measured') }})</span></td>
          </tr>
          <tr>
            <td>{{ t('profile.cost') }}</td>
            <td>
              {{ profile.record.costUsdEstimated === null ? t('top.unknown') : profile.record.costUsdEstimated.toFixed(4) + ' USD' }}
              <span class="hint">({{ t('profile.estimated') }})</span>
            </td>
          </tr>
          <tr><td>{{ t('sel.tickets') }}</td><td>{{ t('profile.ticketsDone', { done: profile.record.ticketsDone, of: profile.record.ticketsAssigned }) }}</td></tr>
          <tr><td>{{ t('profile.reviewed') }}</td><td>{{ words(profile.record.verdictsReceived) }}</td></tr>
          <tr><td>{{ t('profile.gave') }}</td><td>{{ words(profile.record.verdictsGiven) }}</td></tr>
          <tr><td>{{ t('profile.actions') }}</td><td>{{ words(profile.record.actions) }}</td></tr>
          <tr v-if="Object.keys(profile.record.denials).length"><td>{{ t('profile.denied') }}</td><td>{{ words(profile.record.denials) }}</td></tr>
          <tr v-if="profile.record.consultationsAnswered"><td>{{ t('profile.advice') }}</td><td>{{ profile.record.consultationsAnswered }}</td></tr>
        </table>

        <template v-if="profile.tickets.length">
          <div class="sub">{{ t('profile.recentTickets') }}</div>
          <div v-for="x in profile.tickets" :key="x.mission + x.ticket" class="row">
            <b>{{ x.mission }} {{ x.ticket }}</b> {{ x.title }} ·
            <span class="hint">{{ t(`board.col.${x.status as 'TODO'}`) }}</span>
            <span v-if="x.workMs" class="hint"> · ⏱ {{ duration(x.workMs) }}</span>
            <span v-if="x.lastVerdict" class="verdict" :class="x.lastVerdict.toLowerCase()">{{
              tr(`verdict.${x.lastVerdict}`, x.lastVerdict)
            }}</span>
          </div>
        </template>
      </template>
      <div v-else class="hint">{{ t('common.loading') }}</div>
    </div>
  </div>
</template>
