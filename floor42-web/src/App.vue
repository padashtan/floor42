<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AgentView } from './api/types'
import { deliverablesOnEnd, openDeliverablesOnEnd } from './state/missionEnd'
import ContextMenu, { type MenuAction } from './components/ContextMenu.vue'
import { agentOfQuestion } from './components/QuestionCard.vue'
import TopBar from './components/TopBar.vue'
import { firstPage } from './ui/demoMode'
import JournalBanner from './components/JournalBanner.vue'
import NightBanner from './components/NightBanner.vue'
import { startWaits, stopWaits, waitsChanged } from './state/waits'
import { t } from './i18n'
import { installPanels } from './ui/panels'
import type { MachineId } from './scene/mapping'
import type { SceneTarget } from './scene/kit'
import { notifyOpen, office, onLiveEvent, putOnBoard, resumeAgent, start } from './state/office'
import CommandPalette from './components/CommandPalette.vue'
import TutorialOffer from './components/TutorialOffer.vue'
import TutorialOverlay from './components/TutorialOverlay.vue'
import { restartTutorial, shouldOffer, skipTutorial, startTutorial } from './state/tutorial'
import { firstShow } from './ui/checklist'
import { ACTIONS } from './ui/actions'
import type { PaletteItem } from './ui/palette'
import { panelFlag, reportOf, setPanel } from './state/openPanels'
import { listenersOf, panelTable } from './ui/panelMounts'
import { keyHandler } from './ui/keyboard'
import { menuAfterPress } from './ui/panelLayout'
import { useOnePanel } from './ui/onePanel'
import { startContext } from './state/context'
import { useArchive } from './state/archive'
import { hoverText } from './ui/hoverText'
import ModalDialogs from './components/ModalDialogs.vue'
import SignInCard from './components/SignInCard.vue'
import ToastStack from './components/ToastStack.vue'
import HelpBar from './components/HelpBar.vue'
import { fire, suspend } from './ui/useAgentConfirm'
import { machineOf, pickAction, type MenuContext } from './ui/menuActions'
import { useOfficeScene } from './ui/useOfficeScene'

const canvas = ref<HTMLCanvasElement>()
const labels = ref<HTMLDivElement>()
const hud = ref<HTMLDivElement>()
let uninstallPanels: (() => void) | undefined
const settingsFor = ref<string | null>(null)
const selected = ref<SceneTarget | null>(null)
const menu = ref<{ target: SceneTarget; x: number; y: number } | null>(null)
const hover = ref<{ text: string; x: number; y: number } | null>(null)
const [deskOpen, deskFocus] = [panelFlag('desk'), ref<string | null>(null)]
const [meetingOpen, boardOpen] = [panelFlag('meeting'), panelFlag('board')]
const [archiveOpen, deliverablesOpen] = [panelFlag('archive'), panelFlag('deliverables')]
const { scene, rooms, night, perf, petSound, togglePetSound, drawArchive, follow } = useOfficeScene(canvas, labels, {
  onSelect: (t) => select(t),
  onContextMenu: (t, x, y) => (menu.value = { target: t, x, y }),
  onOpen: (t) => open(t),
  onHover: (t, x, y) => (hover.value = t && !menu.value ? { text: hoverText(t, archive.value), x, y } : null),
})
/** What git said of the mission's repository and its agents' branches (W6), read when the mission or its work changes. */
const archive = useArchive((a) => drawArchive(a))
follow(() => archive.value)
/** The guided first mission (ADR-0045): shown over the screen while open; its progress is kept per browser. */
const tutorialOpen = panelFlag('tutorial')
const signedIn = computed(() => !office.needsLogin && office.stream === 'live')
// What is missing on this machine: shown once, at the first signed-in page of this browser; then from Settings.
watch(
  signedIn,
  (s) => {
    if (firstShow(firstPage(s))) setPanel('sandbox', true)
  },
  { immediate: true },
)
const profileOf = ref<string | null>(null)
const requestsOf = ref<string | null>(null)
const outputsOf = ref<MachineId | null>(null)
const paletteOpen = ref(false)
/** Every panel of the HUD, from one table (ui/panelMounts.ts): a new panel is one line there. */
const mounted = panelTable({
  selected,
  deskFocus,
  archive,
  reportOf,
  profileOf,
  requestsOf,
  outputsOf,
  settingsFor,
  openDesk,
  selectAgent,
  suspend,
  resume: (id) => void resumeAgent(id),
  dismiss: (id) => void fireFromProfile(id),
})
const menuContext: MenuContext = {
  profileOf,
  requestsOf,
  outputsOf,
  settingsFor,
  focus: (t) => scene.value?.focus(t),
  select: (t) => select(t),
  openDesk,
}
/** One main panel at a time, a way back from a child panel, the focus back to the opener (ui/onePanel.ts). */
const onePanel = useOnePanel(mounted)
function select(t: SceneTarget | null) {
  // The robot is a mouse shortcut to the terminal, the very action of the menu; the pets only show their name.
  if (t?.kind === 'robot') {
    select(null)
    // A click on the canvas leaves the focus nowhere: put it on the bar, so closing the panel gives it back there.
    if (document.activeElement === document.body || document.activeElement === canvas.value) {
      document.querySelector<HTMLElement>('.bar-nav button[data-menu="system"]')?.focus({ preventScroll: true })
    }
    return runAction('terminal', false)
  }
  if (t?.kind === 'pet') {
    select(null)
    return greet(t)
  }
  selected.value = t
  scene.value?.select(t)
  menu.value = null
  if (t?.kind === 'desk') openDesk(null)
  // A director's meeting open: its panel comes with the meeting room's, whichever table was clicked (ADR-0049).
  if (t?.kind === 'table') meetingOpen.value = true
  if (t?.kind === 'table' && office.meetings.some((m) => m.open)) setPanel('callMeeting', true)
  if (t?.kind === 'board') boardOpen.value = true
  if (t?.kind === 'archive') archiveOpen.value = true
  if (t?.kind === 'library' || t?.kind === 'identity') setPanel(t.kind === 'library' ? 'library' : 'identities', true)
}

