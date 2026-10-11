# Resource list theme starter

A minimal theme with network selection and a searchable resource list. Use it as a starting point
for your own theme.

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

The SDK is a local file dependency. Refresh the browser after rebuilding changes.

## Files

- `src/theme.ts`: metadata, mount lifecycle and presentation.
- `src/style.css`: all styling owned by this theme.
- `dist/theme.js`, `dist/theme.css`: bundled browser assets.
- `theme.json`: package metadata; keep its ID consistent with the exported definition.
- `dist/theme.zip`: installable server theme package.
- `sdk/`: generated types and development application runtime; ignored by Git.

## Supported features

Turn off **Use standard search and ordering** to see unchanged server order. The example limits its
list to the first 100 matching resources. Add pagination or virtualization to display larger lists.
Crafting, CPU/history screens and resource icons are not included.

For JavaScript, see the [author guide](../../docs/themes/README.md#typescript-and-javascript).
Do not hand-edit `dist` while using the TypeScript build, since the next build replaces it.

The starter build bundles module imports into one ES module and CSS image/font references into data
URLs. Install `dist/theme.zip` on the server, then select it in Web Settings. See the
[package guide](../../docs/themes/packages.md) for installation and recovery.
