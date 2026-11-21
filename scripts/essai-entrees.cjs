// Essai de bout en bout du lot 4 (gestion des entrées) dans Chromium, avec le faux lab.
// Usage : node scripts/build.mjs --essai && node scripts/essai-entrees.cjs
const { chromium } = require('playwright');
const path = require('path');
const { start } = require('./faux-lab.cjs');

const ext = path.resolve(__dirname, '..', 'dist', 'essai');
const ID = 'kainmmlggnfinglcapceocelpngjhnol';
const PHRASE = 'tortue lampadaire ciboulette orage velours';
const checks = [];
const check = (name, ok, detail = '') => {
  checks.push(ok);
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? ` — ${detail}` : ''}`);
};

async function profile() {
  const ctx = await chromium.launchPersistentContext('', {
    channel: 'chromium', headless: true,
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
  });
  if (!ctx.serviceWorkers().length) await ctx.waitForEvent('serviceworker');
  const errors = [];
  const open = async (rel) => {
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    await page.goto(`chrome-extension://${ID}/${rel}`);
    return page;
  };
  return { ctx, open, errors };
}

const install = (page, keyFile) => page.evaluate(async ({ keyFile, phrase }) => {
  const { extensionVault } = await import('/lib/extension.js');
  const { createVault } = await import('/lib/crypto.js');
  const kf = keyFile ?? (await createVault(phrase)).keyFile;
  await extensionVault().install(kf, phrase);
  return kf;
}, { keyFile, phrase: PHRASE });

async function connect(page, lab) {
  await page.goto(`chrome-extension://${ID}/pages/coffre.html`);
  await page.waitForSelector('#view-deverrouille:not([hidden])');
  await page.fill('#l-url', lab.url);
  await page.click('#l-save');
  await page.waitForSelector('#l-login:not([hidden])');
  await page.click('#l-login');
  await page.waitForSelector('#l-sync:not([hidden])', { timeout: 20000 });
}

const listNames = (page) => page.$$eval('#list button > span:first-child', (els) => els.map((e) => e.textContent));
const setAuthAge = (page, ms) => page.evaluate(async (ms) => {
  const { session } = await chrome.storage.session.get('session');
  await chrome.storage.session.set({ session: { ...session, authAt: Date.now() - ms } });
}, ms);
const saved = (page) => page.waitForFunction(() => document.querySelector('#status').textContent.includes('enregistrée'));

