<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import type { AgentView } from '../api/types'
import { t, tr, type Key } from '../i18n'
import { office, putOnBoard } from '../state/office'
import { quotaGauges } from '../ui/quota'
import { waits } from '../state/waits'
import BarIcon from './BarIcon.vue'
import BrandBadges from './BrandBadges.vue'
import CrewStrip from './CrewStrip.vue'
import SpendIndicator from './SpendIndicator.vue'
import SessionNote from './SessionNote.vue'
import WaitingBanner from './WaitingBanner.vue'
import { grouped, type Action, type Group } from '../ui/actions'
import { menuLeft, placeUnderBar } from '../ui/topBarLayout'

defineProps<{ rooms: { id: string; name: string }[] }>()
const emit = defineEmits<{
  room: [index: number]
  agent: [agent: AgentView]
  /** Double click: the agent's profile and what it is doing now. */
  open: [id: string]
  /** Right click: the agent's actions, as in the scene. */
  menu: [id: string, x: number, y: number]
  /** An action of the registry (ui/actions.ts), by id: App.vue runs it. */
  action: [id: string]
  palette: []
  /** The waiting banner's "Open": the desk on that question. */
  answer: [question: string]
}>()

/** The page's clock, so a window that resets while nothing happens turns unknown on time, without a reload. */
const now = ref(nowMs())
let tick: number | undefined
onMounted(() => (tick = window.setInterval(() => (now.value = nowMs()), 30_000)))
onUnmounted(() => window.clearInterval(tick))
const quotas = computed(() => quotaGauges(office.usage, now.value))

/** The bar wraps on a narrow screen: the waiting banner sits under it, the crew under both (--waiting-top, --crew-top). */
const bar = ref<HTMLElement>()
const waitingBox = ref<HTMLElement>()
let barObserver: ResizeObserver | undefined
const place = () => placeUnderBar(bar.value!, waitingBox.value)
onMounted(() => {
  if (!bar.value || typeof ResizeObserver === 'undefined') return
  barObserver = new ResizeObserver(place)
  barObserver.observe(bar.value)
  if (waitingBox.value) barObserver.observe(waitingBox.value)
})
onUnmounted(() => {
  barObserver?.disconnect()
  for (const v of ['--crew-top', '--waiting-top', '--banner-h']) document.documentElement.style.removeProperty(v)
})

/**
 * The "switch mission" control, in the Work menu: another mission on the board, a view of this browser that starts
 * nothing. The latest few; the palette (Ctrl+K) finds any by id or title.
 */
const MENU_MISSIONS = 8
const boardMissions = computed(() => [...office.missions].reverse().slice(0, MENU_MISSIONS))

/** Everything that waits for the director, as the desk counts it: questions, proposed tickets and lessons. */
const proposedCount = computed(() => (office.mission?.tickets ?? []).filter((t) => t.status === 'PROPOSED').length)
const lessonsProposed = computed(() => office.lessons.filter((l) => l.status === 'PROPOSED').length)
const pending = computed(() => office.questions.length + proposedCount.value + lessonsProposed.value)
/** The counts shown on an action, by id: what waits behind it. A new badge is one line here. */
const badges = computed<Record<string, number | string>>(() => ({
  desk: pending.value,
  board: proposedCount.value,
  progress: waits.stuck,
  meeting: office.mission?.committee?.stage === 'AWAITING_DIRECTOR' ? '!' : 0,
}))
const layout = grouped()
const groupLabel = (g: Group, suffix: '' | 'Title' = '') => t(`palette.g.${g}${suffix}` as Key)
/** A menu shows a mark when one of its actions not shown on its own has a badge. */
function groupBadge(items: Action[]): boolean {
  return items.some((a) => !a.direct && badges.value[a.id])
}
/** The tooltip: the label with its shortcut (shown only here), then the sentence. */
function tip(a: Action): string {
  const sentence =
    a.id === 'desk' ? pendingTitle.value : a.id === 'progress' && waits.stuck ? t('waits.stuckTitle', { count: waits.stuck }) : a.title ? t(a.title) : ''
  return a.key ? `${t(a.label)} (${a.key.toLowerCase()})${sentence ? ` · ${sentence}` : ''}` : sentence
}

