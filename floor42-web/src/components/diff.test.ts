import { describe, expect, it } from 'vitest'
import { diffLines } from './diff'

describe('diffLines', () => {
  it('separates headers, hunks, additions, removals and context', () => {
    const diff = [
      'diff --git a/README.md b/README.md',
      'index 1111111..2222222 100644',
      '--- a/README.md',
      '+++ b/README.md',
      '@@ -1,2 +1,2 @@',
      ' # Project',
      '-old line',
      '+new line',
      '',
    ].join('\r\n')

    expect(diffLines(diff).map((l) => l.kind)).toEqual([
      'meta',
      'meta',
      'meta',
      'meta',
      'hunk',
      'context',
      'remove',
      'add',
    ])
  })

  it('does not mistake the file headers for changes', () => {
    const kinds = diffLines('--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+hello\n').map((l) => l.kind)

    expect(kinds).toEqual(['meta', 'meta', 'hunk', 'add'])
  })
})
