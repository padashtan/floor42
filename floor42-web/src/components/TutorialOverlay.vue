<script setup lang="ts">
// The guided first mission over the real screen (ADR-0045): a ring around the element the step is about and a card
// that says what it is for. It reads the page and never clicks for the director: each step waits for their action.
// Not part of the 3D scene: it only reads the DOM and the shared state. Escape (focus in the card) or × hides it; it
// resumes from the Work menu or the palette. The card takes the focus when it opens and at each new step.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../i18n'
import { panels, setPanel } from '../state/openPanels'
import { office } from '../state/office'
import { abandonRehearsal, useRehearsal } from '../state/rehearsal'
import { send, skipTutorial, tutorial } from '../state/tutorial'
import { canGoOn, indexOf, type Observation, type ObservedMission, STEP_IDS, stepOf } from '../ui/tutorial'
import GoingRealChecklist from './GoingRealChecklist.vue'

const props = defineProps<{ reportOpen: boolean }>()

const step = computed(() => (tutorial.progress.kind === 'running' ? stepOf(tutorial.progress.step) : null))
const rect = ref<{ top: number; left: number; width: number; height: number } | null>(null)
const shown = ref(false)
const card = ref<HTMLElement>()
const practiceId = () => (tutorial.progress.kind === 'running' ? tutorial.progress.mission : null)
/** Read from the daemon: whether the rehearsal's script still runs, and the workshop decision (tutorial.decided). */
const { state: practice } = useRehearsal(practiceId)
const stopped = computed(() => practice.value === 'STOPPED' && !!step.value && indexOf(step.value.id) < indexOf('deliverables'))
const busy = ref(false)

/** The element is laid out and inside the window: a closed panel or a hidden tab is not "on screen". */
function onScreen(el: Element | null): el is Element {
  if (!el) return false
  const r = el.getBoundingClientRect()
  return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight
}

function locate(): void {
  const s = step.value
  if (!s) return void (rect.value = null)
  shown.value = onScreen(document.querySelector(s.targets[0]))
  const el = s.targets.map((sel) => document.querySelector(sel)).find(onScreen)
  // What the card itself holds (the last step's checklist) needs no ring.
  const r = el?.closest('.tutorial-card') ? undefined : el?.getBoundingClientRect()
  rect.value = r ? { top: r.top, left: r.left, width: r.width, height: r.height } : null
}

function mission(): ObservedMission | null {
  const p = tutorial.progress
  const m = office.mission
  if (p.kind !== 'running' || !m || m.id !== p.mission) return null
  const questions = m.questions.map((q) => ({ kind: q.kind, status: q.status }))
  return { id: m.id, status: m.status, capDecided: m.capDecided, questions }
}

const observation = computed<Observation>(() => ({
  open: {
    desk: !!panels.desk,
    workshop: !!panels.workshop,
    deliverables: !!panels.deliverables,
    report: props.reportOpen,
    costs: !!panels.costs,
    sandbox: !!panels.sandbox,
  },
  shown: shown.value,
  sample: tutorial.sample,
  mission: mission(),
  decided: tutorial.decided,
}))
watch(observation, (o) => send({ kind: 'observed', observation: o }), { immediate: true })
// A new step points at its element at once, never at the previous one's, and the card takes the focus.
watch(
  () => step.value?.id,
  () =>
    void nextTick(() => {
      locate()
      card.value?.focus({ preventScroll: true })
    }),
)
watch(
  () => tutorial.progress.kind,
  (kind) => kind === 'finished' && setPanel('tutorial', false),
)

let timer: number | undefined
onMounted(() => {
  locate()
  card.value?.focus({ preventScroll: true })
  timer = window.setInterval(locate, 250)
  window.addEventListener('resize', locate)
})
onBeforeUnmount(() => {
  window.clearInterval(timer)
  window.removeEventListener('resize', locate)
})

const number = computed(() => (step.value ? indexOf(step.value.id) + 1 : 0))
const last = computed(() => number.value === STEP_IDS.length)
const waits = computed(() => step.value?.completion.kind === 'until')
const ready = computed(() => canGoOn(tutorial.progress, observation.value))
/** The card stays on the side away from the element, so it never covers what it points at. */
const side = computed(() => (rect.value && rect.value.left + rect.value.width / 2 < innerWidth / 2 ? 'right' : 'left'))
const ring = computed(() => {
  const r = rect.value
  return r && { top: `${r.top - 4}px`, left: `${r.left - 4}px`, width: `${r.width + 8}px`, height: `${r.height + 8}px` }
})

const next = () => send({ kind: 'next' }, observation.value)
const hide = () => setPanel('tutorial', false)

async function startAgain(): Promise<void> {
  const id = practiceId()
  if (!id) return
  busy.value = true
  try {
    await abandonRehearsal(id)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="tutorial-overlay">
    <div v-if="ring" class="tutorial-ring" :style="ring" aria-hidden="true"></div>
    <section
      v-if="step"
      class="panel tutorial-card"
      :class="side"
      ref="card"
      tabindex="-1"
      role="dialog"
      aria-modal="false"
      aria-labelledby="tutorial-step-title"
      aria-describedby="tutorial-step-body"
      @keydown.esc.stop.prevent="hide"
    >
      <div class="title">
        {{ t('tutorial.title') }} · {{ t('tutorial.stepOf', { n: number, total: STEP_IDS.length }) }}
        <button class="close" :title="t('tutorial.hide')" :aria-label="t('tutorial.hide')" @click="hide">
          ×
        </button>
      </div>
      <div class="hint warn">{{ t('tutorial.simulated') }}</div>
      <h3 id="tutorial-step-title">{{ t(step.title) }}</h3>
      <p id="tutorial-step-body">{{ t(step.body) }}</p>
      <p v-if="!shown && step.targets.length > 1" class="hint">{{ t('tutorial.followRing') }}</p>
      <div v-if="stopped" class="stopped" role="alert">
        <p class="warn">{{ t('tutorial.stopped') }}</p>
        <button class="action recommended" :disabled="busy" @click="startAgain">{{ t('tutorial.startAgain') }}</button>
      </div>
      <GoingRealChecklist v-if="step.id === 'goingReal'" />
      <div class="buttons">
        <span v-if="waits" class="hint waiting">{{ t('tutorial.waiting') }}</span>
        <button v-else class="action recommended" :disabled="!ready" @click="next">
          {{ last ? t('tutorial.finish') : t('tutorial.next') }}
        </button>
        <button class="action" @click="skipTutorial">{{ t('tutorial.skip') }}</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.tutorial-ring {
  position: fixed;
  z-index: 60;
  pointer-events: none;
  border: 2px solid var(--amber);
  border-radius: 6px;
  box-shadow: 0 0 0 4px rgba(255, 191, 0, 0.25);
  animation: tutorial-pulse 1.6s ease-in-out infinite;
}
.tutorial-card {
  position: fixed;
  z-index: 61;
  bottom: 56px;
  width: min(340px, calc(100vw - 48px));
}
.tutorial-card.left {
  left: 24px;
}
.tutorial-card.right {
  right: 24px;
}
.tutorial-card:focus {
  outline: 2px solid var(--amber);
}
.tutorial-card h3 {
  margin: 6px 0 4px;
  font-size: 14px;
}
.tutorial-card p {
  margin: 4px 0;
}
.buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.warn {
  color: var(--amber);
}
@keyframes tutorial-pulse {
  50% {
    box-shadow: 0 0 0 8px rgba(255, 191, 0, 0.1);
  }
}
@media (prefers-reduced-motion: reduce) {
  .tutorial-ring {
    animation: none;
  }
}
</style>
