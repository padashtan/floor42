import { describe, expect, it } from 'vitest'
import { ask, askReason, confirmed, pending } from './confirm'

describe('the page confirmation', () => {
  it('answers true to a plain question and false once cancelled', async () => {
    const yes = confirmed({ message: 'Sure?' })
    expect(pending.value?.request.message).toBe('Sure?')
    pending.value?.settle(true)
    expect(await yes).toBe(true)
    const no = confirmed({ message: 'Sure?' })
    pending.value?.settle(null)
    expect(await no).toBe(false)
    expect(pending.value).toBeNull()
  })

  it('still requires a reason where prompt() asked for one: typed text, the recorded default when kept, null when cancelled', async () => {
    const typed = askReason({ message: 'Drop?', reason: { default: 'not needed' } })
    pending.value?.settle('too late')
    expect(await typed).toBe('too late')
    const kept = askReason({ message: 'Drop?', reason: { default: 'pas nécessaire', recorded: 'not needed' } })
    pending.value?.settle(' pas nécessaire ')
    expect(await kept).toBe('not needed')
    const cancelled = askReason({ message: 'Drop?', reason: { default: 'not needed' } })
    pending.value?.settle(null)
    expect(await cancelled).toBeNull()
  })

  it('queues a second request behind the open one', async () => {
    const first = ask({ message: 'one' })
    const second = ask({ message: 'two' })
    expect(pending.value?.request.message).toBe('one')
    pending.value?.settle(true)
    await first
    expect(pending.value?.request.message).toBe('two')
    pending.value?.settle(null)
    expect(await second).toBeNull()
  })
})
