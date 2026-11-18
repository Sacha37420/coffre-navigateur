# Contrat de format — v1

Ce document fait foi. Un fichier écrit par l'extension doit s'y conformer ; un fichier qui n'y
est pas conforme est refusé, jamais « réparé ». Toute évolution crée une **version 2** lue à côté
de la v1 : on ne modifie jamais le sens d'un champ existant.

Encodage binaire : **base64url sans remplissage** (RFC 4648 §5), noté `b64u`.

## 1. Principe

- Une **clé de coffre** : 32 octets aléatoires (AES-256-GCM), tirée par l'extension à la création.
  Elle ne quitte jamais l'appareil autrement que **dans le fichier de clé**, chiffrée.
- Un **identifiant de clé** (`key_id`) : 16 octets aléatoires, public. Il permet de reconnaître
  qu'une entrée ou un coffre distant correspond bien à la clé chargée, sans rien révéler d'elle.
- Le serveur (`storage`) ne reçoit que des **enveloppes d'entrée** chiffrées et un descripteur
  public. Il ne voit jamais la clé, la phrase de passe ni un mot de passe en clair.

## 2. Fichier de clé — `<nom>.coffre-cle`

Fichier JSON, écrit par l'utilisateur sur une clé USB. **Ne transite jamais par Internet**.

```json
{
  "format": "coffre-navigateur/cle",
  "version": 1,
  "key_id": "<b64u, 16 octets>",
  "created": "2026-11-18T10:00:00.000Z",
  "kdf": { "name": "PBKDF2-SHA256", "iterations": 600000, "salt": "<b64u, 16 octets>" },
  "iv": "<b64u, 12 octets>",
  "wrapped": "<b64u, 32 octets de clé + 16 octets d'étiquette GCM>"
}
```

- Clé d'emballage = PBKDF2-HMAC-SHA256(phrase de passe NFC, `salt`, `iterations`) → AES-256-GCM.
- `wrapped` = AES-GCM(clé d'emballage, `iv`, clé de coffre brute),
  données associées = `coffre-navigateur/cle|1|<key_id>`.
- `iterations` ≥ 600 000 (recommandation OWASP 2023 pour PBKDF2-SHA256), refusé en dessous.
- Phrase de passe : 12 caractères au moins.
- Une phrase de passe fausse et un fichier altéré donnent la même erreur (`phrase_ou_fichier`) :
  l'étiquette GCM ne permet pas de les distinguer, et c'est voulu.

## 3. Enveloppe d'entrée — `coffre/v1/entrees/<id>.bin` dans `storage`

Une entrée = un fichier, pour qu'une modification concurrente sur deux navigateurs ne puisse
écraser qu'une seule entrée (`storage` n'a pas de contrôle de version).

```json
{
  "format": "coffre-navigateur/entree",
  "version": 1,
  "key_id": "<b64u>",
  "id": "<uuid v4>",
  "iv": "<b64u, 12 octets>",
  "ct": "<b64u>"
}
```

- `ct` = AES-GCM(clé de coffre, `iv`, JSON UTF-8 du contenu ci-dessous),
  données associées = `coffre-navigateur/entree|1|<key_id>|<id>`. Une enveloppe recopiée sous un
  autre `id` ou d'un autre coffre est donc refusée.
- `iv` tiré au hasard à **chaque** chiffrement, jamais réutilisé.
- Déposée avec le type `application/octet-stream`.

Contenu déchiffré :

```json
{
  "id": "<uuid, identique à l'enveloppe>",
  "type": "login",
  "name": "Nom affiché",
  "origins": ["https://exemple.fr"],
  "username": "moi",
  "password": "…",
  "notes": "",
  "created": "<ISO 8601>",
  "updated": "<ISO 8601>",
  "deleted": false
}
```

- `origins` : origines **exactes** (schéma + hôte + port). Le préremplissage n'est proposé que sur
  une origine listée, jamais sur un domaine « ressemblant ».
- Suppression = entrée gardée avec `deleted: true` et ses champs secrets vidés (pierre tombale),
  pour qu'un autre navigateur hors ligne apprenne la suppression au lieu de la ré-envoyer.
- Fusion : pour un même `id`, la version au `updated` le plus récent gagne.

## 4. Descripteur public — `coffre/v1/coffre.json` dans `storage`

```json
{ "format": "coffre-navigateur/coffre", "version": 1, "key_id": "<b64u>", "created": "<ISO 8601>" }
```

Aucun secret. Sert à refuser de synchroniser avec un navigateur qui a chargé une autre clé.

## 5. Dans le navigateur

- `storage.local` : le **fichier de clé** (chiffré, identique au `.coffre-cle`) sous `cle`, et les
  réglages sous `reglages`. Jamais une clé en clair.
- `storage.session` (mémoire seulement, vidée à la fermeture du navigateur, inaccessible aux
  scripts de contenu) : sous `session`, `{ key_id, raw: <b64u, 32 octets>, expires: <ms> }` tant
  que le coffre est déverrouillé. C'est le seul endroit où la clé brute existe hors d'un calcul :
  un processus de fond d'extension est arrêté par le navigateur au bout de quelques secondes
  d'inactivité, une clé gardée en mémoire du processus serait perdue à chaque fois.
- `expires` recule à chaque utilisation ; passé ce délai, la session est effacée (verrouillage).

## 6. Ce que le chiffrement ne protège pas (limites connues)

- Le serveur et toute app qui reçoit le jeton de l'utilisateur voient le **nombre** d'entrées,
  leur taille et leurs dates de dépôt, et peuvent les **supprimer** ou servir une **ancienne**
  version d'une entrée. Ils ne peuvent ni les lire ni en fabriquer une valide.
- La perte de toutes les copies du fichier de clé, ou de la phrase de passe, rend le coffre
  illisible pour toujours. Il n'existe aucune récupération, par construction.
