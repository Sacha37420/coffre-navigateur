import { CoffreError, createVault, MIN_PASSPHRASE, parseKeyFile } from '../lib/crypto.js';
import { api, ask, extensionVault, keyFileName } from '../lib/extension.js';
import { labEndpoints, normalizeLabUrl } from '../lib/lab-config.js';
import { passphraseStrength } from '../lib/strength.js';
import { AUTO_LOCK_CHOICES, sameKeyFile } from '../lib/vault-state.js';

const vault = extensionVault();
const $ = (id) => document.getElementById(id);
const VIEWS = ['aucun', 'create-1', 'create-2', 'create-3', 'import', 'verrouille', 'deverrouille'];

// Création en cours : gardée en mémoire de la page seulement, jusqu'à « Terminer ».
let pending = null; // { keyFile, passphrase }

function show(view) {
  for (const v of VIEWS) $(`view-${v}`).hidden = v !== view;
  $('error').hidden = true;
}

function fail(e) {
  // Messages fixes seulement : ceux de CoffreError (et ceux relayés du processus de fond, qui en
  // viennent) sont écrits pour l'utilisateur, le reste non.
  $('error').textContent = e instanceof CoffreError || e?.userMessage ? e.message : 'Une erreur inattendue est survenue.';
  $('error').hidden = false;
}

async function busy(id, fn) {
  $(id).hidden = false;
  try { return await fn(); } finally { $(id).hidden = true; }
}

function strengthOn(input, out) {
  const update = () => {
    const s = passphraseStrength($(input).value);
    $(out).dataset.level = s.level;
    $(out).textContent = $(input).value ? `Solidité : ${s.label}` : '';
  };
  $(input).addEventListener('input', update);
  update();
}

async function readFileInput(id) {
  const file = $(id).files?.[0];
  if (!file) throw new CoffreError('format', 'Choisissez d\'abord le fichier de clé.');
  if (file.size > 10_000) throw new CoffreError('format', 'Ce fichier n\'est pas un fichier de clé.');
  return parseKeyFile(await file.text());
}

