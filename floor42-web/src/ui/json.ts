/**
 * JSON laid out for reading. A body cut at the console's limit is no longer valid JSON: it is still indented, brace by
 * brace, so a long response stays readable; any other text is returned as it was sent.
 */
export function prettyJson(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    const start = body.trimStart()[0]
    return start === '{' || start === '[' ? indent(body) : body
  }
}

/** Indents JSON text without parsing it: strings are copied as they are, structure characters break lines. */
function indent(text: string): string {
  let out = ''
  let depth = 0
  let inString = false
  let escaped = false
  const newline = () => '\n' + '  '.repeat(Math.max(0, depth))
  for (const ch of text) {
    if (inString) {
      out += ch
      if (escaped) escaped = false
      else if (ch === '\\') escaped = true
      else if (ch === '"') inString = false
      continue
    }
    switch (ch) {
      case '"':
        inString = true
        out += ch
        break
      case '{':
      case '[':
        depth++
        out += ch + newline()
        break
      case '}':
      case ']':
        depth--
        out += newline() + ch
        break
      case ',':
        out += ch + newline()
        break
      case ':':
        out += ': '
        break
      case ' ':
      case '\n':
      case '\r':
      case '\t':
        break
      default:
        out += ch
    }
  }
  return out
}
