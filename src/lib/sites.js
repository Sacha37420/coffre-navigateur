// Sites : origine admise, empreintes des sites connus ou refusés (docs/FORMAT.md §5), correspondance d'entrées.
import DEFAULTS from '../defaults.js';

const LOOPBACK = new Set(['127.0.0.1', 'localhost']);

/** Origine d'une adresse d'onglet si le préremplissage y est permis : https seulement (boucle locale en essai). */
export function allowedOrigin(url, { essai = !!DEFAULTS.essai } = {}) {
  let u;
  try { u = new URL(url); } catch { return null; }
  if (u.protocol === 'https:') return u.origin;
  if (essai && u.protocol === 'http:' && LOOPBACK.has(u.hostname)) return u.origin;
  return null;
}

export async function siteHash(keyId, origin) {
  const d = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${keyId}|${origin}`));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Entrées vivantes de l'origine exacte, triées par nom. */
export function entriesForOrigin(entries, origin) {
  return entries
    .filter((e) => !e.deleted && (e.origins || []).includes(origin))
    .sort((a, b) => a.name.localeCompare(b.name, 'fr') || a.username.localeCompare(b.username, 'fr'));
}

/**
 * Que proposer après la soumission d'un formulaire : rien (déjà enregistré tel quel), une mise à jour
 * (même identifiant, autre mot de passe) ou un nouveau compte.
 */
export function proposalFor(entries, origin, username, password) {
  const same = entriesForOrigin(entries, origin);
  if (same.some((e) => e.username === username && e.password === password)) return null;
  const existing = same.find((e) => e.username === username);
  return existing ? { kind: 'maj', entryId: existing.id } : { kind: 'nouveau', entryId: null };
}
