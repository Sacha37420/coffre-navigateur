import { test } from 'node:test';
import assert from 'node:assert/strict';

import { CoffreError, createVault, encryptEntry, vaultDescriptor } from '../src/lib/crypto.js';
import { createEntryStore, synchronize, DESCRIPTOR_PATH, entryPath } from '../src/lib/sync.js';
import { editEntry, newEntry, tombstone } from '../src/lib/entries.js';
import { fakeStorage, memArea } from './fakes.mjs';

const v = await createVault('cheval agrafe batterie correcte');
const descriptor = vaultDescriptor(v.keyFile);

function browser(server) {
  const store = createEntryStore(memArea());
  const sync = () => synchronize({ store, client: server, ns: 'moi', key: v.key, keyId: v.keyId, descriptor });
  const all = async () => (await store.decryptAll(v.key, v.keyId)).entries.sort((a, b) => a.id.localeCompare(b.id));
  return { store, sync, all, put: (e) => store.put(v.key, v.keyId, e) };
}

test('premier passage : descripteur déposé, entrée envoyée chiffrée', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const e = newEntry({ name: 'Banque', origins: ['banque.fr'], password: 'TrèsSecret' });
  await A.put(e);
  const r = await A.sync();
  assert.deepEqual(r, { received: 0, sent: 1, restored: 0, ignored: 0 });
  assert.deepEqual(JSON.parse(server.files.get(`moi/${DESCRIPTOR_PATH}`).text), descriptor);
  const stored = server.files.get(`moi/${entryPath(e.id)}`).text;
  assert.ok(!stored.includes('TrèsSecret') && !stored.includes('Banque') && !stored.includes('banque'));
});

test('une entrée créée sur A apparaît sur B ; un second passage ne refait rien', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const B = browser(server);
  const e = newEntry({ name: 'Mail', password: 'x' });
  await A.put(e);
  await A.sync();
  assert.equal((await B.sync()).received, 1);
  assert.deepEqual(await B.all(), [e]);
  server.calls.length = 0;
  assert.deepEqual(await B.sync(), { received: 0, sent: 0, restored: 0, ignored: 0 });
  assert.ok(!server.calls.some(([op, p]) => op === 'read' && p !== DESCRIPTOR_PATH), 'rien relu si rien n\'a changé');
});

test('conflit : la version la plus récente gagne, des deux côtés', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const B = browser(server);
  const e = newEntry({ name: 'Site', password: 'v1' }, 1000);
  await A.put(e);
  await A.sync();
  await B.sync();
  await A.put(editEntry(e, { password: 'vA' }, 2000));
  await B.put(editEntry(e, { password: 'vB' }, 3000));   // B modifie plus tard
  await A.sync();
  await B.sync();                                        // B voit vA plus ancien : garde vB et l'envoie
  await A.sync();
  assert.equal((await A.all())[0].password, 'vB');
  assert.equal((await B.all())[0].password, 'vB');
});

test('suppression propagée par pierre tombale', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const B = browser(server);
  const e = newEntry({ name: 'Ancien', password: 'p' }, 1000);
  await A.put(e);
  await A.sync();
  await B.sync();
  await A.put(tombstone(e, 2000));
  await A.sync();
  await B.sync();
  const [t] = await B.all();
  assert.equal(t.deleted, true);
  assert.equal(t.password, '');
});

test('fichier effacé du serveur sans pierre tombale ⇒ redéposé', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const e = newEntry({ name: 'Garde', password: 'p' });
  await A.put(e);
  await A.sync();
  server.files.delete(`moi/${entryPath(e.id)}`);
  const r = await A.sync();
  assert.equal(r.restored, 1);
  assert.equal(r.sent, 1);
  assert.ok(server.files.has(`moi/${entryPath(e.id)}`));
});

