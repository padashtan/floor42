<script setup lang="ts">
import { computed, ref } from 'vue'
import type { WorkflowsView } from '../api/types'
import { t, tr } from '../i18n'
import { type Selection, selectionId } from '../ui/workflowRows'

/**
 * The library (ADR-0052): Floor42's own workflow and templates, the company's, the planner's proposals. One button per workflow, so
 * the keyboard reaches each; the selected one is marked with aria-current.
 */
const props = defineProps<{ list: WorkflowsView | null; selected: Selection; busy: boolean }>()
const emit = defineEmits<{ select: [Selection]; new: [] }>()

const root = ref<HTMLElement>()
const current = computed(() => selectionId(props.selected))
const open = computed(() => (props.list?.proposals ?? []).filter((p) => p.status === 'OPEN'))

defineExpose({ focus: () => root.value?.querySelector<HTMLElement>('button')?.focus() })
</script>

<template>
  <nav ref="root" class="workflow-library" :aria-label="t('workflows.lib.title')">
    <div class="sub">{{ t('workflows.lib.builtIn') }}</div>
    <button
      class="lib-item"
      :aria-current="current === 'builtin' ? 'true' : undefined"
      @click="emit('select', { kind: 'builtin' })"
    >{{ t('workflows.builtIn') }}</button>
    <button
      v-for="tpl in list?.templates ?? []"
      :key="tpl.key"
      class="lib-item lib-template"
      :aria-current="current === `t:${tpl.key}` ? 'true' : undefined"
      @click="emit('select', { kind: 'template', key: tpl.key })"
    >{{ tr(`workflows.tpl.${tpl.key}`, tpl.key) }}</button>

    <div class="sub">{{ t('workflows.lib.company') }}</div>
    <button
      v-for="w in list?.workflows ?? []"
      :key="w.name"
      class="lib-item"
      :aria-current="current === `c:${w.name}` ? 'true' : undefined"
      @click="emit('select', { kind: 'company', name: w.name })"
    >
      {{ w.name }}
      <span v-if="!w.workflow" class="bad" :title="t('workflows.unreadable')">! {{ t('workflows.unreadable') }}</span>
    </button>
    <div v-if="list && !list.workflows.length" class="hint">{{ t('workflows.none') }}</div>

    <div class="sub">{{ t('workflows.lib.proposals', { count: open.length }) }}</div>
    <div v-if="!open.length" class="hint">{{ t('workflows.noProposal') }}</div>
    <button
      v-for="p in open"
      :key="p.number"
      class="lib-item"
      :aria-current="current === `p:${p.number}` ? 'true' : undefined"
      @click="emit('select', { kind: 'proposal', number: p.number })"
    >{{ t('workflows.lib.proposal', { name: p.workflow?.name ?? '', mission: p.mission }) }}</button>

    <button class="action lib-new" :disabled="busy || !list" @click="emit('new')">{{ t('workflows.new') }}</button>
    <slot />
  </nav>
</template>
