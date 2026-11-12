<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { DeliverablesView, DeliveredFile } from '../api/types'
import { fileCheck, fileText, fileUrl, ownFileEntry, type FileSource } from '../api/viewedFiles'
import { t } from '../i18n'
import { office } from '../state/office'
import type { ViewedFile } from '../state/viewer'
import { capOf, findFile, formatSize, notViewable, viewerOf, type NotViewable, type Viewer } from '../ui/deliverables'
import ImagePreview from './ImagePreview.vue'
import TerminalBlock from './TerminalBlock.vue'

/**
 * One file of a mission in a panel of its own (ADR-0051): a deliverable of an agent or one of the director's own
 * pending files. Text and Markdown in TerminalBlock (Markdown escaped by ui/markdown.ts, never the file's own HTML), an
 * image (an SVG too) only in an <img> with fit and zoom, a PDF in the browser's own viewer under the daemon's policy,
 * and for anything else a card that says why and offers Download. Every byte comes through the daemon's routes, which
 * decide what may be read and send the type its bytes really are.
 */
const props = defineProps<{ file: ViewedFile }>()
const emit = defineEmits<{ close: [] }>()

const entry = ref<DeliveredFile | null>(null)
const caps = ref<Pick<DeliverablesView, 'maxViewText' | 'maxViewBytes'> | null>(null)
const text = ref<string | null>(null)
const error = ref<string | null>(null)
const unlisted = ref(false)
/** The browser could not show what the daemon sent: an image it cannot draw, a PDF the daemon refused. */
const failed = ref<'image' | 'pdf' | null>(null)
/** The daemon said what it would send: an image or a PDF goes on screen only then. */
const checked = ref(false)
const closeButton = ref<HTMLButtonElement | null>(null)

const source = computed<FileSource>(() =>
  props.file.own
    ? { kind: 'own', path: props.file.path }
    : { kind: 'deliverable', ref: { agent: props.file.agent, path: props.file.path, source: props.file.committed ? 'COMMITTED' : 'WORKTREE' } },
)
const url = computed(() => fileUrl(props.file.mission, source.value, false))
const download = computed(() => fileUrl(props.file.mission, source.value, true))
const name = computed(() => props.file.path.split('/').pop() ?? props.file.path)
const agentName = computed(() => office.agents.find((a) => a.id === props.file.agent)?.name ?? props.file.agent)
const viewer = computed<Viewer | null>(() => (entry.value && caps.value ? viewerOf(entry.value, caps.value) : null))
const refusal = computed<NotViewable | null>(() => (entry.value && caps.value ? notViewable(entry.value, caps.value) : null))
const markdown = computed(() => /\.(md|markdown)$/i.test(props.file.path))

/** The listing's entry: given by the caller, or read again (the workshop has no kinds, sizes nor caps). */
async function locate(f: ViewedFile): Promise<{ file: DeliveredFile | null; view: NonNullable<typeof caps.value> }> {
  if (f.file && f.view) return { file: f.file, view: f.view }
  if (f.own) {
    const own = await ownFileEntry(f.mission, f.path)
    return { file: own.file, view: own }
  }
  const v = await api.deliverables(f.mission, !f.committed)
  return { file: findFile(v, f.agent, f.path, f.committed), view: v }
}

/** The newest load: an earlier call answering late must not overwrite the file shown now. */
let latest: ViewedFile | null = null
async function load(f: ViewedFile): Promise<void> {
  latest = f
  entry.value = null
  caps.value = null
  text.value = null
  error.value = null
  unlisted.value = false
  failed.value = null
  checked.value = false
  try {
    const found = await locate(f)
    if (latest !== f) return
    entry.value = found.file
    caps.value = found.view
    unlisted.value = !f.own && !(f.file && f.view) && found.file === null
    if (viewer.value === 'text') {
      const body = await fileText(f.mission, source.value)
      if (latest !== f) return
      text.value = body
    }
    const shown = viewer.value
    if (shown === 'image' || shown === 'pdf') {
      const seen = await fileCheck(f.mission, source.value)
      if (latest !== f) return
      if (!seen.viewable) failed.value = shown
      checked.value = true
    }
  } catch (e) {
    if (latest === f) error.value = (e as Error).message
  }
}
watch(() => props.file, (f) => void load(f), { immediate: true })
onMounted(() => void nextTick(() => closeButton.value?.focus({ preventScroll: true })))

