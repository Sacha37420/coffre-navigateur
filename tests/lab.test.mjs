import { test } from 'node:test';
import assert from 'node:assert/strict';

import { CoffreError } from '../src/lib/crypto.js';
import { labEndpoints, normalizeLabUrl } from '../src/lib/lab-config.js';
import { authorizeUrl, exchangeCode, parseCallback, pkcePair, refreshTokens } from '../src/lib/oidc.js';
import { createStorageClient } from '../src/lib/storage-client.js';
import { createAuth } from '../src/lib/auth.js';
import { editEntry, newEntry, normalizeOrigin, tombstone } from '../src/lib/entries.js';
import { toB64u } from '../src/lib/base64.js';
import { memArea } from './fakes.mjs';

const isErr = (code) => (e) => e instanceof CoffreError && e.code === code;

test('adresse du lab : https exigé, boucle locale admise en http, origine seule', () => {
  assert.equal(normalizeLabUrl(' https://lab.exemple.fr/storage/x?y '), 'https://lab.exemple.fr');
  assert.equal(normalizeLabUrl('http://127.0.0.1:9000/'), 'http://127.0.0.1:9000');
  assert.throws(() => normalizeLabUrl('http://lab.exemple.fr'), isErr('reglage'));
  assert.throws(() => normalizeLabUrl('ftp://lab.exemple.fr'), isErr('reglage'));
  assert.throws(() => normalizeLabUrl('https://moi:mdp@lab.exemple.fr'), isErr('reglage'));
  assert.throws(() => normalizeLabUrl('pas une adresse'), isErr('reglage'));
});

test('adresses dérivées selon les conventions du lab', () => {
  const ep = labEndpoints({ origin: 'https://lab.fr' });
  assert.equal(ep.authorize, 'https://lab.fr/auth/realms/ssolab/protocol/openid-connect/auth');
  assert.equal(ep.token, 'https://lab.fr/auth/realms/ssolab/protocol/openid-connect/token');
  assert.equal(ep.storage, 'https://lab.fr/storage-api/api');
  assert.equal(ep.clientId, 'coffre-navigateur');
  assert.equal(ep.permission, 'https://lab.fr/*');
});

test('PKCE S256 : vérificateur conforme, défi = SHA-256 du vérificateur', async () => {
  const { verifier, challenge, method } = await pkcePair();
  assert.match(verifier, /^[A-Za-z0-9_-]{43,128}$/);
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  assert.equal(challenge, toB64u(d));
  assert.equal(method, 'S256');
});

test('URL d\'autorisation complète', () => {
  const ep = labEndpoints({ origin: 'https://lab.fr' });
  const u = new URL(authorizeUrl(ep, { redirectUri: 'https://x.chromiumapp.org/', state: 'S', challenge: 'C', prompt: 'none' }));
  const p = Object.fromEntries(u.searchParams);
  assert.deepEqual(p, {
    response_type: 'code', client_id: 'coffre-navigateur', redirect_uri: 'https://x.chromiumapp.org/',
    scope: 'openid', state: 'S', code_challenge: 'C', code_challenge_method: 'S256', prompt: 'none',
  });
});

test('retour de connexion : state vérifié, erreurs fixes', () => {
  const r = 'https://x.chromiumapp.org/';
  assert.equal(parseCallback(`${r}?state=S&code=K`, { redirectUri: r, state: 'S' }), 'K');
  assert.equal(parseCallback(`${r}#state=S&code=K`, { redirectUri: r, state: 'S' }), 'K');
  assert.throws(() => parseCallback(`${r}?state=T&code=K`, { redirectUri: r, state: 'S' }), isErr('connexion'));
  assert.throws(() => parseCallback(`https://ailleurs.fr/?state=S&code=K`, { redirectUri: r, state: 'S' }), isErr('connexion'));
  assert.throws(() => parseCallback(`${r}?state=S&error=access_denied`, { redirectUri: r, state: 'S' }), isErr('acces_refuse'));
  assert.throws(() => parseCallback(`${r}?state=S`, { redirectUri: r, state: 'S' }), isErr('connexion'));
});

function fakeFetch(handler) {
  const calls = [];
  const f = async (url, init = {}) => {
    calls.push({ url, init });
    const { status = 200, json, text } = await handler(url, init);
    return {
      ok: status >= 200 && status < 300, status,
      json: async () => json, text: async () => text ?? JSON.stringify(json),
    };
  };
  f.calls = calls;
  return f;
}

