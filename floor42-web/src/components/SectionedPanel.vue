<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../i18n'
import { moveTo } from '../ui/sectioned'

/**
 * The frame of the information-heavy panels (Settings, the end of a mission): a title bar, an optional hint and search
 * box, a list of sections on the left (a vertical tab list: arrows, Home, End; a row of tabs on a narrow screen) and
 * the content of the chosen section on the right, or the matches of every section while searching. The panel owns
 * nothing about what the sections hold: it fills the default slot (`searching` tells which of the two it is) and the
 * optional `title-extra` and `top` slots. `idPrefix` names the ids the tabs and the content share.
 */
export interface SectionTab {
  id: string
  label: string
  /** A mark beside the label when not searching: `unsaved` (dashed), `changed` (amber), else plain. */
  badge?: { text: string; title?: string; tone?: 'plain' | 'unsaved' | 'changed' } | null
  /** Matches of the search in this section (shown while searching; none dims the tab). */
  matches?: number
}

const props = withDefaults(
  defineProps<{
    title: string
    sections: readonly SectionTab[]
    current: string
    idPrefix: string
    hint?: string
    /** Show the search box. */
    searchable?: boolean
    /** Class of the content area, besides `panel-scroll`. */
    contentClass?: string
    /** Put the focus on the current section when the panel opens. */
    focusOnOpen?: boolean
    /** Height follows the content (up to the panel's max-height) instead of filling a height the panel sets. */
    fit?: boolean
    /** Width of the section list (a CSS length); the narrow layout ignores it. */
    navWidth?: string
  }>(),
  { hint: '', searchable: false, contentClass: '', focusOnOpen: true, fit: false },
)
const emit = defineEmits<{ choose: [id: string]; close: [] }>()
const query = defineModel<string>('query', { default: '' })

defineSlots<{
  default(props: { searching: boolean }): unknown
  'title-extra'(): unknown
  top(): unknown
}>()

const searching = computed(() => query.value.trim().length > 0)
const tabs = new Map<string, HTMLButtonElement>()
const narrow = ref(false)
let media: MediaQueryList | null = null
const onMedia = () => (narrow.value = !!media?.matches)

function setTab(id: string, el: unknown) {
  if (el instanceof HTMLButtonElement) tabs.set(id, el)
  else tabs.delete(id)
}

function choose(id: string) {
  emit('choose', id)
  query.value = ''
}

async function onKey(e: KeyboardEvent) {
  const next = moveTo(
    props.sections.map((s) => s.id),
    props.current,
    e.key,
  )
  if (!next) return
  e.preventDefault()
  choose(next)
  await nextTick()
  tabs.get(next)?.focus()
}

/** Escape in the search box empties it first; the next Escape closes the panel as usual. */
function onSearchKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && query.value) {
    e.stopPropagation()
    query.value = ''
  }
}

onMounted(() => {
  media = window.matchMedia('(max-width: 760px)')
  onMedia()
  media.addEventListener('change', onMedia)
  if (props.focusOnOpen) tabs.get(props.current)?.focus()
})
onBeforeUnmount(() => media?.removeEventListener('change', onMedia))
</script>

<template>
  <div class="panel sectioned-panel framed" :class="{ fit }" :style="navWidth ? { '--sp-nav': navWidth } : undefined">
    <div class="title">
      {{ title }}
      <slot name="title-extra" />
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div v-if="hint || searchable" class="sectioned-top">
      <div class="hint">{{ hint }}</div>
      <input
        v-if="searchable"
        v-model="query"
        class="sectioned-search"
        type="search"
        :placeholder="t('sr.searchPlaceholder')"
        :aria-label="t('sr.search')"
        @keydown="onSearchKey"
      />
    </div>
    <slot name="top" />
    <div class="sectioned-body">
      <nav class="sectioned-nav" role="tablist" :aria-orientation="narrow ? 'horizontal' : 'vertical'" :aria-label="t('sr.sections')">
        <button
          v-for="s in sections"
          :id="`${idPrefix}-tab-${s.id}`"
          :key="s.id"
          :ref="(el) => setTab(s.id, el)"
          type="button"
          role="tab"
          :data-section-tab="s.id"
          :aria-selected="!searching && current === s.id"
          :aria-controls="`${idPrefix}-content`"
          :tabindex="current === s.id ? 0 : -1"
          :class="{ on: !searching && current === s.id, empty: searching && !s.matches }"
          @click="choose(s.id)"
          @keydown="onKey"
        >
          <span>{{ s.label }}</span>
          <span v-if="searching" class="tab-count">{{ s.matches ?? 0 }}</span>
          <span v-else-if="s.badge" class="tab-count" :class="s.badge.tone" :title="s.badge.title">{{ s.badge.text }}</span>
        </button>
      </nav>
      <div
        :id="`${idPrefix}-content`"
        class="panel-scroll sectioned-content"
        :class="contentClass"
        :role="searching ? 'region' : 'tabpanel'"
        :aria-labelledby="searching ? undefined : `${idPrefix}-tab-${current}`"
        :aria-label="searching ? t('sr.search') : undefined"
        tabindex="0"
      >
        <slot :searching="searching" />
      </div>
    </div>
  </div>
</template>

<style>
.sectioned-top {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.sectioned-search {
  font: inherit;
  padding: 6px 10px;
  width: 280px;
  flex: none;
}
.sectioned-body {
  display: grid;
  grid-template-columns: var(--sp-nav, 190px) minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  gap: 18px;
  flex: 1;
  min-height: 0;
}
.sectioned-panel.fit .sectioned-body {
  flex: 0 1 auto;
}
.sectioned-content:focus-visible {
  outline: 2px solid var(--cyan);
  outline-offset: 1px;
}
.sectioned-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-right: 1px solid #1d4a57;
  padding-right: 12px;
  overflow: auto;
}
.sectioned-nav button {
  display: flex;
  justify-content: space-between;
  align-items: center;
  text-align: left;
  padding: 8px 10px;
  background: none;
  border: 1px solid transparent;
  letter-spacing: 0.04em;
}
.sectioned-nav button.on {
  border-color: var(--cyan);
  color: var(--cyan);
  background: #0d2630;
}
.sectioned-nav button.empty {
  opacity: 0.5;
}
.sectioned-nav button:focus-visible {
  outline: 2px solid var(--cyan);
  outline-offset: 1px;
}
.sectioned-nav .tab-count {
  font-size: 12px;
  color: var(--muted);
  border: 1px solid #1d4a57;
  padding: 0 6px;
}
.sectioned-nav .tab-count.unsaved {
  color: var(--cyan);
  border-color: var(--cyan);
  border-style: dashed;
}
.sectioned-nav .tab-count.changed {
  color: var(--amber);
  border-color: var(--amber);
}
/* Narrow screen: the sections become a row of tabs above the content. */
@media (max-width: 760px) {
  .sectioned-top {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }
  .sectioned-search {
    width: auto;
  }
  .sectioned-body {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    gap: 10px;
  }
  .sectioned-nav {
    flex-direction: row;
    border-right: 0;
    border-bottom: 1px solid #1d4a57;
    padding: 0 0 8px;
  }
  .sectioned-nav button {
    flex: none;
    gap: 8px;
  }
}
</style>