/** A click on a pet: its name over the pointer for a moment (the hover tooltip, for a touch screen too). */
let greeting = 0
function greet(t: SceneTarget) {
  const at = hover.value ?? { x: 0, y: 0 }
  const text = hoverText(t, archive.value)
  hover.value = { text, x: at.x, y: at.y }
  window.clearTimeout(greeting)
  // Only its own tooltip goes: if the pointer has since shown something else, that stays.
  greeting = window.setTimeout(() => {
    if (hover.value?.text === text) hover.value = null
  }, 2500)
}

function selectAgent(a: AgentView | string) {
  const id = typeof a === 'string' ? a : a.id
  const target: SceneTarget = { kind: 'agent', id }
  select(target)
  scene.value?.focus(target)
}

/** Double click: an agent opens what it waits for (its profile when nothing waits), a machine its outputs. */
function open(t: SceneTarget) {
  if (t.kind === 'agent') {
    if (office.questions.some((q) => agentOfQuestion(q) === t.id)) requestsOf.value = t.id
    else profileOf.value = t.id
  }
  if (t.kind === 'machine' || t.kind === 'decor') outputsOf.value = machineOf(t)
}

/** Double click on a crew card: the agent's profile (its terminal shows what it is doing now) and what it waits for. */
function openCrew(id: string) {
  selectAgent(id)
  profileOf.value = id
  requestsOf.value = office.questions.some((q) => agentOfQuestion(q) === id) ? id : null
}

/** Right click on a crew card: the same actions as on the agent in the scene. */
function crewMenu(id: string, x: number, y: number) {
  selectAgent(id)
  menu.value = { target: { kind: 'agent', id }, x, y }
}

function openDesk(question: string | null) {
  deskOpen.value = true
  deskFocus.value = question
}

function pick(action: MenuAction) {
  const target = menu.value?.target
  menu.value = null
  if (target) pickAction(menuContext, action, target)
}

/** A dismissed agent's profile closes: it has left through the door. */
async function fireFromProfile(id: string) {
  if ((await fire(id)) && profileOf.value === id) profileOf.value = null
}

/** Runs a registry action (ui/actions.ts): its key and its button toggle the panel, the palette opens it. */
function runAction(id: string, toggle = true) {
  const href = ACTIONS.find((a) => a.id === id)?.href
  if (href) return void window.open(href, '_blank', 'noopener')
  const flag = panelFlag(id)
  const open = toggle ? !flag.value : true
  if (id === 'desk' && open && !flag.value) deskFocus.value = null
  if (id === 'tutorial' && open) return startTutorial()
  setPanel(id, open)
}

/** The palette's commands outside the bar: the tutorial restarted from its first step, or skipped. */
const COMMANDS: Record<string, () => void> = { tutorialRestart: restartTutorial, tutorialSkip: skipTutorial }

