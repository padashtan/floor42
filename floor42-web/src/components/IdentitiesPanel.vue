<script setup lang="ts">
// Agent identities (IAM mode), master-detail: the agents on the left, one line each (the state of their Keycloak
// client, a dot when a session holds a token now); everything about the chosen one on the right (IdentityAgentDetail).
// Each side scrolls on its own, so reading one agent never means scrolling through the others.
import { computed, onMounted, ref } from 'vue'
import type { AgentView } from '../api/types.office'
import type { AgentIdentityView, RefusalView, TokenView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, lang, t } from '../i18n'
import { confirmed } from '../state/confirm'
import { setPanel } from '../state/openPanels'
import { iamOn, lastAgentsRead, refusalText } from '../ui/identities'
import { clientState } from '../ui/identityLights'
import IdentityAgentDetail from './IdentityAgentDetail.vue'

const emit = defineEmits<{ close: [] }>()

const off = ref(false)
const error = ref<string | null>(null)
/** What the last button did, said plainly: a rotation changes nothing else on screen. */
const notice = ref<string | null>(null)
const busy = ref(false)
/** The agents' Keycloak clients cannot be read (an outage): the roster stands in, with no client state. */
const unreadable = ref(false)
const agents = ref<AgentIdentityView[]>([])
const tokens = ref<TokenView[]>([])
const refusals = ref<RefusalView[]>([])
/** The tokens or the refusals could not be read: an empty list then says nothing about them. */
const unread = ref(false)
/** The agent shown on the right; the "other refusals" entry when null and some refusal names no agent's token. */
const chosen = ref<string | null>(null)

const live = (x: TokenView) => !x.expiresAt || Date.parse(x.expiresAt) > Date.now()
const tokensOf = (agent: string) => tokens.value.filter((x) => x.agent === agent)
const holding = (agent: string) => tokensOf(agent).some(live)
/** A refusal belongs to the agent whose token it names (by launch or jti); the rest have no agent. */
const refusalsOf = (agent: string) => {
  const mine = tokensOf(agent)
  return refusals.value.filter((r) => mine.some((x) => x.launch === r.launch || x.jti === r.jti))
}
const orphanRefusals = computed(() =>
  refusals.value.filter((r) => !tokens.value.some((x) => x.launch === r.launch || x.jti === r.jti)),
)
const selected = computed(() => agents.value.find((a) => a.agent === chosen.value) ?? null)
const counts = computed(() => {
  const states = agents.value.map(clientState)
  const n = (s: string) => states.filter((x) => x === s).length
  return { total: states.length, ready: n('ready'), missing: n('missing'), revoked: n('revoked'), live: agents.value.filter((a) => holding(a.agent)).length }
})
const when = (iso: string) => new Date(iso).toLocaleTimeString(dateLocale())

