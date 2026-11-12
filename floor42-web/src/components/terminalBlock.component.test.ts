// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { setLang } from '../i18n'
import TerminalBlock from './TerminalBlock.vue'

describe('TerminalBlock', () => {
  it('shows a coloured output without its ANSI escapes', () => {
    setLang('en')
    const text = '\x1b[1m\x1b[46m RUN \x1b[49m\x1b[22m\n \x1b[32m✓\x1b[39m src/scene/nav.test.ts'
    const w = mount(TerminalBlock, { props: { text } })
    expect(w.text()).toContain('RUN')
    expect(w.text()).toContain('✓ src/scene/nav.test.ts')
    expect(w.html()).not.toContain('\x1b')
    expect(w.html()).not.toContain('[32m')
  })
})
