// Cache local des enveloppes et synchronisation avec storage (docs/FORMAT.md §3-4).
// storage.local ne contient que des enveloppes chiffrées et de l'état de suivi, jamais de clair.
import { CoffreError, decryptEntry, encryptEntry } from './crypto.js';

export const DIR = 'coffre/v1';
export const ENTRIES_PREFIX = `${DIR}/entrees/`;
export const DESCRIPTOR_PATH = `${DIR}/coffre.json`;
export const K_ENVELOPES = 'entrees';
export const K_TRACK = 'suivi';
const PATH_RE = /^coffre\/v1\/entrees\/([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.bin$/;

export const entryPath = (id) => `${ENTRIES_PREFIX}${id}.bin`;

/** Accès au cache local. `local` : { get, set, remove } (voir vault-state). */
export function createEntryStore(local) {
  async function load() {
    return {
      envelopes: (await local.get(K_ENVELOPES)) || {},
      track: (await local.get(K_TRACK)) || {},
    };
  }
  async function save({ envelopes, track }) {
    await local.set(K_ENVELOPES, envelopes);
    await local.set(K_TRACK, track);
  }
  return {
    load,
    save,

    /** Chiffre et range une entrée ; elle partira à la prochaine synchronisation. */
    async put(key, keyId, entry) {
      const st = await load();
      st.envelopes[entry.id] = await encryptEntry(key, keyId, entry);
      st.track[entry.id] = { ...(st.track[entry.id] || { seen: null }), dirty: true };
      await save(st);
    },

    /** Entrées lisibles (pierres tombales comprises) ; les illisibles sont comptées, jamais effacées. */
    async decryptAll(key, keyId) {
      const { envelopes } = await load();
      const entries = [];
      let unreadable = 0;
      for (const env of Object.values(envelopes)) {
        try { entries.push(await decryptEntry(key, keyId, env)); } catch { unreadable++; }
      }
      return { entries, unreadable };
    },

    /** Tout est à renvoyer (changement d'adresse du lab). */
    async markAllDirty() {
      const st = await load();
      for (const id of Object.keys(st.envelopes)) st.track[id] = { seen: null, dirty: true };
      await save(st);
    },

    async clear() {
      await local.remove(K_ENVELOPES);
      await local.remove(K_TRACK);
    },
  };
}

async function readEnvelope(text, key, keyId, expectedId) {
  const env = JSON.parse(text);
  if (env?.id !== expectedId) throw new CoffreError('entree_alteree', 'Entrée illisible ou altérée.');
  return { env, entry: await decryptEntry(key, keyId, env) };
}

/**
 * Un passage de synchronisation. Rend un compte rendu chiffré (nombres seulement).
 * @param {{ store, client, ns: string, key: CryptoKey, keyId: string, descriptor: object }} p
 */
export async function synchronize({ store, client, ns, key, keyId, descriptor }) {
  const report = { received: 0, sent: 0, restored: 0, ignored: 0 };

  const remoteDesc = await client.read(ns, DESCRIPTOR_PATH);
  if (remoteDesc === null) {
    await client.write(ns, DESCRIPTOR_PATH, JSON.stringify(descriptor));
  } else {
    let d;
    try { d = JSON.parse(remoteDesc); } catch { d = null; }
    if (!d || d.format !== 'coffre-navigateur/coffre') {
      throw new CoffreError('serveur', 'Le dossier coffre/ du lab contient un fichier inattendu.');
    }
    if (d.key_id !== keyId) {
      throw new CoffreError('autre_cle', 'Le coffre enregistré sur le lab a été créé avec un autre fichier de clé.');
    }
  }

  const remote = new Map();
  for (const f of await client.list(ns, ENTRIES_PREFIX)) {
    const m = PATH_RE.exec(f.path);
    if (m) remote.set(m[1], f.updated);
  }

  const st = await store.load();
  try {
    // 1. Ce qui a changé sur le serveur.
    for (const [id, rUpdated] of remote) {
      const t = st.track[id];
      if (t && t.seen === rUpdated) continue;
      const text = await client.read(ns, entryPath(id));
      if (text === null) continue;
      let r;
      try { r = await readEnvelope(text, key, keyId, id); } catch { report.ignored++; continue; }
      let l = null;
      if (st.envelopes[id]) {
        try { l = await decryptEntry(key, keyId, st.envelopes[id]); } catch { l = null; }
      }
      if (!l || r.entry.updated > l.updated) {
        st.envelopes[id] = r.env;
        st.track[id] = { seen: rUpdated, dirty: false };
        report.received++;
      } else {
        // Même version : rien à faire. Version locale plus récente : elle repart.
        st.track[id] = { seen: rUpdated, dirty: r.entry.updated !== l.updated || !!t?.dirty };
      }
    }

    // 2. Ce qui a disparu du serveur sans pierre tombale : on redépose.
    for (const [id, env] of Object.entries(st.envelopes)) {
      if (env.key_id !== keyId) continue; // reste d'un autre coffre : jamais envoyé
      if (!remote.has(id) && !st.track[id]?.dirty) {
        st.track[id] = { seen: null, dirty: true };
        report.restored++;
      }
    }
    await store.save(st);

    // 3. Envoi des entrées locales nouvelles ou modifiées.
    for (const [id, t] of Object.entries(st.track)) {
      if (!t.dirty || !st.envelopes[id] || st.envelopes[id].key_id !== keyId) continue;
      const { updated } = await client.write(ns, entryPath(id), JSON.stringify(st.envelopes[id]));
      st.track[id] = { seen: updated, dirty: false };
      report.sent++;
    }
  } finally {
    await store.save(st);
  }
  return report;
}