test('échange du code et rafraîchissement : corps attendu, échéances calculées', async () => {
  const ep = labEndpoints({ origin: 'https://lab.fr' });
  const f = fakeFetch(() => ({ json: { access_token: 'A', refresh_token: 'R', expires_in: 300, refresh_expires_in: 1800 } }));
  const t = await exchangeCode(f, ep, { code: 'K', verifier: 'V', redirectUri: 'https://x/', now: 1000 });
  assert.deepEqual(t, { access: 'A', refresh: 'R', expires: 301_000, refreshExpires: 1_801_000 });
  const body = Object.fromEntries(new URLSearchParams(f.calls[0].init.body));
  assert.deepEqual(body, { grant_type: 'authorization_code', client_id: 'coffre-navigateur', code: 'K', code_verifier: 'V', redirect_uri: 'https://x/' });
  assert.equal(f.calls[0].init.credentials, 'omit');
  await refreshTokens(f, ep, 'R', 0);
  assert.equal(new URLSearchParams(f.calls[1].init.body).get('grant_type'), 'refresh_token');
  const bad = fakeFetch(() => ({ status: 400, json: { error: 'invalid_grant', error_description: 'secret' } }));
  await assert.rejects(exchangeCode(bad, ep, { code: 'K', verifier: 'V', redirectUri: 'x' }),
    (e) => isErr('connexion')(e) && !e.message.includes('secret'));
});

test('client storage : chemins, en-têtes, multipart, erreurs fixes', async () => {
  const f = fakeFetch((url, init) => {
    if (url.endsWith('/me/')) return { json: { username: 'moi', email: 'm@x' } };
    if (url.includes('?prefix=')) return { json: [{ relative_path: 'coffre/v1/a.bin', updated_at: 'T', size: 3 }] };
    if (url.includes('/content/absent')) return { status: 404 };
    if (init.method === 'POST') return { status: 201, json: { updated_at: 'U' } };
    return { text: 'contenu' };
  });
  const c = createStorageClient({ base: 'https://lab.fr/storage-api/api', fetch: f, token: async () => 'TOK' });
  assert.deepEqual(await c.me(), { username: 'moi', email: 'm@x' });
  assert.deepEqual(await c.list('moi', 'coffre/v1/'), [{ path: 'coffre/v1/a.bin', updated: 'T', size: 3 }]);
  assert.equal(f.calls[1].url, 'https://lab.fr/storage-api/api/files/moi/?prefix=coffre%2Fv1%2F');
  assert.equal(await c.read('moi', 'coffre/v1/a b.bin'), 'contenu');
  assert.equal(f.calls[2].url, 'https://lab.fr/storage-api/api/files/moi/content/coffre/v1/a%20b.bin');
  assert.equal(await c.read('moi', 'absent'), null);
  assert.deepEqual(await c.write('moi', 'coffre/v1/a.bin', '{}'), { updated: 'U' });
  const post = f.calls.at(-1);
  assert.equal(post.init.body.get('relative_path'), 'coffre/v1/a.bin');
  assert.equal(post.init.body.get('file').type, 'application/octet-stream');
  for (const call of f.calls) {
    assert.equal(call.init.headers.Authorization, 'Bearer TOK');
    assert.equal(call.init.credentials, 'omit');
    assert.ok(!call.url.includes('token='));
  }
  for (const [status, code] of [[401, 'session'], [403, 'acces_refuse'], [500, 'serveur']]) {
    const e = createStorageClient({ base: 'b', fetch: fakeFetch(() => ({ status })), token: async () => 't' });
    await assert.rejects(e.me(), isErr(code));
  }
  const down = createStorageClient({ base: 'b', fetch: async () => { throw new Error('x'); }, token: async () => 't' });
  await assert.rejects(down.me(), isErr('reseau'));
});

function authSetup({ launchResult, tokenJson = { access_token: 'A1', refresh_token: 'R1', expires_in: 300, refresh_expires_in: 1800 } } = {}) {
  const local = memArea();
  const session = memArea();
  const clock = { t: 1_000_000 };
  const launches = [];
  const identity = {
    redirectUrl: () => 'https://ext.chromiumapp.org/',
    launch: async (url, interactive) => {
      launches.push({ url: new URL(url), interactive });
      if (launchResult === 'fail') throw new Error('fermée');
      const state = new URL(url).searchParams.get('state');
      return launchResult ?? `https://ext.chromiumapp.org/?state=${state}&code=CODE`;
    },
  };
  let n = 0;
  const f = fakeFetch((url, init) => {
    const g = new URLSearchParams(init.body).get('grant_type');
    if (g === 'refresh_token') return { json: { ...tokenJson, access_token: `AR${++n}` } };
    return { json: tokenJson };
  });
  const auth = createAuth({ local, session, identity, fetch: f, now: () => clock.t });
  return { auth, local, session, clock, launches, f };
}

