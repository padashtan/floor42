// Les textes français d'un domaine (le formulaire de nouvelle mission) ; i18n/fr.ts les fusionne.
import type { missionFormEn } from './missionForm.en'

export const missionFormFr: Record<keyof typeof missionFormEn, string> = {
  'mission.labelTitle': 'Titre',
  'mission.labelObjective': 'Objectif',
  'mission.labelIdea': 'Idée',
  'mission.labelProject': 'Projet',
  'mission.labelChecks': 'Commande de vérification (facultatif)',
  'mission.titleSuggest': 'Titre tiré de l’objectif :',
  'mission.titleUse': 'Utiliser ce titre',
  'mission.kindExisting': 'Projet existant : un dépôt git déjà sur cette machine',
  'mission.kindNew': 'Nouveau projet : Floor42 en crée un vide dans le dossier',
  'mission.fieldNewFolder': 'Dossier du nouveau projet, ex. C:\work\mon-nouveau-projet',
  'mission.notRepository': 'Ce dossier n’est pas un dépôt git. Choisissez un dépôt, ou « Nouveau projet ».',
  'mission.willCreate': 'Floor42 va créer un nouveau projet vide dans {folder}.',
  'mission.pathsMissing': 'L’objectif nomme des fichiers qui n’existent pas dans ce nouveau projet : {files}.',
  'afterArchive.title': 'Mission archivée. Que doit montrer le tableau maintenant ?',
  'afterArchive.put': 'Mettre au tableau',
  'afterArchive.empty': 'Laisser le tableau vide',
  'afterArchive.new': 'Nouvelle mission',
  'afterArchive.none': 'Aucune autre mission n’est ouverte.',
}
