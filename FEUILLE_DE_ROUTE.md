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
**Réglages serveur faits le 2026-11-21** (rapport du propriétaire) : client `coffre-navigateur`
public, PKCE S256, deux URI de retour, mapper `groups`, flow `require-coffre-navigateur` conforme au
Verrou 1, rôle attribué aux 8 groupes (aucun écart avec `oauth-hub`) ; `coffre-navigateur` ajouté à
`KEYCLOAK_TRUSTED_CLIENTS` de `storage` ; images identiques avant/après redémarrage. Constats :
`storage` répond 403 (pas 401) sans jeton ; le script a posé les origines des extensions en
`webOrigins` (sans effet pour l'extension, qui n'utilise pas CORS).
**Reste :** l'essai réel de l'extension contre le lab, par le propriétaire (Chrome ou Edge, puis Firefox
pour confirmer son adresse de retour).
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
- [x] Réglages serveur à appliquer par le propriétaire (commandes exactes dans le README) — faits le 2026-11-21
- [x] Jeton refusé par `storage` (403 comme 401) : renouvellement puis une seule nouvelle tentative
- [x] Adresse du lab préremplie depuis `config.local.json` (non versionné), intégrée à `dist/` par la construction
**Tests à passer avant de clore le lot :**
- [x] Tests unitaires : PKCE, URL d'autorisation, retour (`state`, erreur), adresse du lab, client
  `storage` (faux `fetch`), jetons (cache, rafraîchissement, silencieux), fusion (conflits, pierres
  tombales, entrée disparue, autre clé, enveloppe altérée ou déplacée, retour arrière, panne en cours d'envoi)
- [x] Essai Chromium contre un faux Keycloak + faux `storage` local (`scripts/faux-lab.cjs`) :
  connexion réelle par `launchWebAuthFlow`, deux profils, une entrée déposée par l'un apparaît chez
  l'autre, et l'inverse
- [ ] Essai réel contre le lab (par le propriétaire, après les réglages serveur)
**Vérifié le :** 2026-11-21 — `npm test` 63/63 ; `npm run essai` : création 25/25, synchronisation
17/17 (Chromium du conteneur, faux lab qui répond 403 comme le vrai, dont jeton révoqué en cours de
route) ; construction avec `config.local.json` : champ prérempli. Non essayés : Firefox, Edge, le vrai lab.

## Lot 3 bis — Paquets téléchargeables et publication automatique   ·   statut : en cours
**Objectif :** l'utilisateur télécharge l'extension depuis un lien du README, pour pouvoir faire
l'essai du lot 3 sans outil de développement.
**Décisions du propriétaire (2026-11-21) :** diffusion directe, sans boutique (Chrome et Edge en
mode développeur, Firefox signé par Mozilla en « non listé ») ; adresse du lab préremplie dans les
paquets publics (variable de dépôt GitHub `LAB_URL`, jamais dans le code) ; ce lot passe avant le
lot 4, par exception à « un seul lot en cours », parce que l'essai du lot 3 en a besoin.
**Constat :** Chrome pour Android n'accepte aucune extension (ni fichier, ni boutique) ; Edge pour
Android et Firefox pour Android ne les prennent que de leur boutique (Firefox Nightly/Bêta : fichier
signé). Le téléphone fera l'objet d'un lot à part.
**Tâches :**
- [x] `scripts/package.mjs` : `paquets/coffre-chrome-edge.zip` (Chrome et Edge, Linux et Windows, même
  fichier) et `paquets/coffre-firefox-non-signe.zip`
- [x] `LAB_URL` (environnement) prioritaire sur `config.local.json`, même contrôle
- [x] Manifeste Firefox : `data_collection_permissions` (exigé par Mozilla pour signer une nouvelle extension)
- [x] `.github/workflows/publication.yml` : sur une étiquette `v*`, tests, construction avec `LAB_URL`,
  signature Firefox si les secrets `AMO_JWT_ISSUER`/`AMO_JWT_SECRET` existent, publication des
  paquets sous des noms fixes (lien `releases/latest/download/…`)
- [x] README : section « Télécharger » avec les liens et l'installation pas à pas par navigateur
**Tests à passer avant de clore le lot :**
- [x] `npm run paquets` : les zip contiennent le manifeste attendu, l'identifiant Chrome inchangé, aucun
  fichier d'essai (`dist/essai`, faux lab) ni `config.local.json`
- [x] Paquet Chrome décompressé chargé dans Chromium (`npm run essai` sur le contenu du zip)
- [ ] `web-ext lint` sans erreur sur le paquet Firefox (refusé dans le conteneur de travail ; joué par le workflow)
- [ ] Première publication réelle par le propriétaire (étiquette poussée), liens du README vérifiés
**Vérifié le :** 2026-11-21 — `npm test` 64/64 ; `npm run essai` 25/25 et 17/17 ; `npm run paquets`
avec `LAB_URL` : zip Chrome décompressé chargé dans Chromium (`EXT_DIR=…`, 25/25), identifiant
`kainmmlggnfinglcapceocelpngjhnol` conservé, adresse préremplie, aucun fichier d'essai dans les
paquets ; étape « version » du workflow simulée (v0.3.1 acceptée, v1.2 et latest refusées). Restent :
`web-ext lint` et la première publication réelle.

## Lot 4 — Gérer les entrées   ·   statut : livré
**Objectif :** ajouter, modifier, supprimer, rechercher, afficher et copier un mot de passe.
**Décision du propriétaire (2026-11-21) :** lot 4 lancé alors que les lots 3 et 3 bis attendent son
essai et la première publication (exception à « un seul lot en cours »), puis l'application Android.
**Tâches :**
- [x] Page « Mes mots de passe » (`pages/entrees.html`) : liste triée, recherche (nom, sites, identifiant ;
  sans tenir compte des accents ni de la casse), fiche d'entrée (nom, sites un par ligne, identifiant,
  mot de passe, notes), générateur intégré, enregistrement, suppression confirmée (pierre tombale)
