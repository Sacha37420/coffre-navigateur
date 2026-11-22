// Cadre de l'extension : « Enregistrer ce mot de passe ? » après la soumission d'un formulaire.
import { ask } from '../lib/extension.js';

const $ = (id) => document.getElementById(id);

function fail(e) {
  $('erreur').textContent = e?.userMessage ? e.message : 'Une erreur inattendue est survenue.';
  $('erreur').hidden = false;
}

async function load() {
  const r = await ask('save:details');
  if (!r.proposition) { await ask('save:fermer'); return; }
  const p = r.proposition;
  const who = p.username ? ` pour « ${p.username} »` : '';
  $('question').textContent = p.kind === 'maj'
    ? `Mettre à jour le mot de passe de ${p.host}${who} ?`
    : `Enregistrer le mot de passe de ${p.host}${who} ?`;
  $('oui').textContent = p.kind === 'maj' ? 'Mettre à jour' : 'Enregistrer';
  $('verrou').hidden = !r.locked;
  $('oui').disabled = !!r.locked;
}

$('oui').addEventListener('click', () => ask('save:enregistrer').catch(fail));
$('non').addEventListener('click', () => ask('save:ignorer').catch(fail));
$('jamais').addEventListener('click', () => ask('save:jamais').catch(fail));
load().catch(fail);
