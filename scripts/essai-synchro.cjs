// Essai de bout en bout de la synchronisation : deux profils Chromium, un faux lab local
// (scripts/faux-lab.cjs). Connexion réelle par identity.launchWebAuthFlow.
// Usage : npm run build -- --essai && node scripts/essai-synchro.cjs
const { chromium } = require('playwright');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { start } = require('./faux-lab.cjs');

const ext = path.resolve(__dirname, '..', 'dist', 'essai');
const ID = 'kainmmlggnfinglcapceocelpngjhnol';
const REDIRECT = `https://${ID}.chromiumapp.org/`;
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
  const sw = ctx.serviceWorkers()[0] ?? await ctx.waitForEvent('serviceworker');
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(`chrome-extension://${ID}/pages/coffre.html`);
  return { ctx, sw, page, errors, id: new URL(sw.url()).host };
}

// Installe un coffre sans repasser par l'interface de création (déjà éprouvée par essai-chromium).
const install = (page, keyFile) => page.evaluate(async ({ keyFile, phrase }) => {
  const { extensionVault } = await import('/lib/extension.js');
  const { createVault } = await import('/lib/crypto.js');
  const kf = keyFile ?? (await createVault(phrase)).keyFile;
  await extensionVault().install(kf, phrase);
  return kf;
}, { keyFile, phrase: PHRASE });

const addEntry = (page, fields) => page.evaluate(async (fields) => {
  const { extensionVault, entryStore } = await import('/lib/extension.js');
  const { newEntry } = await import('/lib/entries.js');
  const { key, keyId } = await extensionVault().key();
  const e = newEntry(fields);
  await entryStore().put(key, keyId, e);
  return e;
}, fields);

const entries = (page) => page.evaluate(async () => {
  const { extensionVault, entryStore } = await import('/lib/extension.js');
  const { key, keyId } = await extensionVault().key();
  return (await entryStore().decryptAll(key, keyId)).entries;
});

async function connect(p, lab) {
  await p.page.reload();
  await p.page.waitForSelector('#view-deverrouille:not([hidden])');
  await p.page.fill('#l-url', lab.url);
  await p.page.click('#l-save');
  await p.page.waitForSelector('#l-login:not([hidden])');
  await p.page.click('#l-login');
  await p.page.waitForSelector('#l-sync:not([hidden])', { timeout: 20000 });
}

