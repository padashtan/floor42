<script setup lang="ts">
/** The small icon before a top bar label: inline strokes in the text colour, decorative (the button carries the name). */
defineProps<{ name: string }>()
const PATHS: Record<string, string> = {
  desk: 'M2 7h12v6H2zM5 7V4h6v3',
  lock: 'M4 7h8v7H4zM6 7V5a2 2 0 014 0v2',
  unlock: 'M4 7h8v7H4zM6 7V5a2 2 0 014 0',
  board: 'M2 3h12v10H2zM6 3v10M10 3v10',
  progress: 'M2 13V8M6 13V4M10 13V7M14 13V2',
  work: 'M2 5h12v8H2zM6 5V3h4v2',
  team: 'M6 7a2 2 0 100-4 2 2 0 000 4zM2 13c0-3 2-4 4-4s4 1 4 4M11 7a2 2 0 100-4M12 9c2 0 3 1 3 4',
  system: 'M8 10a2 2 0 100-4 2 2 0 000 4zM8 2v2M8 12v2M2 8h2M12 8h2M4 4l1.5 1.5M10.5 10.5L12 12M12 4l-1.5 1.5M5.5 10.5L4 12',
}
</script>

<template>
  <svg class="bar-icon" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="PATHS[name] ?? PATHS.work" />
  </svg>
</template>

<style scoped>
.bar-icon {
  flex: none;
  transition: transform 0.15s ease;
}
button:hover > .bar-icon {
  transform: translateY(-1px) scale(1.15);
}
@media (prefers-reduced-motion: reduce) {
  .bar-icon {
    transition: none;
  }
  button:hover > .bar-icon {
    transform: none;
  }
}
</style>
