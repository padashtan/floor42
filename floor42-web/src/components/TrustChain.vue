<script setup lang="ts">
// The trust chain of one session, left to right: the director's sign-in, the delegation, the agent token, its process,
// the broker tokens; a refusal is a red link with its code. Read-only; no token is ever shown (jti and claims only).
import { onMounted, ref, watch } from 'vue'
import type { Link } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t, tr } from '../i18n'

const props = defineProps<{ launch: string; links?: Link[] }>()

const loaded = ref<Link[] | null>(null)
const failed = ref(false)

async function load() {
  if (props.links) return
  failed.value = false
  try {
    loaded.value = (await request<{ links: Link[] }>('GET', `/api/sessions/${encodeURIComponent(props.launch)}/trust-chain`)).links
  } catch {
    loaded.value = []
    failed.value = true
  }
}
onMounted(load)
watch(() => props.launch, load)

const shown = () => props.links ?? loaded.value
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString(dateLocale()) : '')
</script>

<template>
  <div class="trust-chain" :data-launch="launch">
    <div class="sub">{{ t('idn.trust') }}</div>
    <div v-if="shown() && !shown()!.length" class="hint">{{ t('idn.trustNone') }}</div>
    <ol v-else class="chain" :aria-label="t('idn.trust')">
      <li v-for="(link, i) in shown() ?? []" :key="i" class="link" :class="[link.kind, { refused: !link.ok }]" :data-kind="link.kind">
        <div class="kind">{{ tr(`idn.link.${link.kind}`, link.kind) }}</div>
        <div class="label">{{ link.label }}</div>
        <div v-if="link.detail" class="hint">{{ link.detail }}</div>
        <div v-if="!link.ok && link.code" class="code" role="alert">{{ link.code }}</div>
        <div v-if="link.at" class="hint">{{ when(link.at) }}</div>
      </li>
    </ol>
  </div>
</template>

<style>
.trust-chain .chain {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 6px;
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
}
.trust-chain .link {
  position: relative;
  min-width: 120px;
  max-width: 220px;
  padding: 6px 8px;
  border: 1px solid #1d4a57;
  border-left: 3px solid var(--cyan);
  overflow-wrap: anywhere;
}
.trust-chain .link:not(:last-child)::after {
  content: '→';
  position: absolute;
  right: -11px;
  top: 8px;
  color: var(--muted);
}
.trust-chain .link .kind {
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--muted);
}
.trust-chain .link.refused {
  border-color: #b3261e;
  border-left-color: #ff4d4d;
}
.trust-chain .link.refused .code {
  color: #ff4d4d;
  font-weight: 700;
}
</style>
