<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { SettingView } from '../api/types'
import { t } from '../i18n'
import { confirming } from '../state/confirm'
import { trySetting } from '../state/office'
import { openedAfter, panels, setPanel } from '../state/openPanels'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'
import SettingsSection from './SettingsSection.vue'
import { prune, saveSetting, setDraft, settingsDrafts } from './settingsDrafts'
import {
  filterSections,
  type LinkedPanel,
  type SectionCounts,
  searchText,
  SECTION_IDS,
  SECTIONS,
  type SectionId,
  type SettingKey,
  type SettingsItem,
  withUnfiled,
} from './settingsSections'

/**
 * The screen's language (this browser only) and the company settings (journaled as the director's decisions, applied
 * from the next session on). settings-redesign: one section at a time, chosen on the left, or every match of a search.
 */
const emit = defineEmits<{ close: [] }>()

const LINKED: LinkedPanel[] = ['caps', 'sandbox', 'sessions']
const settings = ref<SettingView[] | null>(null)
/** Why the settings could not be read, or empty. */
const loadError = ref('')
/** One write at a time: settings, default permissions and backups alike. */
const busy = ref(false)
/** The director's unsaved values and refusals, by key: they outlive a change of section, a search and the panel. */
const drafts = settingsDrafts
const current = ref<SectionId>('general')
const query = ref('')
/**
 * Where the focus was when the panel opened, read before the section list takes it: it goes back there on close.
 */
const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null

const byKey = computed(() => new Map((settings.value ?? []).map((s) => [s.key as string, s])))
const sections = computed(() => withUnfiled(SECTIONS, (settings.value ?? []).map((s) => s.key)))
const found = computed(() => filterSections(sections.value, query.value, textOf))

function keysIn(id: SectionId): string[] {
  const s = sections.value.find((x) => x.id === id)
  return (s?.items ?? []).flatMap((i) => (i.kind === 'setting' ? [i.key] : []))
}
/** Per section: the search's matches, the settings the director changed, and those they left unsaved. */
const counts = computed(
  () =>
    Object.fromEntries(
      SECTION_IDS.map((id) => [
        id,
        {
          matches: found.value.find((s) => s.id === id)?.items.length ?? 0,
          changed: keysIn(id).filter((k) => byKey.value.get(k)?.changed).length,
          // Only the rows on screen: a draft of a setting that could not be read is not counted.
          unsaved: keysIn(id).filter((k) => k in drafts.values && byKey.value.has(k)).length,
        },
      ]),
    ) as Record<SectionId, SectionCounts>,
)

/** The section list: beside each, its matches while searching, else a mark when a value is unsaved, else the changes. */
const tabs = computed<SectionTab[]>(() =>
  SECTION_IDS.map((id) => {
    const c = counts.value[id]
    const badge = c.unsaved
      ? { text: t('sr.unsavedMark'), title: t('sr.unsavedCount', { n: c.unsaved }), tone: 'unsaved' as const }
      : c.changed
        ? { text: String(c.changed), title: t('sr.changedCount', { n: c.changed }), tone: 'changed' as const }
        : null
    return { id, label: t(`sr.s.${id}`), matches: c.matches, badge }
  }),
)

function textOf(item: SettingsItem): string {
  return searchText(item, item.kind === 'setting' ? byKey.value.get(item.key)?.value : '')
}

async function load() {
  try {
    settings.value = await api.settings()
    loadError.value = ''
    prune(drafts, settings.value)
  } catch (e) {
    // The rows read last stay as they were, with their drafts; the error says the values may be old.
    loadError.value = t('sr.loadFailed', { reason: e instanceof Error ? e.message : String(e) })
  }
}

async function run(work: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try {
    await work()
  } finally {
    busy.value = false
  }
}

function save(key: SettingKey, value: string | null) {
  return run(async () => {
    // Refused: the draft stays and the refusal shows beside it.
    if (await saveSetting(drafts, key, value, trySetting)) await load()
  })
}

const draft = (key: SettingKey, value: string) => setDraft(drafts, key, value, byKey.value.get(key)?.value)

const open = (panel: LinkedPanel) => setPanel(panel, true)

/**
 * A panel opened from here (Caps, Sandbox, Sessions) sits above the settings: Escape closes it first. Caught before
 * App.vue's own Escape, which closes the settings first; the palette and the menus keep their Escape.
 */
function onEscape(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  // An open confirmation is the top layer: its Escape cancels it and nothing else.
  if (confirming()) return
  // The guide's card handles its own Escape first when it has the focus (it hides the guide, nothing else).
  if (e.target instanceof Element && e.target.closest('.palette, .bar-dropdown, .director-terminal, .tutorial-card')) return
  const above = LINKED.find((p) => openedAfter(p, 'settings'))
  if (!above) return
  e.stopPropagation()
  setPanel(above, false)
}

// The session cap is changed in Caps: read it again when Caps closes.
watch(
  () => panels.caps,
  (open, was) => {
    if (was && !open) void load()
  },
)

onMounted(() => {
  window.addEventListener('keydown', onEscape, true)
  void load()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onEscape, true)
  // The menu item or the palette that opened the panel is gone: the System menu button stands for it.
  const kept = opener && opener.isConnected && opener !== document.body && !opener.closest('.settings-panel')
  const back = kept ? opener : document.querySelector<HTMLElement>('.bar-nav button[data-menu="system"]')
  back?.focus()
})
</script>

<template>
  <SectionedPanel
    v-model:query="query"
    class="settings-panel"
    id-prefix="settings"
    content-class="settings-form settings-content"
    :title="t('settings.title')"
    :hint="t('settings.companyHint')"
    :sections="tabs"
    :current="current"
    searchable
    @choose="(id) => (current = id as SectionId)"
    @close="emit('close')"
  >
    <template #default="{ searching }">
      <div v-if="loadError" class="hint bad load-error" role="alert">
        {{ loadError }} <button class="link" type="button" @click="load">{{ t('sr.retry') }}</button>
      </div>
      <div v-if="settings === null && !loadError" class="hint">{{ t('common.loading') }}</div>
      <template v-else-if="settings === null" />
      <div v-else-if="searching && !found.length" class="hint">{{ t('sr.noMatch', { q: query.trim() }) }}</div>
      <template v-else>
        <SettingsSection
          v-for="s in searching ? found : sections.filter((x) => x.id === current)"
          :key="s.id"
          :section="s"
          :settings="byKey"
          :drafts="drafts"
          :busy="busy"
          :run="run"
          @save="save"
          @draft="draft"
          @open="open"
        />
      </template>
    </template>
  </SectionedPanel>
</template>

<style>
/* settings-redesign: the frame (section list, search, narrow screen) is SectionedPanel's; only the size is the settings'. */
.panel.settings-panel {
  width: min(1000px, calc(100vw - 48px));
  height: calc(100vh - 170px);
}
.settings-content.settings-form {
  gap: 22px;
}
</style>
