<script setup lang="ts">
import { plainText } from '../ui/ansi'
import type { PluginOutcome } from '../api/types'
import { t, tr } from '../i18n'

/** What a confirmed plugin install or removal did: each command run, its exit code and its output. */
defineProps<{ outcome: PluginOutcome }>()
const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="plugin-dialog">
    <div :class="outcome.outcome === 'INSTALLED' || outcome.outcome === 'REMOVED' ? '' : 'bad'">
      {{ tr(`plugins.outcome.${outcome.outcome}`, outcome.outcome) }}
      <span v-if="outcome.version"> · {{ outcome.version }}</span>
    </div>
    <div v-if="!outcome.commandsOk" class="hint warn">{{ t('plugins.commandsFailed') }}</div>
    <div v-for="(s, i) in outcome.steps" :key="i">
      <code>{{ s.command }}</code>
      <span class="facts">
        {{ s.timedOut ? t('plugins.timedOut') : t('plugins.exit', { code: s.exitCode ?? '?' }) }}
      </span>
      <pre class="sandbox-commands" :title="s.exitCode === -1 ? s.output : undefined">{{
        s.exitCode === -1 ? t('plugins.notStarted') : plainText(s.output) || t('plugins.noOutput')
      }}</pre>
    </div>
    <button class="action" type="button" @click="emit('dismiss')">{{ t('plugins.dismiss') }}</button>
  </div>
</template>
