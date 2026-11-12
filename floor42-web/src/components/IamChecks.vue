<script setup lang="ts">
// What IAM mode rests on, checked one by one: Keycloak, the sandbox, the agents' clients, this browser's sign-in and
// the saved settings. Each line says what was measured; nothing is assumed from the mode being on.
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { AgentIdentityView, IamStatusView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t } from '../i18n'

const props = defineProps<{ status: IamStatusView }>()

const agents = ref<AgentIdentityView[] | null>(null)
const sandbox = ref<boolean | null>(null)
const breakGlass = ref<boolean | null>(null)

onMounted(async () => {
  // The clients are read from Keycloak: the route refuses (409) unless IAM mode is up.
  const [a, s, sessions] = await Promise.allSettled([
    props.status.health === 'UP' ? request<AgentIdentityView[]>('GET', '/api/iam/agents') : Promise.reject(),
    api.status(),
    api.sessions(),
  ])
  agents.value = a.status === 'fulfilled' ? a.value : null
  sandbox.value = s.status === 'fulfilled' ? s.value.sandbox.working === true : null
  breakGlass.value = sessions.status === 'fulfilled' ? sessions.value.some((x) => x.current && x.breakGlass) : null
})

interface Check {
  key: string
  ok: boolean | null
  text: string
}

const checks = computed<Check[]>(() => {
  const s = props.status
  const at = s.lastCheck ? new Date(s.lastCheck).toLocaleTimeString(dateLocale()) : null
  const keycloak =
    s.health === 'UP'
      ? t('idn.chk.kcUp', { issuer: s.issuer })
      : s.health === 'UNREACHABLE'
        ? t('idn.chk.kcDown', { issuer: s.issuer, count: s.failedChecks })
        : s.code === 'IAM_API_CREDENTIAL_REFUSED' || s.code === 'IAM_API_SECRET_MISSING'
          ? t('idn.chk.apiRefused', { reason: s.reason ?? '' })
          : t('idn.chk.refused', { reason: s.reason ?? '', code: s.code ?? '' })
  const list: Check[] = [
    { key: 'kc', ok: s.health === 'UP', text: keycloak + (at ? ` ${t('idn.chk.at', { time: at })}` : '') },
    {
      key: 'sandbox',
      ok: sandbox.value,
      text: sandbox.value === null ? t('idn.chk.unknown') : sandbox.value ? t('idn.chk.sandboxUp') : t('idn.chk.sandboxDown'),
    },
  ]
  const a = agents.value
  const revoked = a?.filter((x) => x.revoked).length ?? 0
  const missing = a?.filter((x) => !x.revoked && (!x.clientId || !x.enabled)).length ?? 0
  list.push({
    key: 'agents',
    ok: a === null ? null : missing === 0,
    text:
      a === null
        ? t('idn.chk.agentsUnknown')
        : t('idn.chk.agents', { ready: a.length - revoked - missing, total: a.length, revoked, missing }),
  })
  list.push({
    key: 'session',
    ok: breakGlass.value === null ? null : !breakGlass.value,
    text: breakGlass.value === null ? t('idn.chk.unknown') : breakGlass.value ? t('idn.chk.breakGlass') : t('idn.chk.keycloak'),
  })
  list.push({ key: 'hours', ok: null, text: t('idn.chk.hours', { hours: s.maxTokenHours }) })
  if (s.restartNeeded) list.push({ key: 'restart', ok: false, text: t('idn.set.restartNeeded') })
  return list
})

const problems = computed(() => checks.value.filter((c) => c.ok === false).length)
</script>

<template>
  <div class="iam-checks" role="status">
    <div v-if="!status.enabled" class="iam-summary">{{ t('idn.chk.off') }}</div>
    <template v-else>
      <div class="iam-summary" :class="problems ? 'warn' : 'ok'">
        {{ problems ? t('idn.chk.problems', { count: problems }) : t('idn.chk.allUp') }}
      </div>
      <ul>
        <li v-for="c in checks" :key="c.key" :class="c.ok === false ? 'warn' : c.ok ? 'ok' : 'hint'">
          {{ c.ok === false ? '✗' : c.ok ? '✓' : '·' }} {{ c.text }}
        </li>
      </ul>
    </template>
  </div>
</template>

<style>
.iam-checks .iam-summary {
  font-weight: 700;
  margin: 4px 0;
}
.iam-checks ul {
  list-style: none;
  padding: 0;
  margin: 0 0 8px;
}
.iam-checks li {
  margin: 2px 0;
}
</style>
