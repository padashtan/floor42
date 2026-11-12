<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t, dateLocale } from '../i18n'
import { eventText } from '../i18n/events'
import { office } from '../state/office'

const emit = defineEmits<{ agent: [id: string]; question: [id: string] }>()

type Filter = 'ALL' | 'REAL' | 'SIMULATED'
const FILTER_KEY = 'floor42.log.filter'

function stored(): Filter {
  try {
    const v = localStorage.getItem(FILTER_KEY)
    return v === 'REAL' || v === 'SIMULATED' ? v : 'ALL'
  } catch {
    return 'ALL'
  }
}

/** A viewing filter only: every event stays in the journal and in the API. */
const filter = ref<Filter>(stored())
watch(filter, (f) => {
  try {
    localStorage.setItem(FILTER_KEY, f)
  } catch {
    // storage unavailable: the filter just is not remembered
  }
})

const lines = computed(() =>
  office.log.filter((l) => filter.value === 'ALL' || (filter.value === 'REAL') === (l.origin === 'REAL')).slice(0, 12),
)

function time(iso: string): string {
  return new Date(iso).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function open(line: { agent: string | null; question: string | null }) {
  if (line.question) emit('question', line.question)
  else if (line.agent) emit('agent', line.agent)
}
</script>

<template>
  <div class="panel log">
    <div class="title">
      {{ t('log.title') }}
      <span class="filters">
        <button v-for="f in ['ALL', 'REAL', 'SIMULATED'] as Filter[]" :key="f" :class="{ on: filter === f }" @click="filter = f">
          {{ t(`log.${f}`) }}
        </button>
      </span>
    </div>
    <div v-if="lines.length === 0" class="empty">
      {{ office.log.length ? t('log.noneOfOrigin') : t('log.waiting') }}
    </div>
    <div
      v-for="line in lines"
      :key="line.id"
      class="line"
      :class="{ important: line.important, clickable: line.agent || line.question }"
      @click="open(line)"
    >
      <span class="time">{{ time(line.at) }}</span>
      <span class="origin" :class="line.origin === 'REAL' ? 'real' : 'sim'">{{
        line.origin === 'REAL' ? t('log.REAL') : t('log.SIMULATED')
      }}</span>
      <span>{{ eventText(line.code, line.params) }}</span>
    </div>
  </div>
</template>