- [x] Lien « Mes mots de passe » dans la page de gestion ; section du mode d'emploi
- [x] Popup déverrouillée : recherche, liste courte, copier l'identifiant / le mot de passe, ajouter, tout gérer
- [x] Afficher un mot de passe : redemande de la phrase de passe si le déverrouillage (ou la dernière
  confirmation) date de plus de 5 min ; masqué de nouveau après 30 s
- [x] Copier : presse-papiers vidé au bout de 30 s, si son contenu n'a pas changé entre-temps et tant
  que la page est ouverte (limite connue : la popup se ferme ; un document hors écran viendra plus tard)
- [x] Chaque modification part à la synchronisation (si le lab est connecté)
- [x] Affichage par `textContent` seulement (jamais `innerHTML` d'un contenu d'entrée)
**Tests à passer avant de clore le lot :**
- [x] Tests unitaires : recherche, tri, pierres tombales masquées ; confirmation de la phrase et délai de 5 min
- [x] Essai Chromium du parcours complet : ajouter, rechercher, modifier, afficher (avec et sans
  redemande), copier, supprimer ; l'entrée créée par l'interface arrive dans un second profil par le faux lab
**Vérifié le :** 2026-11-21 — `npm test` 66/66 ; `npm run essai` : création 25/25, synchronisation
17/17, entrées 23/23 (ajout avec générateur, tri, recherche sans accent et par site, origines
normalisées, affichage direct puis redemande après 5 min, mauvaise phrase refusée, copie, modification,
contenu hostile affiché comme texte, suppression, popup, rien de clair sur le faux lab, réception par un
second profil, page vidée au verrouillage). Non essayés : Firefox, Edge, le vrai lab.

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

## Application Android (`android/`)

