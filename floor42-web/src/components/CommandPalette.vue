<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { t, tr } from '../i18n'
import { office } from '../state/office'
import { paletteItems, searchPalette, type PaletteItem } from '../ui/palette'

/**
 * Ctrl+K: one field to reach every action and panel (ui/actions.ts), a room, an agent's profile or a mission on the
 * board. Arrows choose, Enter runs, Escape closes and gives the focus back to where it was.
 */
const props = defineProps<{ rooms: { id: string; name: string }[] }>()
const emit = defineEmits<{ pick: [item: PaletteItem]; close: [] }>()

const query = ref('')
const active = ref(0)
const input = ref<HTMLInputElement>()
const list = ref<HTMLElement>()
let before: HTMLElement | null = null

const items = computed(() =>
  paletteItems({
    rooms: props.rooms,
    agents: office.agents,
    missions: office.missions,
    t,
    room: (id, name) => tr(`room.${id}`, name),
  }),
)
const found = computed(() => searchPalette(items.value, query.value))
watch(query, () => (active.value = 0))

function choose(item: PaletteItem | undefined) {
  if (!item) return
  if (item.href) window.open(item.href, '_blank', 'noopener')
  else emit('pick', item)
  emit('close')
}

function key(e: KeyboardEvent) {
  const n = found.value.length
  if (e.key === 'ArrowDown') active.value = n ? (active.value + 1) % n : 0
  else if (e.key === 'ArrowUp') active.value = n ? (active.value - 1 + n) % n : 0
  else if (e.key === 'Home' && e.ctrlKey) active.value = 0
  else if (e.key === 'End' && e.ctrlKey) active.value = Math.max(0, n - 1)
  else if (e.key === 'Enter') choose(found.value[active.value])
  else if (e.key === 'Escape') emit('close')
  // Focus stays in the field: the list is read through aria-activedescendant.
  else if (e.key === 'Tab') {
    /* trapped */
  } else return
  e.preventDefault()
  e.stopPropagation()
  void nextTick(() => list.value?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }))
}

onMounted(() => {
  before = document.activeElement instanceof HTMLElement ? document.activeElement : null
  input.value?.focus()
})
onUnmounted(() => before?.focus())

const KIND: Record<PaletteItem['kind'], 'palette.kind.action' | 'palette.kind.room' | 'palette.kind.agent' | 'palette.kind.mission'> = {
  action: 'palette.kind.action',
  // A command outside the bar (the tutorial's restart and skip) reads as an action.
  command: 'palette.kind.action',
  room: 'palette.kind.room',
  agent: 'palette.kind.agent',
  mission: 'palette.kind.mission',
}
const optionId = (i: number) => `palette-option-${i}`
</script>

<template>
  <div class="palette-backdrop" @pointerdown.self="emit('close')">
    <div class="panel palette" role="dialog" aria-modal="true" :aria-label="t('palette.label')">
      <input
        ref="input"
        v-model="query"
        type="text"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded="true"
        aria-controls="palette-list"
        :aria-activedescendant="found.length ? optionId(active) : undefined"
        :placeholder="t('palette.placeholder')"
        spellcheck="false"
        autocomplete="off"
        @keydown="key" :aria-label="t('palette.placeholder')" />
      <ul id="palette-list" ref="list" role="listbox" :aria-label="t('palette.label')">
        <li
          v-for="(item, i) in found"
          :id="optionId(i)"
          :key="`${item.kind}:${item.id}`"
          role="option"
          :aria-selected="i === active"
          :class="{ active: i === active }"
          @pointermove="active = i"
          @click="choose(item)"
        >
          <span class="kind">{{ t(KIND[item.kind]) }}</span>
          <span class="label">{{ item.label }}<span v-if="item.href"> ↗</span></span>
          <kbd v-if="item.key">{{ item.key }}</kbd>
          <span class="detail">{{ item.detail }}</span>
        </li>
        <li v-if="!found.length" class="none" role="presentation">{{ t('palette.none') }}</li>
      </ul>
      <div class="hint">{{ t('palette.keys') }}</div>
    </div>
  </div>
</template>
