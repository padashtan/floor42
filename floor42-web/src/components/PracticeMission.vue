<script setup lang="ts">
// The guided first mission's own form (ADR-0045): Floor42's throwaway sample repository, then the director's title and
// objective. The mission is simulated: no model is called, nothing of the director's is touched.
import { ref } from 'vue'
import { api, ApiError } from '../api/client'
import { t } from '../i18n'
import { office, putOnBoard } from '../state/office'
import { send, tutorial } from '../state/tutorial'

const title = ref('')
const objective = ref('')
const busy = ref(false)

async function run(work: () => Promise<void>): Promise<void> {
  busy.value = true
  try {
    await work()
  } catch (e) {
    office.error = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}

const prepare = () =>
  run(async () => {
    tutorial.sample = (await api.prepareSample()).path
  })

const start = () =>
  run(async () => {
    const { mission } = await api.startPractice({ title: title.value, objective: objective.value })
    send({ kind: 'practiceStarted', mission })
    tutorial.decided = false
    await putOnBoard(mission)
  })
</script>

<template>
  <div class="practice-mission">
    <div class="sub">{{ t('tutorial.practice.title') }}</div>
    <div class="hint warn">{{ t('tutorial.practice.simulated') }}</div>
    <div class="sample">
      <button class="action" type="button" :disabled="busy" @click="prepare">{{ t('tutorial.practice.prepare') }}</button>
      <span v-if="tutorial.sample" class="hint">
        {{ t('tutorial.practice.ready') }} <code>{{ tutorial.sample }}</code>
      </span>
    </div>
    <form @submit.prevent="start">
      <input v-model="title" :placeholder="t('mission.fieldTitle')" :aria-label="t('mission.fieldTitle')" required />
      <textarea
        v-model="objective"
        rows="2"
        :placeholder="t('tutorial.practice.objective')"
        :aria-label="t('tutorial.practice.objective')"
        required
      />
      <button class="action recommended" type="submit" :disabled="busy || !tutorial.sample">
        {{ t('tutorial.practice.start') }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.practice-mission form {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
}
.practice-mission .sample {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.practice-mission code {
  word-break: break-all;
}
</style>