Décision du propriétaire (2026-11-21) : une application Android complète, parce que Chrome pour
Android n'accepte aucune extension mais accepte, depuis Chrome 135, un **service de saisie
automatique** tiers. Même coffre, même format (`docs/FORMAT.md` v1), mêmes entrées dans `storage`.
Distribution par APK téléchargeable (lien du README), sans boutique. Identifiant d'application
`io.github.sacha37420.coffre` (définitif dès la première installation). Aucune bibliothèque tierce à
l'exécution : API de la plateforme seulement (JCA, `org.json`, `AutofillService`).

## Lot 7 — Android : socle, format v1 en Kotlin, APK de démonstration   ·   statut : livré
**Objectif :** un module Kotlin pur (`android/core`) qui lit et écrit exactement les fichiers de
l'extension, prouvé par des vecteurs croisés, et une APK qui s'installe (générateur de mots de passe).
**Tâches :**
- [x] Projet Gradle `android/` : module JVM `core` (testable sans téléphone), module `app` (minSdk 26, cible 36)
- [x] `core` : base64url, fichier de clé (PBKDF2-HMAC-SHA256 600 000 → AES-256-GCM), enveloppes d'entrée,
  descripteur ; mêmes données associées, mêmes refus, mêmes codes d'erreur que `src/lib/crypto.js`
- [x] PBKDF2 assemblé sur `Mac("HmacSHA256")` de la plateforme, phrase encodée en UTF-8 (après NFC)
  explicitement : `SecretKeyFactory` n'a pas le même encodage de la phrase selon les versions
  d'Android, un accent donnerait une autre clé que l'extension
- [x] Générateur de mots de passe (mêmes familles, même tirage sans biais)
- [x] Vecteurs croisés : fichier de clé et enveloppe produits par l'extension (`tests/vecteurs/v1.json`)
  ouverts par Kotlin ; enveloppe produite par Kotlin ouverte par l'extension
- [x] APK de démonstration : écran du générateur, thème clair/sombre de la plateforme
**Tests à passer avant de clore le lot :**
- [x] `gradle :core:test` : vecteurs RFC 7914 (PBKDF2-HMAC-SHA256), vecteurs de l'extension, refus
  (mauvaise phrase, fichier altéré, autre clé, id déplacé, itérations abaissées), générateur
