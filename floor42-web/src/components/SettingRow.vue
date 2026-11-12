<script setup lang="ts">
import { computed } from 'vue'
import type { SettingView } from '../api/types'
import { t, tr, type Lang } from '../i18n'
import type { ThemeId } from '../scene/palette'
import { why } from '../ui/why'
import type { LinkedPanel } from './settingsSections'

/**
 * settings-redesign: one company setting. Its name and one line, the value in force and where it comes from (Floor42's
 * default or the director's change), the editor, and the long explanation folded. The draft is the panel's, so it
 * survives a change of section or a search; saving is the panel's too: the same call as before (changeSetting), the
 * server checks the value.
 */
const props = defineProps<{
  setting: SettingView
  draft?: string
  /** Why the daemon refused the last save, phrased; the draft is still in the editor. */
  refusal?: string
  busy: boolean
  editedIn?: LinkedPanel
}>()
const emit = defineEmits<{ save: [value: string | null]; open: [panel: LinkedPanel]; draft: [value: string] }>()

/** A plain string: a newer daemon may send a key this screen does not know. */
const key = computed<string>(() => props.setting.key)
/** What the editor shows: the unsaved draft, else the value in force. */
const shown = computed<string>({
  get: () => props.draft ?? props.setting.value,
  // A number field gives a number: the draft stays text, as the server reads it.
  set: (v: string | number) => emit('draft', String(v)),
})
/** Free texts have no meaningful default to quote. */
const quotesDefault = computed(() => key.value !== 'DIRECTOR_PROFILE' && key.value !== 'ACCEPTED_STREAM_LINES')
const unchanged = computed(() => shown.value === props.setting.value)
const inputId = computed(() => `setting-${key.value}`)
/** A key without texts (a newer daemon) shows its own name and no explanation. */
const label = computed(() => tr(`settings.${key.value}`, key.value))
const line = computed(() => tr(`sr.line.${key.value}`, ''))
const hint = computed(() => tr(`settings.${key.value}.hint`, ''))

function optionText(o: string): string {
  if (key.value === 'AGENT_LANGUAGE') return t(`lang.${o as Lang}`)
  if (key.value === 'OFFICE_THEME') return t(`theme.${o as ThemeId}`)
  return o
}
</script>

<template>
  <div
    class="setting-row"
    :class="{ changed: setting.changed, unsaved: !unchanged, refused: !!refusal, wide: key === 'DIRECTOR_PROFILE' }"
    :data-setting="key"
  >
    <div class="about">
      <label class="name" :for="editedIn ? undefined : inputId">{{ label }}</label>
      <div v-if="line" class="line">{{ line }}</div>
      <div class="source">
        <span v-if="setting.changed" class="tag changed">{{ t('settings.changed') }}</span>
        <span v-else class="tag">{{ t('sr.source.default') }}</span>
        <span v-if="quotesDefault && setting.changed" class="facts">{{ t('settings.default', { value: optionText(setting.defaultValue) }) }}</span>
        <span v-if="!unchanged" class="tag unsaved">{{ t('sr.unsaved') }}</span>
      </div>
      <details v-if="hint" class="more">
        <summary>{{ t('sr.more') }}</summary>
        <div class="hint">{{ hint }}</div>
      </details>
    </div>

    <div v-if="editedIn" class="edit">
      <span class="value">{{ setting.value }} USD</span>
      <button class="action" type="button" @click="emit('open', editedIn)">{{ t('sr.openCaps') }}</button>
    </div>
    <form v-else class="edit" @submit.prevent="emit('save', shown)">
      <select v-if="setting.options.length" :id="inputId" v-model="shown">
        <option v-for="o in setting.options" :key="o" :value="o">{{ optionText(o) }}</option>
      </select>
      <textarea v-else-if="key === 'DIRECTOR_PROFILE'" :id="inputId" v-model="shown" rows="5" maxlength="2000" />
      <input
        v-else-if="key === 'ACCEPTED_STREAM_LINES'"
        :id="inputId"
        v-model="shown"
        type="text"
        maxlength="2000"
        placeholder="system/new_thing, other_kind" aria-label="system/new_thing, other_kind" />
      <input v-else-if="key === 'MISSION_BUDGET_USD'" :id="inputId" v-model="shown" type="text" maxlength="12" placeholder="5 / none" aria-label="5 / none" />
      <input v-else-if="key === 'PLAN_LIMIT_THRESHOLD'" :id="inputId" v-model="shown" type="number" min="0.5" max="1" step="0.05" />
      <!-- A key this screen does not know (a newer daemon): plain text, the server checks the value. -->
      <input v-else :id="inputId" v-model="shown" type="text" maxlength="2000" />
      <div v-if="refusal" class="refusal" role="alert">{{ refusal }}</div>
      <div class="buttons">
        <button class="action" type="submit" :disabled="busy || unchanged" :title="why([unchanged, t('why.unchanged')])">
          {{ t('settings.save') }}
        </button>
        <button v-if="setting.changed" class="action" type="button" :disabled="busy" @click="emit('save', null)">
          {{ t('settings.reset') }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.setting-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 300px);
  gap: 6px 20px;
  padding: 12px 12px 12px 14px;
  border: 1px solid #1d4a57;
  border-left: 3px solid #1d4a57;
}
.setting-row.changed {
  border-left-color: var(--amber);
}
.setting-row.unsaved {
  border-left-style: dashed;
  border-left-color: var(--cyan);
}
.setting-row.refused {
  border-left-color: var(--red);
}
.refusal {
  color: var(--red);
  font-size: 13px;
  line-height: 1.4;
}
.setting-row.wide {
  grid-template-columns: minmax(0, 1fr);
}
.name {
  font-weight: 700;
  color: var(--text);
}
.line {
  color: var(--muted);
  margin-top: 2px;
}
.source {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 6px;
  font-size: 12px;
}
.tag {
  border: 1px solid #1d4a57;
  color: var(--muted);
  padding: 0 6px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.tag.changed {
  border-color: var(--amber);
  color: var(--amber);
}
.tag.unsaved {
  border-color: var(--cyan);
  color: var(--cyan);
}
.facts {
  color: var(--muted);
}
.more {
  margin-top: 6px;
  font-size: 13px;
}
.more summary {
  color: var(--cyan);
  cursor: pointer;
  width: fit-content;
}
.more .hint {
  margin-top: 4px;
  line-height: 1.4;
}
.edit {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-self: start;
  min-width: 0;
}
.edit select,
.edit input,
.edit textarea {
  font: inherit;
  font-size: 15px;
  padding: 6px 8px;
  width: 100%;
  box-sizing: border-box;
}
.value {
  font-family: 'Cascadia Mono', Consolas, monospace;
  padding: 6px 0;
}
.buttons {
  display: flex;
  flex-wrap: wrap;
}
.buttons .action,
.edit > .action {
  margin: 0 6px 0 0;
  align-self: flex-start;
}
</style>
