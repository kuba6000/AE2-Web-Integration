import { spawnSync } from 'node:child_process';
import { rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const project = fileURLToPath(new URL('../', import.meta.url));
const buildDirectory = path.resolve(process.argv[2] || path.join(project, 'build'));
const output = path.join(buildDirectory, 'generated/frontend/assets/web');
const require = createRequire(import.meta.url);
const compiler = path.join(path.dirname(require.resolve('typescript/package.json')), 'bin/tsc');

// This directory contains compiler output only. Remove modules whose source was deleted or renamed.
rmSync(output, { recursive: true, force: true });
const result = spawnSync(process.execPath, [compiler, '--project', 'tsconfig.json', '--outDir', output], {
    cwd: project,
    stdio: 'inherit'
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
