<script setup lang="ts">
import { plainText } from '../ui/ansi'
import type { ProofView } from '../api/proof'
import { t, tr } from '../i18n'
import { resultOf } from '../ui/proof'
import { clock } from '../ui/when'
import ProveCommand from './ProveCommand.vue'

/**
 * Floor42's run (ADR-0058): the button, then each step Floor42 can run with what it measured in the sandbox, network
 * off, on a copy of the worktree: exit code, duration, outcome and the end of the output, as journaled.
 */
defineProps<{ view: ProofView; acting: boolean }>()
const emit = defineEmits<{ run: [] }>()
/** "REASON: path" in the screen's language; the path stays as git wrote it. */
const skipLine = (s: string) => {
  const at = s.indexOf(': ')
  const reason = s.slice(0, at)
  return `${tr(`prove.skip.${reason}`, reason)}: ${s.slice(at + 2)}`
}
</script>

<template>
  <div class="prove-section">
    <div class="prove-bar">
      <button class="action primary" :disabled="acting || view.running || !view.sandboxWorks" @click="emit('run')">
        {{ view.running ? t('prove.running') : t('prove.run') }}
      </button>
      <span class="hint">{{ view.sandboxWorks ? t('prove.runHint') : t('prove.noSandbox') }}</span>
    </div>
    <div class="hint">{{ t('prove.recorded') }}</div>
    <div v-if="view.runError" class="warn" role="alert">{{ t('prove.runError', { code: view.runError }) }}</div>
    <template v-for="w in view.worktrees" :key="w.agent">
      <h4>{{ w.agent }}</h4>
      <details v-if="w.skippedCount" class="warn">
        <summary>{{ t('prove.skipped', { count: w.skippedCount }) }}</summary>
        <ul>
          <li v-for="s in w.skipped" :key="s">{{ skipLine(s) }}</li>
        </ul>
      </details>
      <div v-if="!w.steps.some((s) => s.runner === 'FLOOR42')" class="hint">{{ t('prove.noFloor42Steps') }}</div>
      <div v-else-if="!w.lastRun" class="hint">{{ t('prove.noRun') }}</div>
      <div v-else class="hint">
        {{ t('prove.ranAt', { time: clock(w.lastRun.at), head: w.lastRun.head.slice(0, 8) }) }}
        <span v-if="w.lastRun.stale" class="warn">{{ t('prove.stale') }}</span>
      </div>
      <ProveCommand v-for="s in w.steps.filter((x) => x.runner === 'FLOOR42')" :key="s.index" :step="s">
        <template v-for="r in [resultOf(w, s.index)]" :key="r?.outcome ?? s.index">
          <template v-if="r">
            <div :class="['prove-outcome', r.outcome]">
              {{ tr(`prove.outcome.${r.outcome}`, r.outcome) }}
              <span v-if="r.failure">· {{ tr(`prove.failure.${r.failure}`, r.failure) }}</span>
              <span v-if="r.exitCode !== null">· {{ t('prove.exit', { code: r.exitCode }) }}</span>
              <span>· {{ t('prove.duration', { seconds: (r.durationMs / 1000).toFixed(1) }) }}</span>
              <span class="hint">· {{ t('prove.measured') }}</span>
            </div>
            <pre v-if="r.output" class="prove-output">{{ plainText(r.output) }}</pre>
          </template>
        </template>
      </ProveCommand>
    </template>
  </div>
</template>

<style scoped>
.prove-bar {
  display: flex;
  gap: 12px;
  align-items: center;
}
.prove-output {
  max-height: 220px;
  overflow: auto;
}
</style>
