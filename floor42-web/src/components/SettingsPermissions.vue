<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { DefaultPermissionView, ToolDecision } from '../api/types'
import { t } from '../i18n'

/**
 * settings-redesign: the default permission table for new missions, moved out of the settings panel as it was. Its
 * writes go through the panel's `run`, so a permission and a setting are never written at the same time.
 */
const props = defineProps<{ busy: boolean; run: (work: () => Promise<void>) => Promise<void> }>()
const defaults = ref<DefaultPermissionView[]>([])
const DECISIONS: ToolDecision[] = ['ALLOW', 'ASK', 'ASK_ALWAYS', 'DENY']

async function loadDefaults() {
  try {
    defaults.value = await api.defaultPermissions()
  } catch {
    defaults.value = []
  }
}

function setDefault(tool: string, decision: ToolDecision | null) {
  return props.run(async () => {
    await api.setDefaultPermission(tool, decision)
    await loadDefaults()
  })
}

onMounted(loadDefaults)
</script>

<template>
  <div class="settings-block" data-setting="permissions">
    <div class="name">{{ t('defaults.title') }}</div>
    <div class="line">{{ t('defaults.hint') }}</div>
    <table class="defaults">
      <thead>
        <tr>
          <th>{{ t('sr.col.tool') }}</th>
          <th>{{ t('sr.col.decision') }}</th>
          <th>{{ t('sr.col.source') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="d in defaults" :key="d.tool" :class="{ changed: d.changed }">
          <td>
            <code>{{ d.tool }}</code>
          </td>
          <td>
            <select
              :value="d.decision"
              :disabled="busy"
              :aria-label="d.tool"
              @change="setDefault(d.tool, ($event.target as HTMLSelectElement).value as ToolDecision)"
            >
              <option v-for="o in DECISIONS" :key="o" :value="o">{{ t(`perm.${o}`) }}</option>
            </select>
          </td>
          <td class="facts">
            <template v-if="d.changed">
              <span class="tag changed">{{ t('settings.changed') }}</span>
              <button class="link" :disabled="busy" @click="setDefault(d.tool, null)">
                {{ t('defaults.reset', { value: t(`perm.${d.builtIn}`) }) }}
              </button>
            </template>
            <template v-else>{{ t('defaults.builtIn') }}</template>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
table.defaults {
  border-collapse: collapse;
  width: 100%;
  margin-top: 10px;
}
th {
  text-align: left;
  color: var(--muted);
  font-weight: 400;
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  padding: 4px 8px 4px 0;
}
td {
  padding: 5px 8px 5px 0;
  border-top: 1px solid #1d4a57;
}
tr.changed td:first-child {
  box-shadow: inset 3px 0 0 var(--amber);
  padding-left: 8px;
}
td select {
  font: inherit;
  padding: 4px 6px;
}
.facts {
  color: var(--muted);
  font-size: 13px;
}
.tag.changed {
  border: 1px solid var(--amber);
  color: var(--amber);
  padding: 0 6px;
  margin-right: 8px;
  font-size: 12px;
  text-transform: uppercase;
}
</style>
