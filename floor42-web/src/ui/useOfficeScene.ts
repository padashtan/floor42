import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref } from 'vue'
import type { ArchiveView } from '../api/types'
import { lang, t } from '../i18n'
import { eventText } from '../i18n/events'
import { cssColor, uniformOf } from '../scene/identity'
import { boardColumns } from '../scene/mapping'
import { participantsOf } from '../scene/ceremony'
import { residentsOf } from '../scene/presence'
import { OfficeScene, type SceneCallbacks } from '../scene/OfficeScene'
import { library, loadLibrary, spineColors } from '../state/library'
import { office, onLiveEvent } from '../state/office'
import { refreshDay, remoteIds } from '../state/remote'
import { waits } from '../state/waits'
import { presences } from './meetingTurns'
import { readIdentityLights, type IdentityLights } from './identityLights'

const PET_SOUND_KEY = 'floor42.petSound'

function readPetSound(): boolean {
  try {
    return localStorage.getItem(PET_SOUND_KEY) === '1'
  } catch {
    return false
  }
}

function petWords() {
  return { meow: t('pet.meow'), bark: t('pet.bark'), sleep: t('pet.sleep') }
}

/**
 * The 3D office as App.vue holds it: built on the canvas in the chosen decade, rebuilt on a new theme (agents in
 * place), and following the server state (agents, board, meetings, night, stuck agents, the archive, the language).
 * Also owns the performance readout and the pets' sound switch. Call once, from App.vue's setup.
 */