(async () => {
  const lab = await start({ redirectUris: [REDIRECT] });
  try {
    const A = await profile();
    check('identifiant fixé par la clé du manifeste', A.id === ID, A.id);
    check('adresse de retour affichée = celle à déclarer dans Keycloak',
      await A.page.evaluate(() => chrome.identity.getRedirectURL()) === REDIRECT);

    const keyFile = await install(A.page);
    const e1 = await addEntry(A.page, { name: 'Banque', origins: ['banque.fr'], username: 'moi', password: 'MotDePasse-Tres-Secret' });

    await A.page.reload();
    await A.page.waitForSelector('#view-deverrouille:not([hidden])');
    await A.page.fill('#l-url', 'http://exemple.fr');
    await A.page.click('#l-save');
    check('adresse http hors boucle locale refusée', (await A.page.textContent('#error')).includes('https'));

    await connect(A, lab);
    check('A connecté : espace « moi »', (await A.page.textContent('#l-state')).includes('« moi »'));
    check('connexion par code + PKCE (aucun jeton dans l\'URL de retour)', lab.log.some((l) => l.includes('/token')));
    await A.page.waitForFunction(() => document.querySelector('#l-sync-state').textContent.includes('envoyée'), null, { timeout: 20000 });
    check('A : entrée envoyée au premier passage', (await A.page.textContent('#l-sync-state')).includes('1 envoyée'),
      await A.page.textContent('#l-sync-state'));

    const stored = [...lab.files].map(([k, v]) => [k, v.body.toString()]);
    check('le lab a reçu le descripteur et une enveloppe', stored.length === 2
      && stored.some(([k]) => k === 'moi/coffre/v1/coffre.json') && stored.some(([k]) => k === `moi/coffre/v1/entrees/${e1.id}.bin`));
    check('le lab ne voit aucun clair', stored.every(([, v]) => !/Banque|banque|MotDePasse|"moi"/.test(v)));

    const local = await A.page.evaluate(() => chrome.storage.local.get(null));
    const session = await A.page.evaluate(() => chrome.storage.session.get(null));
    check('jetons en storage.session seulement', !!session.jetons?.access && !JSON.stringify(local).includes(session.jetons.access));

    // ---------- B : même fichier de clé, autre navigateur ----------
    const B = await profile();
    await install(B.page, keyFile);
    await connect(B, lab);
    await B.page.waitForFunction(() => document.querySelector('#l-sync-state').textContent.includes('reçue'), null, { timeout: 20000 });
    const onB = await entries(B.page);
    check('B : l\'entrée créée sur A est arrivée, intacte', onB.length === 1 && onB[0].password === 'MotDePasse-Tres-Secret');

    // B modifie, A la reçoit.
    await B.page.evaluate(async (id) => {
      const { extensionVault, entryStore } = await import('/lib/extension.js');
      const { editEntry } = await import('/lib/entries.js');
      const { key, keyId } = await extensionVault().key();
      const store = entryStore();
      const e = (await store.decryptAll(key, keyId)).entries.find((x) => x.id === id);
      await store.put(key, keyId, editEntry(e, { password: 'Nouveau-Mot-De-Passe' }));
    }, e1.id);
    await B.page.click('#l-sync');
    await B.page.waitForFunction(() => /1 envoyée/.test(document.querySelector('#l-sync-state').textContent), null, { timeout: 20000 });
    await A.page.click('#l-sync');
    await A.page.waitForFunction(() => /1 reçue/.test(document.querySelector('#l-sync-state').textContent), null, { timeout: 20000 });
    check('A : modification faite sur B reçue', (await entries(A.page))[0].password === 'Nouveau-Mot-De-Passe');

    // Jeton d'accès révoqué côté serveur (storage répond alors 403) : renouvellement transparent.
    lab.revokeAccessTokens();
    const r403 = await A.page.evaluate(async () => (await import('/lib/extension.js')).ask('synchroniser'));
    check('jeton refusé (403) : rafraîchi puis synchronisé sans intervention', r403.ok && r403.status?.ok === true,
      JSON.stringify(r403));

    // Verrouillé : la synchronisation de fond ne fait rien et ne déverrouille rien.
    await A.page.evaluate(async () => (await import('/lib/extension.js')).extensionVault().lock());
    const r = await A.page.evaluate(async () => (await import('/lib/extension.js')).ask('synchroniser'));
    check('verrouillé : synchronisation sautée', r.skipped === 'verrouille');

    // Un autre coffre (autre fichier de clé) sur le même compte : refus.
    const C = await profile();
    await install(C.page);   // nouveau coffre, autre clé
    await connect(C, lab);
    await C.page.waitForFunction(() => document.querySelector('#l-sync-state').textContent.includes('échec'), null, { timeout: 20000 });
    check('autre fichier de clé : synchronisation refusée', (await C.page.textContent('#l-sync-state')).includes('autre fichier de clé'));
    check('…et rien n\'a été écrit sur le lab', lab.files.size === 2);

    // Déconnexion.
    await B.page.click('#l-logout');
    await B.page.waitForSelector('#l-login:not([hidden])');
    check('B : déconnecté, jetons effacés', !(await B.page.evaluate(() => chrome.storage.session.get('jetons'))).jetons);

    const errs = [...A.errors, ...B.errors, ...C.errors];
    check('aucune erreur de console', errs.length === 0, errs.join(' | '));
    await Promise.all([A.ctx.close(), B.ctx.close(), C.ctx.close()]);
  } finally {
    lab.close();
  }
  const failed = checks.filter((c) => !c).length;
  console.log(`\n${checks.length - failed}/${checks.length} contrôles réussis`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
