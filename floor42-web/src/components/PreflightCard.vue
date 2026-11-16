<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { PreflightLine, PreflightView } from '../api/types'
import { t, tr } from '../i18n'
import { blockCount, proposal, shownLines, toolWarning, warnCount } from '../ui/preflight'
import SandboxReadiness from './SandboxReadiness.vue'

/**
 * "Before the start" (ADR-0070): what the folder is (read from its files), a proposed checks command, and one list of
 * blockers and warnings. Reading it measures nothing; a tool never measured says so, and the sandbox's offline ability
 * is "unknown" unless a build file says a download is likely. `blocked` holds the reason while a blocker stands, else ''.
 */
const props = defineProps<{ project: string }>()
const checks = defineModel<string>('checks', { required: true })
const blocked = defineModel<string>('blocked', { default: '' })

const view = ref<PreflightView | null>(null)
const error = ref('')

const proposed = computed(() => proposal(view.value, checks.value))
const lines = computed(() => shownLines(view.value))
const summary = computed(() => {
  const b = blockCount(view.value)
  const w = warnCount(view.value)
  if (b) return t('preflight.blocked', { count: b })
  return w ? t('preflight.warnings', { count: w }) : t('preflight.ready')
})
const tone = computed(() => (blockCount(view.value) ? 'bad' : warnCount(view.value) ? 'warn' : 'ok'))
const profile = computed(() => {
  const p = view.value?.profile
  if (!p) return ''
  if (!p.buildTool) return t('preflight.profileNone')
  const params = { tool: p.buildTool, files: p.files.join(', '), branch: p.branch ?? '' }
  return t(p.branch ? 'preflight.profile' : 'preflight.profileNoBranch', params)
})

function text(l: PreflightLine): string {
  return tr(`preflight.line.${l.code}`, l.code, l.params)
}

async function load() {
  try {
    view.value = await api.preflight(props.project, checks.value.trim() || null)
    error.value = ''
  } catch (e) {
    view.value = null
    // A path still being typed is not a folder yet: nothing to say about it.
    error.value = e instanceof ApiError && e.status === 404 ? '' : String(e)
  }
}

watch(
  () => view.value,
  (v) => (blocked.value = blockCount(v) ? t('preflight.blocked', { count: blockCount(v) }) : ''),
)
// The folder is typed or picked: read it again once the director stops typing.
let timer: number | undefined
watch(
  () => [props.project, checks.value],
  () => {
    window.clearTimeout(timer)
    timer = window.setTimeout(() => void load(), 400)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  window.clearTimeout(timer)
  blocked.value = ''
})
</script>

<template>
  <section v-if="view || error" class="preflight" :class="tone" aria-live="polite">
    <div class="head">
      <b>{{ t('preflight.title') }}</b>
      <span v-if="view" class="summary">{{ summary }}</span>
    </div>
    <div v-if="error" class="warn">{{ error }}</div>
    <template v-if="view">
      <div class="hint profile">{{ profile }}</div>
      <div v-if="proposed" class="hint proposal">
        {{ t('preflight.checksSuggest') }}
        <code>{{ proposed.command }}</code>
        <span v-if="proposed.fromLast">({{ t('preflight.checksFromLast') }})</span>
        <button type="button" class="link" @click="checks = proposed.command">{{ t('preflight.checksUse') }}</button>
      </div>
      <ul v-if="lines.length" class="lines">
        <li v-for="(l, i) in lines" :key="i" :class="l.level.toLowerCase()">
          <span class="mark" aria-hidden="true">{{ l.level === 'BLOCK' ? '✕' : l.level === 'WARN' ? '!' : 'i' }}</span>
          {{ text(l) }}
        </li>
      </ul>
      <details class="tools" :open="toolWarning(view)">
        <summary class="hint">{{ t('preflight.tools') }}</summary>
        <SandboxReadiness :project="project" :check="checks || null" />
      </details>
    </template>
  </section>
</template>

<style scoped>
.preflight {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-left: 3px solid var(--line, #556);
  padding: 4px 8px;
}
.preflight.bad {
  border-left-color: #d05050;
}
.preflight.warn {
  border-left-color: #e0a040;
}
.preflight.ok {
  border-left-color: #50a070;
}
.head {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.lines {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.lines .mark {
  display: inline-block;
  width: 1.2em;
  font-weight: bold;
}
.lines .block {
  color: #d05050;
}
.lines .warn {
  color: #e0a040;
}
.warn {
  color: #e0a040;
}
.link {
  background: none;
  border: none;
  padding: 0 0 0 8px;
  color: var(--cyan);
  text-decoration: underline;
  cursor: pointer;
  font: inherit;
}
</style>
