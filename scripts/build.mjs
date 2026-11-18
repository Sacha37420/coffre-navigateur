// Construit dist/chrome (Chrome et Edge) et dist/firefox depuis src/. Aucune dépendance.
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const base = JSON.parse(readFileSync(join(root, 'src/manifest.base.json'), 'utf8'));
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
base.version = pkg.version;

const variants = {
  chrome: (m) => ({ ...m, minimum_chrome_version: '116' }),
  firefox: (m) => ({
    ...m,
    browser_specific_settings: { gecko: { id: 'coffre-navigateur@lab.local', strict_min_version: '128.0' } },
  }),
};

for (const [name, adapt] of Object.entries(variants)) {
  const out = join(root, 'dist', name);
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  cpSync(join(root, 'src'), out, {
    recursive: true,
    filter: (p) => !p.endsWith('manifest.base.json'),
  });
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(adapt(base), null, 2) + '\n');
  console.log(`✓ dist/${name}`);
}
