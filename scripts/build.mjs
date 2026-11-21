// Construit dist/chrome (Chrome et Edge) et dist/firefox depuis src/. Aucune dépendance.
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defaultsModule } from './defaults.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const base = JSON.parse(readFileSync(join(root, 'src/manifest.base.json'), 'utf8'));
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
base.version = pkg.version;

// Clé publique qui fixe l'identifiant Chrome/Edge en mode développeur :
// kainmmlggnfinglcapceocelpngjhnol → adresse de retour https://kainmmlggnfinglcapceocelpngjhnol.chromiumapp.org/
// (aucune clé privée n'est nécessaire pour charger l'extension décompressée ; il n'en existe pas de copie).
const CHROME_PUBLIC_KEY =
  'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsRK+a6TqjoUKYfRmagL9+0SWfEyuZmvhhdkiCt5+Y/2p8RiAURiq4BYM7b/xly7LlpAuTFYTksznnBnFij/L+2Wefs+vicR2cuY9R8m07u1wN+kKl4wZ/9XTR4phj7OywNv+ZbiSgC0jjIruy4BmtJ5sr7iHtNZtzpCLuvUnd5L4PPvj7m2PiHIXVQuLM6wt3YlMCh5ZRGP32s6EDjMRYeZ9kGgo1pbJnsxczo/nc/cdCaUOE5+UBI/IqswSU0xuabLQHIuR5l9AZ70QY9jLbNGOISatnnv3RHImR98ug5Y/HxUyOK+u4YDASQgCA1YOASQfExswdY+hM7POW+eA5QIDAQAB';

// --essai : construit en plus dist/essai (Chromium) avec l'accès à la boucle locale déjà accordé,
// pour que l'essai automatique n'ait pas à répondre à une demande d'autorisation.
const essai = process.argv.includes('--essai');

const variants = {
  chrome: (m) => ({
    ...m,
    key: CHROME_PUBLIC_KEY,
    minimum_chrome_version: '116',
    background: { service_worker: 'background.js', type: 'module' },
  }),
  firefox: (m) => ({
    ...m,
    background: { scripts: ['background.js'], type: 'module' },
    browser_specific_settings: { gecko: { id: 'coffre-navigateur@lab.local', strict_min_version: '128.0' } },
  }),
};

if (essai) {
  variants.essai = (m) => ({ ...variants.chrome(m), host_permissions: ['http://127.0.0.1/*'] });
}

const defaults = defaultsModule(join(root, 'config.local.json'));

for (const [name, adapt] of Object.entries(variants)) {
  const out = join(root, 'dist', name);
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  cpSync(join(root, 'src'), out, {
    recursive: true,
    filter: (p) => !p.endsWith('manifest.base.json'),
  });
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(adapt(base), null, 2) + '\n');
  writeFileSync(join(out, 'defaults.js'), name === 'essai' ? defaultsModule('') : defaults);
  console.log(`✓ dist/${name}`);
}
