<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { CoreDismissalEntry, CoreDismissalOutcome } from '../api/types'
import { t, tr } from '../i18n'
import { dismissAllButCore } from '../state/office'
import { confirmed, groupPreview, reasonKey, tally, type PreviewGroups } from '../ui/coreDismissal'

/**
 * "Dismiss everyone except the core members": a preview first (who leaves, who stays, who cannot leave now and why),
 * then the director confirms; the daemon dismisses each listed agent as a single dismissal would, and reports.
 */
const emit = defineEmits<{ close: [] }>()

const groups = ref<PreviewGroups | null>(null)
const outcomes = ref<CoreDismissalOutcome[] | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)
const reason = ref('')

async function load() {
  try {
    groups.value = groupPreview((await api.coreDismissalPreview()).agents)
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
onMounted(load)

const leaving = computed(() => (groups.value ? confirmed(groups.value) : []))

function why(name: string, code: string | null, fallback: string | null): string {
  const key = reasonKey(code)
  return key ? tr(key, fallback ?? '', { name }) : (fallback ?? '')
}

function blockedWhy(e: CoreDismissalEntry): string {
  return why(e.name, e.code, e.reason)
}

async function confirm() {
  busy.value = true
  try {
    const report = await dismissAllButCore(leaving.value, reason.value.trim() || t('core.defaultReason'))
    if (report) outcomes.value = report.outcomes
  } finally {
    busy.value = false
  }
}

const counts = computed(() => (outcomes.value ? tally(outcomes.value) : null))
</script>

<template>
  <div class="panel hire-panel framed core-dismissal">
    <div class="title">
      {{ t('core.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div v-if="error" class="bad">{{ error }}</div>

      <template v-if="outcomes && counts">
        <div class="sub">{{ t('core.report', counts) }}</div>
        <ul>
          <li v-for="o in outcomes" :key="o.id">
            <b>{{ o.name }}</b> ·
            <span :class="{ bad: o.result === 'REFUSED', hint: o.result === 'SKIPPED' }">{{ t(`core.result.${o.result}`) }}</span>
            <span v-if="o.handsBack.length" class="hint"> · {{ t('core.handsBack', { tickets: o.handsBack.join(', ') }) }}</span>
            <div v-if="o.result !== 'DISMISSED'" class="hint">{{ why(o.name, o.code, o.message) }}</div>
          </li>
        </ul>
        <button class="action" @click="emit('close')">{{ t('core.done') }}</button>
      </template>

      <template v-else-if="groups">
        <div class="hint">{{ t('core.intro') }}</div>

        <div class="sub">{{ t('core.leaving', { count: groups.leaving.length }) }}</div>
        <div v-if="!groups.leaving.length" class="hint">{{ t('core.nobodyLeaves') }}</div>
        <ul>
          <li v-for="e in groups.leaving" :key="e.id">
            <b>{{ e.name }}</b> · {{ e.role }}
            <span v-if="e.handsBack.length" class="hint"> · {{ t('core.handsBack', { tickets: e.handsBack.join(', ') }) }}</span>
          </li>
        </ul>

        <div class="sub">{{ t('core.staying', { count: groups.staying.length }) }}</div>
        <div v-if="!groups.staying.length" class="hint">{{ t('core.noCore') }}</div>
        <ul>
          <li v-for="e in groups.staying" :key="e.id"><span class="core-star">★</span> <b>{{ e.name }}</b> · {{ e.role }}</li>
        </ul>

        <div class="sub">{{ t('core.blocked', { count: groups.blocked.length }) }}</div>
        <ul>
          <li v-for="e in groups.blocked" :key="e.id">
            <b>{{ e.name }}</b> · {{ e.role }}
            <div class="hint">{{ blockedWhy(e) }}</div>
          </li>
        </ul>

        <label>{{ t('core.reason') }} <input v-model="reason" :placeholder="t('core.defaultReason')" maxlength="200" :aria-label="t('core.defaultReason')" /></label>
        <div class="profile-actions">
          <button
            class="action"
            :disabled="busy || !leaving.length"
            :title="!leaving.length ? t('core.nobodyLeaves') : ''"
            @click="confirm"
          >
            {{ t('core.confirm', { count: leaving.length }) }}
          </button>
          <button class="action" @click="load">{{ t('core.refresh') }}</button>
        </div>
      </template>
      <div v-else-if="!error" class="hint">…</div>
    </div>
  </div>
</template>

<style scoped>
.core-star {
  color: var(--amber);
}
.core-dismissal ul {
  margin: 4px 0 8px;
  padding-left: 18px;
}
</style>
