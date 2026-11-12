<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { UncommittedCheck } from '../api/types'
import { t } from '../i18n'
import { uncommittedLines } from '../ui/uncommitted'

/**
 * Work left uncommitted in the mission's worktrees, or a worktree Floor42 cannot read, said on the screen where the
 * director accepts the mission (ADR-0055). A check that fails says so: it is never shown as "all committed".
 */
const props = defineProps<{ mission: string }>()
const check = ref<UncommittedCheck | null>(null)
const failed = ref(false)
const lines = computed(() => (check.value ? uncommittedLines(check.value) : []))

async function load() {
  try {
    check.value = await api.uncommitted(props.mission)
    failed.value = false
  } catch {
    // Handled: the check could not be read; the block says so instead of staying silent.
    check.value = null
    failed.value = true
  }
}

onMounted(load)
watch(() => props.mission, load)
defineExpose({ load })
</script>

<template>
  <div v-if="failed" class="uncommitted warn" role="alert">{{ t('uncommitted.cannotCheck') }}</div>
  <div v-else-if="lines.length" class="uncommitted warn" role="alert">
    <b>{{ t('uncommitted.title') }}</b>
    <div class="hint">{{ t('uncommitted.hint') }}</div>
    <ul>
      <li v-for="(l, i) in lines" :key="i">{{ l }}</li>
    </ul>
  </div>
</template>

<style scoped>
.uncommitted {
  margin: 6px 0;
  color: #e0a040;
}
.uncommitted ul {
  margin: 2px 0;
  padding-left: 1.2em;
}
</style>
