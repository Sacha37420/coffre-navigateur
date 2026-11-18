// Connexion au lab : configuration (storage.local), jetons (storage.session seulement).
import { CoffreError } from './crypto.js';
import { labEndpoints, normalizeLabUrl } from './lab-config.js';
import { authorizeUrl, exchangeCode, parseCallback, pkcePair, randomToken, refreshTokens } from './oidc.js';

export const K_LAB = 'lab';
export const S_TOKENS = 'jetons';

/**
 * @param {{ local, session, identity: { redirectUrl: () => string, launch: (url, interactive) => Promise<string> },
 *           fetch: Function, now?: () => number }} deps
 */
export function createAuth({ local, session, identity, fetch: fetchFn, now = () => Date.now() }) {
  async function config() {
    return (await local.get(K_LAB)) || null;
  }

  async function endpoints() {
    const c = await config();
    if (!c?.origin) throw new CoffreError('reglage', 'Indiquez d\'abord l\'adresse du lab.');
    return labEndpoints(c);
  }

  async function login(interactive) {
    const ep = await endpoints();
    const redirectUri = identity.redirectUrl();
    const { verifier, challenge } = await pkcePair();
    const state = randomToken(16);
    let back;
    try {
      back = await identity.launch(
        authorizeUrl(ep, { redirectUri, state, challenge, prompt: interactive ? undefined : 'none' }), interactive,
      );
    } catch {
      throw new CoffreError('connexion_requise', interactive
        ? 'La fenêtre de connexion a été fermée ou n\'a pas pu s\'ouvrir.'
        : 'Connectez-vous au lab.');
    }
    const code = parseCallback(back, { redirectUri, state });
    const tokens = await exchangeCode(fetchFn, ep, { code, verifier, redirectUri, now: now() });
    await session.set(S_TOKENS, tokens);
    return tokens.access;
  }

  return {
    config,
    endpoints,
    redirectUrl: () => identity.redirectUrl(),

    /** Change d'adresse : oublie jetons et nom d'espace. Rend l'origine normalisée. */
    async setLab(url, extra = {}) {
      const origin = normalizeLabUrl(url);
      await local.set(K_LAB, { ...(extra || {}), origin, username: null });
      await session.remove(S_TOKENS);
      return origin;
    },

    async setUsername(username) {
      const c = await config();
      await local.set(K_LAB, { ...c, username });
    },

    /** Connexion à la demande de l'utilisateur. */
    login: () => login(true),

    async connected() {
      const t = await session.get(S_TOKENS);
      return !!t && (t.expires > now() || t.refreshExpires > now());
    },

    /** Jeton d'accès valable : en cache, rafraîchi, ou obtenu sans fenêtre (session Keycloak). */
    async token() {
      const t = await session.get(S_TOKENS);
      if (t && t.expires - 30_000 > now()) return t.access;
      if (t?.refresh && t.refreshExpires - 5_000 > now()) {
        try {
          const next = await refreshTokens(fetchFn, await endpoints(), t.refresh, now());
          await session.set(S_TOKENS, next);
          return next.access;
        } catch (e) {
          if (e.code === 'reseau') throw e;
        }
      }
      await session.remove(S_TOKENS);
      return login(false);
    },

    /** Oublie les jetons de l'extension (la session Keycloak du navigateur n'est pas fermée). */
    async logout() {
      const t = await session.get(S_TOKENS);
      await session.remove(S_TOKENS);
      if (t?.refresh) {
        try {
          const ep = await endpoints();
          await fetchFn(`${ep.issuer}/protocol/openid-connect/revoke`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({ client_id: ep.clientId, token: t.refresh, token_type_hint: 'refresh_token' }).toString(),
            credentials: 'omit',
          });
        } catch { /* au mieux */ }
      }
    },
  };
}