(async () => {
  const lab = await start({ redirectUris: [`https://${ID}.chromiumapp.org/`] });
  try {
    const A = await profile();
    const p = await A.open('pages/entrees.html');
    await p.waitForSelector('#locked:not([hidden])');
    check('sans coffre ouvert : page « verrouillé »', true);
    const keyFile = await install(p);
    await connect(p, lab);

    await p.goto(`chrome-extension://${ID}/pages/entrees.html#nouveau`);
    await p.waitForSelector('#form:not([hidden])');
    check('#nouveau ouvre une fiche vide', (await p.textContent('#form-title')) === 'Nouvelle entrée');
    await p.fill('#f-name', 'Banque Épargne');
    await p.fill('#f-origins', 'banque.fr/connexion\nhttps://m.banque.fr');
    await p.fill('#f-username', 'client42');
    await p.click('#gen');
    const generated = await p.inputValue('#f-password');
    check('générateur : 20 caractères, affiché', generated.length === 20 && (await p.getAttribute('#f-password', 'type')) === 'text');
    await p.click('#save');
    await saved(p);

    await p.click('#new');
    await p.fill('#f-name', 'Messagerie');
    await p.fill('#f-origins', 'mail.exemple.fr');
    await p.fill('#f-username', '<img src=x onerror=alert(1)>');
    await p.fill('#f-password', 'mdp-messagerie');
    await p.click('#save');
    await p.waitForFunction(() => document.querySelectorAll('#list li').length === 2);
    check('liste triée par nom', JSON.stringify(await listNames(p)) === '["Banque Épargne","Messagerie"]');
    check('contenu affiché comme texte, jamais interprété', (await p.$$('img')).length === 0);

    await p.fill('#q', 'epargne');
    check('recherche sans accent', JSON.stringify(await listNames(p)) === '["Banque Épargne"]');
    await p.fill('#q', 'm.banque');
    check('recherche par site', JSON.stringify(await listNames(p)) === '["Banque Épargne"]');
    await p.fill('#q', '');

    await p.click('#list button:has-text("Banque Épargne")');
    check('sites normalisés en origines exactes',
      (await p.inputValue('#f-origins')) === 'https://banque.fr\nhttps://m.banque.fr');
    await p.click('#show');
    check('phrase saisie il y a moins de 5 min : affichage direct', (await p.getAttribute('#f-password', 'type')) === 'text');
    await p.click('#show');
    await setAuthAge(p, 6 * 60_000);
    await p.click('#show');
    await p.waitForSelector('#reauth:not([hidden])');
    check('plus de 5 min : la phrase est redemandée, mot de passe masqué',
      (await p.getAttribute('#f-password', 'type')) === 'password');
    await p.fill('#reauth-pass', 'mauvaise phrase de passe');
    await p.click('#reauth-go');
    await p.waitForSelector('#error:not([hidden])', { timeout: 20000 });
    check('mauvaise phrase : toujours masqué', (await p.getAttribute('#f-password', 'type')) === 'password');
    await p.fill('#reauth-pass', PHRASE);
    await p.click('#reauth-go');
    await p.waitForFunction(() => document.querySelector('#f-password').type === 'text', null, { timeout: 20000 });
    check('bonne phrase : affiché', true);

    await p.click('#copy-pwd');
    check('copier le mot de passe', (await p.evaluate(() => navigator.clipboard.readText())) === generated);

    await p.fill('#f-username', 'client43');
    await p.click('#save');
    await saved(p);
    check('modification enregistrée', (await p.inputValue('#f-username')) === 'client43');

    await p.click('#list button:has-text("Messagerie")');
    p.once('dialog', (d) => d.accept());
    await p.click('#delete');
    await p.waitForFunction(() => document.querySelectorAll('#list li').length === 1);
    check('suppression : retirée de la liste', JSON.stringify(await listNames(p)) === '["Banque Épargne"]');

    const pop = await A.open('popup/popup.html');
    await pop.waitForSelector('#v-deverrouille:not([hidden])');
    await pop.fill('#v-q', 'banque');
    check('popup : recherche', (await pop.$$eval('#v-list .label', (els) => els.length)) === 1);
    await pop.click('#v-list button:has-text("Id.")');
    check('popup : identifiant copié', (await pop.evaluate(() => navigator.clipboard.readText())) === 'client43');

    const r = await p.evaluate(async () => (await import('/lib/extension.js')).ask('synchroniser'));
    check('synchronisation après les modifications', r.ok === true, JSON.stringify(r));
    const dump = [...lab.files.values()].map((f) => f.body.toString()).join('\n');
    check('le lab ne voit aucun clair', !/Banque|banque|client4|Messagerie|exemple|mdp-messagerie/.test(dump) && !dump.includes(generated));

    const B = await profile();
    const q = await B.open('pages/coffre.html');
    await install(q, keyFile);
    await connect(q, lab);
    await q.goto(`chrome-extension://${ID}/pages/entrees.html`);
    await q.waitForFunction(() => document.querySelectorAll('#list li').length === 1, null, { timeout: 20000 });
    await q.click('#list button:has-text("Banque Épargne")');
    check('B : entrée créée par l\'interface de A reçue, modification comprise',
      (await q.inputValue('#f-username')) === 'client43' && (await q.inputValue('#f-password')) === generated);
    check('B : la suppression faite sur A est appliquée', JSON.stringify(await listNames(q)) === '["Banque Épargne"]');

    await q.click('#lock');
    await q.waitForSelector('#locked:not([hidden])');
    check('verrouiller : fiche vidée et masquée', (await q.inputValue('#f-password')) === '' && await q.locator('#layout').isHidden());

    const errs = [...A.errors, ...B.errors];
    check('aucune erreur de console', errs.length === 0, errs.join(' | '));
    await Promise.all([A.ctx.close(), B.ctx.close()]);
  } finally {
    lab.close();
  }
  const failed = checks.filter((c) => !c).length;
  console.log(`\n${checks.length - failed}/${checks.length} contrôles réussis`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
