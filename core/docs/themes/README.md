# Writing a web theme

A theme defines its HTML, CSS, layout and interactions. The application provides authenticated data,
routing, refreshes and actions. Start with the [example project](../../examples/theme-starter/README.md).

Build a ZIP for server installation. Select an installed theme through **Web Settings → Choose a theme**.
The [package and installation guide](packages.md) describes the manifest, server default, browser
selection and recovery.

## Run the example

From the core repository, with its npm dependencies installed:

```sh
npm run build:theme-example
npm run preview:theme
```

Open `http://127.0.0.1:8770/`. The [preview tool](../../tools/theme-preview/README.md) provides simulated
data without a Minecraft server.

The build generates `build/theme-sdk` and copies it to the example's ignored `sdk` directory.
See the [API guide](api.md) for the theme contract and available services.

## TypeScript and JavaScript

Themes can be written in TypeScript or JavaScript. Packages contain JavaScript, CSS and assets.
The generated SDK provides types for both languages.

In TypeScript, import only the type:

```ts
import type { ThemeDefinition } from '@ae2wi/theme-api';
```

For type checking in JavaScript, use JSDoc:

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

## Views and compatibility

`views` declares what the theme implements. Build navigation from those supported views, and show
an unavailable message when a user follows an unsupported route. Unsupported views have no default-theme fallback.

`supportedModVersion: '1.2.x'` declares support for a major/minor mod version line, with any patch.
Patch releases must preserve the public theme contract. A different known version line produces an
advisory in the theme picker without blocking loading. Supported views are declared separately in `views`.

## State and lifecycle

- Read state and call actions; never mutate shared arrays or their resource objects.
- Search, filtering and sorting helpers are optional and return new arrays.
- Use capabilities from authenticated application state before exposing optional game operations.
- Register resource cleanup immediately through `context.lifetime.onCleanup`. Use its abort signal
  for DOM listeners. Subscriptions and icon observers obtained through the host are cleaned up by it.
- Keep secrets out of theme settings. Settings are browser-local, scoped by application path and theme
  ID, and suitable for appearance preferences. They are not server-persisted player data.
- Theme code runs with the signed-in user's permissions and is not sandboxed.

The host catches synchronous mount and subscribed rendering failures and releases that mount.
External theme failures return to the built-in UI using the same application state. Handle errors and
cancellation in event handlers and asynchronous work. Do not retry crafting mutations automatically.
