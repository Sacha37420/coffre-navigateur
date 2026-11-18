import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  CoffreError, MIN_ITERATIONS, changePassphrase, createVault, decryptEntry, encryptEntry,
  openKeyFile, parseKeyFile, vaultDescriptor,
} from '../src/lib/crypto.js';
import { fromB64u, toB64u } from '../src/lib/base64.js';

const PHRASE = 'cheval agrafe batterie correcte';

function entry(over = {}) {
  return {
    id: crypto.randomUUID(), type: 'login', name: 'Exemple', origins: ['https://exemple.fr'],
    username: 'moi', password: 'S3cr3t!é', notes: '', created: '2026-11-18T10:00:00.000Z',
    updated: '2026-11-18T10:00:00.000Z', deleted: false, ...over,
  };
}

async function rejects(promise, code) {
  await assert.rejects(promise, (e) => e instanceof CoffreError && e.code === code);
}

// Un seul coffre pour la suite : PBKDF2 à 600 000 itérations coûte quelques centaines de ms.
const vault = await createVault(PHRASE);

test('base64url aller-retour, sans remplissage', () => {
  for (const n of [0, 1, 2, 3, 16, 32, 65536]) {
    const b = crypto.getRandomValues(new Uint8Array(n));
    const t = toB64u(b);
    assert.doesNotMatch(t, /[=+/]/);
    assert.deepEqual(fromB64u(t), b);
  }
  assert.throws(() => fromB64u('ab=c'));
});

test('fichier de clé conforme au format v1', () => {
  const k = vault.keyFile;
  assert.equal(k.format, 'coffre-navigateur/cle');
  assert.equal(k.version, 1);
  assert.equal(k.kdf.name, 'PBKDF2-SHA256');
  assert.equal(k.kdf.iterations, MIN_ITERATIONS);
  assert.equal(fromB64u(k.key_id).length, 16);
  assert.equal(fromB64u(k.kdf.salt).length, 16);
  assert.equal(fromB64u(k.iv).length, 12);
  assert.equal(fromB64u(k.wrapped).length, 48);
  assert.deepEqual(parseKeyFile(JSON.stringify(k)), k);
});

test('la clé utilisable n\'est pas extractible', async () => {
  assert.equal(vault.key.extractable, false);
  await assert.rejects(crypto.subtle.exportKey('raw', vault.key));
  const opened = await openKeyFile(vault.keyFile, PHRASE);
  assert.equal(opened.key.extractable, false);
});

test('chiffrer puis déchiffrer une entrée, y compris après réouverture du fichier', async () => {
  const e = entry();
  const env = await encryptEntry(vault.key, vault.keyId, e);
  assert.equal(env.id, e.id);
  assert.ok(!JSON.stringify(env).includes('S3cr3t'));
  assert.deepEqual(await decryptEntry(vault.key, vault.keyId, env), e);

  const opened = await openKeyFile(parseKeyFile(JSON.stringify(vault.keyFile)), PHRASE);
  assert.deepEqual(await decryptEntry(opened.key, opened.keyId, env), e);
});

test('un IV neuf à chaque chiffrement', async () => {
  const e = entry();
  const a = await encryptEntry(vault.key, vault.keyId, e);
  const b = await encryptEntry(vault.key, vault.keyId, e);
  assert.notEqual(a.iv, b.iv);
  assert.notEqual(a.ct, b.ct);
});

test('phrase de passe fausse ⇒ erreur fixe', async () => {
  await rejects(openKeyFile(vault.keyFile, 'pas la bonne phrase'), 'phrase_ou_fichier');
});

test('fichier de clé altéré ⇒ même erreur que la mauvaise phrase', async () => {
  const w = fromB64u(vault.keyFile.wrapped);
  w[0] ^= 1;
  await rejects(openKeyFile({ ...vault.keyFile, wrapped: toB64u(w) }, PHRASE), 'phrase_ou_fichier');
  // key_id changé : les données associées ne correspondent plus.
  const other = toB64u(crypto.getRandomValues(new Uint8Array(16)));
  await rejects(openKeyFile({ ...vault.keyFile, key_id: other }, PHRASE), 'phrase_ou_fichier');
});

test('itérations abaissées ou format inconnu ⇒ refus', async () => {
  const low = { ...vault.keyFile, kdf: { ...vault.keyFile.kdf, iterations: 1000 } };
  await rejects(openKeyFile(low, PHRASE), 'format');
  await rejects(openKeyFile({ ...vault.keyFile, version: 2 }, PHRASE), 'format');
  assert.throws(() => parseKeyFile('{"format":"autre"}'), (e) => e.code === 'format');
  assert.throws(() => parseKeyFile('pas du json'), (e) => e.code === 'format');
});

test('phrase de passe trop courte refusée à la création', async () => {
  await rejects(createVault('court'), 'phrase_courte');
  await rejects(createVault(PHRASE, { iterations: 10 }), 'format');
});

test('enveloppe d\'un autre coffre ⇒ autre_cle', async () => {
  const env = await encryptEntry(vault.key, vault.keyId, entry());
  const autre = toB64u(crypto.getRandomValues(new Uint8Array(16)));
  await rejects(decryptEntry(vault.key, autre, env), 'autre_cle');
});

test('enveloppe recopiée sous un autre id ⇒ refus', async () => {
  const env = await encryptEntry(vault.key, vault.keyId, entry());
  await rejects(decryptEntry(vault.key, vault.keyId, { ...env, id: crypto.randomUUID() }), 'entree_alteree');
});

test('chiffré altéré ⇒ refus', async () => {
  const env = await encryptEntry(vault.key, vault.keyId, entry());
  const ct = fromB64u(env.ct);
  ct[ct.length - 1] ^= 0x80;
  await rejects(decryptEntry(vault.key, vault.keyId, { ...env, ct: toB64u(ct) }), 'entree_alteree');
});

test('id d\'entrée non UUID refusé', async () => {
  await rejects(encryptEntry(vault.key, vault.keyId, entry({ id: '../x' })), 'format');
});

test('changer de phrase de passe garde la même clé de coffre', async () => {
  const env = await encryptEntry(vault.key, vault.keyId, entry());
  const nouveau = await changePassphrase(vault.keyFile, PHRASE, 'une toute autre phrase longue');
  assert.equal(nouveau.key_id, vault.keyFile.key_id);
  assert.notEqual(nouveau.wrapped, vault.keyFile.wrapped);
  await rejects(openKeyFile(nouveau, PHRASE), 'phrase_ou_fichier');
  const o = await openKeyFile(nouveau, 'une toute autre phrase longue');
  assert.equal((await decryptEntry(o.key, o.keyId, env)).password, 'S3cr3t!é');
});

test('le descripteur public ne contient aucun secret', () => {
  const d = vaultDescriptor(vault.keyFile);
  assert.deepEqual(Object.keys(d).sort(), ['created', 'format', 'key_id', 'version']);
});
