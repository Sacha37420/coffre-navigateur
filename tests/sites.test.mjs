import { test } from 'node:test';
import assert from 'node:assert/strict';

import { allowedOrigin, entriesForOrigin, proposalFor, siteHash } from '../src/lib/sites.js';
import { newEntry, tombstone } from '../src/lib/entries.js';

test('origine admise : https seulement, boucle locale en essai seulement', () => {
  assert.equal(allowedOrigin('https://banque.fr/connexion?x=1'), 'https://banque.fr');
  assert.equal(allowedOrigin('https://banque.fr:8443/'), 'https://banque.fr:8443');
  assert.equal(allowedOrigin('http://banque.fr/'), null);
  assert.equal(allowedOrigin('http://127.0.0.1:9000/'), null);
  assert.equal(allowedOrigin('http://127.0.0.1:9000/', { essai: true }), 'http://127.0.0.1:9000');
  assert.equal(allowedOrigin('http://exemple.fr/', { essai: true }), null);
  assert.equal(allowedOrigin('chrome://extensions'), null);
  assert.equal(allowedOrigin('file:///etc/passwd'), null);
  assert.equal(allowedOrigin('pas une adresse'), null);
});

test('empreintes : stables, liées à la clé et à l\'origine', async () => {
  const a = await siteHash('K1', 'https://banque.fr');
  assert.match(a, /^[0-9a-f]{64}$/);
  assert.equal(a, await siteHash('K1', 'https://banque.fr'));
  assert.notEqual(a, await siteHash('K2', 'https://banque.fr'));
  assert.notEqual(a, await siteHash('K1', 'https://banque.fr:444'));
});

test('entrées de l\'origine exacte seulement (ni sous-domaine, ni autre port, ni pierre tombale)', () => {
  const a = newEntry({ name: 'B', origins: ['https://banque.fr'], username: 'u1', password: 'p' });
  const b = newEntry({ name: 'A', origins: ['https://banque.fr', 'https://m.banque.fr'], username: 'u2', password: 'p' });
  const c = newEntry({ name: 'C', origins: ['https://banque.fr:8443'], username: 'u3', password: 'p' });
  const d = tombstone(newEntry({ name: 'D', origins: ['https://banque.fr'], username: 'u4', password: 'p' }));
  assert.deepEqual(entriesForOrigin([a, b, c, d], 'https://banque.fr').map((e) => e.name), ['A', 'B']);
  assert.deepEqual(entriesForOrigin([a, b, c, d], 'https://m.banque.fr').map((e) => e.name), ['A']);
  assert.deepEqual(entriesForOrigin([a, b, c, d], 'https://www.banque.fr'), []);
});

test('proposition après soumission : rien, mise à jour, ou nouveau', () => {
  const a = newEntry({ name: 'B', origins: ['https://banque.fr'], username: 'moi', password: 'ancien' });
  assert.equal(proposalFor([a], 'https://banque.fr', 'moi', 'ancien'), null);
  assert.deepEqual(proposalFor([a], 'https://banque.fr', 'moi', 'nouveau'), { kind: 'maj', entryId: a.id });
  assert.deepEqual(proposalFor([a], 'https://banque.fr', 'autre', 'x'), { kind: 'nouveau', entryId: null });
  assert.deepEqual(proposalFor([a], 'https://ailleurs.fr', 'moi', 'ancien'), { kind: 'nouveau', entryId: null });
});
