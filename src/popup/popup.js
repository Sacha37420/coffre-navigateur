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
