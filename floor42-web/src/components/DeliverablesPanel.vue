<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, deliverablesZipUrl } from '../api/client'
import type { DeliveryHomeView, DeliverablesView, DeliveredBranch, DeliveredFile } from '../api/types'
import { t, tr } from '../i18n'
import { cssColor, uniformOf } from '../scene/identity'
import { office } from '../state/office'
import { focusViewButton, openViewer, takeOpener, viewKey } from '../state/viewer'
import { byAgent, byTicket } from '../ui/deliverables'
import { merged, summary, where } from '../ui/deliverableTexts'
import DeliverableFiles from './DeliverableFiles.vue'
import ReviewItems from './ReviewItems.vue'
import UncommittedAlert from './UncommittedAlert.vue'
import DeliveryHome from './DeliveryHome.vue'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'
import TerminalBlock from './TerminalBlock.vue'

/**
 * What a mission delivered (ADR-0037), open or closed, on SectionedPanel: ticket texts with their reviews, the files of
 * each agent's branch, what reached the project's branch and the pull requests. Files are shown in a sandbox the daemon
 * sets, or downloaded; nothing here is journaled.
 */
const props = defineProps<{ mission: string | null }>()
const emit = defineEmits<{ close: []; agent: [id: string] }>()

// Back from the file viewer (ADR-0051): the list as it was, and the focus on the View button that opened it.
const back = takeOpener('deliverables')
const chosen = ref<string | null>((back?.state.chosen as string | null | undefined) ?? props.mission)
watch(
  () => props.mission,
  (m) => (chosen.value = m),
)
const missions = computed(() => [...office.missions].reverse())
const view = ref<DeliverablesView | null>(null)
/** Where the accepted deliverables land and what was copied there (ADR-0051); null for a mission without a folder. */
const home = ref<DeliveryHomeView | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
const ignored = ref(back?.state.ignored === true)
const section = ref<'ticket' | 'agent' | 'project'>((back?.state.section as 'ticket' | 'agent' | 'project' | undefined) ?? 'ticket')
/** The agent whose files were open when the viewer took this panel's place. */
const reopened = (back?.state.open as string | null | undefined) ?? null
const root = ref<HTMLElement | null>(null)

async function load(): Promise<void> {
  const id = chosen.value
  if (!id) return
  loading.value = true
  error.value = null
  try {
    const [v, m] = await Promise.all([api.deliverables(id, ignored.value), api.mission(id)])
    if (chosen.value === id) {
      view.value = v
      home.value = m.delivery
      if (back) void focusViewButton(() => root.value, back.key)
    }
  } catch (e) {
    error.value = (e as Error).message
    view.value = null
  } finally {
    loading.value = false
  }
}
watch(
  [chosen, ignored],
  () => void load(),
  { immediate: true },
)

const tabs = computed<SectionTab[]>(() => [
  { id: 'ticket', label: t('deliverables.byTicket') },
  { id: 'agent', label: t('deliverables.byAgent') },
  { id: 'project', label: t('deliverables.project') },
])
const name = (id: string | null) => (id ? (office.agents.find((a) => a.id === id)?.name ?? id) : t('deliverables.nobody'))
const tickets = computed(() => (view.value ? byTicket(view.value) : []))
const agents = computed(() => (view.value ? byAgent(view.value) : []))
const zip = computed(() => (chosen.value ? deliverablesZipUrl(chosen.value, ignored.value) : '#'))
const verdict = (v: string | null) => (v ? tr(`verdict.${v}`, v) : t('deliverables.noReview'))

/** Opens the file in the viewer panel (ADR-0051), which explains a file it cannot show and keeps Download. */
function show(branch: DeliveredBranch, file: DeliveredFile): void {
  const v = view.value
  if (!v) return
  openViewer(
    { mission: v.mission, agent: branch.agent, path: file.path, committed: file.committed, file, view: v },
    {
      from: 'deliverables',
      key: viewKey(branch.agent, file.path, file.committed),
      state: { chosen: chosen.value, section: section.value, ignored: ignored.value, open: branch.agent },
    },
  )
}

