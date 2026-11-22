// Essai de bout en bout dans un vrai Chromium (Playwright), sans serveur.
// Usage : npm run essai   (construit dist/chrome puis lance cet essai)
const { chromium } = require('playwright');
const fs = require('fs');
const os = require('os');
const path = require('path');

// EXT_DIR : essayer un autre dossier (par exemple le contenu décompressé d'un paquet).
const ext = process.env.EXT_DIR ? path.resolve(process.env.EXT_DIR) : path.resolve(__dirname, '..', 'dist', 'chrome');
const PHRASE = 'tortue lampadaire ciboulette orage velours';
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'coffre-essai-'));
const checks = [];
const check = (name, ok, detail = '') => {
  checks.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? ` — ${detail}` : ''}`);
};

async function launch() {
  const ctx = await chromium.launchPersistentContext('', {
    channel: 'chromium', headless: true, acceptDownloads: true,
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
  });
  const sw = ctx.serviceWorkers()[0] ?? await ctx.waitForEvent('serviceworker');
  const id = new URL(sw.url()).host;
  return { ctx, sw, id };
}

async function openPage(ctx, id, rel) {
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(`chrome-extension://${id}/${rel}`);
  return { page, errors };
}

const visible = (page, sel) => page.locator(sel).isVisible();
const storageDump = (page, areaName) => page.evaluate((a) => chrome.storage[a].get(null), areaName);

