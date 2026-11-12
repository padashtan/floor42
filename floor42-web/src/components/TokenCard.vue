<script setup lang="ts">
// One agent token as the screen may show it: decoded header and claims, 8 characters of the signature, the chain line
// and the trust chain of its session. Never a token string.
import { computed } from 'vue'
import type { TokenView } from '../api/identity'
import { dateLocale, lang, t } from '../i18n'
import { chainText, decodeForDisplay } from '../ui/identities'
import TrustChain from './TrustChain.vue'

const props = defineProps<{ token: TokenView }>()

const shown = computed(() => decodeForDisplay(props.token.header, props.token.claims, props.token.signaturePrefix))
const hasClaims = computed(() => Object.keys(props.token.header).length > 0)
const expires = computed(() =>
  props.token.expiresAt ? new Date(props.token.expiresAt).toLocaleString(dateLocale(), { dateStyle: 'short', timeStyle: 'short' }) : '',
)
const pretty = (o: object) => JSON.stringify(o, null, 1)
</script>

<template>
  <div class="token-card" :data-session="token.session">
    <div class="name">
      {{ token.agent }}
      <span v-if="token.origin === 'SIMULATED' || shown.simulated" class="badge simulated">{{ t('idn.simulated') }}</span>
    </div>
    <div class="chain-line">{{ hasClaims ? chainText(shown, lang) : token.agent }}</div>
    <div class="hint">
      {{ t('idn.session', { id: token.session }) }}
      <template v-if="expires"> · {{ t('idn.expires', { at: expires }) }}</template> · jti {{ token.jti }}
    </div>
    <details v-if="hasClaims">
      <summary>{{ t('idn.header') }} · {{ t('idn.claims') }} · {{ t('idn.signature') }}</summary>
      <pre class="decoded">{{ pretty(shown.header) }}</pre>
      <pre class="decoded">{{ pretty(shown.claims) }}</pre>
      <code class="signature">{{ shown.signature }}</code>
    </details>
    <div v-else class="hint">{{ t('idn.noClaims') }}</div>
    <TrustChain :launch="token.launch" />
  </div>
</template>

<style>
.token-card {
  padding: 8px 10px;
  border: 1px solid #1d4a57;
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
}
.token-card .badge.simulated {
  border: 1px solid var(--muted);
  color: var(--muted);
  padding: 0 6px;
  font-size: 11px;
  letter-spacing: 0.05em;
}
.token-card .decoded {
  margin: 4px 0;
  max-height: 160px;
  overflow: auto;
  font-size: 12px;
}
</style>
