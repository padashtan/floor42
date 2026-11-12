import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

describe('renderMarkdown', () => {
  it('never lets the source add a tag or an attribute', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)> **<script>x</script>** [a](javascript:alert(1))')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('href="javascript')
    expect(html).toContain('&lt;img')
  })

  it('renders the shapes agents write: headings, lists, tables, code', () => {
    const html = renderMarkdown(
      [
        '## Findings',
        '',
        '- **F1** wrong path in `README.md`',
        '- F2',
        '',
        '| Line | Verdict |',
        '|---|---|',
        '| 91 | **Wrong** |',
        '',
        '```',
        'git log --oneline',
        '```',
        'VERDICT: VALIDATED',
      ].join('\n'),
    )
    expect(html).toContain('<h4>Findings</h4>')
    expect(html).toContain('<li><strong>F1</strong> wrong path in <code>README.md</code></li>')
    expect(html).toContain('<th>Line</th>')
    expect(html).toContain('<td><strong>Wrong</strong></td>')
    expect(html).toContain('<pre class="md-code">git log --oneline</pre>')
    expect(html).toContain('<p>VERDICT: VALIDATED</p>')
  })

  it('keeps marks inside code spans as written', () => {
    expect(renderMarkdown('`**not bold**`')).toBe('<p><code>**not bold**</code></p>')
  })

  it('links only to http and https', () => {
    expect(renderMarkdown('[docs](https://example.org/a)')).toContain('href="https://example.org/a"')
  })

  it('stays fast on a long line of spaces after a table header candidate', () => {
    const start = performance.now()
    renderMarkdown('a|\n' + ' '.repeat(200_000) + 'x')
    expect(performance.now() - start).toBeLessThan(100)
  })

  it('still reads every form of table rule', () => {
    for (const rule of ['|---|---|', '--- | ---', '| :---: | ---: |', '  |---|  ']) {
      expect(renderMarkdown('a | b\n' + rule + '\n1 | 2')).toContain('<table')
    }
    expect(renderMarkdown('a | b\n-- | --\n1 | 2')).not.toContain('<table')
  })
})
