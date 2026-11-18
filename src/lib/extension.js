// Branchement des modules sur les API du navigateur (Chrome, Edge, Firefox).
import { createAuth } from './auth.js';
import { createEntryStore } from './sync.js';
import { createVaultState } from './vault-state.js';

export const api = globalThis.browser ?? globalThis.chrome;
export const LOCK_ALARM = 'verrou';
export const SYNC_ALARM = 'synchro';
export const K_SYNC_STATUS = 'synchro';

export function area(storageArea) {
  return {
    get: async (k) => (await storageArea.get(k))[k],
    set: (k, v) => storageArea.set({ [k]: v }),
    remove: (k) => storageArea.remove(k),
  };
}

export const localArea = () => area(api.storage.local);

export function extensionVault() {
  return createVaultState({
    local: localArea(),
    session: area(api.storage.session),
    schedule: (when) => {
      if (when === null) api.alarms.clear(LOCK_ALARM);
      else api.alarms.create(LOCK_ALARM, { when });
    },
  });
}

export const entryStore = () => createEntryStore(localArea());

export function extensionAuth() {
  return createAuth({
    local: localArea(),
    session: area(api.storage.session),
    fetch: (...a) => globalThis.fetch(...a),
    identity: {
      redirectUrl: () => api.identity.getRedirectURL(),
      launch: (url, interactive) => api.identity.launchWebAuthFlow({ url, interactive }),
    },
  });
}

/** Message vers le processus de fond ; rend la réponse ou lève une erreur au message fixe. */
export async function ask(type, payload = {}) {
  const r = await api.runtime.sendMessage({ type, ...payload });
  if (!r?.ok) {
    const e = new Error(r?.message || 'Une erreur inattendue est survenue.');
    e.code = r?.code;
    e.userMessage = true;
    throw e;
  }
  return r;
}

export function keyFileName(keyFile) {
  return `coffre-du-lab-${keyFile.key_id.slice(0, 6)}.coffre-cle`;
}
