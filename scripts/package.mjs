// Construit les paquets téléchargeables dans paquets/ (après `node scripts/build.mjs`).
//   coffre-chrome-edge.zip        Chrome et Edge, Linux et Windows (même fichier)
//   coffre-firefox-non-signe.zip  Firefox, à faire signer par Mozilla (voir publication.yml)
// Noms fixes, sans version : les liens « releases/latest/download/… » du README restent valables.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'paquets');
rmSync(out, { recursive: true, force: true });
mkdirSync(out);

const targets = { chrome: 'coffre-chrome-edge.zip', firefox: 'coffre-firefox-non-signe.zip' };
for (const [dir, file] of Object.entries(targets)) {
  const src = join(root, 'dist', dir);
  if (!existsSync(join(src, 'manifest.json'))) throw new Error(`dist/${dir} absent : lancer d'abord le build`);
  // -X : sans attributs propres à la machine ; -D : sans entrées de dossier.
  execFileSync('zip', ['-q', '-r', '-X', '-D', join(out, file), '.'], { cwd: src });
  console.log(`✓ paquets/${file}`);
}
