// Branchement de vault-state sur les API du navigateur (Chrome, Edge, Firefox).
import { createVaultState } from './vault-state.js';

export const api = globalThis.browser ?? globalThis.chrome;
export const LOCK_ALARM = 'verrou';

function area(storageArea) {
  return {
    get: async (k) => (await storageArea.get(k))[k],
    set: (k, v) => storageArea.set({ [k]: v }),
    remove: (k) => storageArea.remove(k),
  };
}

export function extensionVault() {
  return createVaultState({
    local: area(api.storage.local),
    session: area(api.storage.session),
    schedule: (when) => {
      if (when === null) api.alarms.clear(LOCK_ALARM);
      else api.alarms.create(LOCK_ALARM, { when });
    },
  });
}

export function keyFileName(keyFile) {
  return `coffre-du-lab-${keyFile.key_id.slice(0, 6)}.coffre-cle`;
}
