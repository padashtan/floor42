// Unsafe mode (ADR-0063): the texts of its setting and of its journal line, kept apart from en.ts, which is at its size
// ceiling (see i18n/index.ts, which merges them). Every key has its French twin in unsafe.fr.ts.
export const unsafeEn = {
  'unsafe.title': 'Unsafe mode',
  'unsafe.line': 'A real mission runs its shell only inside the WSL sandbox. Without a working sandbox it does not start.',
  'unsafe.warn': 'UNSAFE MODE IS ON: real missions run their shell as your own user on this machine, with access to your files, the journal and your credentials.',
  'unsafe.sandbox.ok': 'The sandbox works on this machine.',
  'unsafe.sandbox.ko': 'The sandbox does not work on this machine: real missions do not start until it does, or until unsafe mode is on.',
  'unsafe.reason': 'Why do you need it?',
  'unsafe.on': 'Switch unsafe mode on',
  'unsafe.off': 'Switch unsafe mode off',
  'ev.BreakGlassSignIn': 'The director signed in with the console link (break-glass) while IAM mode is on',
  'ev.UnsafeModeChanged.off': 'Director switched unsafe mode off: a real mission’s shell runs in the sandbox again',
  'ev.UnsafeModeChanged.on': 'Director switched unsafe mode on: a real mission’s shell may run without the sandbox, as the daemon’s own user ({reason})',
} as const