- [x] Vecteur produit par Kotlin vérifié par le code de l'extension (`scripts/verifier-vecteur.mjs`, lancé par le test JVM)
- [x] `gradle :app:assembleDebug` produit une APK ; `lintDebug` sans erreur
- [x] Installation sur un vrai téléphone par le propriétaire (pas d'émulateur ici : pas de KVM) — faite le 2026-11-22 : s'installe et s'ouvre
**Vérifié le :** 2026-11-21 — `./gradlew :core:test` 10/10 (RFC 7914, PBKDF2 identique au JDK accents
compris, vecteurs de l'extension ouverts par Kotlin, phrase NFD, enveloppe Kotlin ouverte par l'extension,
refus, changement de phrase, générateur) ; `:app:assembleDebug` + `:app:lintDebug` : 0 erreur, 4
avertissements de présentation ; APK : aucune permission, minSdk 26, aucune bibliothèque hors runtime Kotlin.
SDK Android dans le conteneur depuis l'ajout de `dl.google.com` à l'environnement (accord du propriétaire).

## Lot 8 — Android : coffre local   ·   statut : en cours
**Objectif :** importer le fichier de clé depuis une clé USB, déverrouiller, verrouiller.
**Tâches :**
- [x] `core` : état du coffre (aucun / verrouillé / déverrouillé), testable sur la JVM : installation
  (refus d'un autre coffre sans le demander), déverrouillage, verrouillage après inactivité (même
  choix de délais que l'extension, 15 min par défaut), confirmation de la phrase datée (pour le lot 10)
- [x] Clé déverrouillée **en mémoire du processus seulement** (jamais écrite) ; fichier de clé (chiffré)
  dans le stockage privé de l'application
- [x] Import du `.coffre-cle` par le sélecteur de fichiers d'Android (clé USB-C ou adaptateur OTG),
  10 ko au plus ; dérivation sur un fil d'arrière-plan avec indicateur
- [x] Écrans : « importer mon fichier de clé » (consignes USB), « déverrouiller », accueil (générateur,
  verrouiller, réglages : délai, empreinte, retirer le coffre)
- [x] Empreinte ou visage (Android 11+ : seule version où la plateforme garantit une biométrie **forte**), en option : clé Android Keystore exigeant l'authentification
  biométrique **forte** à chaque usage, invalidée si une empreinte est ajoutée ; elle chiffre une copie de la
  clé de coffre, stockée dans le stockage privé. La phrase reste toujours possible
- [x] Permission ajoutée : `USE_BIOMETRIC` (normale, sans demande à l'utilisateur) pour l'option empreinte
**Tests à passer avant de clore le lot :**
- [x] Tests JVM de l'état du coffre (mêmes cas que `tests/vault-state.test.mjs`)
- [x] Tests des écrans sur la JVM (Robolectric, dépendance de test seulement) : import refusé puis accepté, mauvaise phrase sans effet, ouverture, verrouillage, déverrouillage, retour au premier plan verrouillé, retrait, générateur, copie marquée sensible, `FLAG_SECURE`
- [x] `assembleDebug` + `lintDebug` sans erreur
- [ ] Essai sur téléphone par le propriétaire : import depuis une clé USB d'un fichier créé par l'extension,
  mauvaise phrase, déverrouillage, verrouillage automatique, empreinte
**Vérifié le :** 2026-11-22 — `./gradlew :core:test` 20/20, `:app:testDebugUnitTest` 2/2 (Robolectric, SDK 35),
`:app:assembleDebug`, `:app:lintDebug` 0 erreur ; APK : permission `USE_BIOMETRIC` seule, aucun paquet AndroidX
ni Robolectric embarqué. Reste : l'essai sur téléphone (clé USB réelle, empreinte réelle).

## Lot 9 — Android : connexion au lab et synchronisation   ·   statut : à faire
**Objectif :** mêmes entrées que les navigateurs, par `storage`.
**Tâches :**
- [ ] Connexion Keycloak dans le navigateur du téléphone, code + PKCE S256, retour par schéma
  d'application ; **réglage côté lab** : adresse de retour non `http(s)` (adapter
  `create-app-client.sh` ou l'ajouter à la main), à faire par le propriétaire
- [ ] Client `storage` et synchronisation : même algorithme que `src/lib/sync.js`, mêmes tests
**Tests à passer avant de clore le lot :**
- [ ] Tests JVM contre un faux storage ; essai réel par le propriétaire (téléphone + navigateur)

## Lot 10 — Android : gérer les entrées   ·   statut : à faire
**Objectif :** liste, recherche, fiche, générateur, affichage avec redemande, copie (presse-papiers
marqué « sensible », vidé après 30 s).
**Tests à passer avant de clore le lot :**
- [ ] Tests JVM du modèle et de la recherche ; essai sur téléphone

## Lot 11 — Android : saisie automatique (Chrome et applications)   ·   statut : à faire
**Objectif :** `AutofillService` : propositions sur les champs de connexion des sites enregistrés
(origine exacte du site déclarée par Chrome), après déverrouillage ; proposition d'enregistrer.
**Tests à passer avant de clore le lot :**
- [ ] Tests JVM de la correspondance site ↔ entrée ; essai dans Chrome pour Android (« Saisie
  automatique avec un autre service ») par le propriétaire

## Lot 12 — Android : APK signée publiée et mode d'emploi   ·   statut : à faire
**Objectif :** l'APK signée part avec chaque Release ; lien dans le README ; installation pas à pas.
**Tâches :**
- [ ] Clé de signature dans les secrets GitHub (à garder : la perdre empêche toute mise à jour)
- [ ] Workflow : construction, tests, signature, publication `coffre-android.apk`
**Tests à passer avant de clore le lot :**
- [ ] Installation réelle depuis le lien du README, puis mise à jour par-dessus
