// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

/** Every attribute of every element the rendered HTML holds, parsed as a browser would. */
function attributes(html: string): string[] {
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html')
  return [...doc.body.querySelectorAll('*')].flatMap((el) => [...el.attributes].map((a) => a.name))
}

const ALLOWED = new Set(['href', 'title', 'rel', 'target', 'class'])

describe('renderMarkdown and replacement patterns', () => {
  const payloads = [
    '- [a](https://y/onmouseover=window.onerror=alert;throw/1///)\n  [b](https://z$`)',
    "- [a](https://y/x)\n  [b](https://z$')",
    '- [a](https://y/x)\n  text $& more',
    '- [a](https://y/x)\n  text $1 $2 $$ more',
    '- item\n  [c](https://w/$`onclick=alert(1)//)',
    '**$`** *$&* [$1](https://q/$`) `$\'`',
    '> quote $` [d](https://e/$`)',
  ]

  for (const p of payloads) {
    it(`adds no attribute from ${JSON.stringify(p)}`, () => {
      const html = renderMarkdown(p)
      const extra = attributes(html).filter((a) => !ALLOWED.has(a))
      expect(extra, html).toEqual([])
    })
  }

  it('keeps the dollar signs of a wrapped item as text', () => {
    const html = renderMarkdown('- one\n  costs $` and $& and $1')
    expect(html).toContain('costs $` and $&amp; and $1')
  })
})
