import { CoffreError } from '../lib/crypto.js';
import { hostOf, searchEntries } from '../lib/entries.js';
import { api, ask, entryStore, extensionVault } from '../lib/extension.js';
import { copySecret } from '../ui/clipboard.js';
import { entropyBits, generatePassword } from '../lib/generator.js';

const $ = (id) => document.getElementById(id);
const FLAGS = ['lower', 'upper', 'digits', 'symbols', 'avoidAmbiguous'];
const CLIPBOARD_CLEAR_MS = 30_000;

function options() {
  const o = { length: Number($('length').value) };
  for (const f of FLAGS) o[f] = $(f).checked;
  return o;
}

function render() {
  $('length-val').textContent = $('length').value;
  try {
    $('pwd').textContent = generatePassword(options());
    $('info').textContent = `Environ ${entropyBits(options())} bits d'entropie.`;
  } catch (e) {
    $('pwd').textContent = '';
    $('info').textContent = e.message;
  }
}

$('regen').addEventListener('click', render);
$('length').addEventListener('input', render);
for (const f of FLAGS) $(f).addEventListener('change', render);

$('copy').addEventListener('click', async () => {
  const value = $('pwd').textContent;
  if (!value) return;
  await navigator.clipboard.writeText(value);
  $('info').textContent = 'Copié. Le presse-papiers sera vidé dans 30 s si la fenêtre reste ouverte.';
  setTimeout(async () => {
    try {
      if ((await navigator.clipboard.readText()) === value) await navigator.clipboard.writeText('');
    } catch { /* lecture refusée : on ne peut rien vérifier, on n'écrase pas au hasard */ }
  }, CLIPBOARD_CLEAR_MS);
});

render();

// ---------- État du coffre ----------
const vault = extensionVault();
const openManager = () => { api.runtime.openOptionsPage(); window.close(); };

const QUICK_MAX = 8;
let quick = []; // entrées en clair, mémoire de la popup seulement

function renderQuick() {
  const found = searchEntries(quick, $('v-q').value);
  $('v-list').replaceChildren(...found.slice(0, QUICK_MAX).map((e) => {
    const li = document.createElement('li');
    const label = document.createElement('span');
    label.className = 'label';
    label.textContent = e.name;
    const sub = document.createElement('span');
    sub.className = 'sub';
    sub.textContent = [e.username, ...e.origins.map(hostOf)].filter(Boolean).join(' · ');
    label.append(sub);
    const mk = (text, value, what) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = text;
      b.disabled = !value;
      b.title = `Copier ${what}`;
      b.addEventListener('click', async () => {
        try {
          await copySecret(value);
          $('v-info').textContent = `${what[0].toUpperCase()}${what.slice(1)} de « ${e.name} » copié.`;
        } catch { $('v-info').textContent = 'Copie refusée par le navigateur.'; }
      });
      return b;
    };
    li.append(label, mk('Id.', e.username, 'l\'identifiant'), mk('Mdp', e.password, 'le mot de passe'));
    return li;
  }));
  const total = quick.filter((e) => !e.deleted).length;
  if (!$('v-info').textContent.includes('copié')) {
    $('v-info').textContent = total === 0 ? 'Aucun mot de passe pour l\'instant.'
      : found.length > QUICK_MAX ? `${found.length} résultats : précisez la recherche.` : '';
  }
}

async function refreshVault() {
  const { state } = await vault.status();
  for (const s of ['aucun', 'verrouille', 'deverrouille']) $(`v-${s}`).hidden = s !== state;
  if (state === 'verrouille') $('v-pass').focus();
  await refreshProposal(state);
  if (state === 'deverrouille') {
    const k = await vault.key();
    quick = k ? (await entryStore().decryptAll(k.key, k.keyId)).entries : [];
    renderQuick();
    $('v-q').focus();
  } else {
    quick = [];
  }
}

// Proposition d'enregistrement en attente pour l'onglet actif (faite coffre verrouillé, par exemple).
let activeTabId = null;
async function refreshProposal(state) {
  $('v-proposition').hidden = true;
  try {
    const [tab] = await api.tabs.query({ active: true, currentWindow: true });
    if (!tab) return;
    activeTabId = tab.id;
    const r = await ask('save:details', { tabId: tab.id });
    if (!r.proposition) return;
    const p = r.proposition;
    const who = p.username ? ` pour « ${p.username} »` : '';
    $('v-prop-text').textContent = `${p.kind === 'maj' ? 'Mettre à jour' : 'Enregistrer'} le mot de passe de ${p.host}${who} ?`
      + (state === 'deverrouille' ? '' : ' Déverrouillez d\'abord le coffre.');
    $('v-prop-oui').disabled = state !== 'deverrouille';
    $('v-proposition').hidden = false;
  } catch { /* pas d'onglet accessible */ }
}
$('v-prop-oui').addEventListener('click', async () => {
  try { await ask('save:enregistrer', { tabId: activeTabId }); await refreshVault(); } catch (e) { $('v-prop-text').textContent = e.message; }
});
$('v-prop-non').addEventListener('click', async () => {
  try { await ask('save:ignorer', { tabId: activeTabId }); } catch { /* rien */ }
  $('v-proposition').hidden = true;
});

const openPage = (path) => { api.tabs.create({ url: api.runtime.getURL(path) }); window.close(); };
$('v-q').addEventListener('input', () => { $('v-info').textContent = ''; renderQuick(); });
$('v-all').addEventListener('click', () => openPage('pages/entrees.html'));
$('v-add').addEventListener('click', () => openPage('pages/entrees.html#nouveau'));

async function unlock() {
  $('v-error').hidden = true;
  $('v-unlock').disabled = true;
  try {
    await vault.unlock($('v-pass').value);
    $('v-pass').value = '';
    await refreshVault();
  } catch (e) {
    $('v-error').textContent = e instanceof CoffreError ? e.message : 'Une erreur inattendue est survenue.';
    $('v-error').hidden = false;
  } finally {
    $('v-unlock').disabled = false;
  }
}

$('v-setup').addEventListener('click', openManager);
$('v-manage1').addEventListener('click', openManager);
$('v-manage2').addEventListener('click', openManager);
$('v-unlock').addEventListener('click', unlock);
$('v-pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') unlock(); });
$('v-lock').addEventListener('click', async () => { await vault.lock(); await refreshVault(); });
refreshVault();
