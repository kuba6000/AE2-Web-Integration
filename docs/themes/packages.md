# Theme ZIP packages

Themes are installed by the server administrator as ZIP packages and selected in each browser.
The starter's build produces `dist/theme.zip`. Put files directly at the archive root, without a
wrapper directory. `theme.json` contains:

```json
{
    "id": "resource-list",
    "name": "Resource list example",
    "version": "1.0.0",
    "supportedModVersion": "1.2.x",
    "views": ["home", "items"],
    "entry": "theme.js",
    "styles": ["theme.css"]
}
```

`entry` is an ES module with a default-exported theme definition. Its ID must match the manifest.
Relative imports and additional code chunks are supported; include those files in the ZIP. The starter
uses esbuild to bundle its source for convenience, but bundling is not required.

The loader attaches the listed stylesheets as stylesheet links and removes them when the theme ends.
CSS `url(...)` and `@import` paths resolve relative to the stylesheet, so include referenced images,
fonts and CSS files in the ZIP. JavaScript can use `new URL('./images/logo.png', import.meta.url)` for
module-relative assets or the optional `context.assets.url('images/logo.png')` helper for package-root
paths. A bare relative URL assigned to a DOM element resolves against the page URL, not the module.

Manifest metadata is inspected without executing the entry. Keep top-level module code free of
timers, DOM edits and other side effects; acquire resources inside `mount` and register their cleanup.
The manifest determines installed display metadata and declared views. An unsupported view must be
handled explicitly by the theme; the host does not inject default-theme views.

## Install on a server

Put the ZIP in `config/ae2webintegration/themes/` and restart the server. No manual extraction is needed.
Set `general.default_theme` in `config/ae2webintegration/config.toml` to `server:resource-list`, or
`builtin:default` for the built-in UI. Changing the configured default can be applied through the normal
config reload; adding/replacing ZIP files requires a server restart.

The authenticated catalog at `GET /api/themes` lists valid installed packages and the effective default.
Assets are served through authenticated `/themes/<id>/<path>` URLs. Invalid archives are logged and
excluded. Duplicate package IDs exclude the conflicting packages. A missing configured default resolves
to the built-in theme. Install packages only from authors you trust: theme JavaScript runs in the page
and can access displayed data and perform operations with the current account's permissions.

## Select in a browser

Open **Web Settings → Choose a theme** to select an installed theme. **Use server default** follows the
administrator's choice. Explicitly selecting the built-in theme or a server theme overrides it in this
browser. The selection is saved for this origin and application base path. A declared mod-line mismatch
shows an advisory without preventing the theme from loading.

## Recovery

Loading or rendering errors return to the built-in UI with an error in the picker. The saved selection
is retained until you choose another theme. An independent Themes button remains accessible with an
external theme, during loading or after a failure. Its safe-mode link is available even while another
theme is waiting to load.

For recovery after navigation, add `?safe=1` before the URL's `#` fragment. The initial UI is then
built-in and selected/server-default custom code and styles are skipped. Safe mode does not sign you
out or erase preferences. Choose the built-in theme explicitly to replace a broken saved selection;
use **Exit safe mode** to resume normal selection. Arbitrary synchronous JavaScript can still hang a
page: trusted themes are not sandboxed, and recovery then requires navigating/reloading with the safe URL.

## Limits and paths

- ZIP: 16 MiB compressed, 64 MiB total expanded, 16 MiB per file, 512 entries.
- Manifest: 64 KiB of valid UTF-8 JSON.
- ID: up to 64 lowercase letters/digits separated by single hyphens.
- Name: up to 100 characters; package version: up to 64 characters.
- Paths: relative ASCII letters/digits, dots, underscores, hyphens and `/`, up to 240 characters.
  No empty or `.`/`..` segments, duplicate paths, absolute paths or backslashes.
- `entry` ends in `.js`; each declared stylesheet ends in `.css` and must exist.

Use the manifest ID, not the ZIP filename, when choosing the administrator's default.
