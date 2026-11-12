<script setup lang="ts">
import { computed } from 'vue'
import type { InitPreview } from '../api/types'
import { t } from '../i18n'
import { namedPaths } from '../ui/missionForm'
import ProjectPicker from './ProjectPicker.vue'
import SandboxReadiness from './SandboxReadiness.vue'

/**
 * The project of a new mission, with its two explicit choices made before the folder: work on a repository that
 * already exists, or create a new empty project.
 */
const repository = defineModel<string>('repository', { required: true })
const preview = defineModel<InitPreview | null>('preview', { required: true })
const kind = defineModel<'existing' | 'new'>('kind', { required: true })
const props = defineProps<{ objective: string; optional: boolean }>()

/** True when creating the mission would initialise the chosen folder as a new repository. */
const creates = computed(() => !!preview.value && !preview.value.refused)
const missing = computed(() => (creates.value && (preview.value?.files ?? 0) === 0 ? namedPaths(props.objective) : []))
</script>

<template>
  <fieldset class="project">
    <legend class="hint">{{ t('mission.labelProject') }}</legend>
    <label class="hint"><input v-model="kind" type="radio" value="existing" /> {{ t('mission.kindExisting') }}</label>
    <label class="hint"><input v-model="kind" type="radio" value="new" /> {{ t('mission.kindNew') }}</label>
    <ProjectPicker
      v-model="repository"
      v-model:preview="preview"
      :placeholder="t(kind === 'new' ? 'mission.fieldNewFolder' : props.optional ? 'mission.fieldRepositoryOptional' : 'mission.fieldRepository')"
      :required="!props.optional"
    />
    <SandboxReadiness v-if="repository" :project="repository" />
    <div v-if="missing.length" class="warn notice missing-files">{{ t('mission.pathsMissing', { files: missing.join(', ') }) }}</div>
  </fieldset>
</template>

<style scoped>
.project {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: none;
  padding: 0;
  margin: 4px 0 0;
}
.warn {
  color: #e0a040;
}
</style>
