import type { remoteEn } from './remote.en'

// The French texts of one area (remote), typed against the English ones: a missing or unknown key fails the build.
export const remoteFr: Record<keyof typeof remoteEn, string> = {
  // Télétravail : un choix d'affichage du bureau (décor), pas un fait du journal.
  'remote.title': "Télétravail aujourd'hui",
  'remote.sign': 'Télétravail',
  'remote.tip': "Un choix d'affichage du bureau, pas un fait du journal : aujourd'hui cet agent est dessiné à son bureau à la maison. Son travail, son état, ses permissions et ses sessions sont exactement les mêmes.",
  'remote.fold': 'Replier la bande',
  'remote.expand': 'Afficher la bande',
  'remote.more': '+{count} de plus',
  'remote.tile': "Télétravail aujourd'hui · {status}",
  'remote.days.on': 'Jours de télétravail : oui',
  'remote.days.off': 'Jours de télétravail : non',
  'remote.days.title': "Environ un agent sur quatre est dessiné en télétravail chaque jour (décor seul : rien ne change dans le travail)",
}
