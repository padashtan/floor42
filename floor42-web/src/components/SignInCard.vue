<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { t } from '../i18n'
import { trapTab } from '../ui/trapTab'
import BarIcon from './BarIcon.vue'

/** Asks the daemon to open a single-use sign-in link in the browser (ADR-0034): the link never comes back here. */
const signIn = ref<'idle' | 'opened' | 'failed'>('idle')
/** IAM mode on: the daemon answers 204 here (404 when off) and the Keycloak sign-in shows. */
const keycloak = ref(false)
onMounted(async () => {
  keycloak.value = (await fetch('/auth/keycloak/enabled').catch(() => null))?.status === 204
})
const loginButton = ref<HTMLButtonElement>()
const keycloakLink = ref<HTMLAnchorElement>()
// The sign-in card is modal: the focus starts on its main action.
watch(loginButton, (b) => b?.focus())
watch(keycloakLink, (a) => a?.focus())
const box = ref<HTMLElement>()
function onKey(e: KeyboardEvent) {
  if (e.key === 'Tab') trapTab(box.value, e, 'a[href], button:not(:disabled)')
}
async function openSignIn() {
  const r = await fetch('/auth/open', { method: 'POST' }).catch(() => null)
  signIn.value = r?.status === 202 ? 'opened' : 'failed'
}
</script>

<template>
  <div class="confirm-scrim login-scrim"></div>
  <div ref="box" class="panel login" role="dialog" aria-modal="true" aria-labelledby="login-title" @keydown="onKey">
    <div id="login-title" class="title">{{ t('login.title') }}</div>
    <!-- IAM mode: Keycloak is the way in, the console link only the break-glass. -->
    <template v-if="keycloak">
      <a ref="keycloakLink" class="action primary keycloak-sign-in" href="/auth/keycloak">
        <BarIcon name="lock" />{{ t('iam.login.keycloak') }}
      </a>
      <button class="link" type="button" @click="openSignIn">{{ t('iam.login.consoleLink') }}</button>
    </template>
    <template v-else>
      <p>{{ t('d12.login.text') }}</p>
      <button ref="loginButton" @click="openSignIn">{{ t('d12.login.open') }}</button>
    </template>
    <p v-if="signIn === 'opened'" role="status">{{ t('d12.login.opened') }}</p>
    <p v-if="signIn === 'failed'" role="alert">{{ t('d12.login.failed') }}</p>
  </div>
</template>
