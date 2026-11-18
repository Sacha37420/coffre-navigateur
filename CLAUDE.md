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
  temps d'emballer ou de déballer, puis elle est effacée.
- IV neuf à chaque chiffrement ; données associées toujours présentes ; PBKDF2 ≥ 600 000.
- Aucune bibliothèque de chiffrement maison ni externe : **WebCrypto seulement**.
- Messages d'erreur fixes : jamais un secret, un contenu ou une réponse serveur recopiée.
- Rien de sensible dans `console.*`, ni dans `storage.local` en clair (`storage.local` ne garde
  que le fichier de clé chiffré et les enveloppes ; la clé déverrouillée vit en `storage.session`).

## Extension

- Manifest V3, code commun aux trois navigateurs ; les écarts vivent dans `scripts/build.mjs`.
- CSP stricte (`script-src 'self'`), aucun script distant, aucun `eval`, aucune police ou image
  chargée d'Internet.
- Permissions au plus juste : chaque permission ajoutée est justifiée dans la feuille de route.
- Préremplissage seulement sur **origine exacte** et à la demande de l'utilisateur.
- Interface : tokens CSS (aucune couleur en dur hors des `:root`), cibles de 44 px minimum.

## Lab

- `storage` est utilisé **tel quel** : aucune route ne doit y changer. Espace personnel, préfixe
  `coffre/v1/`, jeton Bearer de l'utilisateur seulement.
- Pas de secret dans le dépôt ; un `*.coffre-cle` n'y entre jamais (ignoré par git).

## Tests

`npm test` (Node, sans dépendance) et `node scripts/essai-chromium.cjs` après `npm run build`.
Aucun test n'appelle le vrai lab ; `storage` et Keycloak sont simulés.
