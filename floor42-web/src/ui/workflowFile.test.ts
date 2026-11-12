import codes from '../api/import-codes.json'
import { beforeAll, describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import { fr } from '../i18n/fr'
import { setLang } from '../i18n'
import { fileName, importProblemText, MAX_FILE_BYTES, readFileText } from './workflowFile'

// The codes the daemon can answer, written by the server's ImportCodesContractTest from WorkflowFile.Code: a code added
// there without its sentences fails here.
const CODES = codes as string[]

describe('the workflow file', () => {
  beforeAll(() => setLang('en'))

  it('has a sentence for each code, in both languages, none of them the general one', () => {
    for (const code of CODES) {
      expect(en, code).toHaveProperty(`workflows.imp.${code}`)
      expect(fr, code).toHaveProperty(`workflows.imp.${code}`)
      expect(importProblemText({ code, subject: 'x' }), code).not.toBe(en['workflows.imp.unknown'])
    }
  })

  it('knows the codes the daemon has', () => {
    expect(CODES.length).toBeGreaterThan(20)
    expect(CODES).toContain('IMPORT_TOO_LARGE')
  })

  it('says a code it does not know in general words and fills the subject', () => {
    expect(importProblemText({ code: 'IMPORT_FROM_THE_FUTURE', subject: null })).toBe(en['workflows.imp.unknown'])
    expect(importProblemText({ code: 'IMPORT_STEP_UNKNOWN', subject: 'ship' })).toContain('ship')
    expect(importProblemText({ code: 'IMPORT_STEP_UNKNOWN', subject: null })).toContain(en['workflows.imp.unnamed'])
  })

  it('names the file after the workflow with nothing a file name should not hold', () => {
    expect(fileName('Light read')).toBe('Light read.floor42-workflow.json')
    expect(fileName('a/b\\c:d')).toBe('a_b_c_d.floor42-workflow.json')
    expect(fileName('   ')).toBe('workflow.floor42-workflow.json')
  })

  it('never reads a file past one byte more than the cap', async () => {
    const big = new File(['x'.repeat(MAX_FILE_BYTES * 3)], 'big.json')
    expect((await readFileText(big)).length).toBe(MAX_FILE_BYTES + 1)
  })
})