export function useOfficeScene(
  canvas: Ref<HTMLCanvasElement | undefined>,
  labels: Ref<HTMLDivElement | undefined>,
  callbacks: SceneCallbacks,
) {
  const scene = shallowRef<OfficeScene>()
  const perf = ref('')
  let perfTimer: number | undefined
  /** The identity server's last reading, drawn again when the scene is rebuilt (a new theme). */
  let identity: IdentityLights | null = null
  let identityTimer = 0
  async function readIdentity(): Promise<void> {
    if (office.needsLogin) return
    identity = (await readIdentityLights()) ?? identity
    if (identity) scene.value?.setIdentity(identity.health, identity.clients)
  }
  const rooms = computed(() => scene.value?.rooms ?? [])
  /** The office closes when the plan's quota is spent (measured by Claude Code); the server says when. */
  // The page checks the reset time itself: nothing is journaled at the moment a window resets.
  const clock = ref(Date.now())
  const night = computed(() => {
    const n = office.usage?.night ?? null
    return n && Date.parse(n.resumesAt) > clock.value ? n : null
  })
  // The pets' sounds: off until the director turns them on; the choice stays in this browser (storage may be refused).
  const petSound = ref(readPetSound())
  /** Those who worked on the mission in progress stay at their desks between two sessions (presence.ts). */
  const agentList = () => {
    const residents = residentsOf(office.missions)
    return office.agents.map((a) => ({
      id: a.id,
      name: a.name,
      status: a.status,
      suspended: a.suspended,
      core: a.core,
      resident: residents.has(a.id) && !a.suspended,
      remote: remoteIds.value.has(a.id) ? t('remote.sign') : undefined,
    }))
  }

  /** The scene's drawing of the archive: counts only, from what the repository said. */
  function drawArchive(a: ArchiveView | null): void {
    scene.value?.setArchive({
      available: !!a?.available,
      trunk: a?.trunk.length ?? 0,
      branches: (a?.branches ?? []).map((b) => ({ color: cssColor(uniformOf(b.agent)), ahead: b.ahead })),
    })
  }

  /** Builds the office in the chosen decade; a new theme builds it again, agents in place. */
  function buildScene(archive: () => ArchiveView | null): void {
    // The director stays where they were looking: same place, same zoom, another decade.
    const view = scene.value?.view
    scene.value?.dispose()
    document.documentElement.dataset.theme = office.theme
    const built = new OfficeScene(canvas.value!, labels.value, callbacks, office.theme)
    if (view) built.view = view
    built.setLanguage(lang.value)
    built.setPetWords(petWords())
    built.setPetSound(petSound.value)
    if (night.value !== undefined) built.setNight(!!night.value)
    built.setAgents(agentList())
    built.setBoard(boardColumns(office.mission?.tickets ?? []))
    built.setStuck(waits.stuckAgents)
    built.setMeetings(presences(office.meetings))
    built.setLibrary(spineColors(library.projects, office.theme))
    if (identity) built.setIdentity(identity.health, identity.clients)
    scene.value = built
    drawArchive(archive())
  }

  /** Wires the scene to the live events and the server state; `archive` is read when the scene is (re)built. */
  function follow(archive: () => ArchiveView | null): void {
    onMounted(() => {
      buildScene(archive)
      onLiveEvent((e) =>
        scene.value?.react(e, { text: eventText(e.code, e.params), waits: (subject) => t('scene.waitsFor', { subject }) }),
      )
      // A mission's end: those who worked on it gather at the meeting table (ADR-0039); the event alone calls for it.
      onLiveEvent((e) => {
        const m = office.mission
        if (e.type !== 'MissionCompleted' || !m || e.mission !== m.id) return
        scene.value?.celebrate(participantsOf(m))
      })
      perfTimer = window.setInterval(() => {
        clock.value = Date.now()
        refreshDay()
        const p = scene.value?.performance
        if (p) perf.value = p.fps > 0 ? t('perf.fps', { fps: p.fps.toFixed(0), resolution: p.resolution }) : t('perf.measuring')
      }, 1000)
      // The identity server shows what IAM mode measured, read again every minute (the daemon checks as often).
      void readIdentity()
      identityTimer = window.setInterval(readIdentity, 60_000)
    })
    onBeforeUnmount(() => {
      window.clearInterval(perfTimer)
      window.clearInterval(identityTimer)
      scene.value?.dispose()
    })
    // The scene follows server state: agents move when their derived status changes, the board mirrors the tickets.
    watch(
      () => agentList().map((a) => `${a.id}:${a.status}:${a.suspended}:${a.core}:${a.resident}:${a.remote ?? ''}`).join(','),
      () => scene.value?.setAgents(agentList()),
    )
    watch(
      () => JSON.stringify(presences(office.meetings)),
      () => scene.value?.setMeetings(presences(office.meetings)),
    )
    // The bookcase holds one spine per project the director knows, in the colours of the decade.
    watch(
      () => [library.projects.map((p) => p.path).join('|'), office.theme],
      () => scene.value?.setLibrary(spineColors(library.projects, office.theme)),
    )
    // A new mission can bring a project, and a pin or a forgotten folder moves it: read the list again.
    watch(() => [office.needsLogin, office.missions.length], () => !office.needsLogin && void loadLibrary(), { immediate: true })
    watch(night, (n) => scene.value?.setNight(!!n), { immediate: true })
    watch(() => office.needsLogin, (n) => !n && void readIdentity())
    watch(
      () => waits.stuckAgents.join(','),
      () => scene.value?.setStuck(waits.stuckAgents),
    )
    watch(
      lang,
      (l) => {
        scene.value?.setLanguage(l)
        scene.value?.setPetWords(petWords())
      },
      { immediate: true },
    )
    // After a reload the first click or key on the page is the gesture that lets the sound start.
    const unlockPets = () => scene.value?.unlockPetSound()
    onMounted(() => {
      window.addEventListener('pointerdown', unlockPets)
      window.addEventListener('keydown', unlockPets)
    })
    onBeforeUnmount(() => {
      window.removeEventListener('pointerdown', unlockPets)
      window.removeEventListener('keydown', unlockPets)
    })
    watch(
      () => office.theme,
      async () => {
        if (!scene.value) return
        await nextTick()
        buildScene(archive)
      },
    )
    watch(
      () => office.mission,
      (m) => scene.value?.setBoard(boardColumns(m?.tickets ?? [])),
    )
  }

  function togglePetSound() {
    petSound.value = !petSound.value
    scene.value?.setPetSound(petSound.value)
    scene.value?.unlockPetSound()
    try {
      localStorage.setItem(PET_SOUND_KEY, petSound.value ? '1' : '0')
    } catch {
      // The choice then lasts until the page closes.
    }
  }

  return { scene, rooms, night, perf, petSound, togglePetSound, drawArchive, follow }
}
