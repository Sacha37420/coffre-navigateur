// État du coffre dans un navigateur : aucun / verrouillé / déverrouillé (docs/FORMAT.md §5).
// Logique pure : les zones de stockage, l'horloge et la planification sont injectées.

import { toB64u, fromB64u } from './base64.js';
import { CoffreError, importVaultKey, openKeyFileRaw, changePassphrase as rewrap } from './crypto.js';

export const K_FILE = 'cle';
export const K_SETTINGS = 'reglages';
export const S_KEY = 'session';
export const DEFAULT_SETTINGS = Object.freeze({ autoLockMinutes: 15 });
export const AUTO_LOCK_CHOICES = Object.freeze([1, 5, 15, 30, 60, 240]);

/**
 * @param {{ local: Area, session: Area, now?: () => number, schedule?: (when: number|null) => void }} deps
 * Area = { get(key) → Promise<any>, set(key, value) → Promise, remove(key) → Promise }
 */
export function createVaultState({ local, session, now = () => Date.now(), schedule = () => {} }) {
  async function settings() {
    return { ...DEFAULT_SETTINGS, ...((await local.get(K_SETTINGS)) || {}) };
  }

  async function startSession(keyId, raw) {
    try {
      const { autoLockMinutes } = await settings();
      const expires = now() + autoLockMinutes * 60_000;
      await session.set(S_KEY, { key_id: keyId, raw: toB64u(raw), expires });
      schedule(expires);
    } finally {
      raw.fill(0);
    }
  }

  async function lock() {
    await session.remove(S_KEY);
    schedule(null);
  }

  async function readSession() {
    const s = await session.get(S_KEY);
    if (!s) return null;
    if (now() >= s.expires) {
      await lock();
      return null;
    }
    return s;
  }

  return {
    settings,

    async setSettings(patch) {
      const next = { ...(await settings()), ...patch };
      if (!AUTO_LOCK_CHOICES.includes(next.autoLockMinutes)) {
        throw new CoffreError('reglage', 'Délai de verrouillage non pris en charge.');
      }
      await local.set(K_SETTINGS, next);
      return next;
    },

    async status() {
      const kf = await local.get(K_FILE);
      if (!kf) return { state: 'aucun' };
      const s = await readSession();
      if (!s || s.key_id !== kf.key_id) return { state: 'verrouille', keyId: kf.key_id };
      return { state: 'deverrouille', keyId: kf.key_id, expires: s.expires };
    },

    /** Installe un fichier de clé (création ou import) et déverrouille. */
    async install(keyFile, passphrase, { replace = false } = {}) {
      const current = await local.get(K_FILE);
      if (current && current.key_id !== keyFile.key_id && !replace) {
        throw new CoffreError('autre_coffre_installe', 'Un autre coffre est déjà installé dans ce navigateur.');
      }
      const { keyId, raw } = await openKeyFileRaw(keyFile, passphrase); // valide phrase et fichier
      await local.set(K_FILE, keyFile);
      await startSession(keyId, raw);
    },

    async unlock(passphrase) {
      const kf = await local.get(K_FILE);
      if (!kf) throw new CoffreError('aucun_coffre', 'Aucun coffre dans ce navigateur.');
      const { keyId, raw } = await openKeyFileRaw(kf, passphrase);
      await startSession(keyId, raw);
    },

    lock,

    /** Clé utilisable, ou null si verrouillé. Chaque appel repousse le verrouillage. */
    async key() {
      const s = await readSession();
      if (!s) return null;
      const raw = fromB64u(s.raw);
      try {
        const key = await importVaultKey(raw);
        const { autoLockMinutes } = await settings();
        const expires = now() + autoLockMinutes * 60_000;
        await session.set(S_KEY, { ...s, expires });
        schedule(expires);
        return { keyId: s.key_id, key };
      } finally {
        raw.fill(0);
      }
    },

    /** À l'échéance planifiée : verrouille si le délai est passé, sinon replanifie. */
    async expireIfDue() {
      const s = await session.get(S_KEY);
      if (!s) return;
      if (now() >= s.expires) await lock();
      else schedule(s.expires);
    },

    /** Le fichier de clé de ce navigateur (chiffré), pour en refaire une copie sur USB. */
    async keyFile() {
      return (await local.get(K_FILE)) || null;
    },

    /** Nouvelle phrase de passe ; rend le nouveau fichier à recopier sur les clés USB. */
    async changePassphrase(oldPassphrase, newPassphrase) {
      const kf = await local.get(K_FILE);
      if (!kf) throw new CoffreError('aucun_coffre', 'Aucun coffre dans ce navigateur.');
      const next = await rewrap(kf, oldPassphrase, newPassphrase);
      await local.set(K_FILE, next);
      return next;
    },

    /** Retire le coffre de ce navigateur (le fichier sur USB n'est pas touché). */
    async forget() {
      await lock();
      await local.remove(K_FILE);
    },
  };
}

/** Deux fichiers de clé sont-ils la même copie (même clé, même phrase, même emballage) ? */
export function sameKeyFile(a, b) {
  return !!a && !!b && a.key_id === b.key_id && a.wrapped === b.wrapped && a.iv === b.iv
    && a.kdf?.salt === b.kdf?.salt && a.kdf?.iterations === b.kdf?.iterations;
}
