# Feuille de route — coffre-navigateur

Extension de navigateur (Chrome, Edge, Firefox) qui garde les mots de passe de l'utilisateur,
**chiffrés dans le navigateur**, et les dépose dans son espace personnel de `storage` du lab. La
clé de chiffrement vit dans un fichier que l'utilisateur garde sur une clé USB ; elle ne passe
jamais par Internet. Format des fichiers : `docs/FORMAT.md` (fait foi).

Décisions du propriétaire (2026-11-18) :
- aucun nouveau service : seule l'extension est construite ; `storage` est utilisé tel quel ;
- l'extension s'ouvre à tout compte rattaché au lab (membre d'au moins un groupe) ;
- la clé est chargée à la main par l'utilisateur, à partir de sa clé USB, avec un mode d'emploi
  détaillé ; il est très fortement déconseillé de l'envoyer par Internet ;
- deux réglages côté serveur acceptés : un client Keycloak pour l'extension et son ajout à
  `KEYCLOAK_TRUSTED_CLIENTS` de `storage`.

## Commande de test
npm test

## Lot 1 — Socle : format, chiffrement, générateur, squelette d'extension   ·   statut : livré
**Objectif :** contrat de format écrit et versionné ; bibliothèque de chiffrement et générateur
testés ; une extension qui se charge dans les trois navigateurs avec le générateur dans la popup.
**Tâches :**
- [x] `docs/FORMAT.md` v1 : fichier de clé, enveloppe d'entrée, descripteur public, limites
- [x] `src/lib/crypto.js` : PBKDF2-SHA256 600 000 itérations → AES-256-GCM, clé non extractible,
  données associées liant format, version, clé et id ; erreurs à code fixe
- [x] `src/lib/generator.js` : tirage uniforme par rejet, une famille de chaque, mélange
- [x] Manifeste MV3 commun, `scripts/build.mjs` → `dist/chrome` (Chrome et Edge), `dist/firefox`
- [x] Popup : générateur, copie, presse-papiers vidé au bout de 30 s
**Tests à passer avant de clore le lot :**
- [x] `npm test` : 21 tests (chiffrement aller-retour, mauvaise phrase, fichier altéré, itérations
  abaissées, enveloppe d'un autre coffre ou sous un autre id, clé non extractible, générateur)
- [x] `npm run build && node scripts/essai-chromium.cjs` : extension chargée dans Chromium, popup sans erreur
**Vérifié le :** 2026-11-18 — `npm test` 21/21 ; essai Chromium (Playwright, Chromium du conteneur)
sans erreur de console. Firefox et Edge non essayés (pas de navigateur ici).

## Lot 2 — Créer, importer, déverrouiller le coffre (local)   ·   statut : livré
**Objectif :** l'utilisateur crée son coffre, enregistre son fichier de clé sur USB, l'importe
dans un autre navigateur, déverrouille et verrouille — sans serveur.
**Tâches :**
- [x] Page de gestion (`pages/coffre.html`, page d'options) : « Créer mon coffre » / « J'ai déjà un fichier de clé »
- [x] Création en trois étapes : phrase de passe (deux fois, indicateur de solidité, phrase faible
  refusée) ; enregistrement (« Enregistrer sur la clé USB… » par sélecteur de fichier sous
  Chrome/Edge, téléchargement sinon, consignes pour sortir le fichier de « Téléchargements ») ;
  vérification obligatoire d'une copie relue depuis la clé USB + deux cases à cocher. Rien n'est
  installé avant « Terminer »
- [x] Import : choix du fichier, phrase de passe, contrôle du format ; mauvaise phrase ⇒ rien d'installé
- [x] Fichier de clé (chiffré) en `storage.local` ; clé déverrouillée en `storage.session` seulement
  (docs/FORMAT.md §5) ; verrouillage après inactivité (1 min à 4 h, 15 min par défaut), échéance
  repoussée à chaque usage, alarme qui efface la clé même popup fermée
- [x] Changer de phrase de passe (nouveau fichier téléchargé), refaire une copie, retirer le coffre du navigateur
- [x] Popup : état du coffre (configurer / déverrouiller / verrouiller) au-dessus du générateur
- [x] Guide intégré (`pages/guide.html`) : principe, création, clé USB pas à pas, autre
  navigateur, quotidien, changement de phrase, pertes, interdits
- [x] Permissions ajoutées : `storage` (fichier de clé, réglages, session), `alarms` (verrouillage
  automatique même popup fermée). Processus de fond : seulement le verrouillage
**Tests à passer avant de clore le lot :**
- [x] `npm test` : 35 tests (dont 14 sur l'état du coffre : installation, mauvaise phrase,
  expiration glissante, alarme, autre coffre, changement de phrase, retrait ; solidité de la phrase)
- [x] `npm run essai` : 25 contrôles dans Chromium — création complète avec téléchargement réel et
  vérification de copie, contenu exact de `storage.local`/`storage.session`, phrase écrite nulle
  part, alarme planifiée, popup verrouiller/déverrouiller, mauvaise phrase, verrouillage à
  échéance, import dans un second profil, retrait ; aucune erreur de console
**Vérifié le :** 2026-11-18 — `npm test` 35/35 ; `npm run essai` 25/25 (Chromium du conteneur,
Playwright). Non essayés : Firefox et Edge, et le bouton « Enregistrer sur la clé USB… » (sélecteur
natif, non pilotable en essai automatique) — à vérifier à la main au lot 6.

## Lot 3 — Connexion au lab et synchronisation par `storage`   ·   statut : en cours
**Objectif :** les entrées chiffrées sont déposées dans `coffre/v1/` de l'espace personnel et
récupérées sur un autre navigateur.
**Décision du propriétaire (2026-11-18) :** installation en mode développeur ; identifiant
Chrome/Edge fixé par la clé publique du manifeste (`kainmmlggnfinglcapceocelpngjhnol`), Firefox par
son id (`coffre-navigateur@lab.local`). Les identifiants des boutiques s'ajouteront au client plus tard.
**Reste :** l'essai réel contre le lab, par le propriétaire, après les réglages serveur du README.
**Tâches :**
- [x] Adresse du lab saisie par l'utilisateur (https seulement ; http admis pour la seule boucle
  locale, pour les essais) ; accès à cette seule origine demandé à ce moment
  (`optional_host_permissions`), jamais `<all_urls>` d'office
- [x] Connexion Keycloak par le processus de fond : `identity.launchWebAuthFlow`, code + PKCE S256,
  `state` vérifié ; jetons en `storage.session` seulement ; rafraîchissement, puis reconnexion
  silencieuse (`prompt=none`), puis demande à l'utilisateur ; déconnexion avec révocation
- [x] Client `storage` : `/api/me/` (nom de l'espace), liste, dépôt, lecture, suppression ; Bearer seulement ;
  erreurs à message fixe
- [x] Descripteur `coffre.json` : créé par le premier navigateur, refus de synchroniser avec une autre clé
- [x] Synchronisation : fusion par entrée (`updated` le plus récent), pierres tombales, cache local
  d'enveloppes ; une entrée disparue du serveur sans pierre tombale est **redéposée** (protection
  contre une suppression par une autre app) ; une enveloppe illisible est ignorée, jamais supprimée ;
  une enveloppe d'un autre coffre restée en cache n'est jamais envoyée ; un seul passage à la fois
  (`navigator.locks`)
- [x] Synchronisation au déverrouillage, à la demande et toutes les 10 min ; elle ne repousse
  **pas** le verrouillage automatique (`peekKey`)
- [x] Carte « Connexion au lab » dans la page de gestion ; adresse de retour affichée pour l'administrateur
- [x] Permissions ajoutées : `identity` (connexion Keycloak), `optional_host_permissions` (origine du
  lab, demandée au moment où l'utilisateur l'enregistre). CSP : `connect-src 'self' https:` + boucle
  locale (sans quoi aucun appel au lab ne part — constaté par l'essai)
- [x] Processus de fond : ne répond qu'aux pages de l'extension (`sender.id` et `sender.url` vérifiés)
- [x] Réglages serveur à appliquer par le propriétaire (commandes exactes dans le README)
**Tests à passer avant de clore le lot :**
- [x] Tests unitaires : PKCE, URL d'autorisation, retour (`state`, erreur), adresse du lab, client
  `storage` (faux `fetch`), jetons (cache, rafraîchissement, silencieux), fusion (conflits, pierres
  tombales, entrée disparue, autre clé, enveloppe altérée ou déplacée, retour arrière, panne en cours d'envoi)
- [x] Essai Chromium contre un faux Keycloak + faux `storage` local (`scripts/faux-lab.cjs`) :
  connexion réelle par `launchWebAuthFlow`, deux profils, une entrée déposée par l'un apparaît chez
  l'autre, et l'inverse
- [ ] Essai réel contre le lab (par le propriétaire, après les réglages serveur)
**Vérifié le :** 2026-11-18 — `npm test` 59/59 ; `npm run essai` : création 25/25, synchronisation
16/16 (Chromium du conteneur, faux lab). Non essayés : Firefox, Edge, le vrai lab.

## Lot 4 — Gérer les entrées   ·   statut : à faire
**Objectif :** ajouter, modifier, supprimer, rechercher, afficher et copier un mot de passe.
**Tâches :**
- [ ] Liste et recherche ; fiche d'entrée ; générateur intégré à la saisie
- [ ] Afficher un mot de passe : redemande de la phrase de passe si le déverrouillage date de plus de 5 min
- [ ] Copier avec effacement du presse-papiers
**Tests à passer avant de clore le lot :**
- [ ] Tests unitaires du modèle d'entrée (origines normalisées, validation)
- [ ] Essai Chromium du parcours complet

## Lot 5 — Préremplissage   ·   statut : à faire
**Objectif :** proposer l'identifiant et le mot de passe sur les sites enregistrés, et proposer
d'enregistrer un nouveau compte.
**Tâches :**
- [ ] Script de contenu : détection des formulaires, remplissage seulement sur origine exacte et
  seulement à la demande de l'utilisateur (clic), jamais dans un cadre d'une autre origine
- [ ] Proposition d'enregistrement après soumission d'un formulaire
**Tests à passer avant de clore le lot :**
- [ ] Essai Chromium sur des pages de test locales : bonne origine remplie, sous-domaine, autre
  port, cadre étranger et page en http refusés

## Lot 6 — Empaquetage et mode d'emploi   ·   statut : à faire
**Objectif :** une extension installable dans les trois navigateurs et un guide utilisateur complet.
**Tâches :**
- [ ] Paquets Chrome/Edge (zip) et Firefox (xpi signé par Mozilla, non listé)
- [ ] `docs/GUIDE.md` : installation par navigateur, création, clé USB, nouvel appareil, perte, sauvegarde
**Tests à passer avant de clore le lot :**
- [ ] Installation réelle dans Firefox, Chrome et Edge, parcours du guide suivi à la lettre
