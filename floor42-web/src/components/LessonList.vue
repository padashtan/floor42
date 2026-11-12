<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { LessonView } from '../api/types'
import { t } from '../i18n'
import { defaultNote } from '../i18n/events'
import { why } from '../ui/why'
import { acceptLesson, office, rejectLesson, retireLesson } from '../state/office'

/**
 * Lessons drawn by retrospectives (ADR-0020). The chair proposes; you accept (reworded if you like), reject, or later
 * retire a lesson. Only accepted lessons reach the briefs of later missions, for the role they name.
 */
const props = defineProps<{ onlyProposed?: boolean }>()

const wording = reactive<Record<string, string>>({})
const proposed = computed(() => office.lessons.filter((l) => l.status === 'PROPOSED'))
const accepted = computed(() => office.lessons.filter((l) => l.status === 'ACCEPTED'))
const closed = computed(() => office.lessons.filter((l) => l.status === 'REJECTED' || l.status === 'RETIRED'))

function audience(l: LessonView): string {
  return l.audience ? t(`lessons.for.${l.audience}`) : t('lessons.forEveryone')
}

async function accept(l: LessonView) {
  if (await acceptLesson(l.id, (wording[l.id] ?? l.text).trim())) delete wording[l.id]
}
</script>

<template>
  <div class="lessons">
    <div class="sub">{{ t('lessons.toValidate') }}</div>
    <div v-if="!proposed.length" class="hint">{{ t('lessons.none') }}</div>
    <div v-for="l in proposed" :key="l.id" class="lesson">
      <div class="hint">
        {{ l.id }} · {{ t('lessons.fromRetro', { mission: l.mission }) }}<span v-if="l.proposedBy"> {{ t('lessons.by', { who: l.proposedBy }) }}</span> ·
        {{ audience(l) }}
      </div>
      <textarea
        :value="wording[l.id] ?? l.text"
        rows="2"
        maxlength="500"
        :aria-label="t('lessons.wording')"
        @input="wording[l.id] = ($event.target as HTMLTextAreaElement).value"
      />
      <div class="options">
        <button
          class="action recommended"
          :disabled="!(wording[l.id] ?? l.text).trim()"
          :title="why([!(wording[l.id] ?? l.text).trim(), t('why.needText')])"
          @click="accept(l)"
        >
          {{ t('lessons.accept') }}
        </button>
        <button class="action" @click="rejectLesson(l.id, defaultNote('note.lessonRejected'))">{{ t('lessons.reject') }}</button>
      </div>
    </div>

    <template v-if="!props.onlyProposed">
      <div class="sub">{{ t('lessons.inForce') }}</div>
      <div v-if="!accepted.length" class="hint">{{ t('lessons.noneInForce') }}</div>
      <div v-for="l in accepted" :key="l.id" class="lesson">
        <div>{{ l.text }}</div>
        <div class="hint">{{ l.id }} · {{ audience(l) }} · {{ t('lessons.from', { mission: l.mission }) }}</div>
        <div class="options">
          <button class="action" @click="retireLesson(l.id, defaultNote('note.lessonRetired'))">{{ t('lessons.retire') }}</button>
        </div>
      </div>

      <template v-if="closed.length">
        <div class="sub">{{ t('lessons.closed') }}</div>
        <div v-for="l in closed" :key="l.id" class="lesson closed">
          <div>{{ l.text }}</div>
          <div class="hint">{{ l.id }} · {{ t(`lessons.status.${l.status}`) }}: {{ l.reason }}</div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.lesson {
  margin: 8px 0 14px;
}
.lesson textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin: 4px 0 6px;
  font: inherit;
}
.lesson.closed {
  opacity: 0.7;
}
</style>
