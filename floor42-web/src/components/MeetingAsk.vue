<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import type { MeetingView } from '../api/types'
import { t } from '../i18n'
import { workshopCall } from '../state/office'
import { defaultCap, moved, roundUnderWay } from '../ui/meetingTurns'
import { why } from '../ui/why'

/**
 * "Ask for their view" (ADR-0049): who speaks, in which order, about what, within a cap for the whole meeting that the
 * director sets before it starts. Paid, so nothing is asked without this click; the cap is shown with what was charged.
 */
const props = defineProps<{ meeting: MeetingView }>()
const emit = defineEmits<{ asked: [] }>()

const order = ref<string[]>(props.meeting.attendees.map((a) => a.id))
const chosen = ref<string[]>([...order.value])
const question = ref('')
const cap = ref(defaultCap(props.meeting, order.value.length))
const busy = ref(false)

watch(
  () => props.meeting.id,
  () => {
    order.value = props.meeting.attendees.map((a) => a.id)
    chosen.value = [...order.value]
    cap.value = defaultCap(props.meeting, order.value.length)
  },
)

const underWay = computed(() => roundUnderWay(props.meeting))
const speaking = computed(() => underWay.value?.turns.find((x) => x.state === 'SPEAKING')?.name ?? t('meetv.noneSpeaking'))
const nameOf = (id: string) => props.meeting.attendees.find((a) => a.id === id)?.name ?? id
const refused = computed(() =>
  why(
    [chosen.value.length === 0, t('meetv.speakers')],
    [!(cap.value > props.meeting.spentUsdCharged), t('meetv.cap')],
    [busy.value, '…'],
  ),
)

async function ask() {
  busy.value = true
  try {
    const speakers = order.value.filter((a) => chosen.value.includes(a))
    const done = await workshopCall(() => api.askForViews(props.meeting.id, speakers, cap.value, question.value.trim() || null))
    if (done) {
      question.value = ''
      emit('asked')
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="meeting-ask">
    <div class="sub">{{ t('meetv.askTitle') }}</div>
    <div class="hint">{{ t('meetv.spent', { usd: meeting.spentUsdCharged.toFixed(4) }) }}</div>
    <div v-if="underWay" class="hint under-way">{{ t('meetv.underWay', { n: underWay.number, who: speaking }) }}</div>
    <template v-else>
      <div class="hint">{{ t('meetv.askHint') }}</div>
      <div class="sub">{{ t('meetv.speakers') }}</div>
      <ol class="order">
        <li v-for="(id, i) in order" :key="id">
          <label class="check speaker"><input v-model="chosen" type="checkbox" :value="id" /> {{ nameOf(id) }}</label>
          <button class="action small" :disabled="i === 0" :title="t('meetv.earlier')" @click="order = moved(order, i, -1)">↑</button>
          <button class="action small" :disabled="i === order.length - 1" :title="t('meetv.later')" @click="order = moved(order, i, 1)">↓</button>
        </li>
      </ol>
      <label class="field">
        {{ t('meetv.question') }}
        <input v-model="question" class="meeting-question" maxlength="500" />
      </label>
      <label class="field">
        {{ t('meetv.cap') }}
        <input v-model.number="cap" class="meeting-cap" type="number" min="0.01" max="20" step="0.05" />
      </label>
      <div class="hint">{{ t('meetv.capHint') }}</div>
      <button class="action recommended meeting-ask-go" :disabled="!!refused" :title="refused" @click="ask">
        {{ t('meetv.ask') }}
      </button>
    </template>
  </div>
</template>

<style scoped>
.order {
  margin: 4px 0;
  padding-left: 18px;
}
.order li {
  display: flex;
  gap: 4px;
  align-items: center;
}
.speaker {
  flex: 1;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 6px 0;
}
.under-way {
  color: var(--amber);
}
</style>
