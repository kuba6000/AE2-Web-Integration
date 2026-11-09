# Local theme preview

A small development server for theme authors. It serves a compiled theme, the shared application
runtime and a simulated API with resources, a network, an empty CPU list, icon availability
and logout/reset. Other API operations are unsupported. It does not connect to Minecraft.

From the core checkout:

```sh
npm ci
npm run build:theme-example
npm run preview:theme
```

Open `http://127.0.0.1:8770/`. To preview another compiled theme:

```sh
npm run preview:theme -- --theme "/path/to/theme/dist" --port 8771
```

Paths are relative to the current working directory or absolute. The theme directory must contain
`theme.js` with a default-exported theme definition and its assets. The shared runtime is generated
by `npm run build:theme-sdk`. Rebuild changed TypeScript and refresh the browser to see changes.

The server binds to `127.0.0.1` only. Stop it with Ctrl+C. Logout affects this single local preview;
**Restart preview** resets it.
