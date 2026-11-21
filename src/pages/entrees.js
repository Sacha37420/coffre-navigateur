// « Mes mots de passe » : liste, recherche, fiche. Tout contenu d'entrée est affiché par textContent.
import { CoffreError } from '../lib/crypto.js';
import { editEntry, hostOf, newEntry, searchEntries, tombstone } from '../lib/entries.js';
import { api, ask, entryStore, extensionVault } from '../lib/extension.js';
import { generatePassword } from '../lib/generator.js';
import { copySecret } from '../ui/clipboard.js';

const vault = extensionVault();
const store = entryStore();
const $ = (id) => document.getElementById(id);
const HIDE_AFTER_MS = 30_000;

let entries = [];        // en clair, en mémoire de la page seulement
let selected = null;     // id, ou 'nouveau'
let dirty = false;
let hideTimer = null;

function fail(e) {
  $('error').textContent = e instanceof CoffreError || e?.userMessage ? e.message : 'Une erreur inattendue est survenue.';
  $('error').hidden = false;
}
const clearError = () => { $('error').hidden = true; };
const status = (t) => { $('status').textContent = t; };

function hidePassword() {
  clearTimeout(hideTimer);
  $('f-password').type = 'password';
  $('show').textContent = 'Afficher';
  $('show').setAttribute('aria-pressed', 'false');
}

function revealPassword() {
  $('f-password').type = 'text';
  $('show').textContent = 'Masquer';
  $('show').setAttribute('aria-pressed', 'true');
  clearTimeout(hideTimer);
  hideTimer = setTimeout(hidePassword, HIDE_AFTER_MS);
}

async function load() {
  const k = await vault.key();
  if (!k) {
    entries = [];
    selected = null;
    $('locked').hidden = false;
    $('layout').hidden = true;
    $('lock').hidden = true;
    return;
  }
  const { entries: all, unreadable } = await store.decryptAll(k.key, k.keyId);
  entries = all;
  $('locked').hidden = true;
  $('layout').hidden = false;
  $('lock').hidden = false;
  if (unreadable) status(`${unreadable} entrée(s) illisible(s) ignorée(s).`);
  renderList();
}

function renderList() {
  const found = searchEntries(entries, $('q').value);
  const total = entries.filter((e) => !e.deleted).length;
  $('count').textContent = $('q').value ? `${found.length} sur ${total}` : `${total} entrée(s)`;
  const ul = $('list');
  ul.replaceChildren(...found.map((e) => {
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.type = 'button';
    b.dataset.id = e.id;
    b.setAttribute('aria-current', String(e.id === selected));
    const name = document.createElement('span');
    name.textContent = e.name;
    const sub = document.createElement('span');
    sub.className = 'sub';
    sub.textContent = [e.username, ...e.origins.map(hostOf)].filter(Boolean).join(' · ');
    b.append(name, sub);
    b.addEventListener('click', () => select(e.id));
    li.append(b);
    return li;
  }));
}

function fill(e) {
  $('f-name').value = e?.name ?? '';
  $('f-origins').value = (e?.origins ?? []).join('\n');
  $('f-username').value = e?.username ?? '';
  $('f-password').value = e?.password ?? '';
  $('f-notes').value = e?.notes ?? '';
  $('meta').textContent = e
    ? `Créée le ${new Date(e.created).toLocaleString('fr-FR')} · modifiée le ${new Date(e.updated).toLocaleString('fr-FR')}`
    : '';
}

function select(id) {
  if (dirty && !confirm('Abandonner les modifications en cours ?')) return;
  clearError();
  hidePassword();
  $('reauth').hidden = true;
  selected = id;
  dirty = false;
  const e = id === 'nouveau' ? null : entries.find((x) => x.id === id);
  $('form-title').textContent = e ? e.name : 'Nouvelle entrée';
  $('delete').hidden = !e;
  fill(e);
  $('form').hidden = false;
  $('empty-detail').hidden = true;
  renderList();
  (e ? $('f-username') : $('f-name')).focus();
}

function closeForm() {
  hidePassword();
  selected = null;
  dirty = false;
  $('form').hidden = true;
  $('empty-detail').hidden = false;
  fill(null);
  renderList();
}