const folderNote = ref<string | null>(null)
async function openFolder(agent: string): Promise<void> {
  if (!chosen.value) return
  folderNote.value = null
  try {
    await api.openFolder(chosen.value, agent)
    folderNote.value = t('deliverables.folderOpened')
  } catch (e) {
    folderNote.value = (e as Error).message
  }
}
</script>

<template>
  <SectionedPanel
    class="deliverables-panel"
    id-prefix="deliverables"
    :title="t('deliverables.title')"
    :sections="tabs"
    :current="section"
    :focus-on-open="false"
    fit
    nav-width="130px"
    @choose="(id) => (section = id as typeof section)"
    @close="emit('close')"
  >
    <template #title-extra>
      <span v-if="view" class="hint">· {{ view.mission }} · {{ view.title }} · {{ tr(`mstatus.${view.status}`, view.status) }}</span>
    </template>
    <template #top>
      <div class="bar">
        <select v-model="chosen" :title="t('deliverables.missionTitle')" :aria-label="t('deliverables.missionTitle')">
          <option v-for="m in missions" :key="m.id" :value="m.id">{{ m.id }} · {{ m.title }} ({{ tr(`mstatus.${m.status}`, m.status) }})</option>
        </select>
        <label :title="t('deliverables.ignoredTitle')"><input v-model="ignored" type="checkbox" /> {{ t('deliverables.showIgnored') }}</label>
        <a v-if="chosen" class="button" :href="zip" download :title="t('deliverables.zipTitle')">{{ t('deliverables.zip') }}</a>
        <button :disabled="loading" @click="load">{{ t('deliverables.refresh') }}</button>
      </div>
      <div class="hint">{{ t('deliverables.readOnly') }}</div>
    </template>

    <div ref="root" class="deliverables-body">
    <div v-if="!chosen" class="hint">{{ t('board.noMission') }}</div>
    <div v-else-if="error" class="error-line">{{ error }}</div>
    <div v-else-if="!view" class="hint">{{ t('deliverables.loading') }}</div>
    <template v-else>
      <UncommittedAlert v-if="view.status === 'IN_PROGRESS'" :mission="view.mission" />
      <div v-if="home" class="home">
        <DeliveryHome v-if="home.copy" :copy="home.copy" />
        <div v-else class="hint">{{ t('home.willCopy', { folder: home.destination, mission: view.mission }) }}</div>
      </div>
      <template v-if="section === 'ticket'">
        <div v-if="tickets.length === 0" class="hint">{{ t('deliverables.noTicket') }}</div>
        <div v-for="g in tickets" :key="g.ticket.id" class="ticket">
          <div class="head">
            <b>{{ g.ticket.id }}</b> {{ g.ticket.title }}
            <span class="hint">· {{ tr(`board.col.${g.ticket.status}`, g.ticket.status) }} · {{ name(g.ticket.assignee) }}</span>
            <span class="verdict" :class="g.ticket.verdict ?? 'none'">{{ verdict(g.ticket.verdict) }}</span>
          </div>
          <TerminalBlock v-if="g.ticket.deliverable" :text="g.ticket.deliverable" :name="`${g.ticket.id}-deliverable`" format="markdown" height="160px" />
          <div v-else class="hint">{{ t('deliverables.noText') }}</div>
          <details v-for="(r, i) in g.ticket.reviews" :key="i" class="review">
            <summary>{{ t('deliverables.review', { n: i + 1, reviewer: name(r.reviewer), verdict: verdict(r.verdict) }) }}</summary>
            <ReviewItems :items="r.items" />
            <TerminalBlock v-if="r.findings" :text="r.findings" :name="`${g.ticket.id}-review-${i + 1}`" format="markdown" height="140px" />
          </details>
          <details v-if="g.branch" class="files-of" :open="reopened === g.branch.agent || undefined">
            <summary>{{ t('deliverables.filesOf', { name: name(g.branch.agent) }) }} · {{ summary(g.branch) }}</summary>
            <DeliverableFiles :mission="view.mission" :view="view" :branch="g.branch" @show="show" />
          </details>
        </div>
      </template>

      <template v-else-if="section === 'agent'">
        <div v-if="folderNote" class="hint">{{ folderNote }}</div>
        <div v-if="agents.length === 0" class="hint">{{ t('deliverables.noBranch') }}</div>
        <div v-for="g in agents" :key="g.agent ?? '-'" class="agent">
          <div class="head">
            <span v-if="g.agent" class="dot" :style="{ background: cssColor(uniformOf(g.agent)) }"></span>
            <button v-if="g.agent" class="link" @click="emit('agent', g.agent)">{{ name(g.agent) }}</button>
            <b v-else>{{ name(null) }}</b>
            <template v-if="g.branch">
              <code>{{ g.branch.branch ?? '?' }}</code>
              <span class="hint">· {{ merged(g.branch) }}</span>
            </template>
          </div>
          <div v-if="g.branch" class="where">
            <span class="hint">
              {{ g.branch.worktree ?? t('deliverables.noWorktree') }}
              <span v-if="g.branch.legacy">· {{ t('deliverables.legacy') }}</span>
              <span v-if="!g.branch.worktreePresent">· {{ t('deliverables.gone') }}</span>
            </span>
            <button v-if="g.branch.worktreePresent" class="small" :title="t('deliverables.openFolderTitle')" @click="openFolder(g.branch.agent)">
              {{ t('deliverables.openFolder') }}
            </button>
          </div>
          <div v-for="tk in g.tickets" :key="tk.id" class="mini">
            <b>{{ tk.id }}</b> {{ tk.title }} <span class="verdict" :class="tk.verdict ?? 'none'">{{ verdict(tk.verdict) }}</span>
          </div>
          <details v-if="g.branch && g.branch.commits.length" class="commits">
            <summary>{{ t('deliverables.commits', { count: g.branch.commits.length }) }}</summary>
            <div v-for="c in g.branch.commits" :key="c.hash" class="commit">
              <code>{{ c.hash }}</code> {{ c.subject }}
              <span class="hint">· {{ c.author }} · {{ c.inProject ? t('deliverables.inProject') : t('deliverables.notInProjectOne') }}</span>
            </div>
          </details>
          <DeliverableFiles v-if="g.branch" :mission="view.mission" :view="view" :branch="g.branch" @show="show" />
        </div>
      </template>

      <template v-else>
        <div class="sub">{{ t('deliverables.project') }} <span class="hint">· {{ where(view) }}</span></div>
        <div v-if="view.pullRequests.length === 0" class="hint">{{ t('deliverables.noPullRequest') }}</div>
        <div v-for="pr in view.pullRequests" :key="pr.number" class="pr">
          {{ t('deliverables.pullRequest', { number: pr.number, branch: pr.branch, base: pr.base }) }}
          <a :href="pr.url" target="_blank" rel="noopener noreferrer">{{ pr.url }}</a>
        </div>
      </template>
    </template>
    </div>
  </SectionedPanel>