/** A palette choice: an action, a room, an agent's profile, or a mission put on the board. */
function pickPalette(item: PaletteItem) {
  if (item.kind === 'action') runAction(item.id, false)
  if (item.kind === 'command') COMMANDS[item.id]?.()
  if (item.kind === 'room') scene.value?.goToRoom(Number(item.id))
  if (item.kind === 'agent') openCrew(item.id)
  if (item.kind === 'mission') void putOnBoard(item.id).then(() => (boardOpen.value = true))
}

/** A press anywhere outside the context menu closes it: the menu always has a way out besides Escape. */
function closeMenuOutside(e: PointerEvent) {
  menu.value = menuAfterPress(menu.value, e.target instanceof Element ? e.target : null)
}

const key = keyHandler({
  paletteOpen,
  menu,
  selected,
  mounted,
  select: (t) => select(t),
  runAction: (id) => runAction(id),
  goToRoom: (i) => scene.value?.goToRoom(i),
  focus: (t) => scene.value?.focus(t),
})

onMounted(() => {
  uninstallPanels = installPanels(hud.value!)
  // What waits for the director: the Progress badge, the tab's title, notifications, the "?" over a stuck mission.
  startWaits()
  onLiveEvent(() => waitsChanged())
  window.addEventListener('keydown', key)
  window.addEventListener('pointerdown', closeMenuOutside, true)
  // The context gauge's stream needs the session too: opened once the director is logged in.
  void start().then(() => {
    if (!office.needsLogin) startContext()
  })
})

onBeforeUnmount(() => {
  window.clearTimeout(greeting)
  window.removeEventListener('keydown', key)
  window.removeEventListener('pointerdown', closeMenuOutside, true)
  uninstallPanels?.()
  stopWaits()
})

// A mission's end opens its deliverables, unless a panel holds something the director typed: a toast offers them then.
openDeliverablesOnEnd(() =>
  deliverablesOnEnd(
    onePanel.shown(),
    () => (deliverablesOpen.value = true),
    () => notifyOpen(t('ux.toast.deliverables'), 'deliverables'),
  ),
)
</script>

<template>
  <!-- A new decade gets a new canvas: a WebGL context is never handed from one renderer to the next. -->
  <canvas :key="office.theme" ref="canvas" class="view" role="img" :aria-label="t('scene.label')"></canvas>
  <div ref="labels" class="name-tags"></div>

  <div ref="hud" class="hud">
    <!-- Signed out, the bar, the crew and the panels are not mounted: nothing to press, no call that ends in a 401. -->
    <TopBar
      v-if="!office.needsLogin"
      :rooms="rooms"
      @room="(i) => scene?.goToRoom(i)"
      @agent="selectAgent"
      @open="openCrew"
      @menu="crewMenu"
      @action="runAction"
      @palette="paletteOpen = !paletteOpen"
      @answer="openDesk"
    />
    <NightBanner v-if="night && !office.needsLogin" :night="night" />
    <JournalBanner v-if="!office.needsLogin && office.status" />
    <template v-if="!office.needsLogin">
      <template v-for="p in mounted" :key="p.id">
        <component :is="p.component" v-if="p.shown()" :data-panel="p.id" v-bind="p.props?.() ?? {}" v-on="listenersOf(p)" />
      </template>
    </template>

    <ToastStack @answer="openDesk" />
    <HelpBar :pet-sound="petSound" :perf="perf" @toggle-pet-sound="togglePetSound" />

    <TutorialOverlay v-if="tutorialOpen && signedIn" :report-open="!!reportOf" />
    <TutorialOffer v-else-if="shouldOffer(firstPage(signedIn))" />
    <CommandPalette v-if="paletteOpen" :rooms="rooms" @pick="pickPalette" @close="paletteOpen = false" />
    <ContextMenu v-if="menu" :target="menu.target" :x="menu.x" :y="menu.y" @pick="pick" @close="menu = null" />
    <ModalDialogs />
    <div v-if="hover" class="tooltip" :style="{ left: hover.x + 14 + 'px', top: hover.y + 14 + 'px' }">
      {{ hover.text }}
    </div>

    <SignInCard v-if="office.needsLogin" />
    <div v-if="office.error" class="panel error">
      {{ office.error }} <button class="close" @click="office.error = null">×</button>
    </div>
  </div>
</template>
