// Processus de fond : verrouillage automatique, connexion au lab, synchronisation, préremplissage.
// Pages de l'extension (dont ses cadres dans les sites) : tous les gestionnaires `handlers`.
// Script de contenu : seulement `contentHandlers`, qui ne rendent jamais ni clé, ni liste, ni nom de compte.
import { CoffreError, vaultDescriptor } from './lib/crypto.js';
import { editEntry, newEntry } from './lib/entries.js';
import { generatePassword } from './lib/generator.js';
import { allowedOrigin, entriesForOrigin, proposalFor, siteHash } from './lib/sites.js';
import DEFAULTS from './defaults.js';
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

// ---------- Préremplissage : index des sites, propositions, script de contenu ----------

const K_INDEX = 'index_sites';
const K_REFUSED = 'sites_refuses';
const K_PREFILL = 'preremplissage';
const S_PROPOSALS = 'propositions';
const PROPOSAL_MS = 3 * 60_000;
const SCRIPT_ID = 'coffre-formulaires';
const session = { get: async (k) => (await api.storage.session.get(k))[k], set: (k, v) => api.storage.session.set({ [k]: v }) };

const host = (origin) => { try { return new URL(origin).host; } catch { return ''; } };

/** Empreintes des origines des entrées vivantes (coffre ouvert seulement). */
async function rebuildIndex() {
  const k = await vault.peekKey();
  if (!k) return;
  const { entries } = await store.decryptAll(k.key, k.keyId);
  const hashes = new Set();
  for (const e of entries) if (!e.deleted) for (const o of e.origins || []) hashes.add(await siteHash(k.keyId, o));
  await local.set(K_INDEX, [...hashes]);
}

async function keyIdNow() {
  return (await vault.keyFile())?.key_id ?? null;
}

async function known(origin) {
  const keyId = await keyIdNow();
  return !!keyId && ((await local.get(K_INDEX)) || []).includes(await siteHash(keyId, origin));
}

async function refused(origin) {
  const keyId = await keyIdNow();
  return !!keyId && ((await local.get(K_REFUSED)) || []).includes(await siteHash(keyId, origin));
}

async function proposals() {
  const all = (await session.get(S_PROPOSALS)) || {};
  const now = Date.now();
  for (const [t, p] of Object.entries(all)) if (now - p.at > PROPOSAL_MS) delete all[t];
  return all;
}

async function setProposal(tabId, p) {
  const all = await proposals();
  if (p) all[tabId] = p; else delete all[tabId];
  await session.set(S_PROPOSALS, all);
  try { await api.action.setBadgeText({ tabId, text: p ? '1' : '' }); } catch { /* onglet fermé */ }
}

/**
 * Onglet visé. Un cadre de l'extension placé dans un site (frameId ≠ 0) ne vise QUE l'onglet qui le contient.
 * Une page de l'extension de premier niveau (popup, page ouverte dans un onglet) peut nommer un onglet.
 */
async function targetTab(msg, sender) {
  const embedded = sender.tab?.id !== undefined && sender.frameId !== 0;
  if (embedded) return sender.tab;
  if (Number.isInteger(msg.tabId)) return api.tabs.get(msg.tabId);
  if (sender.tab?.id !== undefined) return sender.tab;
  throw new CoffreError('format', 'Onglet inconnu.');
}

async function tabOrigin(tab) {
  const origin = allowedOrigin(tab.url || '');
  if (!origin) throw new CoffreError('reglage', 'Le préremplissage n\'est proposé que sur les sites en https.');
  return origin;
}

const syncSoon = () => { runSync().catch(() => {}); };

/** Le script de contenu n'est enregistré qu'après accord de l'utilisateur, sur les seules origines accordées. */
async function ensureContentScript() {
  const cfg = (await local.get(K_PREFILL)) || null;
  const registered = await api.scripting.getRegisteredContentScripts({ ids: [SCRIPT_ID] });
  const allowed = cfg?.actif && await api.permissions.contains({ origins: cfg.matches });
  if (allowed && !registered.length) {
    await api.scripting.registerContentScripts([{
      id: SCRIPT_ID, matches: cfg.matches, js: ['content/formulaires.js'], runAt: 'document_idle', allFrames: false,
    }]);
  } else if (!allowed && registered.length) {
    await api.scripting.unregisterContentScripts({ ids: [SCRIPT_ID] });
  }
}

