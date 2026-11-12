<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { t } from '../i18n'
import { offer } from '../state/afterArchive'
import { office, putOnBoard } from '../state/office'
import { setPanel } from '../state/openPanels'
import { BOARD_EMPTY } from '../ui/recent'
import { trapTab } from '../ui/trapTab'

/**
 * Right after an archive: what the board shows now (the archived mission would otherwise stay on it). Closing it any way
 * but choosing a mission leaves the board empty, never on the automatic choice.
 */
const box = ref<HTMLElement>()
const open = computed(() =>
  [...office.missions].reverse().filter((m) => !m.archived && m.id !== offer.value && m.status !== 'COMPLETED' && m.status !== 'CANCELLED'),
)

watch(offer, async (id) => {
  if (!id) return
  await nextTick()
  box.value?.querySelector<HTMLElement>('button')?.focus()
})

function choose(id: string, thenNew = false) {
  offer.value = null
  void putOnBoard(id)
  if (thenNew) setPanel('desk', true)
}

function key(e: KeyboardEvent) {
  e.stopPropagation()
  if (e.key === 'Escape') {
    e.preventDefault()
    choose(BOARD_EMPTY)
  } else if (e.key === 'Tab') trapTab(box.value, e, 'button:not(:disabled)')
}
</script>

<template>
  <div v-if="offer" class="confirm-scrim" @pointerdown.self="choose(BOARD_EMPTY)">
    <div ref="box" class="panel confirm-dialog after-archive" role="alertdialog" aria-modal="true" aria-labelledby="after-archive-title" @keydown="key">
      <p id="after-archive-title" class="confirm-message">{{ t('afterArchive.title') }}</p>
      <ul v-if="open.length" class="after-archive-list">
        <li v-for="m in open" :key="m.id" class="after-archive-row" :data-mission="m.id">
          <span class="after-archive-name">{{ m.id }} · {{ m.title }}</span>
          <button class="action" @click="choose(m.id)">{{ t('afterArchive.put') }}</button>
        </li>
      </ul>
      <p v-else class="hint">{{ t('afterArchive.none') }}</p>
      <div class="confirm-actions after-archive-actions">
        <button class="action leave-empty" @click="choose(BOARD_EMPTY)">{{ t('afterArchive.empty') }}</button>
        <button v-if="!open.length" class="action recommended new-mission" @click="choose(BOARD_EMPTY, true)">{{ t('afterArchive.new') }}</button>
      </div>
    </div>
  </div>
</template>