test('connexion : PKCE, jetons en session seulement', async () => {
  const s = authSetup();
  await assert.rejects(s.auth.login(), isErr('reglage'));
  assert.equal(await s.auth.setLab('https://lab.fr/x'), 'https://lab.fr');
  assert.equal(await s.auth.login(), 'A1');
  assert.equal(s.launches[0].interactive, true);
  assert.equal(s.launches[0].url.searchParams.get('prompt'), null);
  const verifier = new URLSearchParams(s.f.calls[0].init.body).get('code_verifier');
  const challenge = toB64u(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  assert.equal(s.launches[0].url.searchParams.get('code_challenge'), challenge);
  assert.equal(s.session.m.get('jetons').access, 'A1');
  assert.ok(!JSON.stringify([...s.local.m]).includes('A1'));
  assert.ok(!JSON.stringify([...s.local.m]).includes('R1'));
  assert.equal(await s.auth.connected(), true);
});

test('jeton : cache, puis rafraîchissement, puis reconnexion silencieuse', async () => {
  const s = authSetup();
  await s.auth.setLab('https://lab.fr');
  await s.auth.login();
  assert.equal(await s.auth.token(), 'A1');
  s.clock.t += 290_000;                       // à moins de 30 s de l'échéance
  assert.equal(await s.auth.token(), 'AR1');
  s.clock.t += 10_000_000;                    // tout a expiré
  assert.equal(await s.auth.token(), 'A1');
  assert.equal(s.launches.at(-1).interactive, false);
  assert.equal(s.launches.at(-1).url.searchParams.get('prompt'), 'none');
});

test('reconnexion silencieuse impossible ⇒ connexion_requise, jamais de fenêtre', async () => {
  const s = authSetup({ launchResult: 'fail' });
  await s.auth.setLab('https://lab.fr');
  await assert.rejects(s.auth.token(), isErr('connexion_requise'));
  assert.equal(s.launches.length, 1);
  assert.equal(s.launches[0].interactive, false);
});

test('changer d\'adresse oublie jetons et espace ; déconnexion révoque', async () => {
  const s = authSetup();
  await s.auth.setLab('https://lab.fr');
  await s.auth.login();
  await s.auth.setUsername('moi');
  await s.auth.logout();
  assert.equal(s.session.m.has('jetons'), false);
  assert.ok(s.f.calls.at(-1).url.endsWith('/revoke'));
  await s.auth.login();
  await s.auth.setLab('https://autre.fr');
  assert.equal(s.session.m.has('jetons'), false);
  assert.equal((await s.auth.config()).username, null);
});

test('entrées : origines normalisées, horodatage croissant, pierre tombale vide', () => {
  assert.equal(normalizeOrigin('exemple.fr/login'), 'https://exemple.fr');
  assert.equal(normalizeOrigin('http://exemple.fr:8080/a'), 'http://exemple.fr:8080');
  assert.throws(() => normalizeOrigin('javascript:alert(1)'), isErr('format'));
  const e = newEntry({ origins: ['exemple.fr', 'https://exemple.fr/x'], username: 'u', password: 'p' }, 1000);
  assert.deepEqual(e.origins, ['https://exemple.fr']);
  assert.equal(e.name, 'exemple.fr');
  const e2 = editEntry(e, { password: 'q' }, 500);   // horloge qui recule
  assert.ok(e2.updated > e.updated);
  assert.equal(e2.created, e.created);
  const t = tombstone(e2, 2000);
  assert.equal(t.deleted, true);
  assert.equal(t.password, '');
  assert.equal(t.username, '');
  assert.ok(t.updated > e2.updated);
  assert.throws(() => editEntry(t, { password: 'x' }), isErr('format'));
  assert.throws(() => newEntry({}), isErr('format'));
});

test('storage refuse le jeton (403 comme le vrai storage) : renouvellement puis une seule nouvelle tentative', async () => {
  let accepted = 'NEUF';
  let renewed = 0;
  let current = 'VIEUX';
  const f = fakeFetch((url, init) => (init.headers.Authorization === `Bearer ${accepted}`
    ? { json: { username: 'moi' } } : { status: 403, json: { detail: 'x' } }));
  const c = createStorageClient({
    base: 'b', fetch: f, token: async () => current, renew: async () => { renewed++; current = 'NEUF'; },
  });
  assert.equal((await c.me()).username, 'moi');
  assert.equal(renewed, 1);
  assert.equal(f.calls.length, 2);
  accepted = 'JAMAIS';                       // vrai refus : une seule nouvelle tentative, puis l'erreur
  await assert.rejects(c.me(), isErr('acces_refuse'));
  assert.equal(renewed, 2);
  assert.equal(f.calls.length, 4);
});

test('invalidate : le prochain jeton est rafraîchi, sans fenêtre', async () => {
  const s = authSetup();
  await s.auth.setLab('https://lab.fr');
  await s.auth.login();
  await s.auth.invalidate();
  assert.equal(await s.auth.token(), 'AR1');
  assert.equal(s.launches.length, 1);
});
