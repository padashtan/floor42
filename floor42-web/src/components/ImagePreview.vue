<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t } from '../i18n'
import { nextZoom, type Zoom } from '../ui/zoom'

/**
 * An image of the viewer panel (ADR-0051): only ever an <img>, so an SVG's scripts never run and nothing it names is
 * loaded. Fit to the panel by default; actual size and zoom steps on demand. `failed` when the browser cannot draw it
 * (the daemon refused it, or its bytes are no image): the panel then offers Download.
 */
const props = defineProps<{ src: string; alt: string }>()
const emit = defineEmits<{ failed: [] }>()

const zoom = ref<Zoom>('fit')
const natural = ref<{ w: number; h: number } | null>(null)
watch(
  () => props.src,
  () => {
    zoom.value = 'fit'
    natural.value = null
  },
)

function loaded(e: Event): void {
  const img = e.target as HTMLImageElement
  natural.value = { w: img.naturalWidth, h: img.naturalHeight }
}

const style = computed(() => {
  if (zoom.value === 'fit' || !natural.value) return {}
  return { width: `${Math.round(natural.value.w * zoom.value)}px`, maxWidth: 'none', maxHeight: 'none' }
})
const percent = computed(() => (zoom.value === 'fit' ? null : Math.round(zoom.value * 100)))
</script>

<template>
  <div class="image-preview">
    <div class="zoom-bar" role="toolbar" :aria-label="t('viewer.zoom')">
      <button class="small" :aria-pressed="zoom === 'fit'" @click="zoom = 'fit'">{{ t('viewer.fit') }}</button>
      <button class="small" :aria-pressed="zoom === 1" @click="zoom = 1">{{ t('viewer.actualSize') }}</button>
      <button class="small" :title="t('viewer.zoomOut')" :aria-label="t('viewer.zoomOut')" @click="zoom = nextZoom(zoom, -1)">−</button>
      <button class="small" :title="t('viewer.zoomIn')" :aria-label="t('viewer.zoomIn')" @click="zoom = nextZoom(zoom, 1)">+</button>
      <span v-if="percent !== null" class="hint" data-zoom>{{ percent }} %</span>
    </div>
    <div class="image-frame" :class="{ fit: zoom === 'fit' }">
      <img class="image" :src="src" :alt="alt" :style="style" @load="loaded" @error="emit('failed')" />
    </div>
  </div>
</template>

<style scoped>
.zoom-bar {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-bottom: 4px;
}
.small {
  font-size: max(12px, 0.8em);
  padding: 2px 6px;
}
.image-frame {
  overflow: auto;
  max-height: calc(100vh - 290px);
}
.image {
  display: block;
  margin: 0 auto;
  background: repeating-conic-gradient(#ccc 0% 25%, #fff 0% 50%) 50% / 16px 16px;
}
.fit .image {
  max-width: 100%;
  max-height: calc(100vh - 300px);
}
</style>
