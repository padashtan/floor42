const ANSI = /\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07\x1b\x9c\n]*(\x07|\x1b\\|\x9c)?/g

/** Tools print colours as ANSI escapes: an output is shown, copied and exported without them (the journal keeps it). */
export const plainText = (text: string): string => text.replace(ANSI, '')
