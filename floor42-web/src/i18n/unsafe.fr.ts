// Mode non sécurisé (ADR-0063) : les textes français, jumeaux de unsafe.en.ts (mêmes clés, mêmes champs).
import type { unsafeEn } from './unsafe.en'

export const unsafeFr: Record<keyof typeof unsafeEn, string> = {
  'unsafe.title': 'Mode non sécurisé',
  'unsafe.line': 'Une mission réelle n’exécute son shell que dans le bac à sable WSL. Sans bac à sable qui fonctionne, elle ne démarre pas.',
  'unsafe.warn': 'LE MODE NON SÉCURISÉ EST ACTIF : les missions réelles exécutent leur shell sous votre propre utilisateur, avec accès à vos fichiers, au journal et à vos identifiants.',
  'unsafe.sandbox.ok': 'Le bac à sable fonctionne sur cette machine.',
  'unsafe.sandbox.ko': 'Le bac à sable ne fonctionne pas sur cette machine : les missions réelles ne démarrent pas tant qu’il ne marche pas, ou que le mode non sécurisé n’est pas actif.',
  'unsafe.reason': 'Pourquoi en avez-vous besoin ?',
  'unsafe.on': 'Activer le mode non sécurisé',
  'unsafe.off': 'Désactiver le mode non sécurisé',
  'ev.BreakGlassSignIn': 'Le directeur s’est connecté avec le lien de la console (accès de secours) alors que le mode IAM est actif',
  'ev.UnsafeModeChanged.off': 'Le directeur a désactivé le mode non sécurisé : le shell d’une mission réelle repasse dans le bac à sable',
  'ev.UnsafeModeChanged.on': 'Le directeur a activé le mode non sécurisé : le shell d’une mission réelle peut s’exécuter hors du bac à sable, sous l’utilisateur du démon ({reason})',
}
