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

## Développer

```bash
npm test                          # tests unitaires (Node ≥ 20, aucune dépendance)
npm run build                     # dist/chrome (Chrome et Edge) et dist/firefox
npm run essai                     # parcours complet dans Chromium (Playwright)
```

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

**3. Essai** : charger l'extension, créer un coffre, « Gérer le coffre » → « Connexion au lab » :
adresse `https://<domaine>`, « Enregistrer l'adresse » (le navigateur demande l'autorisation pour
ce seul site), « Se connecter ». Une entrée créée dans un navigateur doit apparaître dans l'autre
après « Synchroniser maintenant ».