(async () => {
  // ---------- Profil A : création ----------
  const A = await launch();
  const { page, errors } = await openPage(A.ctx, A.id, 'pages/coffre.html');
  check('A : état initial « aucun coffre »', await visible(page, '#view-aucun'));

  await page.click('#go-create');
  await page.fill('#c-pass1', 'court');
  await page.fill('#c-pass2', 'court');
  await page.click('#c-next1');
  check('phrase trop courte refusée', await visible(page, '#error'));

  await page.fill('#c-pass1', PHRASE);
  await page.fill('#c-pass2', PHRASE + 'x');
  await page.click('#c-next1');
  check('deux saisies différentes refusées', (await page.textContent('#error')).includes('identiques'));

  await page.fill('#c-pass2', PHRASE);
  check('indicateur de solidité « bonne »', (await page.getAttribute('#c-strength', 'data-level')) === 'bonne');
  await page.click('#c-next1');
  await page.waitForSelector('#view-create-2:not([hidden])', { timeout: 20000 });
  check('étape 2 atteinte, champs de phrase vidés', (await page.inputValue('#c-pass1')) === '');

  const [download] = await Promise.all([page.waitForEvent('download'), page.click('#c-save-download')]);
  const keyPath = path.join(work, download.suggestedFilename());
  await download.saveAs(keyPath);
  const keyFile = JSON.parse(fs.readFileSync(keyPath, 'utf8'));
  check('fichier de clé téléchargé, nom en .coffre-cle', keyPath.endsWith('.coffre-cle') && keyFile.format === 'coffre-navigateur/cle');
  check('rien n\'est encore installé avant « Terminer »', Object.keys(await storageDump(page, 'local')).length === 0);

  await page.click('#c-next2');
  check('« Terminer » désactivé avant vérification', await page.isDisabled('#c-finish'));
  const wrong = path.join(work, 'faux.coffre-cle');
  fs.writeFileSync(wrong, JSON.stringify({ ...keyFile, iv: 'AAAAAAAAAAAAAAAA' }));
  await page.setInputFiles('#c-verify-file', wrong);
  await page.waitForFunction(() => document.querySelector('#c-verify-result').textContent.length > 0);
  check('une copie différente est signalée', (await page.getAttribute('#c-verify-result', 'class')).includes('danger'));

  await page.setInputFiles('#c-verify-file', keyPath);
  await page.waitForFunction(() => document.querySelector('#c-verify-result').textContent.includes('✓'));
  await page.check('#c-second');
  check('« Terminer » encore désactivé sans la seconde case', await page.isDisabled('#c-finish'));
  await page.check('#c-understand');
  await page.click('#c-finish');
  await page.waitForSelector('#view-deverrouille:not([hidden])', { timeout: 20000 });
  check('coffre créé et ouvert', true);

  const local = await storageDump(page, 'local');
  const session = await storageDump(page, 'session');
  // Chrome rend les champs triés : comparaison profonde, pas textuelle.
  // storage.local : le fichier de clé chiffré et, au plus, l'index des sites connus (empreintes seulement).
  check('storage.local = fichier de clé chiffré (+ index d\'empreintes) seulement',
    Object.keys(local).every((k) => ['cle', 'index_sites'].includes(k))
    && require('util').isDeepStrictEqual(local.cle, keyFile)
    && (local.index_sites || []).every((h) => /^[0-9a-f]{64}$/.test(h)), Object.keys(local).join());
  check('clé brute en storage.session, absente de storage.local',
    typeof session.session?.raw === 'string' && !JSON.stringify(local).includes(session.session.raw));
  check('phrase de passe écrite nulle part',
    !JSON.stringify(local).includes(PHRASE) && !JSON.stringify(session).includes(PHRASE));
  const alarm = await A.sw.evaluate(() => chrome.alarms.get('verrou'));
  check('alarme de verrouillage planifiée', !!alarm && Math.abs(alarm.scheduledTime - session.session.expires) < 1000);

  // ---------- Verrouiller / déverrouiller (popup) ----------
  const pop = await openPage(A.ctx, A.id, 'popup/popup.html');
  await pop.page.waitForSelector('#v-deverrouille:not([hidden])');
  await pop.page.click('#v-lock');
  await pop.page.waitForSelector('#v-verrouille:not([hidden])');
  check('popup : verrouillé', Object.keys(await storageDump(pop.page, 'session')).length === 0);
  await page.waitForSelector('#view-verrouille:not([hidden])');
  check('la page de gestion suit le verrouillage', true);

  await pop.page.fill('#v-pass', 'mauvaise phrase de passe');
  await pop.page.click('#v-unlock');
  await pop.page.waitForSelector('#v-error:not([hidden])', { timeout: 20000 });
  check('popup : mauvaise phrase refusée', (await pop.page.textContent('#v-error')).includes('incorrecte'));
  await pop.page.fill('#v-pass', PHRASE);
  await pop.page.click('#v-unlock');
  await pop.page.waitForSelector('#v-deverrouille:not([hidden])', { timeout: 20000 });
  check('popup : déverrouillé', true);

  // ---------- Verrouillage automatique ----------
  await A.sw.evaluate(async () => {
    const { session } = await chrome.storage.session.get('session');
    await chrome.storage.session.set({ session: { ...session, expires: Date.now() - 1 } });
  });
  await pop.page.reload();
  await pop.page.waitForSelector('#v-verrouille:not([hidden])');
  check('échéance passée ⇒ verrouillé, clé effacée', Object.keys(await storageDump(pop.page, 'session')).length === 0);

  check('aucune erreur de console (profil A)', errors.length + pop.errors.length === 0, [...errors, ...pop.errors].join(' | '));
  await A.ctx.close();

  // ---------- Profil B : import depuis la « clé USB » ----------
  const B = await launch();
  const b = await openPage(B.ctx, B.id, 'pages/coffre.html');
  await b.page.click('#go-import');
  await b.page.setInputFiles('#i-file', keyPath);
  await b.page.fill('#i-pass', 'mauvaise phrase de passe');
  await b.page.click('#i-go');
  await b.page.waitForSelector('#error:not([hidden])', { timeout: 20000 });
  check('B : import avec mauvaise phrase refusé, rien d\'installé',
    Object.keys(await storageDump(b.page, 'local')).length === 0);
  await b.page.fill('#i-pass', PHRASE);
  await b.page.click('#i-go');
  await b.page.waitForSelector('#view-deverrouille:not([hidden])', { timeout: 20000 });
  check('B : coffre importé et ouvert', (await storageDump(b.page, 'local')).cle?.key_id === keyFile.key_id);

  b.page.once('dialog', (d) => d.accept());
  await b.page.click('#d-forget');
  await b.page.waitForSelector('#view-aucun:not([hidden])');
  check('B : coffre retiré du navigateur', Object.keys(await storageDump(b.page, 'local')).length === 0);
  check('aucune erreur de console (profil B)', b.errors.length === 0, b.errors.join(' | '));
  await B.ctx.close();

  const failed = checks.filter((c) => !c.ok).length;
  console.log(`\n${checks.length - failed}/${checks.length} contrôles réussis`);
  fs.rmSync(work, { recursive: true, force: true });
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); fs.rmSync(work, { recursive: true, force: true }); process.exit(1); });
