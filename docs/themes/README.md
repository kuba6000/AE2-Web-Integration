# Writing a web theme

A theme owns its HTML, CSS, layout and interactions. The application provides authenticated data,
routing, refreshes and actions. It does not insert default-theme views into a custom theme.
The included [starter](../../examples/theme-starter/README.md) uses a plain resource list instead of
the default terminal grid.

This stage provides the author contract, optional helpers, generated declarations and a runnable
local example. Installing themes from a server directory and selecting them in Web Settings are
not implemented yet. The production page still mounts the built-in theme.

## Try the complete example

From the core repository, with its npm dependencies installed:

```sh
npm run build:theme-example
npm run preview:theme
```

Open `http://127.0.0.1:8770/`. The preview uses the real application services against a small simulated
HTTP API provided by the shared [preview tool](../../tools/theme-preview/README.md). The example contains a network chooser, searchable and sortable resources, selection, refresh,
logout, a persisted theme preference and an explicit unsupported-view screen.

The build generates `build/theme-sdk` and copies it to the example's ignored `sdk` directory.
Declarations come from the application's TypeScript sources; there is no second handwritten API.
The source entry is `src/main/typescript/app/theme-api.ts`. See the [API guide](api.md).

## TypeScript and JavaScript

The public contract is written in TypeScript. Runtime theme files are JavaScript and CSS. TypeScript,
declaration files and the development preview are development tools, not runtime dependencies added
to the mod JAR.

In TypeScript, import only the type:

```ts
import type { ThemeDefinition } from '@ae2wi/theme-api';
```

JavaScript authors can use exactly the same services. Optional editor checking uses the same generated
declarations:

```js
// @ts-check
/** @type {import('@ae2wi/theme-api').ThemeDefinition} */
const theme = {
    id: 'my-theme',
    name: 'My theme',
    supportedModVersion: '1.2.x',
    views: ['home'],
    mount(root, application, context) {
        const heading = document.createElement('h1');
        root.append(heading);
        application.subscribe((state) => {
            heading.textContent =
                state.route.view === 'home'
                    ? `Hello, ${context.user.username}`
                    : 'This view is not supported by this theme.';
        });
    }
};
export default theme;
```

Install the generated SDK locally (`file:./sdk`) to resolve those types. Do not import
`@ae2wi/theme-api` as a runtime value: it is a type entry. The host supplies services to `mount`.
The starter's compiled `dist/theme.js` also demonstrates the complete JavaScript output.

## Views and compatibility

`views` declares what the theme implements. Build navigation from those supported views, and show
an explicit unavailable message when a user follows an unsupported route. Missing views do not
silently inherit the default terminal or its styles. A theme need not use grids at all.

`supportedModVersion: '1.2.x'` declares support for a major/minor mod version line, with any patch.
Patch releases must preserve the public theme contract. A different line is intended to produce an
advisory in the future theme picker, not prohibit loading. This metadata is not a separate API version
or a guarantee that every view is implemented. The current starter displays its declaration and the
server version; there is no installed-theme compatibility checker yet.

## Ownership

- Read state and call actions; never mutate shared arrays or their resource objects.
- Choose your own presentation. Search, filtering and sorting helpers are optional and return new
  arrays. They do not choose HTML, columns, scrolling or CSS.
- Use capabilities from authenticated application state before exposing optional game operations.
- Register resource cleanup immediately through `context.lifetime.onCleanup`. Use its abort signal
  for DOM listeners. Subscriptions and icon observers obtained through the host are cleaned up by it.
- Keep secrets out of theme settings. Settings are browser-local, scoped by application path and theme
  ID, and suitable for appearance preferences. They are not server-persisted player data.
- Treat theme code as trusted JavaScript running in the page. Readonly types are author guidance,
  not a sandbox or runtime deep freeze.

The host catches synchronous mount and subscribed rendering failures, releases that mount and offers
Retry using the same application state. Theme event handlers and arbitrary asynchronous work still
need their own error handling and cancellation. Do not retry crafting mutations automatically.
