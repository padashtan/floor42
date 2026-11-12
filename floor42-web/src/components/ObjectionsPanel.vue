<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api/client'
import type { HeldDeveloper } from '../api/types'
import { t } from '../i18n'
import { objectToDelivery } from '../state/objections'
import { office } from '../state/office'
import { agentName } from '../ui/ticketInfo'
import { MAX_OBJECTIONS, MAX_TEXT, NEW_WORK, type Row, checksOf, nextTarget, objectable, ready, sessionCounts } from '../ui/objections'

/**
 * The "Object" panel (ADR-0060), opened from the board beside "Accept the delivery": 1 to 10 objections, each a card
 * about a done ticket (it goes back to its producer, in its own worktree) or about new work, sent in one call after
 * the director has read what the round costs. The list scrolls inside the frame; the counts and Send stay in view.
 */
const emit = defineEmits<{ close: [] }>()

const mission = computed(() => office.mission)
const busy = ref(false)
const rows = ref<Row[]>([])
const held = ref<HeldDeveloper[]>([])
const list = ref<HTMLElement | null>(null)
const done = computed(() => objectable(mission.value?.tickets ?? [], mission.value?.committees))
const counts = computed(() => sessionCounts(rows.value, mission.value ? checksOf(mission.value) : 0))
let gone = false
onBeforeUnmount(() => (gone = true))

// A developer holding a finished ticket makes new work wait (the confirmation says so): read when the panel opens.
watch(
  () => mission.value?.id,
  async (id) => {
    held.value = []
    if (!id) return
    try {
      const answer = await api.held(id)
      if (!gone) held.value = answer
    } catch {
      /* the confirmation then simply does not mention a wait */
    }
  },
  { immediate: true },
)

async function add() {
  if (!mission.value || rows.value.length >= MAX_OBJECTIONS) return
  rows.value.push({ target: nextTarget(mission.value, rows.value), text: '' })
  await nextTick()
  const areas = list.value?.querySelectorAll('textarea')
  const last = areas?.item(areas.length - 1)
  last?.focus()
  last?.scrollIntoView?.({ block: 'nearest' })
}
void add()

async function send() {
  if (!mission.value) return
  busy.value = true
  try {
    if (await objectToDelivery(mission.value.id, rows.value, checksOf(mission.value), held.value)) emit('close')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="panel framed objections-panel">
    <div class="title">
      {{ t('objections.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="hint">{{ t('objections.hint') }}</div>
    <div ref="list" class="panel-scroll objection-list">
      <div v-for="(r, i) in rows" :key="i" class="objection">
        <label class="hint">
          {{ t('objections.target') }}
          <select v-model="r.target" :disabled="busy">
            <option :value="NEW_WORK">{{ t('objections.newWork') }}</option>
            <option v-for="x in done" :key="x.id" :value="x.id">
              {{ x.id }} · {{ x.title }}{{ x.assignee ? ` (${agentName(x.assignee)})` : '' }}
            </option>
          </select>
        </label>
        <button class="action remove" :disabled="busy" :title="t('objections.remove')" :aria-label="t('objections.remove')" @click="rows.splice(i, 1)">×</button>
        <textarea
          v-model="r.text"
          rows="4"
          :maxlength="MAX_TEXT"
          :disabled="busy"
          :aria-label="t('objections.text')"
          :placeholder="t('objections.text')"
        />
      </div>
    </div>
    <div class="objections-foot">
      <div class="row">
        <button class="action" :disabled="busy || rows.length >= MAX_OBJECTIONS" @click="add">{{ t('objections.add') }}</button>
        <span class="hint">{{ t('objections.count', { count: rows.length, max: MAX_OBJECTIONS }) }}</span>
        <span class="hint">{{ t('objections.sessions', counts) }}</span>
      </div>
      <div class="row">
        <button class="action recommended send-objections" :disabled="busy || !ready(rows)" @click="send">{{ t('objections.send') }}</button>
        <button class="action" :disabled="busy" @click="emit('close')">{{ t('objections.close') }}</button>
      </div>
    </div>
  </div>
</template>

<style>
.panel.objections-panel {
  top: 90px;
  left: 50%;
  right: auto;
  transform: translateX(-50%);
  width: min(720px, calc(100vw - 48px));
  max-height: calc(100vh - 170px);
  z-index: 24;
}
.objection-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 10px 0;
}
.objection {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px 8px;
  align-items: center;
  border: 1px solid var(--line);
  padding: 8px;
}
.objection label select {
  max-width: 100%;
}
.objection textarea {
  grid-column: 1 / -1;
  width: 100%;
  box-sizing: border-box;
  height: 96px;
  resize: vertical;
}
.objections-foot .row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
}
.objections-foot {
  border-top: 1px solid var(--line);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
</style>
