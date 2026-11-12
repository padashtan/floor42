<script setup lang="ts">
import { watch } from 'vue'
import { t } from '../i18n'

/**
 * How the team works on a new mission (ADR-0062): the loop, or the task graph, which needs the planner. The choice is
 * pinned with the mission's workflow and never changes after.
 */
const props = defineProps<{ plan: boolean }>()
const mode = defineModel<'loop' | 'graph'>({ required: true })

// Without the planner there is no graph to draw: the choice falls back to the loop.
watch(
  () => props.plan,
  (plan) => {
    if (!plan) mode.value = 'loop'
  },
)
</script>

<template>
  <fieldset class="team-mode hint">
    <legend>{{ t('mission.mode.title') }}</legend>
    <label><input v-model="mode" type="radio" value="loop" /> {{ t('mission.mode.loop') }}</label>
    <label :title="plan ? '' : t('mission.mode.needsPlan')">
      <input v-model="mode" type="radio" value="graph" :disabled="!plan" /> {{ t('mission.mode.graph') }}
    </label>
    <div v-if="!plan" class="hint">{{ t('mission.mode.needsPlan') }}</div>
  </fieldset>
</template>

<style scoped>
.team-mode {
  border: 1px solid var(--line);
  margin: 4px 0;
  padding: 2px 8px 4px;
}
.team-mode label {
  display: block;
}
</style>
