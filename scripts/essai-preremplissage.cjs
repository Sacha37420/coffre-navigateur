// Essai de bout en bout du lot 5 (préremplissage et enregistrement) dans Chromium, sur des pages locales.
// Trois serveurs = trois origines (A, B, C). Usage : node scripts/build.mjs --essai && node scripts/essai-preremplissage.cjs
const { chromium } = require('playwright');
const http = require('http');
const path = require('path');

const ext = path.resolve(__dirname, '..', 'dist', 'essai');
const ID = 'kainmmlggnfinglcapceocelpngjhnol';
const PHRASE = 'tortue lampadaire ciboulette orage velours';
const checks = [];
const check = (name, ok, detail = '') => {
  checks.push(ok);
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? ` — ${detail}` : ''}`);
};

const page = (title, body) => `<!doctype html><html lang="fr"><head><meta charset="utf-8"><title>${title}</title></head>
<body style="font:16px sans-serif;padding:20px"><h1>${title}</h1>${body}
<script>window.saisies=0;document.addEventListener('input',()=>window.saisies++,true);</script></body></html>`;

function site(other) {
  return new Promise((resolve) => {
    const s = http.createServer((req, res) => {
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      const u = new URL(req.url, 'http://x');
      if (u.pathname === '/connexion') return res.end(page('Connexion', `<form method="post" action="/bienvenue">
        <label>Identifiant <input name="identifiant" type="text" style="width:300px;height:32px"></label><br><br>
        <label>Mot de passe <input name="mdp" type="password" style="width:300px;height:32px"></label><br><br>
        <button type="submit">Se connecter</button></form>`));
      if (u.pathname === '/inscription') return res.end(page('Créer un compte', `<form method="post" action="/bienvenue">
        <label>Courriel <input name="courriel" type="email" style="width:300px;height:32px"></label><br><br>
        <label>Mot de passe <input name="mdp1" type="password" autocomplete="new-password" style="width:300px;height:32px"></label><br><br>
        <label>Confirmer <input name="mdp2" type="password" autocomplete="new-password" style="width:300px;height:32px"></label><br><br>
        <button type="submit">Créer</button></form>`));
      if (u.pathname === '/cadre') return res.end(page('Page avec cadre', `<iframe src="${other}/connexion" style="width:600px;height:400px"></iframe>`));
      if (u.pathname === '/bienvenue') { req.resume(); return res.end(page('Bienvenue', '<p>Connecté.</p>')); }
      res.statusCode = 404; res.end('');
    });
    s.listen(0, '127.0.0.1', () => resolve({ url: `http://127.0.0.1:${s.address().port}`, close: () => s.close() }));
  });
}