/** The open menu, placed under its button and kept inside the window. */
const openMenu = ref<Group | null>(null)
const menuAt = ref({ left: 0, top: 0 })
const menuEl = ref<HTMLElement[]>([])
const MENU_WIDTH = 280
async function toggleMenu(group: Group, event: Event, fromKeyboard = false) {
  if (openMenu.value === group && !fromKeyboard) return closeMenu(false)
  const r = (event.currentTarget as HTMLElement).getBoundingClientRect()
  menuAt.value = { left: menuLeft(r.left, MENU_WIDTH, window.innerWidth), top: r.bottom + 4 }
  openMenu.value = group
  await nextTick()
  menuItems()[0]?.focus()
}
function menuItems(): HTMLElement[] {
  return [...(menuEl.value[0]?.querySelectorAll<HTMLElement>('[role^="menuitem"]') ?? [])]
}
function closeMenu(refocus: boolean) {
  const group = openMenu.value
  openMenu.value = null
  if (refocus && group) document.querySelector<HTMLElement>(`[data-menu="${group}"]`)?.focus()
}
/** Arrows move through the menu, Home and End jump, Escape closes it and gives the focus back to its button. */
function menuKey(e: KeyboardEvent) {
  const items = menuItems()
  const i = items.indexOf(document.activeElement as HTMLElement)
  const go = (n: number) => items[(n + items.length) % items.length]?.focus()
  if (e.key === 'ArrowDown') go(i + 1)
  else if (e.key === 'ArrowUp') go(i - 1)
  else if (e.key === 'Home') go(0)
  else if (e.key === 'End') go(items.length - 1)
  else if (e.key === 'Escape' || e.key === 'Tab') closeMenu(e.key === 'Escape')
  else return
  if (e.key !== 'Tab') e.preventDefault()
  e.stopPropagation()
}
function pick(run: () => void) {
  closeMenu(false)
  run()
}
/** A click anywhere outside the open menu closes it. */
function outside(e: PointerEvent) {
  const target = e.target as HTMLElement | null
  if (openMenu.value && !target?.closest('.bar-dropdown, [data-menu]')) closeMenu(false)
}
onMounted(() => window.addEventListener('pointerdown', outside))
onUnmounted(() => window.removeEventListener('pointerdown', outside))

const pendingTitle = computed(() =>
  t('top.deskWaiting', {
    questions: office.questions.length,
    tickets: proposedCount.value,
    lessons: lessonsProposed.value,
  }),
)
</script>

