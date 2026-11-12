<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api } from "../api/client";
import type { WorkshopView } from "../api/types";
import { t } from "../i18n";
import { office, onLiveEvent, workshopCall } from "../state/office";
import WorkshopActions from "./WorkshopActions.vue";
import WorkshopChecks from "./WorkshopChecks.vue";
import WorkshopFiles from "./WorkshopFiles.vue";
import WorkshopTrace from "./WorkshopTrace.vue";
import WorkshopViewer from "./WorkshopViewer.vue";
import { directiveMayGo } from "../ui/directiveWarning";
import { useWorkshopDecisions } from "../ui/workshopDecisions";
import { useWorkshopEditor } from "../ui/workshopEditor";
import { focusViewButton, openViewer, takeOpener, viewKey } from "../state/viewer";

const emit = defineEmits<{ close: [] }>();

const view = ref<WorkshopView | null>(null);
const agent = ref<string | null>(null);
const files = ref<string[]>([]);
const directive = ref("");
const ticket = ref<string | null>(null);
const message = ref("");
const reason = ref("");
const busy = ref(false);
/** The mission id the view was loaded for. */
const loadedFor = ref<string | null>(null);

const mission = computed(() => office.mission);
const { path, mode, text, binary, draft, note, mayLeaveDraft, show, open, startEdit, newFile, clear } =
  useWorkshopEditor(() => mission.value?.id, agent);
/** A pending change that still exists: the file viewer reads it from the worktree (ADR-0051). */
const viewable = computed(() =>
  (workspace.value?.changes ?? []).some((c) => c.path === path.value && !c.status.startsWith("D")),
);
function viewFile(p: string) {
  const m = mission.value;
  if (!m || !agent.value) return;
  openViewer(
    { mission: m.id, agent: agent.value, path: p, committed: false },
    { from: "workshop", key: viewKey(agent.value, p, false), state: { agent: agent.value, path: p } },
  );
}
// Back from the file viewer: the same workspace and file, and the focus on the View button that opened it.
const back = takeOpener("workshop");
const root = ref<HTMLElement | null>(null);
async function comeBack() {
  if (!back) return;
  await show(back.state.path as string, "file");
  await focusViewButton(() => root.value, back.key);
}
const workspace = computed(
  () => view.value?.workspaces.find((w) => w.agent === agent.value) ?? null,
);
const changes = computed(() => workspace.value?.changes ?? []);
const tickets = computed(() =>
  (mission.value?.tickets ?? []).filter((t) => t.status !== "CANCELLED"),
);
/** Who has a session running on the mission: the director waits for every one of them (the domain's rule). */
const atWork = computed(() => [
  ...new Set((mission.value?.sessions ?? []).filter((s) => s.endedAt === null).map((s) => s.agent)),
]);
const agentAtWork = computed(() => atWork.value.length > 0);
/** Work of this workspace the director accepted: what a pull request can carry. */
const accepted = computed(() =>
  (view.value?.interventions ?? []).some((i) => i.kind === "PATCH_ACCEPTED" && i.workspace === agent.value),
);

async function load() {
  const m = mission.value;
  if (!m) return;
  const v = await workshopCall(() => api.workshop(m.id), false);
  if (!v || mission.value?.id !== m.id) return;
  view.value = v;
  loadedFor.value = m.id;
  // Opens on a workspace that has changes to decide on, not on the first one, which may have none.
  if (!agent.value || !v.workspaces.some((w) => w.agent === agent.value))
    agent.value = (v.workspaces.find((w) => w.changes.length > 0) ?? v.workspaces[0])?.agent ?? null;
  if (!ticket.value) ticket.value = tickets.value[0]?.id ?? null;
  if (!message.value) message.value = `${m.title} (${m.id})`;
}

async function loadFiles() {
  const m = mission.value;
  if (!m || !agent.value) return;
  files.value =
    (await workshopCall(() => api.workshopFiles(m.id, agent.value!), false)) ??
    [];
}

async function act(run: () => Promise<WorkshopView | null>) {
  // The view on screen belongs to the mission it was loaded for: never act on another one.
  if (!loadedFor.value || loadedFor.value !== mission.value?.id) return null;
  busy.value = true;
  try {
    const v = await run();
    if (v) {
      view.value = v;
      await loadFiles();
      if (path.value && (await mayLeaveDraft()))
        await show(path.value, mode.value === "edit" ? "file" : mode.value);
    }
    return v;
  } finally {
    busy.value = false;
  }
}

const m = () => loadedFor.value ?? "";

async function save() {
  const p = path.value;
  if (!p || !agent.value) return;
  const v = await act(() =>
    workshopCall(async () => {
      const saved = draft.value;
      const edited = await api.editFile(m(), agent.value!, p, saved, note.value);
      if (path.value === p) text.value = saved; // saved: nothing left to lose when the file is shown again
      return edited;
    }),
  );
  // The director may have opened another file while it saved: the screen stays where they are.
  if (v && path.value === p) await show(p, "diff");
}