async function saveKeyFile(keyFile, usePicker) {
  const text = JSON.stringify(keyFile, null, 2) + '\n';
  const name = keyFileName(keyFile);
  if (usePicker && 'showSaveFilePicker' in window) {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName: name,
        types: [{ description: 'Fichier de clé du coffre', accept: { 'application/json': ['.coffre-cle'] } }],
      });
      const w = await handle.createWritable();
      await w.write(text);
      await w.close();
      return;
    } catch (e) {
      if (e?.name === 'AbortError') return;
      throw new CoffreError('format', 'Impossible d\'écrire à cet emplacement. Essayez « Télécharger ».');
    }
  }
  const url = URL.createObjectURL(new Blob([text], { type: 'application/octet-stream' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

function checkNewPassphrase(a, b) {
  if ([...a.normalize('NFC')].length < MIN_PASSPHRASE) {
    throw new CoffreError('phrase_courte', `La phrase de passe doit faire au moins ${MIN_PASSPHRASE} caractères.`);
  }
  if (a !== b) throw new CoffreError('phrase_courte', 'Les deux saisies ne sont pas identiques.');
  if (passphraseStrength(a).level === 'faible') {
    throw new CoffreError('phrase_courte', 'Phrase trop faible : trop répétitive ou trop prévisible.');
  }
}

async function refresh() {
  const st = await vault.status();
  if (st.state === 'deverrouille') {
    const { autoLockMinutes } = await vault.settings();
    $('d-status').textContent = `Identifiant du coffre : ${st.keyId.slice(0, 6)}… · verrouillage après ${autoLockMinutes} min sans utilisation.`;
    $('d-autolock').value = String(autoLockMinutes);
  }
  show(st.state);
  if (st.state === 'deverrouille') await refreshLab();
}

// ---------- Connexion au lab ----------
function syncLine(s) {
  if (!s) return '';
  const at = new Date(s.at).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' });
  if (!s.ok) return `Dernière synchronisation (${at}) en échec : ${s.message}`;
  const r = s.report;
  return `Dernière synchronisation : ${at} — ${r.received} reçue(s), ${r.sent} envoyée(s)`
    + (r.restored ? `, ${r.restored} redéposée(s)` : '') + (r.ignored ? `, ${r.ignored} illisible(s) ignorée(s)` : '') + '.';
}

async function refreshLab() {
  const e = await ask('etat');
  if (document.activeElement !== $('l-url')) $('l-url').value = e.lab?.origin ?? '';
  $('l-redirect').textContent = e.redirectUrl;
  const hasLab = !!e.lab?.origin;
  const online = hasLab && e.connected && !!e.lab.username;
  $('l-state').textContent = !hasLab ? 'Aucune adresse enregistrée.'
    : online ? `Connecté : espace « ${e.lab.username} ».`
      : e.lab.username ? 'Session expirée : reconnectez-vous.' : 'Non connecté.';
  $('l-login').hidden = !hasLab || online;
  $('l-sync').hidden = !online;
  $('l-logout').hidden = !online;
  $('l-sync-state').textContent = syncLine(e.synchro);
}

$('l-save').addEventListener('click', async () => {
  try {
    // L'autorisation doit être demandée tout de suite dans le clic (Firefox), avant toute attente.
    const origin = normalizeLabUrl($('l-url').value);
    const granted = await api.permissions.request({ origins: [labEndpoints({ origin }).permission] });
    if (!granted) throw new CoffreError('reglage', 'Sans cette autorisation, l\'extension ne peut pas joindre le lab.');
    await ask('definir_lab', { url: origin });
    await refreshLab();
  } catch (e) { fail(e); }
});
$('l-login').addEventListener('click', async () => {
  $('l-state').textContent = 'Connexion… (une fenêtre du lab peut s\'ouvrir)';
  try { await ask('connexion'); } catch (e) { fail(e); }
  await refreshLab();
});
$('l-sync').addEventListener('click', async () => {
  $('l-sync-state').textContent = 'Synchronisation…';
  try { await ask('synchroniser'); } catch (e) { fail(e); }
  await refreshLab();
});
$('l-logout').addEventListener('click', async () => {
  try { await ask('deconnexion'); } catch (e) { fail(e); }
  await refreshLab();
});

// ---------- Aucun coffre ----------
$('go-create').addEventListener('click', () => show('create-1'));
$('go-import').addEventListener('click', () => show('import'));

// ---------- Création ----------
strengthOn('c-pass1', 'c-strength');
$('c-cancel1').addEventListener('click', () => { $('c-pass1').value = $('c-pass2').value = ''; show('aucun'); });
$('c-next1').addEventListener('click', async () => {
  try {
    const p = $('c-pass1').value;
    checkNewPassphrase(p, $('c-pass2').value);
    const { keyFile } = await busy('c-busy1', () => createVault(p));
    pending = { keyFile, passphrase: p };
    $('c-pass1').value = $('c-pass2').value = '';
    $('c-filename').textContent = keyFileName(keyFile);
    show('create-2');
  } catch (e) { fail(e); }
});

const hasPicker = 'showSaveFilePicker' in window;
$('c-save-picker').hidden = !hasPicker;
$('d-save-picker').hidden = !hasPicker;
$('c-save-picker').addEventListener('click', () => saveKeyFile(pending.keyFile, true).catch(fail));
$('c-save-download').addEventListener('click', () => saveKeyFile(pending.keyFile, false).catch(fail));
$('c-next2').addEventListener('click', () => show('create-3'));
$('c-back3').addEventListener('click', () => show('create-2'));

let verified = false;
function updateFinish() {
  $('c-finish').disabled = !(verified && $('c-second').checked && $('c-understand').checked);
}
$('c-verify-file').addEventListener('change', async () => {
  verified = false;
  try {
    const copy = await readFileInput('c-verify-file');
    verified = sameKeyFile(copy, pending.keyFile);
    $('c-verify-result').className = verified ? 'alert ok' : 'alert danger';
    $('c-verify-result').textContent = verified
      ? '✓ Copie vérifiée : ce fichier est bien votre clé.'
      : 'Ce fichier n\'est pas la clé qui vient d\'être créée. Enregistrez-la à nouveau.';
  } catch (e) {
    $('c-verify-result').className = 'alert danger';
    $('c-verify-result').textContent = e instanceof CoffreError ? e.message : 'Fichier illisible.';
  }
  updateFinish();
});
$('c-second').addEventListener('change', updateFinish);
$('c-understand').addEventListener('change', updateFinish);
$('c-finish').addEventListener('click', async () => {
  try {
    await vault.install(pending.keyFile, pending.passphrase);
    pending = null;
    verified = false;
    await refresh();
  } catch (e) { fail(e); }
});

// ---------- Import ----------
$('i-cancel').addEventListener('click', () => { $('i-pass').value = ''; show('aucun'); });
$('i-go').addEventListener('click', async () => {
  try {
    const keyFile = await readFileInput('i-file');
    await busy('i-busy', () => vault.install(keyFile, $('i-pass').value));
    $('i-pass').value = '';
    await refresh();
  } catch (e) { fail(e); }
});

// ---------- Verrouillé ----------
async function unlock() {
  try {
    await busy('u-busy', () => vault.unlock($('u-pass').value));
    $('u-pass').value = '';
    await refresh();
  } catch (e) { fail(e); }
}
$('u-go').addEventListener('click', unlock);
$('u-pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') unlock(); });

async function forget() {
  if (!confirm('Retirer le coffre de ce navigateur ? Vos fichiers sur clé USB ne sont pas touchés.')) return;
  await vault.forget();
  await ask('oublier_entrees'); // les enveloppes de ce coffre n'ont plus rien à faire ici
  await refresh();
}
$('u-forget').addEventListener('click', () => forget().catch(fail));
$('d-forget').addEventListener('click', () => forget().catch(fail));

// ---------- Déverrouillé ----------
for (const m of AUTO_LOCK_CHOICES) {
  $('d-autolock').append(new Option(m < 60 ? `${m} min` : `${m / 60} h`, String(m)));
}
$('d-autolock').addEventListener('change', async () => {
  try {
    await vault.setSettings({ autoLockMinutes: Number($('d-autolock').value) });
    await vault.key(); // applique tout de suite le nouveau délai
    await refresh();
  } catch (e) { fail(e); }
});
$('d-lock').addEventListener('click', async () => { await vault.lock(); await refresh(); });
$('d-save-picker').addEventListener('click', async () => saveKeyFile(await vault.keyFile(), true).catch(fail));
$('d-save-download').addEventListener('click', async () => saveKeyFile(await vault.keyFile(), false).catch(fail));

strengthOn('p-new1', 'p-strength');
$('p-go').addEventListener('click', async () => {
  try {
    checkNewPassphrase($('p-new1').value, $('p-new2').value);
    const next = await vault.changePassphrase($('p-old').value, $('p-new1').value);
    $('p-old').value = $('p-new1').value = $('p-new2').value = '';
    $('p-result').className = 'alert warn';
    $('p-result').textContent = 'Phrase changée. Enregistrez maintenant le nouveau fichier sur vos clés USB '
      + '(bouton ci-dessus « Refaire une copie »), puis effacez les anciens fichiers.';
    await saveKeyFile(next, false);
  } catch (e) { fail(e); }
});

// Un autre onglet (ou l'alarme) peut changer l'état : on suit.
api.storage.onChanged.addListener((_c, areaName) => {
  if (!$('view-deverrouille').hidden || !$('view-verrouille').hidden) {
    if (areaName === 'session' || areaName === 'local') refresh();
  }
});

refresh().catch(fail);
