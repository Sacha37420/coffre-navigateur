// Faux partagés par les tests : zone de stockage en mémoire, faux storage du lab.
export function memArea() {
  const m = new Map();
  return {
    m,
    get: async (k) => structuredClone(m.get(k)),
    set: async (k, v) => { m.set(k, structuredClone(v)); },
    remove: async (k) => { m.delete(k); },
  };
}

/** Faux client storage (même interface que createStorageClient), un espace par nom. */
export function fakeStorage() {
  const files = new Map(); // `${ns}/${path}` → { text, updated }
  let clock = 0;
  const stamp = () => new Date(Date.UTC(2026, 10, 18) + ++clock * 1000).toISOString();
  return {
    files,
    calls: [],
    async me() { return { username: 'moi', email: 'moi@ex.fr' }; },
    async list(ns, prefix) {
      this.calls.push(['list', prefix]);
      return [...files].filter(([k]) => k.startsWith(`${ns}/${prefix}`))
        .map(([k, v]) => ({ path: k.slice(ns.length + 1), updated: v.updated, size: v.text.length }));
    },
    async read(ns, path) { this.calls.push(['read', path]); return files.get(`${ns}/${path}`)?.text ?? null; },
    async write(ns, path, text) {
      this.calls.push(['write', path]);
      const updated = stamp();
      files.set(`${ns}/${path}`, { text, updated });
      return { updated };
    },
    async remove(ns, path) { files.delete(`${ns}/${path}`); },
  };
}
