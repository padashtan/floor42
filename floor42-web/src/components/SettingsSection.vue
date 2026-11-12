<script setup lang="ts">
import type { SettingView } from '../api/types'
import { lang, setLang, t, type Lang } from '../i18n'
import { restartTutorial, skipTutorial, tutorial } from '../state/tutorial'
import BackupsSection from './BackupsSection.vue'
import IamSettings from './IamSettings.vue'
import UnsafeModeSettings from './UnsafeModeSettings.vue'
import SettingRow from './SettingRow.vue'
import SettingsPermissions from './SettingsPermissions.vue'
import type { SettingsDrafts } from './settingsDrafts'
import { itemId, type LinkedPanel, type SettingKey, type SettingsSection } from './settingsSections'

/** settings-redesign: one section of the settings panel: its name, one line, and its settings in their order. */
defineProps<{
  section: SettingsSection
  settings: ReadonlyMap<string, SettingView>
  drafts: SettingsDrafts
  busy: boolean
  run: (work: () => Promise<void>) => Promise<void>
}>()
const emit = defineEmits<{
  save: [key: SettingKey, value: string | null]
  draft: [key: SettingKey, value: string]
  open: [panel: LinkedPanel]
}>()

const LANGS: Lang[] = ['en', 'fr']
</script>

<template>
  <section class="settings-section" :data-section="section.id" :aria-labelledby="`settings-h-${section.id}`">
    <h3 :id="`settings-h-${section.id}`" class="section-name">{{ t(`sr.s.${section.id}`) }}</h3>
    <div class="section-line">{{ t(`sr.d.${section.id}`) }}</div>
    <template v-for="item in section.items" :key="itemId(item)">
      <template v-if="item.kind === 'setting'">
        <SettingRow
          v-if="settings.get(item.key)"
          :setting="settings.get(item.key)!"
          :draft="drafts.values[item.key]"
          :refusal="drafts.refusals[item.key]"
          :busy="busy"
          :edited-in="item.editedIn"
          @save="(v) => emit('save', item.key, v)"
          @draft="(v) => emit('draft', item.key, v)"
          @open="(p) => emit('open', p)"
        />
      </template>

      <div v-else-if="item.kind === 'language'" class="settings-block" data-setting="language">
        <div class="name" id="settings-lang">{{ t('settings.language') }}</div>
        <div class="line">{{ t('settings.languageHint') }}</div>
        <div class="choices" role="radiogroup" aria-labelledby="settings-lang">
          <label v-for="l in LANGS" :key="l" class="choice" :class="{ on: lang === l }">
            <input type="radio" name="settings-lang" :checked="lang === l" @change="setLang(l)" /> {{ t(`lang.${l}`) }}
          </label>
        </div>
        <div class="source">
          <span class="tag">{{ t('sr.source.browser') }}</span>
        </div>
      </div>

      <div v-else-if="item.kind === 'tutorial'" class="settings-block" data-setting="tutorial">
        <div class="name">{{ t('tutorial.title') }}</div>
        <div class="line">{{ t(`tutorial.state.${tutorial.progress.kind}`) }}</div>
        <div class="choices">
          <button class="action" type="button" @click="restartTutorial">{{ t('tutorial.restart') }}</button>
          <button class="action" type="button" :disabled="tutorial.progress.kind !== 'running'" @click="skipTutorial">
            {{ t('tutorial.skip') }}
          </button>
        </div>
        <div class="source">
          <span class="tag">{{ t('sr.source.browser') }}</span>
        </div>
      </div>

      <SettingsPermissions v-else-if="item.kind === 'permissions'" :busy="busy" :run="run" />
      <BackupsSection v-else-if="item.kind === 'backups'" :locked="busy" :lock="run" />
      <IamSettings v-else-if="item.kind === 'iam'" />
      <UnsafeModeSettings v-else-if="item.kind === 'unsafe'" />

      <div v-else class="settings-block linked" :data-setting="itemId(item)">
        <div>
          <div class="name">{{ t(`sr.panel.${item.panel}`) }}</div>
          <div class="line">{{ t(`sr.panel.${item.panel}.line`) }}</div>
        </div>
        <button class="action" type="button" @click="emit('open', item.panel)">{{ t('sr.open') }}</button>
      </div>
    </template>
  </section>
</template>

<style>
/* settings-redesign: shared by every line of a section (the rows, the permission table, the backups). */
.settings-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.settings-section .section-name {
  margin: 0;
  color: var(--cyan);
  font-size: 13px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.settings-section .section-line {
  color: var(--muted);
  margin-top: -6px;
}
.settings-block {
  padding: 12px 12px 12px 14px;
  border: 1px solid #1d4a57;
  border-left: 3px solid #1d4a57;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.settings-block.linked {
  flex-direction: row;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
}
.settings-block.linked .action {
  margin: 0;
  flex: none;
}
.settings-block .name {
  font-weight: 700;
}
.settings-block .line {
  color: var(--muted);
  line-height: 1.4;
}
.settings-block th {
  text-align: left;
}
.settings-block .source {
  font-size: 12px;
}
.settings-block .tag {
  border: 1px solid #1d4a57;
  color: var(--muted);
  padding: 0 6px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
</style>
