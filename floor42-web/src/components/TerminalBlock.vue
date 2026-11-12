<script setup lang="ts">
import { plainText } from '../ui/ansi'
import { computed, nextTick, ref, watch } from 'vue'
import { t } from '../i18n'
import { renderMarkdown } from '../ui/markdown'
import { shapeOutput } from '../ui/outputs'

/**
 * A read-only output (command output, report, deliverable), shown for reading: a report written in Markdown is
 * rendered, a tool's output is laid out (line numbers in a gutter, matches grouped by file). The recorded text is never
 * changed: "Raw" shows it as is, Copy and Export take it as is. Resizable by its corner.
 */
const props = withDefaults(
  defineProps<{
    text: string
    name?: string
    height?: string
    follow?: boolean
    /** markdown: a report or review; text: an output shown as recorded. */
    format?: 'markdown' | 'text'
    /** The tool that produced the output, to lay it out (Read, Grep, Glob, Bash…). */
    tool?: string | null
  }>(),
  { name: 'output', height: '220px', follow: false, format: 'text', tool: null },
)

const copied = ref(false)
const large = ref(false)
const raw = ref(false)
const pre = ref<HTMLElement>()
const plain = computed(() => plainText(props.text))
const lines = computed(() => plain.value.split('\n').length)
const shape = computed(() => shapeOutput(props.tool, plain.value))
const html = computed(() => (props.format === 'markdown' ? renderMarkdown(plain.value) : ''))
const pretty = computed(() => !raw.value && (props.format === 'markdown' || shape.value.kind !== 'text'))
// Code keeps its indentation: no wrapping, a horizontal scroll instead. Prose wraps.
const wrap = ref(props.tool !== 'Read')
const groups = computed(() => {
  const s = shape.value
  if (s.kind !== 'matches') return []
  const byFile = new Map<string, { n: string | null; text: string }[]>()
  for (const l of s.lines) byFile.set(l.path, [...(byFile.get(l.path) ?? []), { n: l.n, text: l.text }])
  return [...byFile.entries()].map(([path, hits]) => ({ path, hits }))
})

// Build and test verdicts are at the end of their output: a followed block opens scrolled to the bottom.
watch(
  () => plain.value,
  async () => {
    if (!props.follow) return
    await nextTick()
    if (pre.value) pre.value.scrollTop = pre.value.scrollHeight
  },
  { immediate: true },
)

async function copy() {
  try {
    await navigator.clipboard.writeText(plain.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    copied.value = false
  }
}

function exportText() {
  const blob = new Blob([plain.value], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${props.name.replace(/[^\w.-]+/g, '_')}.${props.format === 'markdown' ? 'md' : 'txt'}`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <div class="terminal" :class="{ large }">
    <div class="terminal-bar">
      <span class="hint">{{ t('out.lines', { count: lines }) }}</span>
      <button
        v-if="format === 'markdown' || shape.kind !== 'text'"
        :class="{ on: raw }"
        :title="t('out.rawTitle')"
        @click="raw = !raw"
      >
        {{ t('out.raw') }}
      </button>
      <button :class="{ on: wrap }" :title="t('out.wrapTitle')" @click="wrap = !wrap">{{ t('out.wrap') }}</button>
      <button :title="t('out.sizeTitle')" @click="large = !large">{{ large ? 'A−' : 'A+' }}</button>
      <button :title="t('out.copyTitle')" @click="copy">{{ copied ? t('out.copied') : t('out.copy') }}</button>
      <button :title="t('out.exportTitle')" @click="exportText">{{ t('out.export') }}</button>
    </div>
    <div
      ref="pre"
      class="terminal-body"
      :class="{ nowrap: !wrap }"
      :style="{ height: lines > 8 ? height : 'auto' }"
    >
      <div v-if="pretty && format === 'markdown'" class="md" v-html="html"></div>
      <table role="presentation" v-else-if="pretty && shape.kind === 'numbered'" class="numbered">
        <tbody>
          <tr v-for="(l, i) in shape.lines" :key="i">
            <td class="gutter">{{ l.n }}</td>
            <td class="code-line">{{ l.text || ' ' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="pretty && shape.kind === 'matches'" class="matches">
        <div v-for="g in groups" :key="g.path" class="match-file">
          <div class="match-path">{{ g.path }} <span class="hint">· {{ g.hits.length }}</span></div>
          <table role="presentation" class="numbered">
            <tbody>
              <tr v-for="(h, i) in g.hits" :key="i">
                <td class="gutter">{{ h.n }}</td>
                <td class="code-line">{{ h.text || ' ' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <ul v-else-if="pretty && shape.kind === 'paths'" class="paths">
        <li v-for="p in shape.lines" :key="p">{{ p }}</li>
      </ul>
      <pre v-else class="terminal-text">{{ plain }}</pre>
    </div>
  </div>
</template>
