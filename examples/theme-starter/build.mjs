import { spawnSync } from 'node:child_process';
import { cpSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('./', import.meta.url));
const require = createRequire(import.meta.url);
const compiler = path.join(path.dirname(require.resolve('typescript/package.json')), 'bin/tsc');
rmSync(path.join(directory, 'dist'), { recursive: true, force: true });
const result = spawnSync(process.execPath, [compiler, '--project', 'tsconfig.json'], {
    cwd: directory,
    stdio: 'inherit'
});
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
cpSync(path.join(directory, 'src/style.css'), path.join(directory, 'dist/style.css'));
