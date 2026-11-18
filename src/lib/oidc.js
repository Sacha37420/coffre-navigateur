// OpenID Connect, flux « code d'autorisation » + PKCE S256, pour un client public (l'extension).
import { toB64u } from './base64.js';
import { CoffreError } from './crypto.js';

export function randomToken(bytes = 32) {
  return toB64u(globalThis.crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function pkcePair() {
  const verifier = randomToken(48); // 64 caractères, dans [43, 128]
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return { verifier, challenge: toB64u(digest), method: 'S256' };
}

export function authorizeUrl(ep, { redirectUri, state, challenge, prompt }) {
  const u = new URL(ep.authorize);
  u.search = new URLSearchParams({
    response_type: 'code',
    client_id: ep.clientId,
    redirect_uri: redirectUri,
    scope: 'openid',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    ...(prompt ? { prompt } : {}),
  }).toString();
  return u.toString();
}

/** Lit l'URL de retour ; rend le code ou lève une erreur fixe. */
export function parseCallback(url, { redirectUri, state }) {
  let u;
  try { u = new URL(url); } catch { throw new CoffreError('connexion', 'Retour de connexion invalide.'); }
  if (!url.startsWith(redirectUri)) throw new CoffreError('connexion', 'Retour de connexion invalide.');
  // Keycloak peut répondre dans la requête ou le fragment selon response_mode.
  const p = new URLSearchParams(u.search || u.hash.slice(1));
  if (p.get('state') !== state) throw new CoffreError('connexion', 'Retour de connexion refusé (état inattendu).');
  if (p.get('error')) {
    throw new CoffreError(p.get('error') === 'access_denied' ? 'acces_refuse' : 'connexion',
      p.get('error') === 'access_denied'
        ? 'Accès refusé : votre compte n\'est pas autorisé à utiliser le coffre.'
        : 'La connexion a échoué.');
  }
  const code = p.get('code');
  if (!code) throw new CoffreError('connexion', 'Retour de connexion invalide.');
  return code;
}

function tokensFrom(json, now) {
  if (!json || typeof json.access_token !== 'string') throw new CoffreError('connexion', 'Réponse de connexion invalide.');
  return {
    access: json.access_token,
    refresh: typeof json.refresh_token === 'string' ? json.refresh_token : null,
    expires: now + Math.max(0, Number(json.expires_in) || 0) * 1000,
    refreshExpires: json.refresh_token
      ? now + Math.max(0, Number(json.refresh_expires_in) || 0) * 1000
      : 0,
  };
}

async function postToken(fetchFn, ep, body, now) {
  let res;
  try {
    res = await fetchFn(ep.token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(body).toString(),
      credentials: 'omit',
    });
  } catch {
    throw new CoffreError('reseau', 'Le lab est injoignable.');
  }
  if (!res.ok) throw new CoffreError('connexion', 'La connexion a été refusée par le lab.');
  return tokensFrom(await res.json().catch(() => null), now);
}

export function exchangeCode(fetchFn, ep, { code, verifier, redirectUri, now = Date.now() }) {
  return postToken(fetchFn, ep, {
    grant_type: 'authorization_code', client_id: ep.clientId, code, code_verifier: verifier, redirect_uri: redirectUri,
  }, now);
}

export function refreshTokens(fetchFn, ep, refresh, now = Date.now()) {
  return postToken(fetchFn, ep, { grant_type: 'refresh_token', client_id: ep.clientId, refresh_token: refresh }, now);
}
