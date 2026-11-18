// Faux lab pour les essais : Keycloak (code + PKCE S256) et storage (routes du vrai, mêmes règles
// d'accès : Bearer obligatoire, un espace par compte). Ne sert qu'aux essais, sur 127.0.0.1.
const http = require('http');
const crypto = require('crypto');

function b64u(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function start({ clientId = 'coffre-navigateur', redirectUris, username = 'moi' } = {}) {
  const codes = new Map();   // code → { challenge, redirectUri }
  const tokens = new Set();
  const refreshes = new Set();
  const files = new Map();   // `${ns}/${path}` → { body: Buffer, updated }
  const log = [];
  let loggedIn = false;
  let tick = 0;

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks);
    const send = (status, data, type = 'application/json') => {
      res.writeHead(status, { 'Content-Type': type });
      res.end(typeof data === 'string' || Buffer.isBuffer(data) ? data : JSON.stringify(data));
    };
    log.push(`${req.method} ${url.pathname}`);
    const oidc = '/auth/realms/ssolab/protocol/openid-connect';

    if (url.pathname === `${oidc}/auth`) {
      const p = url.searchParams;
      const redirect = p.get('redirect_uri');
      if (p.get('client_id') !== clientId || !redirectUris.includes(redirect)) return send(400, 'client ou redirection refusés', 'text/plain');
      if (p.get('response_type') !== 'code' || p.get('code_challenge_method') !== 'S256' || !p.get('code_challenge')) {
        return send(400, 'PKCE exigé', 'text/plain');
      }
      const back = new URL(redirect);
      back.searchParams.set('state', p.get('state'));
      if (p.get('prompt') === 'none' && !loggedIn) back.searchParams.set('error', 'login_required');
      else {
        loggedIn = true;
        const code = b64u(crypto.randomBytes(16));
        codes.set(code, { challenge: p.get('code_challenge'), redirectUri: redirect });
        back.searchParams.set('code', code);
      }
      res.writeHead(302, { Location: back.toString() });
      return res.end();
    }

    if (url.pathname === `${oidc}/token` && req.method === 'POST') {
      const p = new URLSearchParams(body.toString());
      if (p.get('client_id') !== clientId) return send(401, { error: 'invalid_client' });
      const issue = () => {
        const access = b64u(crypto.randomBytes(24));
        const refresh = b64u(crypto.randomBytes(24));
        tokens.add(access);
        refreshes.add(refresh);
        return send(200, { access_token: access, refresh_token: refresh, expires_in: 300, refresh_expires_in: 1800, token_type: 'Bearer' });
      };
      if (p.get('grant_type') === 'authorization_code') {
        const c = codes.get(p.get('code'));
        codes.delete(p.get('code'));
        const verifierHash = b64u(crypto.createHash('sha256').update(p.get('code_verifier') || '').digest());
        if (!c || c.challenge !== verifierHash || c.redirectUri !== p.get('redirect_uri')) return send(400, { error: 'invalid_grant' });
        return issue();
      }
      if (p.get('grant_type') === 'refresh_token' && refreshes.delete(p.get('refresh_token'))) return issue();
      return send(400, { error: 'invalid_grant' });
    }
    if (url.pathname === `${oidc}/revoke`) return send(200, {});

    if (url.pathname.startsWith('/storage-api/api/')) {
      const auth = req.headers.authorization || '';
      if (!auth.startsWith('Bearer ') || !tokens.has(auth.slice(7))) return send(401, { detail: 'non authentifié' });
      const rest = url.pathname.slice('/storage-api/api'.length);
      if (rest === '/me/') return send(200, { username, email: `${username}@ex.fr`, groups: ['amis'] });
      const m = /^\/files\/([^/]+)\/(?:content\/(.+))?$/.exec(rest);
      if (!m) return send(404, { detail: 'introuvable' });
      const ns = decodeURIComponent(m[1]);
      if (ns !== username) return send(403, { detail: 'refusé' });
      const path = m[2] ? m[2].split('/').map(decodeURIComponent).join('/') : null;
      if (!path && req.method === 'GET') {
        const prefix = url.searchParams.get('prefix') || '';
        return send(200, [...files].filter(([k]) => k.startsWith(`${ns}/${prefix}`)).map(([k, v]) => ({
          namespace: ns, relative_path: k.slice(ns.length + 1), size: v.body.length,
          content_type: 'application/octet-stream', updated_at: v.updated,
        })));
      }
      if (!path && req.method === 'POST') {
        const form = await new Request('http://x/', { method: 'POST', headers: { 'content-type': req.headers['content-type'] }, body }).formData();
        const rel = form.get('relative_path');
        const file = form.get('file');
        if (!rel || !file) return send(400, { detail: 'requis' });
        const updated = new Date(Date.UTC(2026, 10, 18) + ++tick * 1000).toISOString();
        files.set(`${ns}/${rel}`, { body: Buffer.from(await file.arrayBuffer()), updated });
        return send(201, { namespace: ns, relative_path: rel, updated_at: updated });
      }
      if (path && req.method === 'GET') {
        const f = files.get(`${ns}/${path}`);
        return f ? send(200, f.body, 'application/octet-stream') : send(404, { detail: 'introuvable' });
      }
      if (path && req.method === 'DELETE') { files.delete(`${ns}/${path}`); res.writeHead(204); return res.end(); }
    }
    send(404, { detail: 'introuvable' });
  });

  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => {
    resolve({ url: `http://127.0.0.1:${server.address().port}`, files, log, close: () => server.close() });
  }));
}

module.exports = { start };
