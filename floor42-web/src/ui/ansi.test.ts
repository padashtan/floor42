import { expect, it } from 'vitest'
import { plainText } from './ansi'

it('strips colours and operating system commands, keeps the text', () => {
  expect(plainText('\x1b[31mred\x1b[0m')).toBe('red')
  expect(plainText('\x1b]0;window title\x07text')).toBe('text')
  expect(plainText('a\x1b]8;;https://example.org\x1b\\link\x1b]8;;\x1b\\b')).toBe('alinkb')
})

it('strips an operating system command ended by the 8-bit terminator, or never ended (to the end of the line)', () => {
  expect(plainText('a\x1b]0;title\x9cb')).toBe('ab')
  expect(plainText('a\x1b]0;never ended\nnext line')).toBe('a\nnext line')
})
