// Adresse du lab et adresses qui en découlent (conventions du lab : Keycloak sous /auth, storage sous /storage-api).
import { CoffreError } from './crypto.js';

export const DEFAULT_REALM = 'ssolab';
export const DEFAULT_CLIENT_ID = 'coffre-navigateur';
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]']);

/** Rend l'origine du lab, ou lève une erreur fixe. https seulement, sauf boucle locale (essais). */
export function normalizeLabUrl(input) {
  let u;
  try {
    u = new URL(String(input ?? '').trim());
  } catch {
    throw new CoffreError('reglage', 'Adresse du lab invalide (exemple : https://mon-domaine.fr).');
  }
  if (u.username || u.password) throw new CoffreError('reglage', 'Adresse du lab invalide.');
  if (u.protocol === 'http:' && !LOOPBACK.has(u.hostname)) {
    throw new CoffreError('reglage', 'Le lab doit être joint en https.');
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') {
    throw new CoffreError('reglage', 'Adresse du lab invalide (exemple : https://mon-domaine.fr).');
  }
  return u.origin;
}

export function labEndpoints({ origin, realm = DEFAULT_REALM, clientId = DEFAULT_CLIENT_ID }) {
  const issuer = `${origin}/auth/realms/${encodeURIComponent(realm)}`;
  return {
    origin,
    clientId,
    issuer,
    authorize: `${issuer}/protocol/openid-connect/auth`,
    token: `${issuer}/protocol/openid-connect/token`,
    logout: `${issuer}/protocol/openid-connect/logout`,
    storage: `${origin}/storage-api/api`,
    permission: `${origin}/*`,
  };
}
