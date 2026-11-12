// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { useOnePanel } from './onePanel'
import type { PanelMount } from './panelMounts'

/** The focus around a main panel: it moves in when the panel opens, stays while it is open, comes back on close. */
describe('the focus of a main panel', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  const setup = () => {
    document.body.innerHTML = `
      <nav class="bar-nav"><button data-menu="team" id="opener">Team</button></nav>
      <div class="hud"><div class="panel away-panel framed" data-panel="away"><button>Got it</button></div></div>`
    const hud = document.querySelector('.hud')!
    const subject = ref<string | null>(null)
    // As Vue patches a panel in place: a new subject changes its text, the panel and its controls stay.
    const render = () => {
      const panel = hud.querySelector('[data-panel="profile"]')
      if (!subject.value) panel?.remove()
      else if (panel) panel.querySelector('#inner')!.textContent = subject.value
      else
        hud.insertAdjacentHTML(
          'beforeend',
          `<section class="panel api-console" data-panel="profile"><div class="title"><button class="close">x</button></div><button id="inner">${subject.value}</button></section>`,
        )
    }
    const mounts = [
      { id: 'away', component: {}, shown: () => true },
      { id: 'profile', component: {}, shown: () => subject.value !== null, close: () => (subject.value = null) },
    ] as unknown as PanelMount[]
    const scope = effectScope()
    scope.run(() => useOnePanel(mounts))
    const show = async (value: string | null) => {
      subject.value = value
      render()
      await nextTick()
      await nextTick()
    }
    return { show, scope }
  }

  it('stays in the panel when its subject changes, and goes back to the opener when it closes', async () => {
    const { show, scope } = setup()
    document.getElementById('opener')!.focus()
    await show('agent A')
    // Its own frame, whatever its classes, not the away summary docked before it.
    expect(document.activeElement?.getAttribute('data-panel')).toBe('profile')
    document.querySelector<HTMLElement>('#inner')!.focus()
    await show('agent B')
    expect(document.activeElement?.id).toBe('inner')
    await show(null)
    expect(document.activeElement?.id).toBe('opener')
    scope.stop()
  })
})