<template>
  <div ref="bar" class="panel brand">
    <b>FLOOR42</b>
    <BrandBadges />
    <span class="stream" :class="office.stream" :title="t('top.stream', { state: office.stream })">●</span>
    <!-- The quotas stay by the brand, whole: measured numbers never shrink, the other badges give way first. -->
    <button v-if="quotas.length" class="gauges measure" @click="emit('action', 'usage')">
      <span v-for="q in quotas" :key="q.window" class="gauge" :class="q.level" :title="q.title">{{ q.text }}</span>
    </button>
    <!-- A status like the quotas: what the real mission on the board has spent; a click opens the costs panel. -->
    <SpendIndicator class="measure" @open="emit('action', 'costs')" />
    <SessionNote />
    <!-- Every button comes from ui/actions.ts: a new panel is one line there, none here. -->
    <nav class="bar-nav" :aria-label="t('palette.nav')">
      <button
        v-for="a in layout.direct"
        :key="a.id"
        :class="{ desk: a.id === 'desk', waiting: a.id === 'desk' && pending }"
        :title="tip(a)"
        :aria-keyshortcuts="a.key"
        :aria-label="t(a.label)"
        @click="emit('action', a.id)"
      >
        <!-- Narrower than ui/topBarLayout COMPACT_UPTO, a direct button shows its icon alone (style.css). -->
        <BarIcon :name="a.id" />
        <span class="direct-label" :class="{ keyed: !!a.key }">{{ t(a.label) }}</span>
        <span v-if="badges[a.id]" class="count" :class="{ 'stuck-badge': a.id === 'progress' }">{{ badges[a.id] }}</span>
      </button>
      <button
        v-for="m in layout.menus"
        :key="m.group"
        :data-menu="m.group"
        aria-haspopup="menu"
        :aria-expanded="openMenu === m.group"
        :title="groupLabel(m.group, 'Title')"
        @click="toggleMenu(m.group, $event)"
        @keydown.down.prevent="toggleMenu(m.group, $event, true)"
      >
        <BarIcon :name="m.group" />{{ groupLabel(m.group) }} <span v-if="groupBadge(m.items)" class="count">!</span> ▾
      </button>
      <button
        class="palette-open"
        :title="t('palette.openTitle')"
        :aria-label="t('palette.openTitle')"
        aria-keyshortcuts="Control+K"
        @click="emit('palette')"
      >
        ⌕
      </button>
    </nav>
    <template v-for="m in layout.menus" :key="m.group">
      <div
        v-if="openMenu === m.group"
        ref="menuEl"
        class="panel menu bar-dropdown"
        role="menu"
        :aria-label="groupLabel(m.group)"
        :style="{ left: `${menuAt.left}px`, top: `${menuAt.top}px` }"
        @keydown="menuKey"
      >
        <template v-if="m.group === 'work'">
          <button v-for="(r, i) in rooms" :key="r.id" role="menuitem" class="item" :title="i < 5 ? String(i + 1) : undefined" @click="pick(() => emit('room', i))">
            {{ tr(`room.${r.id}`, r.name) }}
          </button>
          <div class="sep" role="separator"></div>
          <template v-if="office.missions.length > 1">
            <div class="group-label" :id="`switch-${m.group}`">{{ t('board.switch') }}</div>
            <button
              role="menuitemradio"
              class="item"
              :aria-checked="!office.boardChoice"
              :title="t('board.switchTitle')"
              @click="pick(() => putOnBoard(null))"
            >
              <span class="mark">{{ office.boardChoice ? '' : '●' }}</span>{{ t('board.switchAuto') }}
            </button>
            <button
              v-for="ms in boardMissions"
              :key="ms.id"
              role="menuitemradio"
              class="item"
              :aria-checked="office.boardChoice === ms.id"
              :title="t('board.switchTitle')"
              @click="pick(() => putOnBoard(ms.id))"
            >
              <span class="mark">{{ office.boardChoice === ms.id ? '●' : '' }}</span>{{ ms.id }} · {{ ms.title }}
              <span class="key">{{ tr(`mstatus.${ms.status}`, ms.status) }}</span>
            </button>
            <div class="sep" role="separator"></div>
          </template>
        </template>
        <template v-for="a in m.items" :key="a.id">
          <a
            v-if="a.href"
            role="menuitem"
            class="item"
            :href="a.href"
            target="_blank"
            rel="noopener"
            :title="tip(a)"
            @click="closeMenu(false)"
          >
            {{ t(a.label) }}
          </a>
          <button v-else role="menuitem" class="item" :title="tip(a)" @click="pick(() => emit('action', a.id))">
            {{ t(a.label) }} <span v-if="badges[a.id]" class="count">{{ badges[a.id] }}</span>
          </button>
        </template>
        <template v-if="m.group === 'team'">
          <div class="sep" role="separator"></div>
          <button
            v-for="a in office.agents"
            :key="a.id"
            role="menuitem"
            class="item"
            :title="t('palette.openProfile', { role: a.role })"
            @click="pick(() => emit('open', a.id))"
          >
            {{ a.name }} <span class="key">{{ a.role }}</span>
          </button>
        </template>
      </div>
    </template>
  </div>

  <div ref="waitingBox" class="waiting-box"><WaitingBanner @open="(q) => emit('answer', q)" /></div>

  <CrewStrip
    @agent="(a) => emit('agent', a)"
    @open="(id) => emit('open', id)"
    @menu="(id, x, y) => emit('menu', id, x, y)"
    @action="(id) => emit('action', id)"
  />
</template>

<style scoped>
.group-label {
  color: var(--muted);
  font-size: 12px;
  text-transform: uppercase;
  padding: 2px 12px;
}
.mark {
  display: inline-block;
  width: 14px;
  color: var(--cyan);
}
.stuck-badge {
  background: var(--red, #e05050);
  color: #fff;
}
</style>
