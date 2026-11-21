// Vérifie, avec le code de l'extension, un vecteur produit ailleurs (application Android) :
//   node scripts/verifier-vecteur.mjs <fichier.json>
// Le fichier contient { passphrase, keyFile, envelope, entry } ; sortie 0 si l'enveloppe s'ouvre
// et redonne exactement l'entrée attendue, 1 sinon.
import { readFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';
import { decryptEntry, openKeyFile, parseKeyFile } from '../src/lib/crypto.js';

const v = JSON.parse(readFileSync(process.argv[2], 'utf8'));
try {
  const { key, keyId } = await openKeyFile(parseKeyFile(JSON.stringify(v.keyFile)), v.passphrase);
  const entry = await decryptEntry(key, keyId, v.envelope);
  if (!isDeepStrictEqual(entry, v.entry)) {
    console.error('Entrée déchiffrée différente de l\'entrée attendue.');
    process.exit(1);
  }
  console.log(`ok ${entry.name}`);
} catch (e) {
  console.error(`refus : ${e.code ?? e.message}`);
  process.exit(1);
}
