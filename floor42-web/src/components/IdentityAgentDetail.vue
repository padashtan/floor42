<script setup lang="ts">
// Everything about one agent's identity, in reading order: its Keycloak client and buttons, the token its session
// holds now with its trust chain, its earlier tokens, its refusals, its exchanges with Keycloak, the export of its
// missions' identity events. The detail side of the Identities panel; tokens are decoded, never shown raw.
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AgentIdentityView, IdpExchangeView, RefusalView, TokenView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, lang, t } from '../i18n'
import { setPanel } from '../state/openPanels'
import { missionsOf, refusalText } from '../ui/identities'
import { clientState } from '../ui/identityLights'
import TokenCard from './TokenCard.vue'

const props = defineProps<{ agent: AgentIdentityView; tokens: TokenView[]; refusals: RefusalView[]; busy?: boolean }>()
const emit = defineEmits<{ act: [what: 'revoke' | 'restore' | 'rotate'] }>()

const when = (iso: string) => new Date(iso).toLocaleString(dateLocale())
const live = (x: TokenView) => !x.expiresAt || Date.parse(x.expiresAt) > Date.now()
const current = computed(() => props.tokens.filter(live).at(-1) ?? null)
const earlier = computed(() => props.tokens.filter((x) => x !== current.value).reverse())
const missions = computed(() => missionsOf(props.tokens))

// The exchanges whose form or answer names this agent's client: read with the panel, every 5 s.
const exchanges = ref<IdpExchangeView[]>([])
async function loadExchanges() {
  const id = props.agent.clientId
  if (!id) return void (exchanges.value = [])
  try {
    const all = await request<IdpExchangeView[]>('GET', '/api/iam/exchanges')
    if (props.agent.clientId !== id) return
    exchanges.value = all.filter((x) => x.request.client_id === id || x.answer['access_token.azp'] === id).slice(0, 8)
  } catch {
    if (props.agent.clientId === id) exchanges.value = []
  }
}
let timer = 0
onMounted(() => {
  void loadExchanges()
  timer = window.setInterval(loadExchanges, 5000)
})
onBeforeUnmount(() => window.clearInterval(timer))
watch(() => props.agent.agent, loadExchanges)
</script>

<template>
  <div class="identity-detail" :data-agent="agent.agent">
    <div class="detail-head">
      <span class="state" :class="clientState(agent)">{{ t(`idn.row.${clientState(agent)}`) }}</span>
      <span class="name">{{ agent.agent }}</span>
      <span class="facts hint">
        <span>{{ agent.clientId ? t('idn.client', { id: agent.clientId }) : t('idn.row.noClient') }}</span>
        <span>{{ agent.serviceAccountId ? t('idn.row.sa') : t('idn.row.noSa') }}</span>
      </span>
      <span class="row-actions">
        <button v-if="agent.revoked" class="small" :disabled="busy" @click="emit('act', 'restore')">{{ t('idn.restore') }}</button>
        <button v-else class="small" :disabled="busy" @click="emit('act', 'revoke')">{{ t('idn.revoke') }}</button>
        <button class="small" :disabled="busy" @click="emit('act', 'rotate')">{{ t('idn.rotate') }}</button>
      </span>
    </div>

    <div class="sub">{{ t('idn.d.current') }}</div>
    <TokenCard v-if="current" :token="current" />
    <div v-else class="hint">{{ t('idn.row.noToken') }}</div>

    <template v-if="earlier.length">
      <div class="sub">{{ t('idn.d.earlier', { count: earlier.length }) }}</div>
      <details v-for="x in earlier" :key="x.launch" class="earlier">
        <summary>{{ x.launch }} · {{ x.expiresAt ? t('idn.d.until', { until: when(x.expiresAt) }) : '' }}</summary>
        <TokenCard :token="x" />
      </details>
    </template>

    <div class="sub">{{ t('idn.refusals') }}</div>
    <div v-if="!refusals.length" class="hint">{{ t('idn.noRefusals') }}</div>
    <div v-for="(r, i) in refusals" :key="i" class="refusal-row" :data-code="r.code">
      <strong class="code bad">{{ r.code }}</strong>
      <span class="hint">{{ refusalText(r.code, lang) }} · {{ when(r.at) }}</span>
    </div>

    <div class="sub">{{ t('idx.title') }}</div>
    <div v-if="!exchanges.length" class="hint">{{ t('idx.none') }}</div>
    <div v-for="x in exchanges" :key="`${x.at}-${x.op}`" class="exchange-line" :class="x.status === 0 || x.status >= 400 ? 'bad' : 'ok'">
      <span>{{ new Date(x.at).toLocaleTimeString(dateLocale()) }}</span>
      <span>{{ x.status || t('idx.noAnswer') }}</span>
      <span>{{ x.op }}</span>
      <span class="hint">{{ x.millis }} ms</span>
    </div>
    <div class="panel-actions">
      <button class="action" @click="setPanel('iamExchanges', true)">{{ t('idx.open') }}</button>
      <a
        v-for="m in missions"
        :key="m"
        class="action export-link"
        :href="`/api/missions/${encodeURIComponent(m)}/identity-audit`"
        download
        :title="t('idn.exportTitle', { mission: m })"
        >{{ t('idn.export') }} · {{ m }}</a
      >
    </div>
  </div>
</template>

<style>
.identity-detail .detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #12323c;
}
.identity-detail .detail-head .name {
  font-weight: 700;
  font-size: 15px;
}
.identity-detail .facts {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 2px 14px;
}
.identity-detail .row-actions,
.identity-detail .panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.identity-detail .panel-actions {
  margin-top: 8px;
}
.identity-detail .sub {
  margin-top: 12px;
}
.identity-detail .earlier summary {
  cursor: pointer;
  padding: 3px 0;
}
.identity-detail .refusal-row {
  display: flex;
  gap: 10px;
  margin: 3px 0;
}
.identity-detail .exchange-line {
  display: grid;
  grid-template-columns: 90px 60px 1fr 70px;
  gap: 8px;
  padding: 2px 6px;
  border-left: 3px solid var(--green);
  margin: 2px 0;
}
.identity-detail .exchange-line.bad {
  border-left-color: var(--red);
}
</style>
