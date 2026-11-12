<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed, onMounted, ref } from 'vue'
import { t } from '../i18n'
import { coverOf } from '../scene/cover'
import { library, loadLibrary, startMissionOn } from '../state/library'
import { office } from '../state/office'
import { ago } from '../ui/away'
import { coverSvg } from '../ui/coverArt'
import { booksOf, LIBRARY_SECTIONS, type LibrarySection, sectionCounts, shortPath } from '../ui/libraryCards'
import { folderName } from '../ui/recent'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * The library: one book per project the director knows, its cover drawn from its name (the same colours as its spine
 * on the office's bookcase). A click opens the new-mission form on that project; one card opens the folder browser.
 */
const emit = defineEmits<{ close: [] }>()

const current = ref<LibrarySection>('all')
const query = ref('')
onMounted(() => void loadLibrary())

const counts = computed(() => sectionCounts(library.projects))
const tabs = computed<SectionTab[]>(() =>
  LIBRARY_SECTIONS.map((id) => ({
    id,
    label: t(`library.s.${id}`),
    badge: { text: String(counts.value[id]) },
    matches: booksOf(library.projects, id, query.value).length,
  })),
)
const searching = computed(() => query.value.trim().length > 0)
/** While searching every section's matches show together; else the chosen section. */
const books = computed(() => booksOf(library.projects, searching.value ? 'all' : current.value, query.value))
const cover = (path: string) => coverSvg(coverOf(path, office.theme))
const when = (iso: string | null) => (iso ? t('recent.ago', { when: ago(iso, nowMs()) }) : '')
</script>

<template>
  <SectionedPanel
    v-model:query="query"
    class="library-panel"
    id-prefix="library"
    :title="t('library.title')"
    :hint="t('library.intro')"
    :sections="tabs"
    :current="current"
    searchable
    @choose="(id) => (current = id as LibrarySection)"
    @close="emit('close')"
  >
    <div v-if="library.error" class="hint bad" role="alert">{{ library.error }}</div>
    <div class="shelf">
      <button type="button" class="book other" data-library="other" @click="startMissionOn(null)">
        <span class="plus">+</span>
        <span class="name">{{ t('library.other') }}</span>
        <span class="hint">{{ t('library.otherHint') }}</span>
      </button>
      <button
        v-for="p in books"
        :key="p.path"
        type="button"
        class="book"
        :class="{ gone: !p.exists }"
        :data-project="p.path"
        :title="p.path"
        @click="startMissionOn(p.path)"
      >
        <span class="cover" v-html="cover(p.path)"></span>
        <span class="name">{{ folderName(p.path) }}<span v-if="p.pinned" class="pin"> ★</span></span>
        <span class="path">{{ shortPath(p.path) }}</span>
        <span v-if="p.lastMission" class="hint">{{ t('recent.last', { mission: p.lastMission, title: p.lastTitle ?? '' }) }}</span>
        <span class="hint">
          {{ t('library.missionCount', { count: p.missions.length }) }}<template v-if="when(p.lastActivity)"> · {{ when(p.lastActivity) }}</template>
          <span v-if="!p.exists" class="warn"> · {{ t('recent.gone') }}</span>
        </span>
      </button>
    </div>
    <div v-if="library.loaded && !books.length" class="hint">{{ t(searching ? 'library.noMatch' : 'library.empty') }}</div>
  </SectionedPanel>
</template>

<style>
.panel.library-panel {
  top: 90px;
  left: 50%;
  transform: translateX(-50%);
  width: min(1000px, calc(100vw - 48px));
  height: calc(100vh - 170px);
  z-index: 24;
  background: #071a21;
}
.library-panel .shelf {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 16px;
}
.library-panel .book {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: stretch;
  text-align: left;
  padding: 10px;
  background: #0a232c;
  border: 1px solid #1d4a57;
  font: inherit;
  line-height: 1.45;
  cursor: pointer;
}
.library-panel .book:hover,
.library-panel .book:focus-visible {
  border-color: var(--cyan);
  background: #0f3540;
}
.library-panel .book .cover {
  display: block;
  width: 100%;
  aspect-ratio: 3 / 4;
  margin-bottom: 6px;
  box-shadow: 4px 4px 0 rgba(0, 0, 0, 0.45);
}
.library-panel .book .cover svg {
  display: block;
  width: 100%;
  height: 100%;
}
.library-panel .book .name {
  font-size: 15px;
  color: var(--text);
}
.library-panel .book .path {
  font-size: 12px;
  color: var(--muted);
  word-break: break-all;
}
.library-panel .book .pin {
  color: #e0c040;
}
.library-panel .book.gone .cover {
  opacity: 0.45;
}
.library-panel .book.other {
  align-items: center;
  justify-content: center;
  text-align: center;
  border-style: dashed;
  min-height: 220px;
}
.library-panel .book.other .plus {
  font-size: 44px;
  color: var(--cyan);
  line-height: 1;
}
</style>
