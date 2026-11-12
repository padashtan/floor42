// Jokes told by idle agents in the kitchen and at the ping-pong table. A fixed local list: no model is asked, nothing
// is recorded, and they are shown apart from the activity line, which only ever carries the journal's own words.

export const JOKES: Record<'en' | 'fr', string[]> = {
  en: [
    'There are 10 kinds of people: those who read binary and those who do not.',
    'It works on my machine. Ship the machine?',
    'A SQL query walks into a bar, sees two tables: "May I join you?"',
    'I would tell you a UDP joke, but you might not get it.',
    'Why do Java developers wear glasses? They do not C#.',
    'Debugging: being the detective in a crime movie where you are also the murderer.',
    'Two hard things: cache invalidation, naming things, and off-by-one errors.',
    'My code has no bugs. It has undocumented features.',
    'I asked the reviewer for feedback. It was a critical objection.',
    'Coffee in, code out. Mostly.',
    'git commit -m "final fix (really)"',
    'The budget held my session. Ping-pong it is.',
    'A consensus is not a proof. But it is a nice change.',
    'Unfamiliar stream line? That was me humming.',
  ],
  fr: [
    'Il y a 10 sortes de gens : ceux qui lisent le binaire et les autres.',
    'Ça marche sur ma machine. On livre la machine ?',
    'Une requête SQL entre dans un bar, voit deux tables : « Je peux me joindre à vous ? »',
    'Je te raconterais bien une blague UDP, mais tu risques de ne pas la recevoir.',
    'Pourquoi les développeurs Java portent des lunettes ? Parce qu’ils ne voient pas en C#.',
    'Déboguer, c’est être le détective d’un polar dont on est aussi l’assassin.',
    'Deux choses difficiles : l’invalidation de cache, le nommage, et les erreurs de un.',
    'Mon code n’a pas de bugs. Il a des fonctionnalités non documentées.',
    'J’ai demandé l’avis du relecteur. C’était une objection critique.',
    'Du café entre, du code sort. En général.',
    'git commit -m "correction finale (vraiment)"',
    'Le budget a mis ma session en attente. Ping-pong, alors.',
    'Un consensus n’est pas une preuve. Mais ça change.',
    'Une ligne de flux inconnue ? C’était moi qui fredonnais.',
  ],
}

/**
 * What an idle colleague answers an agent thinking aloud: decoration from a fixed list, shown like the jokes. The
 * thinking agent's own bubble carries its real words, from the journal.
 */
export const REACTIONS: Record<'en' | 'fr', string[]> = {
  en: ['Hmm.', 'Interesting!', 'And the tests?', 'Are you sure?', 'I see.', 'What if we kept it simpler?', 'Go on.', 'Coffee?'],
  fr: ['Hmm.', 'Intéressant !', 'Et les tests ?', 'Tu es sûr ?', 'Je vois.', 'Et si on simplifiait ?', 'Continue.', 'Un café ?'],
}

/** Seconds between two jokes of the same agent, and how long one stays up. */
export const JOKE_EVERY = { min: 25, max: 50, shown: 7 }