(async () => {
  const B = await site('');
  const A = await site(B.url);
  const C = await site('');
  const hostOf = (u) => new URL(u).host;
  try {
    const ctx = await chromium.launchPersistentContext('', {
      channel: 'chromium', headless: true, viewport: { width: 1000, height: 800 },
      args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
    });
    if (!ctx.serviceWorkers().length) await ctx.waitForEvent('serviceworker');
    const errors = [];
    const extPage = await ctx.newPage();
    extPage.on('pageerror', (e) => errors.push(e.message));
    await extPage.goto(`chrome-extension://${ID}/pages/coffre.html`);
    await extPage.evaluate(async (phrase) => {
      const { extensionVault } = await import('/lib/extension.js');
      const { createVault } = await import('/lib/crypto.js');
      await extensionVault().install((await createVault(phrase)).keyFile, phrase);
    }, PHRASE);
    await extPage.reload();
    await extPage.waitForSelector('#view-deverrouille:not([hidden])');
    await extPage.click('#pf-on');
    await extPage.waitForFunction(() => document.querySelector('#pf-state').textContent === 'Activé.');
    check('activation du préremplissage', true);

    const entries = () => extPage.evaluate(async () => {
      const { extensionVault, entryStore } = await import('/lib/extension.js');
      const k = await extensionVault().peekKey();
      return (await entryStore().decryptAll(k.key, k.keyId)).entries.filter((e) => !e.deleted);
    });
    const frame = (p, part) => p.frames().find((f) => f.url().includes(part));
    const waitFrame = async (p, part, timeout = 10000) => {
      const end = Date.now() + timeout;
      while (Date.now() < end) { const f = frame(p, part); if (f) { await f.waitForLoadState(); return f; } await p.waitForTimeout(100); }
      return null;
    };
    const noFrame = async (p, part, ms = 1500) => { await p.waitForTimeout(ms); return !frame(p, part); };
    const clickKey = async (p, selector) => {
      const b = await p.locator(selector).boundingBox();
      await p.mouse.click(b.x + b.width - 30 + 13, b.y + b.height / 2);
    };

    // 1. Enregistrement après soumission.
    const p = await ctx.newPage();
    p.on('pageerror', (e) => errors.push(e.message));
    await p.goto(`${A.url}/connexion`);
    await p.waitForFunction(() => !!document.querySelector('coffre-du-lab'));
    check('la page ne peut pas ouvrir l\'interface du coffre (racine d\'ombre fermée)',
      await p.evaluate(() => document.querySelector('coffre-du-lab').shadowRoot === null && document.querySelectorAll('iframe').length === 0));
    await p.fill('input[name=identifiant]', 'moi');
    await p.fill('input[name=mdp]', 'motdepasse-1');
    await p.click('button[type=submit]');
    await p.waitForURL('**/bienvenue');
    let bar = await waitFrame(p, 'inline/enregistrer.html');
    check('proposition d\'enregistrement après la soumission (nouvelle page)', !!bar);
    const q = await bar.textContent('#question');
    check('la proposition nomme le site et l\'identifiant', q.includes(hostOf(A.url)) && q.includes('« moi »'), q);
    await bar.click('#oui');
    await p.waitForTimeout(500);
    let list = await entries();
    check('entrée enregistrée, origine exacte', list.length === 1 && list[0].username === 'moi' && list[0].password === 'motdepasse-1'
      && JSON.stringify(list[0].origins) === JSON.stringify([A.url]), JSON.stringify(list));
    check('barre refermée après réponse', await noFrame(p, 'inline/enregistrer.html', 500));

    // 2. Hôte connu : liste proposée au focus, remplissage au clic seulement.
    await p.goto(`${A.url}/connexion`);
    await p.waitForFunction(() => !!document.querySelector('coffre-du-lab'));
    await p.waitForTimeout(400);
    await p.click('input[name=identifiant]');
    let menu = await waitFrame(p, 'inline/menu.html');
    check('liste ouverte d\'elle-même au focus (site connu)', !!menu);
    await menu.waitForSelector('#liste button');
    check('la liste montre le compte du site', (await menu.textContent('#liste')).includes('moi'));
    check('rien n\'est rempli avant le clic', (await p.inputValue('input[name=mdp]')) === '');
    await menu.click('#liste button');
    await p.waitForFunction(() => document.querySelector('input[name=mdp]').value !== '');
    check('remplissage au clic', (await p.inputValue('input[name=identifiant]')) === 'moi' && (await p.inputValue('input[name=mdp]')) === 'motdepasse-1');
    check('la page a reçu les événements de saisie', (await p.evaluate(() => window.saisies)) >= 2);
    check('menu refermé après le remplissage', await noFrame(p, 'inline/menu.html', 500));

    // 3. Mot de passe changé : mise à jour, sans doublon. Même mot de passe : rien.
    await p.fill('input[name=mdp]', 'motdepasse-2');
    await p.click('button[type=submit]');
    await p.waitForURL('**/bienvenue');
    bar = await waitFrame(p, 'inline/enregistrer.html');
    check('proposition de mise à jour', (await bar.textContent('#question')).startsWith('Mettre à jour'));
    await bar.click('#oui');
    await p.waitForTimeout(500);
    list = await entries();
    check('mise à jour sans doublon', list.length === 1 && list[0].password === 'motdepasse-2');
    await p.goto(`${A.url}/connexion`);
    await p.fill('input[name=identifiant]', 'moi');
    await p.fill('input[name=mdp]', 'motdepasse-2');
    await p.click('button[type=submit]');
    await p.waitForURL('**/bienvenue');
    check('mot de passe déjà enregistré tel quel : aucune proposition', await noFrame(p, 'inline/enregistrer.html'));

    // 4. Création de compte sur B : bouton « nouveau mot de passe », deux champs identiques, enregistrement.
    await p.goto(`${B.url}/inscription`);
    await p.waitForFunction(() => !!document.querySelector('coffre-du-lab'));
    await p.waitForTimeout(400);
    await p.click('input[name=mdp1]');
    check('site inconnu : pas de liste ouverte d\'office', await noFrame(p, 'inline/menu.html', 800));
    await clickKey(p, 'input[name=mdp1]');
    menu = await waitFrame(p, 'inline/menu.html');
    check('le bouton du champ ouvre le menu', !!menu);
    await menu.waitForSelector('#generer:not([hidden])');
    check('site inconnu : « aucun mot de passe » et génération proposée', !(await menu.isHidden('#aucun')));
    await menu.click('#generer');
    await p.waitForFunction(() => document.querySelector('input[name=mdp1]').value !== '');
    const g1 = await p.inputValue('input[name=mdp1]');
    const g2 = await p.inputValue('input[name=mdp2]');
    check('mot de passe généré dans les deux champs', g1.length === 20 && g1 === g2);
    await p.fill('input[name=courriel]', 'nouveau@exemple.fr');
    await p.click('button[type=submit]');
    await p.waitForURL('**/bienvenue');
    bar = await waitFrame(p, 'inline/enregistrer.html');
    check('proposition pour le compte créé', (await bar.textContent('#question')).includes('« nouveau@exemple.fr »'));
    await bar.click('#oui');
    await p.waitForTimeout(500);
    list = await entries();
    check('compte créé enregistré avec le mot de passe généré', list.some((e) => e.origins[0] === B.url && e.password === g1));

    // 5. Origine exacte : le compte de B n'est pas proposé sur A.
    await p.goto(`${A.url}/connexion`);
    await p.waitForFunction(() => !!document.querySelector('coffre-du-lab'));
    await p.waitForTimeout(400);
    await p.click('input[name=identifiant]');
    menu = await waitFrame(p, 'inline/menu.html');
    await menu.waitForSelector('#liste button');
    const items = await menu.$$eval('#liste button', (b) => b.map((x) => x.textContent));
    check('autre origine (autre port) jamais proposée', items.length === 1 && items[0].includes('moi'), JSON.stringify(items));
    await p.keyboard.press('Escape');

    // 6. Cadre d'une autre origine : jamais décoré ni rempli.
    await p.goto(`${A.url}/cadre`);
    await p.waitForTimeout(1200);
    const inner = p.frames().find((f) => f.url().startsWith(B.url));
    check('cadre d\'une autre origine : le script n\'y est pas', !!inner && !(await inner.evaluate(() => !!document.querySelector('coffre-du-lab'))));

    // 7. « Jamais pour ce site ».
    await p.goto(`${C.url}/connexion`);
    await p.fill('input[name=identifiant]', 'x');
    await p.fill('input[name=mdp]', 'y');
    await p.click('button[type=submit]');
    await p.waitForURL('**/bienvenue');
    bar = await waitFrame(p, 'inline/enregistrer.html');
    await bar.click('#jamais');
    await p.goto(`${C.url}/connexion`);
    await p.waitForTimeout(600);
    await p.fill('input[name=identifiant]', 'x2');
    await p.fill('input[name=mdp]', 'y2');
    await p.click('button[type=submit]');
    await p.waitForURL('**/bienvenue');
    check('« Jamais pour ce site » : plus de proposition', await noFrame(p, 'inline/enregistrer.html'));
    check('…et rien enregistré pour ce site', !(await entries()).some((e) => e.origins[0] === C.url));

    // 8. Coffre verrouillé : la liste le dit, la proposition attend et se reprend après déverrouillage.
    await extPage.evaluate(async () => (await import('/lib/extension.js')).extensionVault().lock());
    await p.goto(`${A.url}/connexion`);
    await p.waitForFunction(() => !!document.querySelector('coffre-du-lab'));
    await p.waitForTimeout(400);
    await p.click('input[name=identifiant]');
    menu = await waitFrame(p, 'inline/menu.html');
    check('verrouillé, site connu (index) : la liste s\'ouvre et dit « verrouillé »', !!menu && !(await menu.isHidden('#verrou')));
    check('verrouillé : aucun compte montré', (await menu.$$('#liste button')).length === 0);
    await p.keyboard.press('Escape');
    await p.fill('input[name=identifiant]', 'moi');
    await p.fill('input[name=mdp]', 'motdepasse-3');
    await p.click('button[type=submit]');
    await p.waitForURL('**/bienvenue');
    bar = await waitFrame(p, 'inline/enregistrer.html');
    check('verrouillé : proposition affichée, bouton désactivé', !(await bar.isHidden('#verrou')) && await bar.isDisabled('#oui'));
    const tabId = await extPage.evaluate(async (u) => (await chrome.tabs.query({ url: `${u}/*` }))[0].id, A.url);
    await extPage.evaluate(async (phrase) => (await import('/lib/extension.js')).extensionVault().unlock(phrase), PHRASE);
    const det = await extPage.evaluate(async (tabId) => (await import('/lib/extension.js')).ask('save:details', { tabId }), tabId);
    // Verrouillé à la soumission : impossible de savoir que c'est une mise à jour ; l'enregistrement le décide.
    check('reprise : la proposition attend (popup)', !!det.proposition && det.proposition.username === 'moi', JSON.stringify(det));
    await extPage.evaluate(async (tabId) => (await import('/lib/extension.js')).ask('save:enregistrer', { tabId }), tabId);
    list = await entries();
    check('reprise : mise à jour faite après déverrouillage, sans doublon',
      list.filter((e) => e.origins[0] === A.url).length === 1 && list.find((e) => e.origins[0] === A.url).password === 'motdepasse-3');
    check('un cadre de l\'extension dans un site ne peut viser un autre onglet que le sien',
      await (async () => {
        const f = await (async () => { await p.goto(`${A.url}/connexion`); await p.waitForTimeout(400); await p.click('input[name=identifiant]'); return waitFrame(p, 'inline/menu.html'); })();
        const otherTab = await extPage.evaluate(async () => (await chrome.tabs.getCurrent()).id);
        const r = await f.evaluate(async (tabId) => (await import('/lib/extension.js')).ask('save:details', { tabId }).catch((e) => ({ err: e.message })), otherTab);
        return r.proposition === null || r.err !== undefined;
      })());

    // 9. Index local : empreintes seulement.
    const local = await extPage.evaluate(() => chrome.storage.local.get(null));
    check('index des sites et refus : empreintes seulement, aucun nom de site en clair',
      [...local.index_sites, ...local.sites_refuses].every((h) => /^[0-9a-f]{64}$/.test(h))
      // (le réglage « preremplissage » contient le motif d'autorisation de la construction d'essai, pas un site visité)
      && ![A.url, B.url, C.url].some((u) => JSON.stringify({ ...local, preremplissage: null }).includes(hostOf(u))),
      Object.keys(local).join());

    check('aucune erreur de page', errors.length === 0, errors.join(' | '));
    await ctx.close();
  } finally {
    A.close(); B.close(); C.close();
  }
  const failed = checks.filter((c) => !c).length;
  console.log(`\n${checks.length - failed}/${checks.length} contrôles réussis`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
