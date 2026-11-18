import { test } from 'node:test';
import assert from 'node:assert/strict';

import { createVault, encryptEntry, decryptEntry, CoffreError } from '../src/lib/crypto.js';
import { createVaultState, sameKeyFile, S_KEY, K_FILE } from '../src/lib/vault-state.js';
import { passphraseStrength } from '../src/lib/strength.js';

const PHRASE = 'cheval agrafe batterie correcte';
const { keyFile } = await createVault(PHRASE);
const other = (await createVault('une autre phrase bien longue')).keyFile;

function memArea() {
  const m = new Map();
  return {
    m,
    get: async (k) => structuredClone(m.get(k)),
    set: async (k, v) => { m.set(k, structuredClone(v)); },
    remove: async (k) => { m.delete(k); },
  };
}

function setup() {
  const local = memArea();
  const session = memArea();
  const clock = { t: 1_000_000 };
  const scheduled = [];
  const vault = createVaultState({ local, session, now: () => clock.t, schedule: (w) => scheduled.push(w) });
  return { local, session, clock, scheduled, vault };
}

const rejects = (p, code) => assert.rejects(p, (e) => e instanceof CoffreError && e.code === code);

test('aucun coffre au départ ; déverrouiller sans coffre refusé', async () => {
  const { vault } = setup();
  assert.deepEqual(await vault.status(), { state: 'aucun' });
  assert.equal(await vault.key(), null);
  await rejects(vault.unlock(PHRASE), 'aucun_coffre');
});

test('installer ⇒ déverrouillé ; la clé brute n\'est qu\'en session', async () => {
  const { vault, local, session, scheduled, clock } = setup();
  await vault.install(keyFile, PHRASE);
  const st = await vault.status();
  assert.equal(st.state, 'deverrouille');
  assert.equal(st.expires, clock.t + 15 * 60_000);
  assert.deepEqual(scheduled, [st.expires]);
  assert.deepEqual(local.m.get(K_FILE), keyFile);
  assert.ok(!JSON.stringify([...local.m]).includes(session.m.get(S_KEY).raw));
});

test('mauvaise phrase à l\'installation : rien n\'est installé', async () => {
  const { vault, local } = setup();
  await rejects(vault.install(keyFile, 'mauvaise phrase de passe'), 'phrase_ou_fichier');
  assert.equal(local.m.size, 0);
});

test('verrouiller, déverrouiller, mauvaise phrase', async () => {
  const { vault, scheduled } = setup();
  await vault.install(keyFile, PHRASE);
  await vault.lock();
  assert.equal((await vault.status()).state, 'verrouille');
  assert.equal(scheduled.at(-1), null);
  assert.equal(await vault.key(), null);
  await rejects(vault.unlock('mauvaise phrase de passe'), 'phrase_ou_fichier');
  await vault.unlock(PHRASE);
  assert.equal((await vault.status()).state, 'deverrouille');
});

test('la clé rendue chiffre et déchiffre ; elle n\'est pas extractible', async () => {
  const { vault } = setup();
  await vault.install(keyFile, PHRASE);
  const { key, keyId } = await vault.key();
  assert.equal(key.extractable, false);
  const e = { id: crypto.randomUUID(), password: 'x' };
  assert.deepEqual(await decryptEntry(key, keyId, await encryptEntry(key, keyId, e)), e);
});

test('verrouillage après inactivité ; chaque usage repousse l\'échéance', async () => {
  const { vault, clock, session } = setup();
  await vault.install(keyFile, PHRASE);
  clock.t += 10 * 60_000;
  assert.ok(await vault.key());                  // repousse à t + 15 min
  clock.t += 10 * 60_000;
  assert.equal((await vault.status()).state, 'deverrouille');
  clock.t += 6 * 60_000;
  assert.equal(await vault.key(), null);
  assert.equal((await vault.status()).state, 'verrouille');
  assert.equal(session.m.size, 0);               // la clé brute est effacée, pas seulement ignorée
});

test('alarme : verrouille à échéance, replanifie sinon', async () => {
  const { vault, clock, scheduled } = setup();
  await vault.install(keyFile, PHRASE);
  const first = scheduled.at(-1);
  clock.t += 60_000;
  await vault.key();
  const pushed = scheduled.at(-1);
  clock.t = first;                               // l'ancienne alarme sonne
  await vault.expireIfDue();
  assert.equal(scheduled.at(-1), pushed);
  assert.equal((await vault.status()).state, 'deverrouille');
  clock.t = pushed;
  await vault.expireIfDue();
  assert.equal((await vault.status()).state, 'verrouille');
});

test('délai réglable, valeurs fermées', async () => {
  const { vault, clock } = setup();
  await vault.setSettings({ autoLockMinutes: 5 });
  await vault.install(keyFile, PHRASE);
  assert.equal((await vault.status()).expires, clock.t + 5 * 60_000);
  await rejects(vault.setSettings({ autoLockMinutes: 7 }), 'reglage');
  await rejects(vault.setSettings({ autoLockMinutes: 'x' }), 'reglage');
});

test('un autre coffre ne remplace pas l\'installé sans le demander', async () => {
  const { vault } = setup();
  await vault.install(keyFile, PHRASE);
  await rejects(vault.install(other, 'une autre phrase bien longue'), 'autre_coffre_installe');
  await vault.install(other, 'une autre phrase bien longue', { replace: true });
  assert.equal((await vault.status()).keyId, other.key_id);
});

test('session d\'une autre clé ignorée', async () => {
  const { vault, session } = setup();
  await vault.install(keyFile, PHRASE);
  const s = session.m.get(S_KEY);
  session.m.set(S_KEY, { ...s, key_id: other.key_id });
  assert.equal((await vault.status()).state, 'verrouille');
});

test('changer de phrase : nouveau fichier, ancienne phrase refusée ensuite', async () => {
  const { vault } = setup();
  await vault.install(keyFile, PHRASE);
  const next = await vault.changePassphrase(PHRASE, 'nouvelle phrase encore plus longue');
  assert.equal(next.key_id, keyFile.key_id);
  assert.deepEqual(await vault.keyFile(), next);
  await vault.lock();
  await rejects(vault.unlock(PHRASE), 'phrase_ou_fichier');
  await vault.unlock('nouvelle phrase encore plus longue');
});

test('retirer le coffre du navigateur', async () => {
  const { vault, local, session } = setup();
  await vault.install(keyFile, PHRASE);
  await vault.forget();
  assert.equal(local.m.has(K_FILE), false);
  assert.equal(session.m.size, 0);
  assert.equal((await vault.status()).state, 'aucun');
});

test('sameKeyFile reconnaît une copie exacte seulement', () => {
  assert.ok(sameKeyFile(keyFile, structuredClone(keyFile)));
  assert.ok(!sameKeyFile(keyFile, other));
  assert.ok(!sameKeyFile(keyFile, { ...keyFile, iv: other.iv }));
  assert.ok(!sameKeyFile(keyFile, null));
});

test('solidité de la phrase de passe', () => {
  assert.equal(passphraseStrength('court').level, 'refusee');
  assert.equal(passphraseStrength('aaaaaaaaaaaaaaaa').level, 'faible');
  assert.equal(passphraseStrength('123456789abcdef').level, 'faible');
  assert.equal(passphraseStrength('motdepasselong').level, 'moyenne');
  assert.equal(passphraseStrength('cheval agrafe batterie correcte pomme').level, 'bonne');
  assert.equal(passphraseStrength('Tr0ub4dor&3xyzAB').level, 'bonne');
});
