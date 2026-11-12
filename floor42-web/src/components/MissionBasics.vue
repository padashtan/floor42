<script setup lang="ts">
import { computed } from 'vue'
import { MISSION_OBJECTIVE_MAX, MISSION_TITLE_MAX } from '../api/client'
import { t } from '../i18n'
import { suggestTitle } from '../ui/missionForm'

/** The title and the objective of a new mission; an empty title offers one drawn from the objective's first line. */
const title = defineModel<string>('title', { required: true })
const objective = defineModel<string>('objective', { required: true })
const props = defineProps<{ idea: boolean }>()
const objectiveKey = computed(() => (props.idea ? 'mission.fieldIdea' : 'mission.fieldObjective'))
const suggestion = computed(() => (title.value.trim() ? '' : suggestTitle(objective.value)))
</script>

<template>
  <label class="field">
    <span class="hint">{{ t('mission.labelTitle') }}</span>
    <input v-model="title" :placeholder="t('mission.fieldTitle')" required :maxlength="MISSION_TITLE_MAX" />
  </label>
  <div v-if="suggestion" class="hint suggestion">
    {{ t('mission.titleSuggest') }}
    <button type="button" class="link" :title="t('mission.titleUse')" @click="title = suggestion">{{ suggestion }}</button>
  </div>
  <label class="field">
    <span class="hint">{{ t(props.idea ? 'mission.labelIdea' : 'mission.labelObjective') }}</span>
    <textarea v-model="objective" rows="3" :maxlength="MISSION_OBJECTIVE_MAX" :placeholder="t(objectiveKey)" required />
  </label>
</template>

<style scoped>
.link {
  background: none;
  border: none;
  padding: 0;
  color: var(--cyan);
  text-decoration: underline;
  cursor: pointer;
  text-align: left;
  font: inherit;
}
</style>
