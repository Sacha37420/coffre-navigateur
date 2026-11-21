import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { defaultsModule, parseDefaults } from '../scripts/defaults.mjs';

test('config.local.json : https sans chemin seulement, clés inconnues refusées', () => {
  assert.deepEqual(parseDefaults('{"labUrl":"https://lab.exemple.fr/"}'), { labUrl: 'https://lab.exemple.fr' });
  assert.deepEqual(parseDefaults('{}'), { labUrl: '' });
  assert.throws(() => parseDefaults('{"labUrl":"http://lab.exemple.fr"}'));
  assert.throws(() => parseDefaults('{"labUrl":"https://lab.exemple.fr/storage"}'));
  assert.throws(() => parseDefaults('{"labUrl":"https://a:b@lab.exemple.fr"}'));
  assert.throws(() => parseDefaults('{"labUrl":"https://lab.exemple.fr","autre":1}'));
});

test('module généré : vide sans fichier, adresse avec', () => {
  assert.match(defaultsModule('/n/existe/pas.json'), /\{"labUrl":""\}/);
  const d = mkdtempSync(join(tmpdir(), 'coffre-'));
  writeFileSync(join(d, 'c.json'), '{"labUrl":"https://lab.exemple.fr"}');
  assert.match(defaultsModule(join(d, 'c.json')), /\{"labUrl":"https:\/\/lab\.exemple\.fr"\}/);
});
