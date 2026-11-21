# Coffre du lab — extension de navigateur

Gestionnaire de mots de passe pour Chrome, Edge et Firefox, branché sur le lab :

- les mots de passe sont **chiffrés dans le navigateur** (AES-256-GCM) avant de partir ;
- ils sont rangés dans **votre espace personnel de `storage`** (`coffre/v1/`), qui ne voit que du chiffré ;
- la **clé de chiffrement** est dans un fichier (`.coffre-cle`) que **vous gardez sur une clé USB** et
  que vous chargez vous-même dans chaque navigateur. Elle ne passe jamais par Internet.

> ⚠ Perdre toutes les copies du fichier de clé, ou sa phrase de passe, c'est perdre tous les
> mots de passe, sans recours possible. Gardez **deux** copies sur **deux** clés USB.
> N'envoyez jamais ce fichier par email, messagerie ou service en ligne.

Mode d'emploi pour l'utilisateur : `src/pages/guide.html` (aussi ouvert depuis l'extension).
État d'avancement : `FEUILLE_DE_ROUTE.md`. Format des fichiers : `docs/FORMAT.md`.

## Télécharger et installer

| Navigateur | Linux et Windows | Installation |
|---|---|---|
| **Firefox** | [⬇ coffre-firefox.xpi](https://github.com/Sacha37420/coffre-navigateur/releases/latest/download/coffre-firefox.xpi) | un clic |
| **Chrome** | [⬇ coffre-chrome-edge.zip](https://github.com/Sacha37420/coffre-navigateur/releases/latest/download/coffre-chrome-edge.zip) | mode développeur, 2 minutes |
| **Edge** | [⬇ coffre-chrome-edge.zip](https://github.com/Sacha37420/coffre-navigateur/releases/latest/download/coffre-chrome-edge.zip) (même fichier que Chrome) | mode développeur, 2 minutes |
| **Téléphone** | pas encore — voir plus bas | |

L'adresse du lab est déjà préremplie dans l'extension. Une fois installée : icône du coffre →
« Configurer le coffre », puis suivez le mode d'emploi intégré.

### Firefox (Linux, Windows)

1. Ouvrez le lien **coffre-firefox.xpi** ci-dessus **avec Firefox**.
2. Firefox affiche « Firefox a empêché ce site de vous demander d'installer un logiciel » :
   cliquez sur **« Continuer l'installation »**.
3. Cliquez sur **« Ajouter »**. L'icône du coffre apparaît dans la barre (sinon : pièce de puzzle 🧩
   → épingler « Coffre du lab »).

Mises à jour : refaire la même chose avec le nouveau fichier ; le coffre et les réglages sont gardés.

### Chrome et Edge (Linux, Windows)

Chrome et Edge n'installent en un clic que depuis leur boutique ; hors boutique, il faut le
**mode développeur**. C'est sans risque pour l'ordinateur, il permet seulement de charger une
extension depuis un dossier.

1. Téléchargez **coffre-chrome-edge.zip**.
2. Décompressez-le dans un dossier **que vous garderez** (par exemple `Documents/coffre-du-lab`) :
   - Windows : clic droit sur le fichier → « Extraire tout… » → choisir le dossier → « Extraire » ;
   - Linux : clic droit → « Extraire ici », ou `unzip coffre-chrome-edge.zip -d ~/coffre-du-lab`.

   ⚠ Ne supprimez pas ce dossier ensuite : le navigateur charge l'extension depuis lui.
3. Ouvrez la page des extensions : tapez `chrome://extensions` (Chrome) ou `edge://extensions`
   (Edge) dans la barre d'adresse.
4. Activez **« Mode développeur »** : en haut à droite dans Chrome, dans la colonne de gauche dans Edge.
5. Cliquez sur **« Charger l'extension non empaquetée »** (Chrome) ou **« Charger l'extension
   décompressée »** (Edge), puis choisissez le dossier de l'étape 2 (celui qui contient `manifest.json`).
6. Épinglez l'icône : pièce de puzzle 🧩 → épingle à côté de « Coffre du lab ».

Mises à jour : téléchargez le nouveau zip, **remplacez le contenu** du même dossier, puis cliquez
sur la flèche circulaire « Actualiser » de l'extension dans la page des extensions. L'identifiant
de l'extension ne change pas : le coffre, la connexion et les réglages sont gardés.

### Téléphone

- **Une application Android est en cours** (dossier `android/`) : même coffre, et saisie
  automatique des mots de passe dans Chrome pour Android, que Chrome accepte d'une application
  tierce depuis sa version 135. Elle sera téléchargeable ici en APK (lot 12 de la feuille de route).
- **Chrome pour Android n'accepte aucune extension** : ni par fichier, ni par boutique. Ce n'est
  pas une limite de cette extension, c'est celle de Chrome sur téléphone.
- **Firefox et Edge pour Android** n'installent que depuis leur boutique (Mozilla, Microsoft). Une
  version téléphone passera par là ; elle fait l'objet d'un lot à part (voir `FEUILLE_DE_ROUTE.md`).

## Publier une nouvelle version (mainteneur)

Réglages du dépôt, une fois (Settings → Secrets and variables → Actions) :
- variable `LAB_URL` = l'adresse du lab à préremplir ;
- secrets `AMO_JWT_ISSUER` et `AMO_JWT_SECRET` = clés d'API addons.mozilla.org (« Outils » →
  « Gérer les clés d'API »). Sans eux, Firefox est publié **non signé** et ne s'installe pas en un clic.

Puis, sur la branche publiée, après avoir mis `version` à jour dans `package.json` :

```bash
git tag v0.3.0 && git push origin v0.3.0
```

Le workflow `publication` teste, construit, contrôle (`web-ext lint`), fait signer la version
Firefox par Mozilla (diffusion « non listée » : signée, mais absente de leur catalogue) et publie
la Release : les liens ci-dessus pointent toujours vers la dernière.

## Développer

```bash
npm test                          # tests unitaires (Node ≥ 20, aucune dépendance)
npm run build                     # dist/chrome (Chrome et Edge) et dist/firefox
npm run essai                     # parcours complet dans Chromium (Playwright)
npm run paquets                   # paquets/ : coffre-chrome-edge.zip, coffre-firefox-non-signe.zip
```

**Adresse du lab préremplie** (facultatif, recommandé pour votre installation) : copier
`config.local.example.json` en `config.local.json` et y mettre l'adresse du lab. Ce fichier est
ignoré par git ; `npm run build` l'intègre à `dist/`. L'utilisateur clique encore « Enregistrer
l'adresse » : c'est ce clic qui demande au navigateur l'autorisation d'accès au site.

Charger l'extension non empaquetée pour l'essayer :
- **Chrome / Edge** : `chrome://extensions` (ou `edge://extensions`) → « Mode développeur » →
  « Charger l'extension non empaquetée » → `dist/chrome`.
- **Firefox** : `about:debugging#/runtime/this-firefox` → « Charger un module temporaire » →
  `dist/firefox/manifest.json`.

## Réglages côté lab (une fois, par l'administrateur, depuis la racine du lab)

Aucun nouveau service : un client Keycloak pour l'extension, et son ajout à la liste des clients
de confiance de `storage`. `storage` n'est pas modifié.

**1. Client Keycloak `coffre-navigateur`** — public, PKCE S256, sans dossier d'app (`--no-env`),
ouvert à tout compte membre d'**au moins un groupe** (même liste que `oauth-hub`) :

```bash
bash scripts/create-app-client.sh coffre-navigateur --public --no-env --pkce \
  --native-redirect 'https://kainmmlggnfinglcapceocelpngjhnol.chromiumapp.org/' \
  --native-redirect 'https://9b3af4d723281ec8a520114f8d952e5d3743cd4f.extensions.allizom.org/' \
  $(grep -o -- '--require-group [^ ]*' oauth-hub/.keycloak-client-opts)
```

- La première adresse est celle de Chrome et d'Edge (identifiant fixé par la clé du manifeste).
- La seconde est celle que Firefox calcule pour `coffre-navigateur@lab.local` (empreinte SHA-1 de
  l'identifiant). **À vérifier** au premier essai sous Firefox : l'adresse réelle est affichée dans
  la page de gestion, « Informations pour l'administrateur du lab ». Si elle diffère, relancer la
  commande avec la bonne adresse (une URI de retour s'ajoute, elle ne remplace rien).
- Comme pour `oauth-hub` : un **nouveau groupe LDAP** doit aussi être ajouté à ce client.

**2. Autoriser l'extension à appeler `storage`** — la clé `KEYCLOAK_*` est gérée par le cadriciel,
`app-env.sh` la refuse : édition directe, puis redémarrage sans reconstruction :

```bash
grep '^KEYCLOAK_TRUSTED_CLIENTS=' storage/.env
```

```bash
grep -q '^KEYCLOAK_TRUSTED_CLIENTS=.*coffre-navigateur' storage/.env \
  || sed -i '/^KEYCLOAK_TRUSTED_CLIENTS=/ s/$/,coffre-navigateur/' storage/.env
```

```bash
bash scripts/app-restart.sh storage
```

L'utilisateur doit aussi satisfaire `KEYCLOAK_REQUIRED_GROUPS` de `storage/.env` (contrôle inchangé).

> `storage` répond **403**, et non 401, sans jeton ou avec un jeton expiré ou révoqué
> (`authenticate_header` absent de sa classe d'authentification). L'extension en tient compte : sur
> 401 comme sur 403, elle renouvelle son jeton une fois avant de conclure à un refus.

**3. Essai** : charger l'extension, créer un coffre, « Gérer le coffre » → « Connexion au lab » :
adresse `https://<domaine>`, « Enregistrer l'adresse » (le navigateur demande l'autorisation pour
ce seul site), « Se connecter ». Une entrée créée dans un navigateur doit apparaître dans l'autre
après « Synchroniser maintenant ».
