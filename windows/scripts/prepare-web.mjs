import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const vendorDir = resolve(root, 'web', 'vendor');
const assetsDir = resolve(root, 'web', 'assets');

mkdirSync(vendorDir, { recursive: true });
mkdirSync(assetsDir, { recursive: true });

const markedSrc = resolve(root, 'node_modules', 'marked', 'lib', 'marked.esm.js');
if (!existsSync(markedSrc)) {
  throw new Error(`Missing marked ESM bundle at ${markedSrc}. Run npm install first.`);
}
copyFileSync(markedSrc, resolve(vendorDir, 'marked.esm.js'));

const drawables = resolve(root, '..', 'app', 'src', 'main', 'res', 'drawable');
for (const name of [
  'hermes_editor_icon.png',
  'asora_theme_sun_minimal.png',
  'asora_theme_moon_minimal.png',
]) {
  const src = resolve(drawables, name);
  if (!existsSync(src)) {
    throw new Error(`Missing drawable asset: ${src}`);
  }
  copyFileSync(src, resolve(assetsDir, name));
}

console.log('Prepared offline web assets in web/vendor and web/assets.');
