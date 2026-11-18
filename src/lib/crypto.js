// Chiffrement du coffre — implémente docs/FORMAT.md (v1). WebCrypto seulement, aucune dépendance.
//
// Règles tenues ici, à ne pas assouplir :
//  - la clé de coffre importée pour l'usage n'est jamais extractible ;
//  - un IV neuf à chaque chiffrement ;
//  - les données associées lient chaque chiffré à son format, sa version, sa clé et son id ;
//  - les messages d'erreur sont fixes (jamais un secret, jamais un contenu).

import { fromB64u, toB64u } from './base64.js';

export const KEY_FORMAT = 'coffre-navigateur/cle';
export const ENTRY_FORMAT = 'coffre-navigateur/entree';
export const VAULT_FORMAT = 'coffre-navigateur/coffre';
export const VERSION = 1;
export const MIN_ITERATIONS = 600_000;
export const MIN_PASSPHRASE = 12;

const subtle = globalThis.crypto.subtle;
const enc = new TextEncoder();
const dec = new TextDecoder('utf-8', { fatal: true });

export class CoffreError extends Error {
  /** @param {'format'|'phrase_courte'|'phrase_ou_fichier'|'autre_cle'|'entree_alteree'|'aucun_coffre'|'autre_coffre_installe'|'reglage'|'connexion'|'connexion_requise'|'acces_refuse'|'reseau'|'session'|'serveur'} code */
  constructor(code, message) {
    super(message);
    this.name = 'CoffreError';
    this.code = code;
  }
}

function random(n) {
  return globalThis.crypto.getRandomValues(new Uint8Array(n));
}

function bytesField(obj, name, len) {
  let v;
  try {
    v = fromB64u(obj[name]);
  } catch {
    throw new CoffreError('format', `Champ « ${name} » invalide.`);
  }
  if (len !== undefined && v.length !== len) {
    throw new CoffreError('format', `Champ « ${name} » de taille inattendue.`);
  }
  return v;
}

function checkHeader(obj, format) {
  if (!obj || typeof obj !== 'object' || obj.format !== format) {
    throw new CoffreError('format', 'Ce fichier n\'est pas au format attendu.');
  }
  if (obj.version !== VERSION) {
    throw new CoffreError('format', 'Version de format non prise en charge.');
  }
  bytesField(obj, 'key_id', 16);
}

async function deriveWrappingKey(passphrase, salt, iterations) {
  const base = await subtle.importKey(
    'raw', enc.encode(passphrase.normalize('NFC')), 'PBKDF2', false, ['deriveKey'],
  );
  return subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/** Importe une clé brute (32 octets) en clé AES-GCM non extractible. */
export function importVaultKey(raw) {
  if (!(raw instanceof Uint8Array) || raw.length !== 32) {
    throw new CoffreError('format', 'Clé de coffre invalide.');
  }
  return subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

async function wrap(raw, keyId, passphrase, iterations) {
  const salt = random(16);
  const iv = random(12);
  const wk = await deriveWrappingKey(passphrase, salt, iterations);
  const wrapped = await subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: enc.encode(`${KEY_FORMAT}|${VERSION}|${keyId}`) },
    wk,
    raw,
  );
  return {
    kdf: { name: 'PBKDF2-SHA256', iterations, salt: toB64u(salt) },
    iv: toB64u(iv),
    wrapped: toB64u(wrapped),
  };
}

async function unwrapRaw(keyFile, passphrase) {
  checkHeader(keyFile, KEY_FORMAT);
  const kdf = keyFile.kdf;
  if (!kdf || kdf.name !== 'PBKDF2-SHA256' || !Number.isInteger(kdf.iterations)
      || kdf.iterations < MIN_ITERATIONS || kdf.iterations > 10_000_000) {
    throw new CoffreError('format', 'Paramètres de dérivation refusés.');
  }
  const salt = bytesField(kdf, 'salt', 16);
  const iv = bytesField(keyFile, 'iv', 12);
  const wrapped = bytesField(keyFile, 'wrapped', 48);
  const wk = await deriveWrappingKey(String(passphrase ?? ''), salt, kdf.iterations);
  try {
    const raw = await subtle.decrypt(
      { name: 'AES-GCM', iv, additionalData: enc.encode(`${KEY_FORMAT}|${VERSION}|${keyFile.key_id}`) },
      wk,
      wrapped,
    );
    return new Uint8Array(raw);
  } catch {
    throw new CoffreError('phrase_ou_fichier', 'Phrase de passe incorrecte, ou fichier de clé abîmé.');
  }
}

function checkPassphrase(passphrase) {
  if (typeof passphrase !== 'string' || [...passphrase.normalize('NFC')].length < MIN_PASSPHRASE) {
    throw new CoffreError('phrase_courte', `La phrase de passe doit faire au moins ${MIN_PASSPHRASE} caractères.`);
  }
}

