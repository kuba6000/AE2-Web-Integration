# Browser tests

Install the frontend build tools from the core repository root:

```sh
npm ci
```

Then run the terminal against a controlled HTTP API in a real browser from `tools/ui-tests`:

```sh
npm ci
npx playwright install chromium
npm test
```

`npm test` builds the frontend, theme SDK and example before running the suite.
Tests use a simulated HTTP API; Minecraft is not required.

Set `BROWSER_CHANNEL=msedge` to use an installed Microsoft Edge instead of downloaded
Chromium.

Run the standalone login-page checks from the core repository root:

```sh
node --test src/test/js/login-status.test.cjs
```

## Frontend checks

From the core repository root, using Node.js 22.13+ on the 22.x line, or Node.js 24+:

```sh
npm ci
npm run format
npm run format:check
npm run lint
npm run typecheck
npm run build
```

TypeScript source lives in `src/main/typescript`; static web assets live in
`src/main/resources/assets/web`. `npm run build` writes JavaScript to
`build/generated/frontend/assets/web`. Use `npm run watch` to compile while editing.
Gradle automatically installs the locked frontend tools and compiles TypeScript before
processing resources, including when core is built as a native branch's submodule.
Node.js and npm must be available on PATH when building; the Minecraft server needs neither.

Prettier formats TypeScript, static assets, browser tests and tooling configuration.
ESLint checks TypeScript and JavaScript; Stylelint checks CSS. `npm run typecheck`
checks strict types without writing output. CI runs formatting, lint and type checks.
The standalone login page is outside this formatter scope.
