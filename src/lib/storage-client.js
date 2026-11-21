// Client de l'API storage du lab (routes inchangées : /api/me/, /api/files/<ns>/…). Bearer seulement.
import { CoffreError } from './crypto.js';

function encodePath(p) {
  return p.split('/').map(encodeURIComponent).join('/');
}

/**
 * @param {{ base: string, fetch: Function, token: () => Promise<string>, renew?: () => Promise<void> }} deps
 * `renew` : oublie le jeton d'accès en cache. storage répond 403 (et non 401) à un jeton expiré ou
 * révoqué : sur 401 comme sur 403, on renouvelle une fois avant de conclure à un refus.
 */
export function createStorageClient({ base, fetch: fetchFn, token, renew }) {
  async function call(path, init = {}, opts = {}, retried = false) {
    const { allow404 = false } = opts;
    const t = await token();
    let res;
    try {
      res = await fetchFn(`${base}${path}`, {
        ...init,
        headers: { ...(init.headers || {}), Authorization: `Bearer ${t}` },
        credentials: 'omit',
        cache: 'no-store',
      });
    } catch {
      throw new CoffreError('reseau', 'Le lab est injoignable.');
    }
    if (res.status === 404 && allow404) return null;
    if ((res.status === 401 || res.status === 403) && renew && !retried) {
      await renew();
      return call(path, init, opts, true);
    }
    if (res.status === 401) throw new CoffreError('session', 'Session expirée : reconnectez-vous au lab.');
    if (res.status === 403) throw new CoffreError('acces_refuse', 'Le lab refuse l\'accès au stockage pour ce compte.');
    if (!res.ok) throw new CoffreError('serveur', `Le stockage du lab a répondu une erreur (${res.status}).`);
    return res;
  }

  return {
    async me() {
      const j = await (await call('/me/')).json();
      if (!j || typeof j.username !== 'string' || !j.username) throw new CoffreError('serveur', 'Réponse inattendue du lab.');
      return { username: j.username, email: j.email || '' };
    },

    /** Liste : [{ path, updated, size }] (chemins relatifs à l'espace). */
    async list(ns, prefix) {
      const res = await call(`/files/${encodeURIComponent(ns)}/?prefix=${encodeURIComponent(prefix)}`);
      const arr = await res.json();
      if (!Array.isArray(arr)) throw new CoffreError('serveur', 'Réponse inattendue du lab.');
      return arr.map((f) => ({ path: f.relative_path, updated: f.updated_at, size: f.size }));
    },

    /** Contenu texte, ou null si absent. */
    async read(ns, path) {
      const res = await call(`/files/${encodeURIComponent(ns)}/content/${encodePath(path)}`, {}, { allow404: true });
      return res ? res.text() : null;
    },

    /** Dépose (ou remplace) ; rend { updated }. */
    async write(ns, path, text) {
      const form = new FormData();
      form.append('relative_path', path);
      form.append('file', new Blob([text], { type: 'application/octet-stream' }), path.split('/').pop());
      const res = await call(`/files/${encodeURIComponent(ns)}/`, { method: 'POST', body: form });
      const j = await res.json().catch(() => ({}));
      return { updated: j.updated_at ?? null };
    },

    async remove(ns, path) {
      await call(`/files/${encodeURIComponent(ns)}/content/${encodePath(path)}`, { method: 'DELETE' }, { allow404: true });
    },
  };
}
