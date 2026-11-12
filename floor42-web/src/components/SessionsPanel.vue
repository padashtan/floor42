<script setup lang="ts">
import { resetAllDrafts } from '../state/drafts'
import { onMounted, ref } from 'vue'
import { api, ApiError } from '../api/client'
import type { DirectorSessionView, IssuedScriptToken, TokenScope } from '../api/types'
import { dateLocale, t } from '../i18n'
import { confirmed } from '../state/confirm'

const emit = defineEmits<{ close: [] }>()

const SCOPES: readonly TokenScope[] = ['read', 'missions:write', 'all']

const sessions = ref<DirectorSessionView[]>([])
const error = ref<string | null>(null)
const scope = ref<TokenScope>('read')
const days = ref(7)
const label = ref('')
const issued = ref<IssuedScriptToken | null>(null)
const copied = ref(false)

function when(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString(dateLocale(), { dateStyle: 'short', timeStyle: 'short' }) : ''
}

async function run(work: () => Promise<void>): Promise<void> {
  error.value = null
  try {
    await work()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : String(e)
  }
}

async function load(): Promise<void> {
  await run(async () => {
    sessions.value = await api.sessions()
  })
}

async function end(s: DirectorSessionView): Promise<void> {
  const message = s.kind === 'SCRIPT' ? t('sessions.revokeConfirm') : s.current ? t('sessions.endCurrentConfirm') : t('sessions.endConfirm')
  if (!(await confirmed({ message, confirmLabel: s.kind === 'SCRIPT' ? t('sessions.revoke') : t('sessions.end'), danger: true }))) return
  await run(async () => {
    await api.endSession(s.id)
    if (s.current) location.reload()
    else await load()
  })
}

async function issue(): Promise<void> {
  await run(async () => {
    copied.value = false
    issued.value = await api.issueToken(scope.value, days.value, label.value.trim() || null)
    label.value = ''
    await load()
  })
}

async function copy(): Promise<void> {
  if (!issued.value) return
  await navigator.clipboard.writeText(issued.value.token).catch(() => {})
  copied.value = true
}

async function signOut(): Promise<void> {
  await run(async () => {
    await api.signOut()
    resetAllDrafts()
    location.reload()
  })
}

async function signOutEverywhere(): Promise<void> {
  if (!(await confirmed({ message: t('sessions.everywhereConfirm'), danger: true }))) return
  await run(async () => {
    await api.signOutEverywhere()
    resetAllDrafts()
    location.reload()
  })
}

onMounted(load)
</script>

<template>
  <div class="panel sessions-panel framed">
    <div class="title">
      {{ t('sessions.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div class="hint">{{ t('sessions.intro') }}</div>
      <div v-if="error" class="error">{{ error }}</div>

      <div class="sub">{{ t('sessions.open') }}</div>
      <div v-if="sessions.length === 0" class="hint">{{ t('sessions.none') }}</div>
      <div v-for="s in sessions" :key="s.id" class="session-row">
        <div class="name">
          {{ t(`sessions.kind.${s.kind}`) }} {{ s.id }}
          <span v-if="s.label"> · {{ s.label }}</span>
          <span v-if="s.kind === 'SCRIPT'"> · {{ s.scope }}</span>
          <strong v-if="s.current"> · {{ t('sessions.current') }}</strong>
        </div>
        <div class="facts">
          {{ t('sessions.created', { at: when(s.created) }) }} · {{ t('sessions.lastUsed', { at: when(s.lastUsed) }) }}
          <template v-if="s.expires"> · {{ t('sessions.expires', { at: when(s.expires) }) }}</template>
          <template v-if="s.idleUntil"> · {{ t('sessions.idleUntil', { at: when(s.idleUntil) }) }}</template>
        </div>
        <div v-if="s.userAgent" class="facts muted">{{ s.userAgent }}</div>
        <button class="small" @click="end(s)">
          {{ s.kind === 'SCRIPT' ? t('sessions.revoke') : t('sessions.end') }}
        </button>
      </div>

      <div class="sub">{{ t('sessions.issue') }}</div>
      <div class="hint">{{ t('sessions.issueHint') }}</div>
      <form class="token-form" @submit.prevent="issue">
        <label>
          {{ t('sessions.scope') }}
          <select v-model="scope">
            <option v-for="s in SCOPES" :key="s" :value="s">{{ t(`sessions.scope.${s}`) }}</option>
          </select>
        </label>
        <label>
          {{ t('sessions.days') }}
          <input v-model.number="days" type="number" min="1" max="30" required />
        </label>
        <label>
          {{ t('sessions.label') }}
          <input v-model="label" type="text" maxlength="80" />
        </label>
        <button type="submit">{{ t('sessions.issueButton') }}</button>
      </form>
      <div v-if="issued" class="issued-token">
        <div class="hint">{{ t('sessions.shownOnce') }}</div>
        <code>{{ issued.token }}</code>
        <button class="small" @click="copy">{{ copied ? t('sessions.copied') : t('sessions.copy') }}</button>
      </div>

      <div class="sub"></div>
      <button :title="t('sessions.signOutTitle')" @click="signOut">{{ t('sessions.signOut') }}</button>
      <button class="danger" :title="t('sessions.signOutEverywhereTitle')" @click="signOutEverywhere">
        {{ t('sessions.signOutEverywhere') }}
      </button>
    </div>
  </div>
</template>
