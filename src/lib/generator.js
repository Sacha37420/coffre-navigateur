// Générateur de mots de passe — tirage uniforme (rejet), au moins un caractère de chaque famille demandée.

export const CLASSES = {
  lower: 'abcdefghijklmnopqrstuvwxyz',
  upper: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
  digits: '0123456789',
  symbols: '!#$%&()*+,-./:;<=>?@[]^_{|}~',
};
const AMBIGUOUS = new Set('Il1O0o|`\'"');

export const DEFAULTS = { length: 20, lower: true, upper: true, digits: true, symbols: true, avoidAmbiguous: true };

function randomIndex(n) {
  // Rejet pour éviter le biais du modulo.
  const limit = Math.floor(0x100000000 / n) * n;
  const buf = new Uint32Array(1);
  for (;;) {
    globalThis.crypto.getRandomValues(buf);
    if (buf[0] < limit) return buf[0] % n;
  }
}

export function generatePassword(options = {}) {
  const o = { ...DEFAULTS, ...options };
  if (!Number.isInteger(o.length) || o.length < 8 || o.length > 128) {
    throw new RangeError('La longueur doit être comprise entre 8 et 128.');
  }
  const sets = Object.keys(CLASSES)
    .filter((k) => o[k])
    .map((k) => [...CLASSES[k]].filter((c) => !o.avoidAmbiguous || !AMBIGUOUS.has(c)));
  if (sets.length === 0) throw new RangeError('Choisir au moins une famille de caractères.');
  if (sets.length > o.length) throw new RangeError('Longueur trop courte pour les familles choisies.');

  const all = sets.flat();
  const chars = sets.map((s) => s[randomIndex(s.length)]);
  while (chars.length < o.length) chars.push(all[randomIndex(all.length)]);
  // Mélange de Fisher-Yates, pour que les caractères imposés ne soient pas en tête.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomIndex(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

/** Entropie théorique en bits (indicative, pour l'affichage). */
export function entropyBits(options = {}) {
  const o = { ...DEFAULTS, ...options };
  const n = Object.keys(CLASSES)
    .filter((k) => o[k])
    .reduce((acc, k) => acc + [...CLASSES[k]].filter((c) => !o.avoidAmbiguous || !AMBIGUOUS.has(c)).length, 0);
  return n ? Math.floor(o.length * Math.log2(n)) : 0;
}