/**
 * The frame's document once loaded: the browser's PDF viewer for a PDF; the daemon's JSON refusal (415, 413) when its
 * bytes are no PDF. Same origin, so the type can be read; a refusal shows the card instead of the JSON.
 */
function framed(e: Event): void {
  const doc = (e.target as HTMLIFrameElement).contentDocument
  if (doc && doc.URL !== 'about:blank' && doc.contentType !== 'application/pdf') failed.value = 'pdf'
}

const why = computed(() => {
  const r = refusal.value
  if (!r || !entry.value || !caps.value) return ''
  if (r !== 'tooBig') return t(`viewer.refused.${r}`)
  return t('viewer.refused.tooBig', { size: formatSize(entry.value.size), cap: formatSize(capOf(entry.value, caps.value)) })
})
</script>

<template>
  <section class="panel file-viewer framed" role="dialog" aria-modal="false" aria-labelledby="file-viewer-title" @keydown.esc.stop="emit('close')">
    <div class="title">
      <span id="file-viewer-title">{{ t('viewer.title', { name }) }}</span>
      <button ref="closeButton" class="close" :title="t('viewer.close')" :aria-label="t('viewer.close')" @click="emit('close')">×</button>
    </div>
    <div class="head">
      <code class="path">{{ file.path }}</code>
      <span class="hint">
        · {{ file.mission }}
        <template v-if="file.own"> · {{ t('viewer.own') }}</template>
        <template v-else> · {{ agentName }} · {{ file.committed ? t('viewer.committed') : t('viewer.worktree') }}</template>
        <template v-if="entry"> · {{ formatSize(entry.size) }}</template>
      </span>
      <a class="button small" :href="download" download>{{ t('viewer.download') }}</a>
    </div>
    <div class="panel-scroll body" aria-live="polite">
      <div v-if="unlisted" class="hint">{{ t('viewer.unlisted') }}</div>
      <div v-else-if="error || refusal || failed" class="no-preview" :role="error ? 'alert' : 'status'" data-no-preview>
        <strong>{{ t('viewer.noPreview') }}</strong>
        <span :class="error ? 'error-line' : 'refusal'">{{ error ?? (failed === 'image' ? t('viewer.imageFailed') : failed === 'pdf' ? t('viewer.pdfFailed') : why) }} {{ t('viewer.downloadInstead') }}</span>
        <a class="button" :href="download" download>{{ t('viewer.download') }}</a>
      </div>
      <div v-else-if="!entry" class="hint">{{ t('viewer.loading') }}</div>
      <template v-else-if="viewer === 'text'">
        <div v-if="text === null" class="hint">{{ t('viewer.loading') }}</div>
        <TerminalBlock v-else :text="text" :name="name" :format="markdown ? 'markdown' : 'text'" tool="Read" height="calc(100vh - 300px)" />
      </template>
      <div v-else-if="(viewer === 'image' || viewer === 'pdf') && !checked" class="hint">{{ t('viewer.loading') }}</div>
      <ImagePreview v-else-if="viewer === 'image'" :src="url" :alt="t('viewer.imageAlt', { name })" @failed="failed = 'image'" />
      <!-- An iframe, not <object>: the page's policy says object-src 'none' (ADR-0044); the file keeps its own. -->
      <template v-else-if="viewer === 'pdf'">
        <iframe class="pdf" :src="url" :title="t('viewer.pdfTitle', { name })" @load="framed"></iframe>
        <span class="hint">{{ t('viewer.pdfFallback') }}</span>
      </template>
    </div>
  </section>
</template>

<style scoped>
.file-viewer {
  top: 72px;
  left: 50%;
  transform: translateX(-50%);
  width: min(900px, calc(100vw - 32px));
  max-height: calc(100vh - 140px);
  z-index: 26;
  display: flex;
  flex-direction: column;
}
.head {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  margin: 4px 0;
}
.path {
  word-break: break-all;
}
.body {
  overflow: auto;
}
.no-preview {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin: 12px 0;
  padding: 12px;
  border: 1px dashed currentColor;
  border-radius: 6px;
}
.pdf {
  width: 100%;
  height: calc(100vh - 300px);
  border: 0;
}
.small {
  font-size: max(12px, 0.8em);
  padding: 3px 6px;
}
.refusal {
  color: #e0a040;
}
.error-line {
  color: var(--red, #e05050);
}
</style>
