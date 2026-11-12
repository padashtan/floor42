<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { SceneTarget } from '../scene/kit'
import { t } from '../i18n'
import { office } from '../state/office'
import { agentOfQuestion } from './QuestionCard.vue'
import { currentTicket, dismissal } from '../ui/dismissal'

export type MenuAction =
  | 'details'
  | 'focus'
  | 'desk'
  | 'workshop'
  | 'meeting'
  | 'callMeeting'
  | 'usage'
  | 'console'
  | 'settings'
  | 'suspend'
  | 'resume'
  | 'board'
  | 'profile'
  | 'requests'
  | 'outputs'
  | 'fire'
  | 'identities'
  | 'iamExchanges'

const props = defineProps<{ target: SceneTarget; x: number; y: number }>()
const emit = defineEmits<{ pick: [action: MenuAction]; close: [] }>()

interface Item {
  label: string
  action?: MenuAction
  /** Why the item is not available now. Spec section 18: shown, disabled, explained. */
  disabled?: string
  hint?: string
  /** A destructive action: marked in red so it does not look like the others. */
  danger?: boolean
}

const proposed = computed(() => (office.mission?.tickets ?? []).filter((t) => t.status === 'PROPOSED').length)

const items = computed<Item[]>(() => {
  switch (props.target.kind) {
    case 'agent': {
      const a = office.agents.find((x) => x.id === props.target.id)
      const tickets = (office.mission?.tickets ?? []).filter((t) => t.assignee === props.target.id)
      const current = currentTicket(props.target.id, tickets)
      const gone = dismissal(a ?? { name: props.target.id, stays: false, atWork: false }, current)
      const waits = office.questions.filter((q) => agentOfQuestion(q) === props.target.id).length
      return [
        waits
          ? { label: t('menu.resolveCount', { count: waits }), action: 'requests', hint: t('menu.doubleClick') }
          : { label: t('menu.resolve'), disabled: t('menu.resolveNone') },
        { label: t('menu.profile'), action: 'profile' },
        { label: t('menu.recent'), action: 'details' },
        { label: t('menu.follow'), action: 'focus', hint: 'F' },
        tickets.length
          ? { label: t('menu.workshop') + (current ? ` (${current.id})` : ''), action: 'workshop', hint: 'W' }
          : { label: t('menu.workshop'), disabled: t('menu.workshopNone') },
        { label: t('menu.settings', { model: a?.model ?? '?', effort: a?.effort ?? t('menu.default') }), action: 'settings' },
        a?.suspended
          ? { label: t('menu.resume'), action: 'resume' }
          : { label: t('menu.suspend'), action: 'suspend', hint: t('menu.suspendHint') },
        // The same rule as the profile's Dismiss button (ui/dismissal.ts).
        gone.refused
          ? { label: t('menu.dismiss'), disabled: gone.refused }
          : { label: t('menu.dismissDoor'), action: 'fire', hint: gone.hint, danger: true },
      ]
    }
    case 'machine':
      return [
        { label: t('menu.outputs'), action: 'outputs', hint: t('menu.doubleClick') },
        { label: t('menu.checks'), action: 'workshop', hint: 'W' },
        { label: t('menu.console'), action: 'console', hint: 'C' },
      ]
    case 'board':
      return [
        { label: t('menu.board'), action: 'board', hint: 'B' },
        proposed.value
          ? { label: t('menu.validateCount', { count: proposed.value }), action: 'board', hint: 'B' }
          : { label: t('menu.validate'), disabled: t('menu.validateNone') },
      ]
    case 'desk':
      return [
        { label: t('sel.openDesk'), action: 'desk', hint: 'D' },
        { label: t('menu.usage'), action: 'usage', hint: 'U' },
        { label: t('menu.console'), action: 'console', hint: 'C' },
      ]
    case 'table':
      return [
        // The director's meeting (ADR-0047), on the meeting table and on the conference table.
        { label: t('menu.callMeeting'), action: 'callMeeting', hint: t('menu.callMeetingHint') },
        { label: t('menu.meeting'), action: 'meeting', hint: 'M' },
        { label: t('menu.committee'), action: 'desk', hint: 'D' },
      ]
    case 'identity':
      return [
        { label: t('idn.top'), action: 'identities' },
        { label: t('idx.open'), action: 'iamExchanges' },
      ]
    default:
      // The server rack stands with the machines: it shows what they ran.
      return [{ label: t('menu.outputs'), action: 'outputs' }]
  }
})

// Opened near the right or bottom edge, the menu moves back inside the window instead of being cut off.
const el = ref<HTMLElement>()
const place = ref({ left: props.x, top: props.y })
watch(
  () => [props.x, props.y, items.value.length],
  async () => {
    place.value = { left: props.x, top: props.y }
    await nextTick()
    const r = el.value?.getBoundingClientRect()
    if (!r) return
    place.value = {
      left: Math.max(0, Math.min(props.x, window.innerWidth - r.width - 4)),
      top: Math.max(0, Math.min(props.y, window.innerHeight - r.height - 4)),
    }
  },
  { immediate: true },
)

// The ARIA menu pattern: the first item takes the focus, the arrows, Home and End move through the items (a disabled
// item stays reachable, its reason is read with it), Escape closes, and the focus goes back to where it was.
let opener: HTMLElement | null = null
const buttons = () => [...(el.value?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
onMounted(async () => {
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  buttons()[0]?.focus({ preventScroll: true })
})
onBeforeUnmount(() => {
  if (opener?.isConnected) opener.focus({ preventScroll: true })
})
function key(e: KeyboardEvent) {
  const all = buttons()
  const i = all.indexOf(document.activeElement as HTMLElement)
  const go = (n: number) => all[(n + all.length) % all.length]?.focus()
  if (e.key === 'ArrowDown') go(i + 1)
  else if (e.key === 'ArrowUp') go(i - 1)
  else if (e.key === 'Home') go(0)
  else if (e.key === 'End') go(all.length - 1)
  else if (e.key === 'Escape') emit('close')
  else if (e.key === 'Tab') emit('close')
  else return
  if (e.key !== 'Tab') e.preventDefault()
  e.stopPropagation()
}
</script>

<template>
  <div
    ref="el"
    class="panel menu"
    role="menu"
    :aria-label="t('menu.label')"
    :style="{ left: place.left + 'px', top: place.top + 'px' }"
    @contextmenu.prevent
    @keydown="key"
  >
    <button
      v-for="item in items"
      :key="item.label"
      type="button"
      role="menuitem"
      class="item"
      :class="{ disabled: item.disabled, danger: item.danger }"
      :aria-disabled="item.disabled ? 'true' : undefined"
      @click="item.action && emit('pick', item.action)"
    >
      {{ item.label }} <span v-if="item.hint" class="key">{{ item.hint }}</span>
      <span v-if="item.disabled" class="why">{{ item.disabled }}</span>
    </button>
  </div>
</template>
