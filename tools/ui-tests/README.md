# Browser tests

Install the frontend build tools from the core repository root:

```sh
npm ci
```

Then run the new terminal against a controlled HTTP API in a real browser from `tools/ui-tests`:

```sh
npm ci
npx playwright install chromium
npm test
```

`npm test` compiles the frontend before starting the browser tests.

Tests cover discovery, item browsing, navigation, preferences, delayed responses,
access failures and login destination preservation. Crafting coverage uses visible
controls and HTTP requests to verify quantity validation, calculation polling and
deletion, stable CPU choice, direct plan entry, submission, unavailable plans,
lost access, and uncertain mutations without replay. Delayed HTTP responses exercise
navigation and mutation lifetimes. CPU monitoring coverage checks stable selection,
current resource and optional tracking data, explicit cancellation, missing CPUs,
access loss and uncertain/pending cancellation across navigation. Tests do not inspect private application state
or production source, and do not require Minecraft.

History and settings tests cover entry identity, measured intervals and provider positions,
read-only restoration, explicit tracking saves, draft preservation, unconfirmed persistence,
grouped access information, and delayed reads or saves across navigation and access changes.

Set `BROWSER_CHANNEL=msedge` to use an installed Microsoft Edge instead of downloaded
Chromium. Playwright is a development dependency and is not included in mod JARs.

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
The legacy web assets are outside this scope. These dependencies stay outside `resources`
and are not packaged in mod JARs. In IntelliJ IDEA, select the root
`node_modules/prettier` package to use the same formatter and configuration on save.
