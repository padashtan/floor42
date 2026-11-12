<script setup lang="ts">
import type { PluginPreview } from '../api/types'
import { t, tr } from '../i18n'
import { needsAcknowledge } from './pluginInstall'

/** The confirmation of a plugin install or removal: nothing runs before the director confirms it. */
defineProps<{ preview: PluginPreview; busy: boolean }>()
const acknowledged = defineModel<boolean>('acknowledged', { required: true })
const emit = defineEmits<{ confirm: []; cancel: [] }>()

function targetText(target: string): string {
  return tr(`plugins.target.${target}`, target)
}
</script>

<template>
  <div class="plugin-dialog">
    <div class="skills-group">
      {{ tr(`plugins.dialog.${preview.action}`, preview.action, { plugin: `${preview.plugin}@${preview.marketplace}` }) }} ·
      {{ targetText(preview.target) }}
    </div>
    <div v-if="preview.description">{{ preview.description }}</div>
    <table role="presentation" class="skills-table">
      <tbody>
        <tr><td>{{ t('plugins.source') }}</td><td><code>{{ preview.source ?? '?' }}</code></td></tr>
        <tr><td>{{ t('plugins.publisher') }}</td><td>{{ preview.publisher ?? t('plugins.notStated') }}</td></tr>
        <tr><td>{{ t('plugins.version') }}</td><td>{{ preview.version ?? t('plugins.versionAtInstall') }}</td></tr>
        <tr><td>{{ t('plugins.license') }}</td><td>{{ preview.license ?? t('plugins.notStated') }}</td></tr>
        <tr v-if="preview.contents">
          <td>{{ t('plugins.contents') }}</td>
          <td>
            <div>{{ t('plugins.skills', { list: preview.contents.skills.join(', ') || '—' }) }}</div>
            <div>{{ t('plugins.agents', { list: preview.contents.agents.join(', ') || '—' }) }}</div>
            <div v-if="preview.contents.commands.length">
              {{ t('plugins.commandsList', { list: preview.contents.commands.join(', ') }) }}
            </div>
            <div :class="preview.contents.hooks ? 'bad' : ''">
              {{ preview.contents.hooks ? t('plugins.hooksYes') : t('plugins.hooksNo') }}
            </div>
            <div :class="preview.contents.mcpServers ? 'bad' : ''">
              {{ preview.contents.mcpServers ? t('plugins.mcpYes') : t('plugins.mcpNo') }}
            </div>
            <div v-if="preview.contents.lspServers" class="bad">{{ t('plugins.lspYes') }}</div>
          </td>
        </tr>
      </tbody>
    </table>
    <div
      v-for="w in preview.warnings"
      :key="w"
      class="hint"
      :class="['HOOKS', 'MCP', 'LSP', 'CONTENTS_UNKNOWN'].includes(w) ? 'bad' : 'warn'"
    >
      {{ tr(`plugins.warning.${w}`, w) }}
    </div>
    <div v-if="preview.selectedBy.length" class="hint warn">
      {{ t('plugins.selectedBy', { missions: preview.selectedBy.join(', ') }) }}
    </div>
    <div class="hint">{{ t('plugins.exactCommands') }}</div>
    <pre class="sandbox-commands">{{ preview.commands.join('\n') }}</pre>
    <div v-if="preview.refused" class="hint bad">
      {{ tr(`plugins.refused.${preview.refused}`, preview.refused) }} {{ preview.refusal }}
    </div>
    <label v-else-if="needsAcknowledge(preview)" class="hint bad">
      <input v-model="acknowledged" type="checkbox" /> {{ t('plugins.acknowledge') }}
    </label>
    <div>
      <button
        class="action"
        type="button"
        :disabled="busy || !!preview.refused || (needsAcknowledge(preview) && !acknowledged)"
        @click="emit('confirm')"
      >
        {{ busy ? t('plugins.running') : tr(`plugins.confirm.${preview.action}`, preview.action) }}
      </button>
      <button class="action" type="button" :disabled="busy" @click="emit('cancel')">
        {{ t('plugins.cancel') }}
      </button>
    </div>
  </div>
</template>