async function sendDirective() {
  if (!ticket.value || !directive.value.trim()) return;
  const target = tickets.value.find((x) => x.id === ticket.value);
  if (!(await directiveMayGo(ticket.value, target?.environmentBlocker))) return;
  const v = await act(() =>
    workshopCall(() =>
      api.directive(m(), ticket.value!, directive.value.trim()),
    ),
  );
  if (v) directive.value = "";
}

const { unticked, picked, accept, reject, discard } = useWorkshopDecisions({
  mission: m,
  agent, path,
  changes,
  message,
  reason,
  act,
  clearViewer: clear,
});

function short(sha: string) {
  return sha.slice(0, 8);
}

// Agents keep working while the workshop is open: reload when something happens on this mission.
let pending: number | undefined;
const unsubscribe = onLiveEvent((e) => {
  if (e.mission !== mission.value?.id) return;
  window.clearTimeout(pending);
  pending = window.setTimeout(() => void load(), 400);
});

onMounted(async () => {
  if (back) agent.value = back.state.agent as string;
  await load();
  await loadFiles();
  await comeBack();
});
onBeforeUnmount(() => {
  unsubscribe();
  window.clearTimeout(pending);
});
/** Switching workspace drops the open file: with a dirty draft it asks first, and a no stays on the workspace. */
let keeping = false;
watch(agent, async (_now, before) => {
  if (keeping) return;
  if (before && !(await mayLeaveDraft())) {
    keeping = true;
    agent.value = before;
    await nextTick();
    keeping = false;
    return;
  }
  clear();
  void loadFiles();
});

async function close() {
  if (await mayLeaveDraft()) emit("close");
}
watch(
  () => mission.value?.id,
  () => {
    view.value = null;
    loadedFor.value = null;
    agent.value = null;
    ticket.value = null;
    message.value = "";
    clear();
    void load();
  },
);
</script>

<template>
  <div ref="root" class="panel workshop">
    <div class="title">
      {{ t("ws.title") }}<span v-if="mission"
        >&nbsp;· {{ mission.id }} · {{ mission.title }}</span
      >
      <button class="close" :title="t('desk.close')" @click="close">
        ×
      </button>
    </div>

    <details class="workshop-help" :open="!!view && view.workspaces.length === 0">
      <summary>{{ t("ws.helpTitle") }}</summary>
      <ol>
        <li>{{ t("ws.help1") }}</li>
        <li>{{ t("ws.help2") }}</li>
        <li>{{ t("ws.help3") }}</li>
        <li>{{ t("ws.help4") }}</li>
      </ol>
      <div class="hint">{{ t("ws.helpWhen") }}</div>
    </details>

    <div v-if="!mission" class="hint">{{ t("board.noMission") }}</div>
    <div v-else-if="view && view.workspaces.length === 0" class="hint">{{ t("ws.noWorkspace") }}</div>

    <div v-else-if="view" class="workshop-body">
      <aside class="workshop-side">
        <div class="sub">{{ t("ws.workspace") }}</div>
        <select v-if="view.workspaces.length > 1" v-model="agent" :aria-label="t('ws.workspace')">
          <option v-for="w in view.workspaces" :key="w.agent" :value="w.agent">
            {{ w.agent }}{{ w.changes.length ? ` (${w.changes.length})` : "" }}
          </option>
        </select>
        <div v-if="workspace" class="hint">
          <b>{{ workspace.agent }}</b> · {{ t("ws.branch") }}
          <code>{{ workspace.branch }}</code
          ><br />
          {{ t("ws.base") }} {{ short(workspace.baseCommit)
          }}<span v-if="workspace.head !== workspace.baseCommit">
            · {{ t("ws.head") }} {{ short(workspace.head) }}</span
          >
        </div>
        <div v-if="agentAtWork" class="hint warn">
          {{ t("ws.agentAtWork", { who: atWork.join(", ") }) }}
        </div>

        <WorkshopChecks :mission="mission" :agent="agent" :busy="busy" :agent-at-work="agentAtWork" />
        <WorkshopFiles v-model:unticked="unticked" :workspace="workspace" :files="files" :path="path" :mode="mode" :busy="busy" @open="open" @new-file="newFile" @discard="discard" />
        <WorkshopTrace :interventions="view.interventions" />
      </aside>

      <WorkshopViewer
        v-model:draft="draft"
        v-model:note="note"
        :path="path"
        :mode="mode"
        :text="text"
        :binary="binary"
        :busy="busy"
        :agent-at-work="agentAtWork"
        :viewable="viewable"
        :view-key="agent && path ? viewKey(agent, path, false) : undefined"
        @open="open"
        @edit="startEdit"
        @save="save"
        @view="viewFile"
      />
    </div>

    <WorkshopActions
      v-if="view && view.workspaces.length"
      v-model:ticket="ticket"
      v-model:directive="directive"
      v-model:message="message"
      v-model:reason="reason"
      :mission="mission?.id ?? null"
      :workspace="workspace"
      :tickets="tickets"
      :pending="changes.length"
      :picked="picked.length"
      :busy="busy"
      :at-work="atWork"
      :accepted="accepted"
      @send="sendDirective"
      @accept="accept"
      @reject="reject"
    />
  </div>
</template>