test('enveloppe altérée ou d\'un autre coffre sur le serveur : ignorée, rien n\'est effacé', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const e = newEntry({ name: 'Ok', password: 'p' });
  await A.put(e);
  await A.sync();
  const autre = await createVault('une autre phrase bien longue');
  const intrus = newEntry({ name: 'Intrus', password: 'x' });
  await server.write('moi', entryPath(intrus.id), JSON.stringify(await encryptEntry(autre.key, autre.keyId, intrus)));
  const copie = JSON.parse(server.files.get(`moi/${entryPath(e.id)}`).text);
  const faux = newEntry({ name: 'Faux' });
  await server.write('moi', entryPath(faux.id), JSON.stringify(copie)); // enveloppe déplacée sous un autre id
  await server.write('moi', entryPath(crypto.randomUUID()), 'pas du json');
  const r = await A.sync();
  assert.equal(r.ignored, 3);
  assert.deepEqual((await A.all()).map((x) => x.name), ['Ok']);
  assert.equal(server.files.size, 5);
});

test('version ancienne resservie par le serveur : la locale plus récente est gardée et renvoyée', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const e = newEntry({ name: 'S', password: 'v1' }, 1000);
  await A.put(e);
  await A.sync();
  const old = server.files.get(`moi/${entryPath(e.id)}`).text;
  await A.put(editEntry(e, { password: 'v2' }, 2000));
  await A.sync();
  await server.write('moi', entryPath(e.id), old);   // retour arrière
  await A.sync();
  assert.equal((await A.all())[0].password, 'v2');
  assert.ok(server.files.get(`moi/${entryPath(e.id)}`).text !== old);
});

test('coffre du lab créé avec une autre clé ⇒ refus, rien n\'est envoyé', async () => {
  const server = fakeStorage();
  const autre = await createVault('une autre phrase bien longue');
  await server.write('moi', DESCRIPTOR_PATH, JSON.stringify(vaultDescriptor(autre.keyFile)));
  const A = browser(server);
  await A.put(newEntry({ name: 'X' }));
  await assert.rejects(A.sync(), (e) => e instanceof CoffreError && e.code === 'autre_cle');
  assert.equal(server.files.size, 1);
});

test('panne pendant l\'envoi : ce qui reste à envoyer repart au passage suivant', async () => {
  const server = fakeStorage();
  const A = browser(server);
  const e1 = newEntry({ name: 'Un' });
  const e2 = newEntry({ name: 'Deux' });
  await A.put(e1);
  await A.put(e2);
  await A.sync();   // dépose le descripteur et les deux entrées
  await A.put(editEntry(e1, { password: 'a' }));
  await A.put(editEntry(e2, { password: 'b' }));
  const write = server.write.bind(server);
  let n = 0;
  server.write = async (...args) => { if (++n === 2) throw new CoffreError('reseau', 'x'); return write(...args); };
  await assert.rejects(A.sync());
  server.write = write;
  const r = await A.sync();
  assert.equal(r.sent, 1);
  const B = browser(server);
  await B.sync();
  assert.deepEqual((await B.all()).map((x) => x.password).sort(), ['a', 'b']);
});

test('le cache local ne contient aucun clair', async () => {
  const local = memArea();
  const store = createEntryStore(local);
  await store.put(v.key, v.keyId, newEntry({ name: 'Nom secret', origins: ['secret.fr'], username: 'idsecret', password: 'mdpsecret' }));
  const dump = JSON.stringify([...local.m]);
  for (const s of ['Nom secret', 'secret.fr', 'idsecret', 'mdpsecret']) assert.ok(!dump.includes(s), s);
});

test('une enveloppe d\'un autre coffre restée dans le cache n\'est jamais envoyée', async () => {
  const server = fakeStorage();
  const local = memArea();
  const autre = await createVault('une autre phrase bien longue');
  await createEntryStore(local).put(autre.key, autre.keyId, newEntry({ name: 'Vieux' }));
  const store = createEntryStore(local);
  const r = await synchronize({ store, client: server, ns: 'moi', key: v.key, keyId: v.keyId, descriptor });
  assert.equal(r.sent + r.restored, 0);
  assert.equal(server.files.size, 1); // le descripteur seul
});

test('vider le cache retire réellement les clés', async () => {
  const local = memArea();
  const store = createEntryStore(local);
  await store.put(v.key, v.keyId, newEntry({ name: 'X' }));
  await store.clear();
  assert.equal(local.m.size, 0);
});
