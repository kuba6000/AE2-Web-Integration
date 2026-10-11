import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const project = fileURLToPath(new URL('../', import.meta.url));
const output = path.join(project, 'build/theme-sdk');
const require = createRequire(import.meta.url);
const compiler = path.join(path.dirname(require.resolve('typescript/package.json')), 'bin/tsc');
function run(args) {
    const result = spawnSync(process.execPath, args, { cwd: project, stdio: 'inherit' });
    if (result.error) throw result.error;
    if (result.status !== 0) process.exit(result.status ?? 1);
}
run(['tools/build-frontend.mjs']);
rmSync(output, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
run([
    compiler,
    '--project',
    'tsconfig.json',
    '--declaration',
    '--emitDeclarationOnly',
    '--outDir',
    path.join(output, 'types')
]);
cpSync(path.join(project, 'build/generated/frontend/assets/web/app'), path.join(output, 'runtime/app'), {
    recursive: true
});
writeFileSync(
    path.join(output, 'package.json'),
    JSON.stringify(
        {
            name: '@ae2wi/theme-api',
            version: '0.0.0-development',
            private: true,
            type: 'module',
            types: './types/app/theme-api.d.ts',
            exports: {
                '.': { types: './types/app/theme-api.d.ts' },
                './host': { types: './types/app/boot.d.ts', default: './runtime/app/boot.js' }
            }
        },
        null,
        2
    ) + '\n'
);
cpSync(path.join(project, 'LICENSE'), path.join(output, 'LICENSE'));
console.log(`Theme SDK: ${output}`);
