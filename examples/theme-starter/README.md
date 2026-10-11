# Resource list theme starter

This is a working small theme, not a copy of the default UI. It supports Home (network selection)
and Resources, presents resources as a list, and explicitly reports unsupported routes. It demonstrates
the shared application contract, optional helpers, browser-local settings and mount cleanup.

## In the core checkout

```sh
npm ci
npm run build:theme-example
npm run preview:theme
```

Open `http://127.0.0.1:8770/`. The preview uses simulated resources; it does not connect to Minecraft.
After logging out, use **Restart preview**. Stop the process with Ctrl+C.

## As a separate project

First run the example build in the core checkout. Copy this entire directory, including its generated
`sdk` directory, to your own project. Then:

```sh
npm install
npm run build
```

Run the shared preview from the core checkout, pointing it at your compiled theme:

```sh
npm run preview:theme -- --theme "/path/to/your-theme/dist"
```

The directory must contain `theme.js` (default-exported theme definition) and its assets.
The preview tool stays in the core checkout; do not copy a mock server into each theme project.

The SDK is a local file dependency. TypeScript uses the same version as core. You do not need to
publish an npm package, install Java dependencies or bundle a second copy of the application into
your theme. Refresh the browser after rebuilding changes.

## Files

- `src/theme.ts`: metadata, mount lifecycle and presentation.
- `src/style.css`: all styling owned by this theme.
- `dist/theme.js`, `dist/style.css`: generated browser assets.
- `sdk/`: generated types and development application runtime; ignored by Git.
  The shared preview is documented in [tools/theme-preview](../../tools/theme-preview/README.md).

Turn off **Use standard search and ordering** to see unchanged server order. The example limits its
DOM to the first 100 matching resources and reports that limit; implement pagination or virtualization
for a full terminal. It deliberately does not provide crafting, CPU/history screens or resource icons.

For JavaScript, see the [author guide](../../docs/themes/README.md#typescript-and-javascript).
The generated `dist/theme.js` is also the full runnable JavaScript version of this example.
Do not hand-edit `dist` while using the TypeScript build, since the next build replaces it.

Server-folder installation and a production theme picker are a later implementation step. There is
no supported theme ZIP layout yet; this example validates the author contract independently of it.
