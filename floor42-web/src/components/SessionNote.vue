<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { IamStatusView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t } from '../i18n'
import { resetAllDrafts } from '../state/drafts'
import { clock, ending, sessionEnd } from '../ui/sessionTime'
import BarIcon from './BarIcon.vue'

/**
 * How this browser is signed in, in the top bar, with the way out. In IAM mode: Keycloak, or the console link
 * (break-glass), which cannot delegate. Out of IAM mode only the sign-out shows.
 */
const how = ref<'keycloak' | 'breakGlass' | null>(null)
const endMs = ref<number | null>(null)
const keycloakMs = ref<number | null>(null)
const now = ref(nowMs())
const warned = ref(false)

// Idle time is renewed by use: read the session again now and then, so the shown time follows the real one.
async function load(): Promise<void> {
  try {
    const [sessions, iam] = await Promise.all([api.sessions(), request<IamStatusView>('GET', '/api/iam/status')])
    const me = sessions.find((s) => s.current)
    endMs.value = me ? sessionEnd(me) : null
    keycloakMs.value = iam.enabled && me?.keycloakUntil ? Date.parse(me.keycloakUntil) : null
    if (!iam.enabled) how.value = null
    else how.value = sessions.some((s) => s.current && s.breakGlass) ? 'breakGlass' : 'keycloak'
  } catch {
    how.value = null
  }
  now.value = nowMs()
  if (endMs.value !== null && ending(endMs.value, now.value)) warned.value = true
}
const timer = window.setInterval(load, 60_000)
onMounted(load)
onBeforeUnmount(() => window.clearInterval(timer))

const time = (ms: number) => clock(ms, dateLocale())
const sessionEnding = computed(() => endMs.value !== null && ending(endMs.value, now.value))
const keycloakEnding = computed(() => keycloakMs.value !== null && ending(keycloakMs.value, now.value))
/** Signed in with Keycloak: one padlock chip with the first end to come; both ends in its tooltip. */
const chipEnd = computed(() => Math.min(...[endMs.value, keycloakMs.value].filter((x): x is number => x !== null)))
const chipTitle = computed(() =>
  [
    t('idn.top.keycloak'),
    keycloakMs.value !== null ? t('session.kcUntilTitle', { time: time(keycloakMs.value) }) : null,
    endMs.value !== null ? t('session.untilTitle', { time: time(endMs.value) }) : null,
  ]
    .filter(Boolean)
    .join('\n'),
)

// A refused sign-out keeps the drafts and the page, as in the sessions panel.
async function signOut(): Promise<void> {
  await api.signOut()
  resetAllDrafts()
  location.reload()
}
</script>

<template>
  <!-- Once, 5 minutes before the end; the next load after a use moves the end away and the warning goes. -->
  <div v-if="warned && sessionEnding && endMs !== null" class="session-toast" role="alert">
    {{ t('session.endingToast', { time: time(endMs) }) }}
    <button class="small" type="button" :aria-label="t('toast.close')" @click="warned = false">×</button>
  </div>
  <!-- Console link (break-glass): an open padlock that leads to the Keycloak sign-in, the reason in its tooltip. -->
  <a v-if="how === 'breakGlass'" class="id-chip warn keycloak-sign-in" href="/auth/keycloak" :title="t('iam.breakGlass')">
    <BarIcon name="unlock" />{{ t('iam.chip.signIn') }}
  </a>
  <span
    v-else-if="how === 'keycloak'"
    class="id-chip signed-in"
    :class="{ ending: sessionEnding || keycloakEnding }"
    role="status"
    :title="chipTitle"
  >
    <BarIcon name="lock" /><span class="visually-hidden">{{ chipTitle }}</span
    ><span v-if="Number.isFinite(chipEnd)" class="session-time">{{ time(chipEnd) }}</span>
  </span>
  <span
    v-else-if="endMs !== null"
    class="session-time shrink"
    :class="{ ending: sessionEnding }"
    :title="t('session.untilTitle', { time: time(endMs) })"
    >{{ t('session.until', { time: time(endMs) }) }}</span
  >
  <button class="sign-out" :title="t('sessions.signOutTitle')" @click="signOut">{{ t('sessions.signOut') }}</button>
</template>
