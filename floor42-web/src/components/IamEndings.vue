<script setup lang="ts">
// In IAM mode: one toast 30 min before a delegation ends and one 10 min before an agent token ends. Screen only.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { DelegationView, TokenView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t } from '../i18n'
import { office } from '../state/office'
import { nowMs } from '../ui/clock'
import { iamOn } from '../ui/identities'
import { DELEGATION_LEAD_MS, TOKEN_LEAD_MS, type End, soonest, warnKey } from '../ui/iamEndings'
import { when } from '../ui/sessionTime'

const delegations = ref<End[]>([])
const tokens = ref<End[]>([])
const now = ref(nowMs())
const closed = ref<string[]>([])

// A failed call shows nothing: a Keycloak that is down must not raise a false warning.
async function load(): Promise<void> {
  if (!(await iamOn())) {
    delegations.value = []
    tokens.value = []
    return
  }
  const open = office.missions.filter((m) => !m.archived && m.status !== 'COMPLETED' && m.status !== 'CANCELLED')
  const ds = await Promise.all(
    open.map((m) =>
      request<DelegationView>('GET', `/api/iam/missions/${encodeURIComponent(m.id)}/delegation`)
        .then((d) => (d.revoked ? null : { id: m.title || m.id, endMs: Date.parse(d.expiresAt) }))
        .catch(() => null),
    ),
  )
  delegations.value = ds.filter((d): d is End => d !== null)
  const ts = await request<TokenView[]>('GET', '/api/iam/tokens').catch(() => [] as TokenView[])
  tokens.value = ts.filter((x) => x.expiresAt).map((x) => ({ id: x.agent, endMs: Date.parse(x.expiresAt as string) }))
  now.value = nowMs()
}
const timer = window.setInterval(load, 60_000)
onMounted(load)
onBeforeUnmount(() => window.clearInterval(timer))

/** One toast per kind: up to 3 names, then "+N". */
function group(kind: 'delegation' | 'token', ends: End[], lead: number) {
  const due = soonest(ends, now.value, lead).filter((e) => !closed.value.includes(warnKey(kind, e.id, e.endMs)))
  if (!due.length) return null
  const names = due.slice(0, 3).map((e) => e.id).join(', ') + (due.length > 3 ? ` +${due.length - 3}` : '')
  const text = kind === 'delegation' ? 'idn.deleg.endingToast' : 'idn.token.endingToast'
  return { kind, keys: due.map((e) => warnKey(kind, e.id, e.endMs)), text: t(text, { mission: names, agent: names, when: when(due[0].endMs, dateLocale(), now.value) }) }
}
const toasts = computed(() =>
  [group('delegation', delegations.value, DELEGATION_LEAD_MS), group('token', tokens.value, TOKEN_LEAD_MS)].filter((x) => x !== null),
)
</script>

<template>
  <div v-if="toasts.length" class="iam-toasts">
    <div v-for="x in toasts" :key="x.kind" class="session-toast" :class="`iam-${x.kind}`" role="alert">
      {{ x.text }}
      <button class="small" type="button" :aria-label="t('toast.close')" @click="closed.push(...x.keys)">×</button>
    </div>
  </div>
</template>
