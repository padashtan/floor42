<script setup lang="ts">
import { computed, ref } from "vue";
import type { WorkspaceView } from "../api/types";
import { t } from "../i18n";

/** The workshop's files: the changes pending a decision, then every file of the workspace, filtered by name. */
const props = defineProps<{
  workspace: WorkspaceView | null;
  files: string[];
  path: string | null;
  mode: "diff" | "file" | "edit";
  busy: boolean;
}>();
const emit = defineEmits<{ open: [path: string, mode: "diff" | "file"]; newFile: []; discard: [path: string] }>();
/** The pending files left out of the next accept (ticked by default). */
const unticked = defineModel<string[]>("unticked", { default: () => [] });
const toggle = (p: string) =>
  (unticked.value = unticked.value.includes(p) ? unticked.value.filter((x) => x !== p) : [...unticked.value, p]);

const filter = ref("");
const changes = computed(() => props.workspace?.changes ?? []);
const leftOut = computed(() => props.workspace?.leftOut ?? []);
const shownFiles = computed(() => {
  const f = filter.value.trim().toLowerCase();
  return (f ? props.files.filter((p) => p.toLowerCase().includes(f)) : props.files).slice(0, 300);
});
</script>

<template>
  <div class="sub">{{ t("ws.pending") }} · {{ changes.length }}</div>
  <div v-if="changes.length === 0" class="hint">{{ t("ws.nothingPending") }}</div>
  <div v-for="c in changes" :key="c.path" class="change-line">
  <input
    type="checkbox"
    :checked="!unticked.includes(c.path)"
    :aria-label="t('ws.pickFile', { path: c.path })"
    @change="toggle(c.path)"
  />
  <button
    class="file-row"
    :class="{ on: path === c.path && mode === 'diff' }"
    @click="emit('open', c.path, 'diff')"
  >
    <span class="status" :class="c.status.toLowerCase()">{{
      c.status[0]
    }}</span>
    <span class="name">{{ c.path }}</span>
    <span
      v-if="workspace?.editedByDirector.includes(c.path)"
      class="mine"
      :title="t('ws.editedByYou')"
      >{{ t("ws.you") }}</span
    >
    <span class="counts">
      <span v-if="c.added !== null" class="plus">+{{ c.added }}</span>
      <span v-if="c.removed !== null" class="minus"
        >-{{ c.removed }}</span
      >
    </span>
  </button>
  <button class="link discard" :disabled="busy" :title="t('ws.discard')" @click="emit('discard', c.path)">
    {{ t("ws.discardShort") }}
  </button>
  </div>

  <div v-if="leftOut.length" class="hint left-out" :title="leftOut.join('\n')">
    {{ t("ws.leftOut", { count: leftOut.length, files: leftOut.slice(0, 3).join(", ") }) }}
  </div>

  <div class="sub">
    {{ t("ws.files") }} · {{ files.length }}
    <button class="link" :disabled="busy" @click="emit('newFile')">
      {{ t("ws.newFile") }}
    </button>
  </div>
  <input v-model="filter" class="filter" :placeholder="t('ws.filterFiles')" :aria-label="t('ws.filterFiles')" />
  <div class="file-list">
    <button
      v-for="f in shownFiles"
      :key="f"
      class="file-row"
      :class="{ on: path === f && mode !== 'diff' }"
      @click="emit('open', f, 'file')"
    >
      <span class="name">{{ f }}</span>
    </button>
  </div>
</template>