</template>

<style scoped>
.deliverables-panel {
  top: 80px;
  left: 50%;
  transform: translateX(-50%);
  width: min(760px, calc(100vw - 32px));
  max-height: calc(100vh - 150px);
  z-index: 25;
}
.bar {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 4px;
}
.bar select {
  max-width: 300px;
}
.ticket,
.agent {
  border-top: 1px solid var(--muted);
  padding-top: 6px;
  margin-top: 8px;
}
.head,
.where {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.verdict {
  font-size: max(12px, 0.8em);
  border-radius: 3px;
  padding: 0 4px;
  border: 1px solid var(--muted);
}
.verdict.VALIDATED {
  border-color: var(--green);
  color: var(--green);
}
.verdict.VALIDATED_WITH_RESERVATIONS,
.verdict.UNVERIFIABLE {
  border-color: var(--amber);
  color: var(--amber);
}
.verdict.REJECTED {
  border-color: var(--red, #e05050);
  color: var(--red, #e05050);
}
.commit,
.mini {
  font-size: 0.9em;
  margin: 2px 0 2px 12px;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.link {
  background: none;
  border: none;
  padding: 0;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.small {
  font-size: max(12px, 0.8em);
  padding: 3px 6px;
}
.error-line {
  color: var(--red, #e05050);
}
</style>
