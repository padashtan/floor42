<script setup lang="ts">
import type { ProofStepView } from '../api/proof'
import { t } from '../i18n'
import { useCopy } from '../ui/clipboard'
import { expected, noCopy, stepNote } from '../ui/proof'

/**
 * One step of a recipe (ADR-0058): its title, its command as the producer wrote it with a copy button, what it must
 * show, and who runs it. The command is data: shown as text, never run from here; a command cut or holding hidden
 * characters gets no copy button.
 */
defineProps<{ step: ProofStepView }>()
const { copied, copy } = useCopy()
</script>

<template>
  <div class="prove-step" :class="{ director: step.runner === 'DIRECTOR', bad: step.problems.length > 0 }">
    <div class="prove-step-head">
      <b>{{ step.index }}. {{ step.title }}</b>
      <span class="hint">{{ stepNote(step) }}</span>
    </div>
    <div class="prove-cmd">
      <pre>{{ step.command }}</pre>
      <button v-if="!noCopy(step)" class="action" :title="t('prove.copyTitle')" @click="copy(step.command)">
        {{ copied === step.command ? t('prove.copied') : t('prove.copy') }}
      </button>
    </div>
    <div v-if="expected(step)" class="hint">{{ t('prove.expected') }} {{ expected(step) }}</div>
    <slot />
  </div>
</template>

<style scoped>
.prove-step {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 12px;
  background: #0a232c;
  border: 1px solid #1d4a57;
  border-left: 3px solid #2f7f94;
}
.prove-step.director {
  border-left-color: var(--amber);
}
.prove-step.bad {
  border-left-color: #c05050;
}
.prove-step-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: baseline;
}
.prove-cmd {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.prove-cmd pre {
  flex: 1;
}
</style>