async function load() {
  error.value = null
  off.value = !(await iamOn())
  if (off.value) return
  unreadable.value = false
  unread.value = false
  try {
    try {
      ;[agents.value, tokens.value, refusals.value] = await Promise.all([
        request<AgentIdentityView[]>('GET', '/api/iam/agents'),
        request<TokenView[]>('GET', '/api/iam/tokens'),
        request<RefusalView[]>('GET', '/api/iam/refusals'),
      ])
      lastAgentsRead.value = new Date().toISOString()
    } catch {
      // Keycloak down: keep the agents Floor42 knows, and say why their clients are not shown
      const roster = await request<AgentView[]>('GET', '/api/agents')
      agents.value = roster.map((a) => ({ agent: a.id, clientId: null, serviceAccountId: null, enabled: false, revoked: false }))
      tokens.value = await request<TokenView[]>('GET', '/api/iam/tokens').catch(() => ((unread.value = true), []))
      refusals.value = await request<RefusalView[]>('GET', '/api/iam/refusals').catch(() => ((unread.value = true), []))
      unreadable.value = true
    }
    // The first look goes to an agent at work (a token in force), else the first one.
    if (!agents.value.some((a) => a.agent === chosen.value)) {
      chosen.value = (agents.value.find((a) => holding(a.agent)) ?? agents.value[0])?.agent ?? null
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function act(a: AgentIdentityView, what: 'revoke' | 'restore' | 'rotate') {
  if (what === 'revoke' && !(await confirmed({ message: t('idn.revoke') + ' ' + a.agent + '?', confirmLabel: t('idn.revoke'), danger: true }))) return
  if (busy.value) return
  busy.value = true
  notice.value = null
  try {
    await request('POST', `/api/iam/agents/${encodeURIComponent(a.agent)}/${what}`)
    notice.value = t(`idn.done.${what}`, { agent: a.agent, time: when(new Date().toISOString()) })
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

/** Up and down move through the list, as in a file explorer. */
function step(delta: number) {
  const ids: (string | null)[] = agents.value.map((a) => a.agent)
  if (orphanRefusals.value.length) ids.push(null)
  const i = ids.indexOf(chosen.value)
  chosen.value = ids[Math.min(ids.length - 1, Math.max(0, i + delta))] ?? null
}

onMounted(load)
</script>

<template>
  <div class="panel identities-panel framed">
    <div class="title">
      {{ t('idn.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div v-if="off" class="hint iam-off">{{ t('idn.off') }}</div>
    <template v-else>
      <div class="identities-top">
        <span class="line">{{ agents.length ? t('idn.row.summary', counts) : t('idn.noAgents') }}</span>
        <button class="action" @click="setPanel('iamExchanges', true)">{{ t('idx.open') }}</button>
      </div>
      <div v-if="unreadable" class="hint keycloak-down" role="status">
        {{ t('idn.kcDown') }}
        <span v-if="lastAgentsRead">{{ t('idn.kcLastRead', { time: when(lastAgentsRead) }) }}</span>
      </div>
      <div v-if="unread" class="hint warn tokens-unread" role="status">{{ t('idn.unread') }}</div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-if="notice" class="ok notice" role="status">{{ notice }}</div>
      <div class="identities-split">
        <div class="identities-list" role="listbox" :aria-label="t('idn.agents')" tabindex="0" @keydown.down.prevent="step(1)" @keydown.up.prevent="step(-1)">
          <button
            v-for="a in agents"
            :key="a.agent"
            class="agent-item"
            :class="{ selected: chosen === a.agent }"
            role="option"
            :aria-selected="chosen === a.agent"
            :data-agent="a.agent"
            @click="chosen = a.agent"
          >
            <span v-if="unreadable" class="state unknown">?</span>
            <span v-else class="state" :class="clientState(a)">{{ t(`idn.row.${clientState(a)}`) }}</span>
            <span class="name">{{ a.agent }}</span>
            <span v-if="holding(a.agent)" class="live" :title="t('idn.d.holding')">●</span>
          </button>
          <button
            v-if="orphanRefusals.length"
            class="agent-item"
            :class="{ selected: chosen === null }"
            role="option"
            :aria-selected="chosen === null"
            @click="chosen = null"
          >
            <span class="state revoked">{{ orphanRefusals.length }}</span>
            <span class="name">{{ t('idn.d.otherRefusals') }}</span>
          </button>
        </div>
        <div class="identities-detail">
          <IdentityAgentDetail
            v-if="selected"
            :key="selected.agent"
            :agent="selected"
            :tokens="tokensOf(selected.agent)"
            :refusals="refusalsOf(selected.agent)"
            :busy="busy"
            @act="(what) => act(selected!, what)"
          />
          <template v-else-if="orphanRefusals.length">
            <div class="sub">{{ t('idn.d.otherRefusals') }}</div>
            <div v-for="(r, i) in orphanRefusals" :key="i" class="refusal-row" :data-code="r.code">
              <strong class="code bad">{{ r.code }}</strong>
              <span class="hint">{{ refusalText(r.code, lang) }} · {{ when(r.at) }}</span>
            </div>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<style>
.identities-panel {
  /* Centred and wide, down to the help bar: a list beside a detail, each scrolling on its own. */
  top: 64px;
  left: 50%;
  transform: translateX(-50%);
  width: min(1300px, calc(100vw - 32px));
  height: calc(100vh - 150px);
  z-index: 24;
}
.identities-panel .identities-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}
.identities-panel .identities-top .line {
  flex: 1;
}
.identities-panel .notice {
  margin: 4px 0;
}
.identities-panel .identities-split {
  display: flex;
  gap: 14px;
  flex: 1;
  min-height: 0;
}
.identities-panel .identities-list {
  flex: 0 0 240px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid #12323c;
  padding-right: 8px;
}
.identities-panel .identities-detail {
  flex: 1;
  min-width: 0;
  overflow: auto;
  padding-right: 8px;
}
.identities-panel .agent-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  background: none;
  border: 1px solid transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.identities-panel .agent-item:hover,
.identities-panel .agent-item.selected {
  border-color: var(--cyan);
  background: #0f3140;
}
.identities-panel .agent-item .name {
  flex: 1;
  font-weight: 700;
}
.identities-panel .agent-item .live {
  color: var(--green);
}
.identities-panel .state {
  display: inline-block;
  min-width: 80px;
  font-weight: 700;
}
.identities-panel .state.ready {
  color: var(--green);
}
.identities-panel .state.missing {
  color: var(--amber);
}
.identities-panel .state.revoked {
  color: var(--red);
}
@media (max-width: 900px) {
  .identities-panel .identities-split {
    flex-direction: column;
  }
  .identities-panel .identities-list {
    flex: none;
    max-height: 30vh;
    border-right: none;
    border-bottom: 1px solid #12323c;
  }
}
</style>