/**
 * Crée un nouveau coffre : tire la clé, rend le fichier de clé à enregistrer sur USB
 * et la clé utilisable (non extractible).
 */
export async function createVault(passphrase, { iterations = MIN_ITERATIONS, now = new Date() } = {}) {
  checkPassphrase(passphrase);
  if (!Number.isInteger(iterations) || iterations < MIN_ITERATIONS) {
    throw new CoffreError('format', 'Nombre d\'itérations insuffisant.');
  }
  const raw = random(32);
  const keyId = toB64u(random(16));
  try {
    const keyFile = {
      format: KEY_FORMAT,
      version: VERSION,
      key_id: keyId,
      created: now.toISOString(),
      ...(await wrap(raw, keyId, passphrase, iterations)),
    };
    return { keyFile, keyId, key: await importVaultKey(raw) };
  } finally {
    raw.fill(0);
  }
}

/** Ouvre un fichier de clé avec sa phrase de passe. */
export async function openKeyFile(keyFile, passphrase) {
  const raw = await unwrapRaw(keyFile, passphrase);
  try {
    return { keyId: keyFile.key_id, key: await importVaultKey(raw) };
  } finally {
    raw.fill(0);
  }
}

/**
 * Ouvre un fichier de clé et rend la clé brute : seulement pour la poser en storage.session
 * (docs/FORMAT.md §5). L'appelant efface `raw` dès qu'il l'a recopiée.
 */
export async function openKeyFileRaw(keyFile, passphrase) {
  return { keyId: keyFile.key_id, raw: await unwrapRaw(keyFile, passphrase) };
}

/** Rend un nouveau fichier de clé, même clé de coffre, autre phrase de passe. */
export async function changePassphrase(keyFile, oldPassphrase, newPassphrase) {
  checkPassphrase(newPassphrase);
  const raw = await unwrapRaw(keyFile, oldPassphrase);
  try {
    return {
      format: KEY_FORMAT,
      version: VERSION,
      key_id: keyFile.key_id,
      created: keyFile.created,
      ...(await wrap(raw, keyFile.key_id, newPassphrase, Math.max(keyFile.kdf.iterations, MIN_ITERATIONS))),
    };
  } finally {
    raw.fill(0);
  }
}

/** Lit un texte comme fichier de clé (JSON) — erreur fixe si ce n'en est pas un. */
export function parseKeyFile(text) {
  let obj;
  try {
    obj = JSON.parse(text);
  } catch {
    throw new CoffreError('format', 'Ce fichier n\'est pas un fichier de clé.');
  }
  checkHeader(obj, KEY_FORMAT);
  return obj;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function entryAad(keyId, id) {
  return enc.encode(`${ENTRY_FORMAT}|${VERSION}|${keyId}|${id}`);
}

/** Chiffre une entrée (objet docs/FORMAT.md §3) en enveloppe. */
export async function encryptEntry(key, keyId, entry) {
  if (!entry || typeof entry.id !== 'string' || !UUID_RE.test(entry.id)) {
    throw new CoffreError('format', 'Identifiant d\'entrée invalide.');
  }
  const iv = random(12);
  const ct = await subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: entryAad(keyId, entry.id) },
    key,
    enc.encode(JSON.stringify(entry)),
  );
  return { format: ENTRY_FORMAT, version: VERSION, key_id: keyId, id: entry.id, iv: toB64u(iv), ct: toB64u(ct) };
}

/** Déchiffre une enveloppe ; refuse une autre clé, un autre id ou une altération. */
export async function decryptEntry(key, keyId, envelope) {
  checkHeader(envelope, ENTRY_FORMAT);
  if (typeof envelope.id !== 'string' || !UUID_RE.test(envelope.id)) {
    throw new CoffreError('format', 'Identifiant d\'entrée invalide.');
  }
  if (envelope.key_id !== keyId) {
    throw new CoffreError('autre_cle', 'Cette entrée appartient à un autre coffre.');
  }
  const iv = bytesField(envelope, 'iv', 12);
  const ct = bytesField(envelope, 'ct');
  let entry;
  try {
    const plain = await subtle.decrypt(
      { name: 'AES-GCM', iv, additionalData: entryAad(keyId, envelope.id) }, key, ct,
    );
    entry = JSON.parse(dec.decode(plain));
  } catch {
    throw new CoffreError('entree_alteree', 'Entrée illisible ou altérée.');
  }
  if (!entry || entry.id !== envelope.id) {
    throw new CoffreError('entree_alteree', 'Entrée illisible ou altérée.');
  }
  return entry;
}

/** Descripteur public déposé à côté des entrées (docs/FORMAT.md §4). */
export function vaultDescriptor(keyFile) {
  return { format: VAULT_FORMAT, version: VERSION, key_id: keyFile.key_id, created: keyFile.created };
}
