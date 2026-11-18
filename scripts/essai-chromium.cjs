// Essai de fumée : charge dist/chrome dans un vrai Chromium (Playwright) et vérifie la popup.
// Usage : npm run build && node scripts/essai-chromium.cjs
const { chromium } = require('playwright');
(async () => {
  const ext = require('path').resolve(__dirname, '..', 'dist', 'chrome');
  const ctx = await chromium.launchPersistentContext('', {
    channel: 'chromium', headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
  });
  // Pas de service worker au lot 1 : on retrouve l'id par la page des extensions.
  // Id d'une extension décompressée = sha256 du chemin, 32 premiers chiffres hexa traduits en a-p.
  const id = require('crypto').createHash('sha256').update(ext).digest('hex').slice(0, 32)
    .replace(/./g, c => String.fromCharCode(97 + parseInt(c, 16)));
  if (!id) throw new Error('extension non chargée');
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => m.type() === 'error' && errors.push(m.text()));
  await page.goto(`chrome-extension://${id}/popup/popup.html`);
  const a = await page.textContent('#pwd');
  await page.click('#regen');
  const b = await page.textContent('#pwd');
  await page.fill('#length', '32'); await page.dispatchEvent('#length', 'input');
  const c = await page.textContent('#pwd');
  console.log(JSON.stringify({ id, a, b, cLen: c.length, info: await page.textContent('#info'), errors }));
  await ctx.close();
})().catch(e => { console.error(e); process.exit(1); });
