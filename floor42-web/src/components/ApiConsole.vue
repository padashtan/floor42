<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api/client'
import { EventStream, type StreamState } from '../api/eventStream'
import type { ApiCall, CallDetail } from '../api/types'
import { t, dateLocale } from '../i18n'
import { prettyJson } from '../ui/json'
import TerminalBlock from './TerminalBlock.vue'

const emit = defineEmits<{ close: [] }>()

const calls = ref<ApiCall[]>([])
const state = ref<StreamState>('connecting')
const paused = ref(false)
const filter = ref('')
const CALLERS: ApiCall['caller'][] = ['UI', 'script', 'agent hook', 'anonymous']
const callers = ref<Record<ApiCall['caller'], boolean>>({ UI: true, script: true, 'agent hook': true, anonymous: true })
const list = ref<HTMLElement | null>(null)
let stream: EventStream<ApiCall> | null = null

const shown = computed(() => {
  const text = filter.value.trim().toLowerCase()
  return calls.value.filter(
    (c) => callers.value[c.caller] && (!text || `${c.method} ${c.path} ${c.status}`.toLowerCase().includes(text)),
  )
})

function add(call: ApiCall) {
  if (calls.value.some((c) => c.sequence === call.sequence)) return
  calls.value.push(call)
  if (calls.value.length > 500) calls.value.splice(0, calls.value.length - 500)
  if (!paused.value) void nextTick(() => list.value?.scrollTo({ top: list.value.scrollHeight }))
}

// One call opened: its request and response bodies, fetched on demand (the daemon keeps them beside the call).
const opened = ref<number | null>(null)
const detail = ref<CallDetail | null>(null)
const detailError = ref<string | null>(null)

async function toggle(c: ApiCall) {
  if (opened.value === c.sequence) {
    opened.value = null
    return
  }
  opened.value = c.sequence
  detail.value = null
  detailError.value = null
  try {
    const d = await api.call(c.sequence)
    if (opened.value === c.sequence) detail.value = d
  } catch (e) {
    if (opened.value === c.sequence) detailError.value = e instanceof Error ? e.message : String(e)
  }
}

function time(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + '.' + String(d.getMilliseconds()).padStart(3, '0')
}

function statusClass(status: number): string {
  if (status >= 500) return 'bad'
  if (status >= 400) return 'warn'
  return 'ok'
}

onMounted(async () => {
  try {
    for (const c of await api.calls()) add(c)
  } catch {
    // The live feed still works; history is a convenience.
  }
  stream = new EventStream<ApiCall>(add, (s) => (state.value = s), () => {}, '/ws/api-calls')
  stream.open()
})

onUnmounted(() => stream?.close())
</script>

<template>
  <div class="panel api-console">
    <div class="title">
      {{ t('console.title') }}
      <span class="hint">&nbsp;· {{ state }} · {{ t('console.subtitle') }}</span>
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="row">
      <input v-model="filter" :placeholder="t('console.filter')" :aria-label="t('console.filter')" />
      <label v-for="who in CALLERS" :key="who" class="hint">
        <input v-model="callers[who]" type="checkbox" /> {{ t(`console.caller.${who}`) }}
      </label>
      <label class="hint"><input v-model="paused" type="checkbox" /> {{ t('console.pause') }}</label>
      <button class="action" @click="calls = []">{{ t('console.clear') }}</button>
      <a class="button" href="/q/swagger-ui" target="_blank" rel="noopener">{{ t('console.docs') }}</a>
    </div>
    <div ref="list" class="calls">
      <div v-if="shown.length === 0" class="hint">{{ t('console.none') }}</div>
      <template v-for="c in shown" :key="c.sequence">
        <div class="call" :class="{ opened: opened === c.sequence }" :title="t('console.open')" @click="toggle(c)">
          <span class="time">{{ time(c.at) }}</span>
          <span class="method" :class="c.method.toLowerCase()">{{ c.method }}</span>
          <span class="status" :class="statusClass(c.status)">{{ c.status }}</span>
          <span class="duration">{{ c.durationMs }} ms</span>
          <span class="caller">{{ t(`console.caller.${c.caller}`) }}</span>
          <span class="path">{{ c.path }}</span>
        </div>
        <div v-if="opened === c.sequence" class="call-detail">
          <div v-if="detailError" class="bad">{{ detailError }}</div>
          <div v-else-if="!detail" class="hint">{{ t('common.loading') }}</div>
          <template v-else>
            <div class="sub">{{ t('console.request') }}</div>
            <TerminalBlock v-if="detail.requestBody" :text="prettyJson(detail.requestBody)" :name="`call-${c.sequence}-request`" height="160px" />
            <div v-else class="hint">{{ t('console.noBody') }}</div>
            <div class="sub">{{ t('console.response') }}</div>
            <TerminalBlock v-if="detail.responseBody" :text="prettyJson(detail.responseBody)" :name="`call-${c.sequence}-response`" height="220px" />
            <div v-else class="hint">{{ t('console.noBody') }}</div>
            <div v-if="detail.truncated" class="warn">{{ t('console.truncated') }}</div>
          </template>
        </div>
      </template>
    </div>
    <div class="hint">{{ t('console.kept') }}</div>
  </div>
</template>
