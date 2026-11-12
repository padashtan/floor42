<script setup lang="ts">
// The tutorial's last step (ADR-0045): what a real mission needs, each item as the daemon reports it now. Opening a
// panel is the director's click; nothing here changes a setting.
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import { t } from '../i18n'
import { setPanel } from '../state/openPanels'
import { office } from '../state/office'
import { defaultCap, usd } from '../ui/costs'

/** undefined while the settings are read; null for no default cap. */
const cap = ref<number | null | undefined>(undefined)
onMounted(async () => {
  try {
    cap.value = defaultCap((await api.settings()).find((s) => s.key === 'MISSION_BUDGET_USD')?.value)
  } catch {
    cap.value = null
  }
})

const runtime = computed(() => office.status?.runtime ?? null)
const sandbox = computed(() => office.status?.sandbox ?? null)
const mark = (ok: boolean | null | undefined) => (ok ? '✓' : ok === false ? '✗' : '…')
</script>

<template>
  <ul class="checklist">
    <li :class="{ ok: runtime?.available }">
      <span class="mark" aria-hidden="true">{{ mark(runtime?.available) }}</span>
      {{ runtime?.available ? t('tutorial.real.claudeOk', { version: runtime.version ?? '' }) : t('tutorial.real.claudeMissing', { reason: runtime?.reason ?? t('top.unknown') }) }}
    </li>
    <li :class="{ ok: sandbox?.working }">
      <span class="mark" aria-hidden="true">{{ mark(sandbox?.enabled ? sandbox.working : false) }}</span>
      {{ sandbox?.enabled && sandbox.working ? t('tutorial.real.sandboxOk') : t('tutorial.real.sandboxCheck') }}
      <button class="action" type="button" @click="setPanel('sandbox', true)">{{ t('sandbox.top') }}</button>
    </li>
    <li :class="{ ok: cap !== null && cap !== undefined }">
      <span class="mark" aria-hidden="true">{{ mark(cap === undefined ? undefined : cap !== null) }}</span>
      {{ cap ? t('tutorial.real.capDefault', { usd: usd(cap) }) : t('tutorial.real.capNone') }}
      <button class="action" type="button" @click="setPanel('settings', true)">{{ t('top.settings') }}</button>
    </li>
  </ul>
</template>

<style scoped>
.checklist {
  list-style: none;
  margin: 6px 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.checklist li button {
  margin-left: 4px;
}
.mark {
  display: inline-block;
  width: 1.2em;
  color: var(--amber);
}
.ok .mark {
  color: var(--green, #6c6);
}
</style>