/** Demandes du script de contenu : droits minimaux, origine toujours prise de l'onglet. */
const contentHandlers = {
  async 'cs:etat'(msg, sender) {
    const origin = allowedOrigin(sender.tab.url || '');
    if (!origin || await refused(origin)) return { actif: false };
    return { actif: true, locked: !(await vault.peekKey()), known: await known(origin) };
  },
  async 'cs:soumis'(msg, sender) {
    const origin = allowedOrigin(sender.tab.url || '');
    const username = typeof msg.username === 'string' ? msg.username.slice(0, 500) : '';
    const password = typeof msg.password === 'string' ? msg.password.slice(0, 2000) : '';
    if (!origin || !password || await refused(origin)) return { proposer: false };
    const k = await vault.peekKey();
    let kind = { kind: 'nouveau', entryId: null };
    if (k) {
      const { entries } = await store.decryptAll(k.key, k.keyId);
      kind = proposalFor(entries, origin, username, password);
      if (!kind) return { proposer: false };
    }
    await setProposal(sender.tab.id, { origin, username, password, ...kind, at: Date.now() });
    return { proposer: true };
  },
  async 'cs:proposition'(msg, sender) {
    return { afficher: !!(await proposals())[sender.tab.id] };
  },
};

const handlers = {
  // ----- Cadres de l'extension dans les sites, et popup -----
  async 'menu:liste'(msg, sender) {
    const tab = await targetTab(msg, sender);
    const origin = allowedOrigin(tab.url || '');
    if (!origin) return { host: '', entries: [], locked: false };
    const k = await vault.key();
    if (!k) return { host: host(origin), entries: [], locked: true };
    const { entries } = await store.decryptAll(k.key, k.keyId);
    // Ni mot de passe ni note : seulement de quoi choisir.
    return { host: host(origin), locked: false, entries: entriesForOrigin(entries, origin).map((e) => ({ id: e.id, name: e.name, username: e.username })) };
  },
  async 'menu:remplir'({ id }, sender) {
    const tab = await targetTab({}, sender);
    const origin = await tabOrigin(tab);
    const k = await vault.key();
    if (!k) throw new CoffreError('aucun_coffre', 'Le coffre est verrouillé.');
    const { entries } = await store.decryptAll(k.key, k.keyId);
    const e = entriesForOrigin(entries, origin).find((x) => x.id === id);
    if (!e) throw new CoffreError('format', 'Ce compte n\'appartient pas à ce site.');
    // Le script de contenu ne répond pas : une promesse rejetée « port fermé » n'est pas une erreur.
    await api.tabs.sendMessage(tab.id, { type: 'coffre:remplir', origin, username: e.username, password: e.password }, { frameId: 0 }).catch(() => {});
    return {};
  },
  async 'menu:generer'(msg, sender) {
    const tab = await targetTab({}, sender);
    const origin = await tabOrigin(tab);
    if (!(await vault.key())) throw new CoffreError('aucun_coffre', 'Le coffre est verrouillé.');
    await api.tabs.sendMessage(tab.id, { type: 'coffre:remplir', origin, password: generatePassword(), nouveau: true }, { frameId: 0 }).catch(() => {});
    return {};
  },
  async 'save:details'(msg, sender) {
    const tab = await targetTab(msg, sender);
    const p = (await proposals())[tab.id];
    if (!p) return { proposition: null };
    return { proposition: { host: host(p.origin), username: p.username, kind: p.kind }, locked: !(await vault.peekKey()) };
  },
  async 'save:enregistrer'(msg, sender) {
    const tab = await targetTab(msg, sender);
    const p = (await proposals())[tab.id];
    if (!p) throw new CoffreError('format', 'La proposition a expiré.');
    const k = await vault.key();
    if (!k) throw new CoffreError('aucun_coffre', 'Déverrouillez le coffre (icône du coffre) pour enregistrer.');
    const { entries } = await store.decryptAll(k.key, k.keyId);
    const decision = proposalFor(entries, p.origin, p.username, p.password);
    if (decision) {
      const existing = decision.entryId && entries.find((e) => e.id === decision.entryId);
      const entry = existing ? editEntry(existing, { password: p.password })
        : newEntry({ name: host(p.origin), origins: [p.origin], username: p.username, password: p.password });
      await store.put(k.key, k.keyId, entry);
    }
    await setProposal(tab.id, null);
    await rebuildIndex();
    if (sender.tab) await api.tabs.sendMessage(tab.id, { type: 'coffre:fermer-barre' }, { frameId: 0 }).catch(() => {});
    syncSoon();
    return {};
  },
  async 'save:ignorer'(msg, sender) {
    const tab = await targetTab(msg, sender);
    await setProposal(tab.id, null);
    if (sender.tab) await api.tabs.sendMessage(tab.id, { type: 'coffre:fermer-barre' }, { frameId: 0 }).catch(() => {});
    return {};
  },
  async 'save:jamais'(msg, sender) {
    const tab = await targetTab(msg, sender);
    const p = (await proposals())[tab.id];
    const keyId = await keyIdNow();
    if (p && keyId) {
      const list = new Set((await local.get(K_REFUSED)) || []);
      list.add(await siteHash(keyId, p.origin));
      await local.set(K_REFUSED, [...list]);
    }
    await setProposal(tab.id, null);
    if (sender.tab) await api.tabs.sendMessage(tab.id, { type: 'coffre:fermer-barre' }, { frameId: 0 }).catch(() => {});
    return {};
  },
  async 'save:fermer'(msg, sender) {
    if (sender.tab) await api.tabs.sendMessage(sender.tab.id, { type: 'coffre:fermer-barre' }, { frameId: 0 }).catch(() => {});
    return {};
  },
  // ----- Réglage (page de gestion) : la page a déjà obtenu l'autorisation sur un clic -----
  async preremplissage({ actif }) {
    const matches = DEFAULTS.essai ? ['http://127.0.0.1/*'] : ['https://*/*'];
    await local.set(K_PREFILL, { actif: !!actif, matches });
    await ensureContentScript();
    await rebuildIndex();
    return { actif: !!actif && !!(await api.scripting.getRegisteredContentScripts({ ids: [SCRIPT_ID] })).length, matches };
  },
  async preremplissage_etat() {
    const cfg = (await local.get(K_PREFILL)) || { actif: false, matches: DEFAULTS.essai ? ['http://127.0.0.1/*'] : ['https://*/*'] };
    return { actif: !!cfg.actif && !!(await api.scripting.getRegisteredContentScripts({ ids: [SCRIPT_ID] })).length, matches: cfg.matches };
  },
  async sites_refuses_oublier() {
    await local.remove(K_REFUSED);
    return {};
  },

  // ----- Pages de l'extension -----
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
    await local.remove(K_INDEX);
    await local.remove(K_REFUSED);
    return {};
  },
};

api.runtime.onMessage.addListener((msg, sender, reply) => {
  if (sender.id !== api.runtime.id) return false;
  const fromOwnPage = String(sender.url || '').startsWith(api.runtime.getURL(''));
  // Un script de contenu : cadre principal d'un onglet, page qui n'est pas de l'extension.
  const fromContent = !fromOwnPage && sender.tab?.id !== undefined && sender.frameId === 0;
  const table = fromOwnPage ? handlers : fromContent ? contentHandlers : null;
  const handler = table && Object.hasOwn(table, msg?.type) ? table[msg.type] : null;
  if (!handler) return false;
  handler(msg, sender).then((r) => reply({ ok: true, ...r }), (e) => reply(failure(e)));
  return true;
});

// Index des sites connus : refait quand les entrées changent ou au déverrouillage.
api.storage.onChanged.addListener((changes, areaName) => {
  if ((areaName === 'local' && changes.entrees) || (areaName === 'session' && changes.session?.newValue && !changes.session.oldValue)) {
    rebuildIndex().catch(() => {});
  }
});
api.tabs.onRemoved.addListener((tabId) => { setProposal(tabId, null).catch(() => {}); });
ensureContentScript().catch(() => {});

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
