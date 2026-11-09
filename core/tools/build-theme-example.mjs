import { spawnSync } from 'node:child_process';
import { cpSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const project = fileURLToPath(new URL('../', import.meta.url));
const example = path.join(project, 'examples/theme-starter');
const sdk = path.join(example, 'sdk');
rmSync(sdk, { recursive: true, force: true });
cpSync(path.join(project, 'build/theme-sdk'), sdk, { recursive: true });
const result = spawnSync(process.execPath, ['build.mjs'], { cwd: example, stdio: 'inherit' });
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
