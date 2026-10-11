import { spawnSync } from 'node:child_process';
import { cpSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { build } from 'esbuild';
import { zipSync } from 'fflate';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('./', import.meta.url));
const require = createRequire(import.meta.url);
const compiler = path.join(path.dirname(require.resolve('typescript/package.json')), 'bin/tsc');
rmSync(path.join(directory, 'dist'), { recursive: true, force: true });
const result = spawnSync(process.execPath, [compiler, '--project', 'tsconfig.json', '--noEmit'], {
    cwd: directory,
    stdio: 'inherit'
});
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
await build({
    absWorkingDir: directory,
    entryPoints: ['src/theme.ts'],
    outfile: 'dist/theme.js',
    bundle: true,
    format: 'esm',
    target: 'es2022',
    loader: {
        '.png': 'dataurl',
        '.jpg': 'dataurl',
        '.jpeg': 'dataurl',
        '.gif': 'dataurl',
        '.svg': 'dataurl',
        '.webp': 'dataurl',
        '.woff': 'dataurl',
        '.woff2': 'dataurl',
        '.ttf': 'dataurl',
        '.otf': 'dataurl'
    }
});
cpSync(path.join(directory, 'theme.json'), path.join(directory, 'dist/theme.json'));
const files = Object.fromEntries(
    ['theme.json', 'theme.js', 'theme.css'].map((name) => [name, readFileSync(path.join(directory, 'dist', name))])
);
writeFileSync(path.join(directory, 'dist/theme.zip'), zipSync(files));
