// Modèle d'entrée (docs/FORMAT.md §3) : création, modification, pierre tombale.
import { CoffreError } from './crypto.js';

/** Normalise une origine saisie (« exemple.fr », « https://exemple.fr/login ») en origine exacte. */
export function normalizeOrigin(input) {
  let text = String(input ?? '').trim();
  if (!text) throw new CoffreError('format', 'Adresse de site vide.');
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = `https://${text}`;
  let u;
  try { u = new URL(text); } catch { throw new CoffreError('format', 'Adresse de site invalide.'); }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') throw new CoffreError('format', 'Adresse de site invalide.');
  return u.origin;
}

function clean(fields) {
  const origins = [...new Set((fields.origins || []).map(normalizeOrigin))];
  const name = String(fields.name ?? '').trim() || (origins[0] ? new URL(origins[0]).hostname : '');
  if (!name) throw new CoffreError('format', 'Donnez un nom ou une adresse de site.');
  return {
    name: name.slice(0, 200),
    origins,
    username: String(fields.username ?? '').slice(0, 500),
    password: String(fields.password ?? '').slice(0, 2000),
    notes: String(fields.notes ?? '').slice(0, 10_000),
  };
}

function iso(now) {
  return new Date(now).toISOString();
}

export function newEntry(fields, now = Date.now()) {
  const t = iso(now);
  return { id: globalThis.crypto.randomUUID(), type: 'login', ...clean(fields), created: t, updated: t, deleted: false };
}

/** `updated` strictement croissant, même si l'horloge recule. */
function nextStamp(prev, now) {
  const t = iso(now);
  return t > prev ? t : iso(Date.parse(prev) + 1);
}

export function editEntry(entry, fields, now = Date.now()) {
  if (entry.deleted) throw new CoffreError('format', 'Cette entrée a été supprimée.');
  return { ...entry, ...clean({ ...entry, ...fields }), updated: nextStamp(entry.updated, now) };
}

export function tombstone(entry, now = Date.now()) {
  return {
    id: entry.id, type: entry.type, name: '', origins: [], username: '', password: '', notes: '',
    created: entry.created, updated: nextStamp(entry.updated, now), deleted: true,
  };
}

function fold(text) {
  return String(text ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}

/** Entrées vivantes correspondant à la requête (nom, sites, identifiant), triées par nom. */
export function searchEntries(entries, query = '') {
  const words = fold(query).split(/\s+/).filter(Boolean);
  return entries
    .filter((e) => !e.deleted)
    .filter((e) => {
      const hay = fold([e.name, e.username, ...(e.origins || [])].join(' '));
      return words.every((w) => hay.includes(w));
    })
    .sort((a, b) => fold(a.name).localeCompare(fold(b.name), 'fr') || a.id.localeCompare(b.id));
}

/** Nom d'hôte lisible d'une origine (pour l'affichage). */
export function hostOf(origin) {
  try { return new URL(origin).host; } catch { return ''; }
}
