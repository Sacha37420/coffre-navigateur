// Processus de fond : verrouillage automatique, connexion au lab, synchronisation.
// Il ne répond qu'aux pages de l'extension elle-même (jamais à un script de contenu ni à un site).
import { CoffreError, vaultDescriptor } from './lib/crypto.js';
import {
  api, entryStore, extensionAuth, extensionVault, K_SYNC_STATUS, localArea, LOCK_ALARM, SYNC_ALARM,
} from './lib/extension.js';
import { createStorageClient } from './lib/storage-client.js';
import { synchronize } from './lib/sync.js';

const vault = extensionVault();
const auth = extensionAuth();
const store = entryStore();
const local = localArea();

const failure = (e) => ({
  ok: false,
  code: e?.code ?? 'inattendu',
  message: e instanceof CoffreError ? e.message : 'Une erreur inattendue est survenue.',
});

async function client() {
  const ep = await auth.endpoints();
  if (!(await api.permissions.contains({ origins: [ep.permission] }))) {
    throw new CoffreError('reglage', 'L\'extension n\'a pas l\'autorisation de joindre le lab : enregistrez à nouveau son adresse.');
  }
  return createStorageClient({ base: ep.storage, fetch: (...a) => globalThis.fetch(...a), token: () => auth.token(), renew: () => auth.invalidate() });
}

/** Un passage de synchronisation ; jamais deux à la fois. N'allonge pas le délai de verrouillage. */
function runSync() {
  return navigator.locks.request('coffre-synchro', { ifAvailable: true }, async (lock) => {
    if (!lock) return { ok: true, skipped: 'en_cours' };
    const k = await vault.peekKey();
    if (!k) return { ok: true, skipped: 'verrouille' };
    const cfg = await auth.config();
    if (!cfg?.origin || !cfg.username) return { ok: true, skipped: 'non_connecte' };
    let status;
    try {
      const report = await synchronize({
        store, client: await client(), ns: cfg.username, key: k.key, keyId: k.keyId,
        descriptor: vaultDescriptor(await vault.keyFile()),
      });
      status = { at: Date.now(), ok: true, report };
    } catch (e) {
      status = { at: Date.now(), ...failure(e) };
    }
    await local.set(K_SYNC_STATUS, status);
    return status.ok ? { ok: true, status } : status;
  });
}

const handlers = {
  async etat() {
    return {
      lab: await auth.config(),
      connected: await auth.connected(),
      synchro: (await local.get(K_SYNC_STATUS)) || null,
      redirectUrl: auth.redirectUrl(),
    };
  },
  // La page a déjà obtenu l'autorisation d'origine (elle seule peut la demander, sur un clic).
  async definir_lab({ url }) {
    const origin = await auth.setLab(url);
    await store.markAllDirty();
    await local.remove(K_SYNC_STATUS);
    return { origin };
  },
  async connexion() {
    await auth.login();
    const me = await (await client()).me();
    await auth.setUsername(me.username);
    return { username: me.username, sync: await runSync() };
  },
  async deconnexion() {
    await auth.logout();
    return {};
  },
  async synchroniser() {
    const r = await runSync();
    if (!r.ok) throw Object.assign(new CoffreError(r.code, r.message));
    return r;
  },
  async oublier_entrees() {
    await store.clear();
    await local.remove(K_SYNC_STATUS);
    return {};
  },
};

api.runtime.onMessage.addListener((msg, sender, reply) => {
  const fromOwnPage = sender.id === api.runtime.id && String(sender.url || '').startsWith(api.runtime.getURL(''));
  const handler = fromOwnPage && Object.hasOwn(handlers, msg?.type) ? handlers[msg.type] : null;
  if (!handler) return false;
  handler(msg).then((r) => reply({ ok: true, ...r }), (e) => reply(failure(e)));
  return true;
});

api.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === LOCK_ALARM) vault.expireIfDue();
  if (alarm.name === SYNC_ALARM) runSync();
});

// Synchronise dès que le coffre est déverrouillé, où que ce soit.
api.storage.onChanged.addListener((changes, areaName) => {
  if (areaName === 'session' && changes.session?.newValue && !changes.session.oldValue) runSync();
});

api.alarms.get(SYNC_ALARM).then((a) => {
  if (!a) api.alarms.create(SYNC_ALARM, { periodInMinutes: 10 });
});
