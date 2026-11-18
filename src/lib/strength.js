// Évaluation indicative d'une phrase de passe (affichage seulement ; la règle dure est MIN_PASSPHRASE).
import { MIN_PASSPHRASE } from './crypto.js';

export function passphraseStrength(text) {
  const p = String(text ?? '').normalize('NFC');
  const chars = [...p];
  if (chars.length < MIN_PASSPHRASE) {
    return { level: 'refusee', label: `Trop courte (${MIN_PASSPHRASE} caractères au moins).` };
  }
  const distinct = new Set(chars.map((c) => c.toLowerCase())).size;
  if (distinct < 6 || /(.)\1{3,}/u.test(p) || /^(?:0123|1234|abcd|azer|qwer)/i.test(p)) {
    return { level: 'faible', label: 'Faible : trop répétitive ou trop prévisible.' };
  }
  const classes = [/\p{Ll}/u, /\p{Lu}/u, /\p{N}/u, /[^\p{L}\p{N}\s]/u, /\s/u].filter((r) => r.test(p)).length;
  const words = p.trim().split(/\s+/).filter((w) => w.length >= 3).length;
  if (chars.length >= 24 || (chars.length >= 16 && classes >= 3) || words >= 5) {
    return { level: 'bonne', label: 'Bonne.' };
  }
  return { level: 'moyenne', label: 'Moyenne : allongez-la (une phrase de cinq mots ou plus est idéale).' };
}
