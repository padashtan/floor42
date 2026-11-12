/**
 * The Markdown agents write (reports, reviews, findings) as safe HTML: every character of the source is escaped
 * first, then only a known subset is turned into tags (headings, lists, tables, quotes, code, bold, italics, links to
 * http(s) only). Nothing the agent wrote can add a tag, an attribute or a script. Text that is not Markdown reads as
 * plain paragraphs.
 */
export function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/** Inline marks on already escaped text. Code spans are cut out first so nothing inside them is interpreted. */
export function inline(escaped: string): string {
  const codes: string[] = []
  let s = escaped.replace(/`([^`]+)`/g, (_, c: string) => {
    codes.push(c)
    return `\u0000${codes.length - 1}\u0000`
  })
  s = s
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[\s(])\*([^*\s][^*]*)\*(?=[\s).,;:!?]|$)/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  return s.replace(/\u0000(\d+)\u0000/g, (_, i: string) => `<code>${codes[Number(i)]}</code>`) // NOSONAR S6324: the NUL placeholder never occurs in the escaped text
}

function cells(row: string): string[] {
  return row
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim())
}

// Tested on a trimmed line: no whitespace run next to another, so a long line of spaces cannot backtrack.
const TABLE_RULE = /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?$/

export function renderMarkdown(source: string): string {
  const lines = escapeHtml(source.replace(/\r\n/g, '\n')).split('\n')
  const out: string[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (/^\s*```/.test(line)) {
      const body: string[] = []
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i])) body.push(lines[i++])
      i++
      out.push(`<pre class="md-code">${body.join('\n')}</pre>`)
      continue
    }
    const heading = /^(#{1,4})\s+(.*)$/.exec(line)
    if (heading) {
      const level = heading[1].length + 2 // h3 to h6: a report sits inside a panel that has its own title
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      i++
      continue
    }
    if (line.includes('|') && i + 1 < lines.length && TABLE_RULE.test(lines[i + 1].trim())) {
      const head = cells(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows.push(cells(lines[i++]))
      out.push(
        '<table class="md-table"><thead><tr>' +
          head.map((c) => `<th>${inline(c)}</th>`).join('') +
          '</tr></thead><tbody>' +
          rows.map((r) => '<tr>' + r.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') +
          '</tbody></table>',
      )
      continue
    }
    if (/^\s*([-*+]|\d+[.)])\s+/.test(line)) {
      const ordered = /^\s*\d+[.)]/.test(line)
      const items: string[] = []
      while (i < lines.length && /^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) {
        const nested = /^\s{2,}/.test(lines[i])
        const text = inline(lines[i].replace(/^\s*([-*+]|\d+[.)])\s+/, ''))
        items.push(`<li${nested ? ' class="nested"' : ''}>${text}</li>`)
        i++
        // A wrapped item continues on indented lines.
        // A function, not a string: a string replacement would read $` $& $' in the agent's text as patterns.
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) {
          const more = ' ' + inline(lines[i++].trim()) + '</li>'
          items[items.length - 1] = items[items.length - 1].replace(/<\/li>$/, () => more)
        }
      }
      const tag = ordered ? 'ol' : 'ul'
      out.push(`<${tag}>${items.join('')}</${tag}>`)
      continue
    }
    if (/^&gt;\s?/.test(line)) {
      const body: string[] = []
      while (i < lines.length && /^&gt;\s?/.test(lines[i])) body.push(inline(lines[i++].replace(/^&gt;\s?/, '')))
      out.push(`<blockquote>${body.join('<br>')}</blockquote>`)
      continue
    }
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
      out.push('<hr>')
      i++
      continue
    }
    if (!line.trim()) {
      i++
      continue
    }
    const para: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^(#{1,4})\s|^\s*```|^\s*([-*+]|\d+[.)])\s+|^&gt;/.test(lines[i]) &&
      !(lines[i].includes('|') && i + 1 < lines.length && TABLE_RULE.test(lines[i + 1].trim()))
    )
      para.push(inline(lines[i++]))
    out.push(`<p>${para.join('<br>')}</p>`)
  }
  return out.join('\n')
}
