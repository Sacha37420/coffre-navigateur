# coffre-navigateur — règles de travail (pour toute IA, et tout humain)

Extension de navigateur qui garde des mots de passe. Une erreur ici livre les comptes des
utilisateurs : ces règles ne sont pas des conseils.

## Méthode — lot par lot

Même discipline que le lab : écrire le lot dans `FEUILLE_DE_ROUTE.md` avant de coder, un seul lot
« en cours », ne jamais clore un lot sans avoir lancé ses tests (date et résultat sous
« Vérifié le »), un lot livré = un commit poussé. Ne pas changer le format des titres de lot.

## Chiffrement — à ne jamais assouplir

- `docs/FORMAT.md` fait foi. Une évolution = une **version 2** lue à côté de la v1, jamais un
  changement de sens d'un champ existant.
- **Rien ne part en clair** : ni mot de passe, ni phrase de passe, ni clé de coffre. Le serveur ne
  reçoit que des enveloppes chiffrées et le descripteur public.
- La clé de coffre utilisable est importée **non extractible** ; sa forme brute n'existe que le
  temps d'emballer ou de déballer, et dans `storage.session` tant que le coffre est déverrouillé
  (docs/FORMAT.md §5). Jamais dans `storage.local`, jamais dans un message vers un script de contenu.
- `storage.session` reste au niveau d'accès par défaut (`TRUSTED_CONTEXTS`) : ne jamais appeler
  `setAccessLevel` pour l'ouvrir aux scripts de contenu.
- IV neuf à chaque chiffrement ; données associées toujours présentes ; PBKDF2 ≥ 600 000.
- Aucune bibliothèque de chiffrement maison ni externe : **WebCrypto seulement**.
- Messages d'erreur fixes : jamais un secret, un contenu ou une réponse serveur recopiée.
- Rien de sensible dans `console.*`, ni dans `storage.local` en clair (`storage.local` ne garde
  que le fichier de clé chiffré et les enveloppes ; la clé déverrouillée vit en `storage.session`).

## Extension

- Manifest V3, code commun aux trois navigateurs ; les écarts vivent dans `scripts/build.mjs`.
- CSP stricte (`script-src 'self'`), aucun script distant, aucun `eval`, aucune police ou image
  chargée d'Internet. `connect-src` n'est ouvert qu'au https (adresse du lab choisie par
  l'utilisateur) et à la boucle locale (essais) ; l'accès réel reste borné par l'autorisation
  d'origine demandée pour le seul lab.
- Le processus de fond ne répond qu'aux pages de l'extension (`sender.id` et `sender.url`) : un
  script de contenu ne doit jamais pouvoir demander une synchronisation, une connexion ou une clé.
- Permissions au plus juste : chaque permission ajoutée est justifiée dans la feuille de route.
- Préremplissage seulement sur **origine exacte** et à la demande de l'utilisateur : la liste des comptes
  peut s'ouvrir d'elle-même, mais le remplissage n'a lieu qu'au clic dans un **cadre de l'extension**.
- Le script de contenu (`src/content/`) ne reçoit jamais ni clé, ni liste de comptes, ni nom de compte :
  il signale des champs et une soumission, et reçoit un remplissage pour sa propre origine, revérifiée.
  L'origine d'une demande est toujours celle de l'onglet vue par le processus de fond (`sender.tab`),
  jamais une valeur envoyée par la page. Pas de `postMessage` entre le cadre de l'extension et la page.
- Interface : tokens CSS (aucune couleur en dur hors des `:root`), cibles de 44 px minimum.

## Lab

- `storage` est utilisé **tel quel** : aucune route ne doit y changer. Espace personnel, préfixe
  `coffre/v1/`, jeton Bearer de l'utilisateur seulement.
- Pas de secret dans le dépôt ; un `*.coffre-cle` n'y entre jamais (ignoré par git).

## Tests

`npm test` (Node, sans dépendance) et `npm run essai` (Chromium : création, puis synchronisation
contre `scripts/faux-lab.cjs`). Aucun test n'appelle le vrai lab ; `storage` et Keycloak sont
simulés. `dist/essai` (construit par `--essai`) n'est **jamais** distribué : il a l'accès à la
boucle locale accordé d'office.

## Application Android (`android/`)

- `android/core` (Kotlin pur, testé sur la JVM) est la **seule** implémentation du format côté
  Android : il suit `docs/FORMAT.md` et `src/lib/crypto.js` à l'identique. Toute modification de l'un
  se fait dans l'autre, et les vecteurs croisés (`tests/vecteurs/v1.json`, `scripts/verifier-vecteur.mjs`)
  doivent rester verts dans les deux sens.
- PBKDF2 : assemblé sur `Mac("HmacSHA256")` à partir de l'UTF-8 de la phrase NFC, jamais
  `SecretKeyFactory` (encodage de la phrase variable selon les versions d'Android).
- Aucune bibliothèque tierce dans l'APK (plateforme Android, `org.json` intégré, runtime Kotlin) ;
  aucune permission non justifiée dans la feuille de route ; `allowBackup=false` et règles
  d'extraction vides : le coffre ne part jamais dans une sauvegarde du téléphone ; `FLAG_SECURE`.
- Tests : `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
  (Robolectric simule les écrans sur la JVM ; dépendance de **test** seulement, jamais dans l APK —
  vérifier avec `apkanalyzer dex packages` qu aucun paquet `androidx` n y entre).
- La clé de coffre déverrouillée ne vit qu en mémoire du processus (`VaultState`) ; l empreinte ne
  stocke qu une copie chiffrée par une clé Android Keystore à biométrie forte, invalidée par
  l ajout d une empreinte.
- L'adresse du lab est **fixée à la construction** de l'APK (`LAB_URL` ou `config.local.json`) : l'APK ne
  revendique (App Links, `autoVerify`) que `https://<ce lab>/coffre-retour/`. Le jeton d'accès vit en
  mémoire ; le jeton de rafraîchissement n'est écrit que chiffré par la clé du coffre. L'adresse de
  retour reçue n'est jamais journalisée ni affichée.
- Une synchronisation ne repousse jamais le verrouillage automatique (`peekKey`).
- Saisie automatique : un domaine web n'est cru que s'il est annoncé par un **navigateur connu ET authentique**
  (appli système ou installée depuis le Play Store, `Forms.trustedBrowser`) ; https seulement ; origine exacte ;
  rien n'est rempli sans toucher une proposition. Toute publication = `npm run …` vert puis workflow `publication`.