function syncSoon() {
  ask('synchroniser').catch(() => { /* lab non connecté ou injoignable : la carte « Connexion au lab » le dit */ });
}

async function save(ev) {
  ev.preventDefault();
  clearError();
  try {
    const k = await vault.key();
    if (!k) throw new CoffreError('aucun_coffre', 'Le coffre s\'est verrouillé : déverrouillez-le.');
    const fields = {
      name: $('f-name').value,
      origins: $('f-origins').value.split('\n').map((l) => l.trim()).filter(Boolean),
      username: $('f-username').value,
      password: $('f-password').value,
      notes: $('f-notes').value,
    };
    const current = selected !== 'nouveau' ? entries.find((x) => x.id === selected) : null;
    const entry = current ? editEntry(current, fields) : newEntry(fields);
    await store.put(k.key, k.keyId, entry);
    dirty = false;
    status(`« ${entry.name} » enregistrée.`);
    await load();
    select(entry.id);
    syncSoon();
  } catch (e) { fail(e); }
}

async function remove() {
  const current = entries.find((x) => x.id === selected);
  if (!current || !confirm(`Supprimer « ${current.name} » ? La suppression sera aussi faite sur vos autres navigateurs.`)) return;
  try {
    const k = await vault.key();
    if (!k) throw new CoffreError('aucun_coffre', 'Le coffre s\'est verrouillé : déverrouillez-le.');
    await store.put(k.key, k.keyId, tombstone(current));
    status(`« ${current.name} » supprimée.`);
    dirty = false;
    closeForm();
    await load();
    syncSoon();
  } catch (e) { fail(e); }
}

async function toggleShow() {
  if ($('f-password').type === 'text') return hidePassword();
  // Une entrée nouvelle (ou un mot de passe tout juste généré) n'est pas un secret déjà enregistré.
  const stored = entries.find((x) => x.id === selected);
  if (!stored || !stored.password || (await vault.recentlyAuthenticated())) return revealPassword();
  $('reauth').hidden = false;
  $('reauth-pass').focus();
}

async function confirmReauth() {
  try {
    await vault.confirm($('reauth-pass').value);
    $('reauth-pass').value = '';
    $('reauth').hidden = true;
    revealPassword();
  } catch (e) { fail(e); }
}

async function copy(id, what) {
  const v = $(id).value;
  if (!v) return;
  try {
    await copySecret(v);
    status(`${what} copié. Le presse-papiers sera vidé dans 30 s si cette page reste ouverte.`);
  } catch { fail(new CoffreError('format', 'Copie refusée par le navigateur.')); }
}

$('q').addEventListener('input', renderList);
$('new').addEventListener('click', () => select('nouveau'));
$('form').addEventListener('submit', save);
$('form').addEventListener('input', () => { dirty = true; });
$('cancel').addEventListener('click', () => { if (!dirty || confirm('Abandonner les modifications ?')) closeForm(); });
$('delete').addEventListener('click', remove);
$('show').addEventListener('click', toggleShow);
$('reauth-go').addEventListener('click', confirmReauth);
$('reauth-pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); confirmReauth(); } });
$('reauth-cancel').addEventListener('click', () => { $('reauth-pass').value = ''; $('reauth').hidden = true; });
$('copy-user').addEventListener('click', () => copy('f-username', 'Identifiant'));
$('copy-pwd').addEventListener('click', () => copy('f-password', 'Mot de passe'));
$('gen').addEventListener('click', () => {
  $('f-password').value = generatePassword();
  dirty = true;
  revealPassword();
});
$('lock').addEventListener('click', async () => { await vault.lock(); await load(); });

// Synchronisation reçue, verrouillage (alarme, autre onglet) : on suit, sans écraser une saisie en cours.
api.storage.onChanged.addListener((changes, areaName) => {
  if (areaName === 'session' && changes.session && !changes.session.newValue) {
    dirty = false;
    closeForm();
    load();
  } else if (areaName === 'session' && changes.session && !changes.session.oldValue) {
    load();
  } else if (areaName === 'local' && changes.entrees && !dirty) {
    load().then(() => { if (selected && selected !== 'nouveau') fill(entries.find((x) => x.id === selected)); });
  }
});

load().then(() => { if (location.hash === '#nouveau' && entries) select('nouveau'); }).catch(fail);
