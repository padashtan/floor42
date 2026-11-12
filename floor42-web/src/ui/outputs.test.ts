import { describe, expect, it } from 'vitest'
import { shapeOutput } from './outputs'

describe('shapeOutput', () => {
  it('moves the file reader line numbers to a gutter', () => {
    expect(shapeOutput('Read', '1\t# Floor42\n2\t\n3\t  indented')).toEqual({
      kind: 'numbered',
      lines: [
        { n: '1', text: '# Floor42' },
        { n: '2', text: '' },
        { n: '3', text: '  indented' },
      ],
    })
  })

  it('splits search matches into file, line and text, Windows paths included', () => {
    const shape = shapeOutput('Grep', 'floor42-server\\src\\A.java:55:  boolean x\nC:\\w\\B.java:7:y')
    expect(shape).toEqual({
      kind: 'matches',
      lines: [
        { path: 'floor42-server/src/A.java', n: '55', text: '  boolean x' },
        { path: 'C:/w/B.java', n: '7', text: 'y' },
      ],
    })
  })

  it('lists paths with one separator', () => {
    expect(shapeOutput('Glob', 'a\\b.java\nc/d.ts')).toEqual({ kind: 'paths', lines: ['a/b.java', 'c/d.ts'] })
  })

  it('leaves anything else exactly as recorded', () => {
    const text = 'BUILD SUCCESS\n  in 3 s'
    expect(shapeOutput('Bash', text)).toEqual({ kind: 'text', text })
    expect(shapeOutput('Read', 'not numbered')).toEqual({ kind: 'text', text: 'not numbered' })
  })
})
