import { test } from 'node:test';
import assert from 'node:assert/strict';

import { CLASSES, entropyBits, generatePassword } from '../src/lib/generator.js';

test('longueur demandée et une famille de chaque', () => {
  for (let i = 0; i < 200; i++) {
    const p = generatePassword({ length: 12 });
    assert.equal(p.length, 12);
    for (const set of Object.values(CLASSES)) {
      assert.ok([...p].some((c) => set.includes(c)), `${p} manque une famille`);
    }
  }
});

test('caractères ambigus écartés par défaut', () => {
  const p = Array.from({ length: 200 }, () => generatePassword({ length: 64 })).join('');
  assert.doesNotMatch(p, /[Il1O0o|`'"]/);
});

test('familles restreintes', () => {
  const p = generatePassword({ length: 40, upper: false, symbols: false, avoidAmbiguous: false });
  assert.match(p, /^[a-z0-9]{40}$/);
});

test('bornes refusées', () => {
  assert.throws(() => generatePassword({ length: 4 }), RangeError);
  assert.throws(() => generatePassword({ length: 500 }), RangeError);
  assert.throws(() => generatePassword({ lower: false, upper: false, digits: false, symbols: false }), RangeError);
});

test('répartition à peu près uniforme (pas de biais grossier)', () => {
  const counts = {};
  const N = 20000;
  for (let i = 0; i < N / 20; i++) {
    for (const c of generatePassword({ length: 20, upper: false, symbols: false, digits: false, avoidAmbiguous: false })) {
      counts[c] = (counts[c] || 0) + 1;
    }
  }
  const expected = N / 26;
  for (const c of CLASSES.lower) {
    assert.ok(Math.abs(counts[c] - expected) < expected * 0.25, `${c}: ${counts[c]}`);
  }
});

test('deux tirages diffèrent ; entropie par défaut ≥ 120 bits', () => {
  assert.notEqual(generatePassword(), generatePassword());
  assert.ok(entropyBits() >= 120);
});
