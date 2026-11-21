// Lecture et contrôle de config.local.json (valeurs par défaut de l'installation, non versionnées).
import { existsSync, readFileSync } from 'node:fs';

export function parseDefaults(text) {
  const raw = JSON.parse(text);
  const out = { labUrl: '' };
  if (raw.labUrl !== undefined && raw.labUrl !== '') {
    const u = new URL(raw.labUrl);
    if (u.protocol !== 'https:' || u.username || u.password || (u.pathname !== '/' && u.pathname !== '')) {
      throw new Error('config.local.json : labUrl doit être une adresse https sans chemin (https://domaine).');
    }
    out.labUrl = u.origin;
  }
  const unknown = Object.keys(raw).filter((k) => k !== 'labUrl');
  if (unknown.length) throw new Error(`config.local.json : clé(s) inconnue(s) : ${unknown.join(', ')}`);
  return out;
}

export function defaultsModule(file) {
  const d = existsSync(file) ? parseDefaults(readFileSync(file, 'utf8')) : { labUrl: '' };
  return `// Généré par scripts/build.mjs depuis config.local.json — ne pas modifier.\nexport default Object.freeze(${JSON.stringify(d)});\n`;
}
