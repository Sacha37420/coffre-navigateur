# Coffre du lab — extension de navigateur

Gestionnaire de mots de passe pour Chrome, Edge et Firefox, branché sur le lab :

- les mots de passe sont **chiffrés dans le navigateur** (AES-256-GCM) avant de partir ;
- ils sont rangés dans **votre espace personnel de `storage`** (`coffre/v1/`), qui ne voit que du chiffré ;
- la **clé de chiffrement** est dans un fichier (`.coffre-cle`) que **vous gardez sur une clé USB** et
  que vous chargez vous-même dans chaque navigateur. Elle ne passe jamais par Internet.

> ⚠ Perdre toutes les copies du fichier de clé, ou sa phrase de passe, c'est perdre tous les
> mots de passe, sans recours possible. Gardez **deux** copies sur **deux** clés USB.
> N'envoyez jamais ce fichier par email, messagerie ou service en ligne.

État d'avancement : `FEUILLE_DE_ROUTE.md`. Format des fichiers : `docs/FORMAT.md`.

## Développer

```bash
npm test                          # tests unitaires (Node ≥ 20, aucune dépendance)
npm run build                     # dist/chrome (Chrome et Edge) et dist/firefox
node scripts/essai-chromium.cjs   # charge dist/chrome dans Chromium (Playwright)
```

Charger l'extension non empaquetée pour l'essayer :
- **Chrome / Edge** : `chrome://extensions` (ou `edge://extensions`) → « Mode développeur » →
  « Charger l'extension non empaquetée » → `dist/chrome`.
- **Firefox** : `about:debugging#/runtime/this-firefox` → « Charger un module temporaire » →
  `dist/firefox/manifest.json`.

## Réglages côté lab (à faire une fois, par l'administrateur)

À partir du lot 3. Les commandes exactes (client Keycloak de l'extension, ajout à
`KEYCLOAK_TRUSTED_CLIENTS` de `storage`) seront écrites ici quand l'identifiant définitif de
l'extension sera connu : c'est lui qui fixe l'adresse de retour de la connexion.
