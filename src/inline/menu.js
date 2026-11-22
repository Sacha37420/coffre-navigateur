// Cadre de l'extension ouvert sous un champ : comptes de l'origine de l'onglet (établie par le processus de
// fond), génération. La page qui l'accueille ne peut pas lire ce cadre (autre origine).
import { ask } from '../lib/extension.js';

const $ = (id) => document.getElementById(id);
const creation = new URLSearchParams(location.search).get('creation') === '1';

function fail(e) {
  $('erreur').textContent = e?.userMessage ? e.message : 'Une erreur inattendue est survenue.';
  $('erreur').hidden = false;
}

async function load() {
  const r = await ask('menu:liste');
  $('site').textContent = r.host || '';
  $('verrou').hidden = !r.locked;
  const items = r.entries || [];
  $('aucun').hidden = r.locked || items.length > 0;
  $('liste').replaceChildren(...items.map((e) => {
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.type = 'button';
    const name = document.createElement('span');
    name.textContent = e.username || e.name;
    const sub = document.createElement('span');
    sub.className = 'sub';
    sub.textContent = e.username ? e.name : 'sans identifiant';
    b.append(name, sub);
    b.addEventListener('click', () => ask('menu:remplir', { id: e.id }).catch(fail));
    li.append(b);
    return li;
  }));
  // Sur un formulaire de création, la génération vient en premier ; sinon, après les comptes.
  if (creation) $('liste').before($('generer'));
  $('generer').hidden = !!r.locked;
}

$('generer').addEventListener('click', () => ask('menu:generer').catch(fail));
load().catch(fail);
