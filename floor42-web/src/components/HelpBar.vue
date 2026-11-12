<script setup lang="ts">
import { t } from '../i18n'
import { remote, toggleRemote } from '../state/remote'

defineProps<{ petSound: boolean; perf: string }>()
defineEmits<{ togglePetSound: [] }>()

</script>

<template>
  <div class="panel help">
    <b>{{ t('help.click') }}</b> {{ t('help.select') }} · <b>{{ t('help.doubleClick') }}</b> {{ t('help.open') }} ·
    <b>{{ t('help.rightClick') }}</b> {{ t('help.actions') }} · <b>{{ t('help.drag') }}</b>/<b>WASD</b> {{ t('help.pan') }} ·
    <b>{{ t('help.wheel') }}</b> {{ t('help.zoom') }} · <b>1–5</b> {{ t('help.rooms') }} · <b>B</b> {{ t('help.board') }} ·
    <b>D</b> {{ t('help.desk') }} · <b>W</b> {{ t('help.workshop') }} · <b>M</b> {{ t('help.meeting') }} ·
    <b>C</b> {{ t('help.console') }} · <b>U</b> {{ t('help.usage') }} · <b>Ctrl K</b> {{ t('palette.help') }} · <b>Esc</b> {{ t('help.close') }}
    <button class="pet-sound" type="button" :aria-pressed="petSound" :title="t('pet.sound.title')" @click="$emit('togglePetSound')">
      {{ petSound ? t('pet.sound.on') : t('pet.sound.off') }}
    </button>
    <button class="pet-sound remote-days" type="button" :aria-pressed="remote.on" :title="t('remote.days.title')" @click="toggleRemote">
      {{ remote.on ? t('remote.days.on') : t('remote.days.off') }}
    </button>
    <div class="fps">{{ perf }}</div>
  </div>
</template>
