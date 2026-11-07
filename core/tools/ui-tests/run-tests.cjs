// Runs every test file in its own process. Each browser test file owns one headless browser, so the number of files
// running at once follows the machine but stays small enough for the browsers not to starve each other.
const os = require('node:os');
const { spawnSync } = require('node:child_process');

const concurrency = Math.min(4, Math.max(2, os.availableParallelism()));
const result = spawnSync(
    process.execPath,
    ['--test', `--test-concurrency=${concurrency}`, ...process.argv.slice(2), '*.test.cjs'],
    { cwd: __dirname, stdio: 'inherit' }
);
process.exit(result.status ?? 1);
