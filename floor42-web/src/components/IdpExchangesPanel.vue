<script setup lang="ts">
// The exchanges between Floor42 and Keycloak, live: each request, its answer, its status and how long it took. What
// the daemon kept (in memory, the last 300, newest first); secret values show as "•••" and tokens never show.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { IdpExchangeView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t } from '../i18n'

const emit = defineEmits<{ close: [] }>()

const all = ref<IdpExchangeView[]>([])
const error = ref<string | null>(null)
const paused = ref(false)
const filter = ref('')
const open = ref<string | null>(null)

const shown = computed(() => {
  const f = filter.value.trim().toLowerCase()
  if (!f) return all.value
  return all.value.filter((x) => `${x.op} ${x.path} ${JSON.stringify(x.request)} ${JSON.stringify(x.answer)}`.toLowerCase().includes(f))
})

const keyOf = (x: IdpExchangeView) => `${x.at}-${x.op}-${x.path}`
const when = (iso: string) => new Date(iso).toLocaleTimeString(dateLocale(), { hour12: false }) + '.' + iso.slice(20, 23)
const tone = (x: IdpExchangeView) => (x.status === 0 || x.status >= 500 ? 'bad' : x.status >= 300 ? 'warn' : 'ok')

async function load() {
  if (paused.value) return
  try {
    all.value = await request<IdpExchangeView[]>('GET', '/api/iam/exchanges')
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

let timer = 0
onMounted(() => {
  void load()
  timer = window.setInterval(load, 2000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div class="panel idp-exchanges-panel framed">
    <div class="title">
      {{ t('idx.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="hint">{{ t('idx.intro') }}</div>
    <div class="row">
      <input v-model="filter" :placeholder="t('idx.filter')" :aria-label="t('idx.filter')" />
      <label class="hint"><input v-model="paused" type="checkbox" /> {{ t('idx.pause') }}</label>
      <span class="hint">{{ t('idx.count', { shown: shown.length, total: all.length }) }}</span>
    </div>
    <div v-if="error" class="error">{{ error }}</div>
    <div class="panel-scroll">
      <div v-if="!all.length" class="hint">{{ t('idx.none') }}</div>
      <div v-for="x in shown" :key="keyOf(x)" class="exchange" :class="tone(x)">
        <button class="line" :aria-expanded="open === keyOf(x)" @click="open = open === keyOf(x) ? null : keyOf(x)">
          <span class="at">{{ when(x.at) }}</span>
          <span class="status">{{ x.status || t('idx.noAnswer') }}</span>
          <span class="op">{{ x.op }}</span>
          <span class="path">{{ x.method }} {{ x.path }}</span>
          <span class="ms">{{ x.millis }} ms</span>
        </button>
        <div v-if="open === keyOf(x)" class="detail">
          <div class="side">
            <div class="sub">{{ t('idx.request') }}</div>
            <div v-if="!Object.keys(x.request).length" class="hint">{{ t('idx.noForm') }}</div>
            <div v-for="(v, k) in x.request" :key="k" class="kv"><code>{{ k }}</code> {{ v }}</div>
          </div>
          <div class="side">
            <div class="sub">{{ t('idx.answer') }}</div>
            <div v-if="x.error" class="error">{{ x.error }}</div>
            <div v-else-if="!Object.keys(x.answer).length" class="hint">{{ t('idx.noAnswerBody') }}</div>
            <div v-for="(v, k) in x.answer" :key="k" class="kv"><code>{{ k }}</code> {{ v }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.idp-exchanges-panel {
  top: 90px;
  left: 50%;
  transform: translateX(-50%);
  width: min(1100px, calc(100vw - 32px));
  max-height: calc(100vh - 170px);
  z-index: 25;
}
.idp-exchanges-panel .row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 6px 0;
}
.idp-exchanges-panel .exchange {
  border-left: 3px solid var(--green);
  margin: 2px 0;
}
.idp-exchanges-panel .exchange.warn {
  border-left-color: var(--amber);
}
.idp-exchanges-panel .exchange.bad {
  border-left-color: var(--red);
}
.idp-exchanges-panel .line {
  display: grid;
  grid-template-columns: 110px 70px 170px 1fr 70px;
  gap: 8px;
  width: 100%;
  text-align: left;
  background: none;
  border: none;
  color: inherit;
  font: inherit;
  padding: 3px 6px;
  cursor: pointer;
}
.idp-exchanges-panel .line:hover {
  background: rgba(255, 255, 255, 0.05);
}
.idp-exchanges-panel .path {
  overflow-wrap: anywhere;
  color: var(--muted);
}
.idp-exchanges-panel .detail {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 4px 12px 8px;
}
.idp-exchanges-panel .kv {
  overflow-wrap: anywhere;
}
.idp-exchanges-panel .kv code {
  display: inline-block;
  min-width: 150px;
  margin-right: 10px;
  color: var(--cyan);
}
</style>
