# Theme API guide

Run `npm run build:theme-sdk` to generate the complete TypeScript declarations in
`build/theme-sdk/types/app/theme-api.d.ts`.

## Definition and mount

Export a `ThemeDefinition` containing `id`, `name`, `supportedModVersion`, `views` and
`mount(root, application, context)`. Mount once and update the DOM from subscriptions. The root belongs
to this presentation; the theme must clean up anything it attaches outside it, including stylesheets,
dialogs, document listeners, timers and observers.

`context.lifetime.signal` aborts when the presentation ends.
`context.lifetime.onCleanup(callback)` registers cleanup, including for partially failed mounts.
Register cleanup immediately after acquiring the resource.

## State and actions

### Action notifications

`application.notifications.subscribe(listener)` supplies the current transient notifications immediately
and when they change. Each entry has an `id`, `kind` (`success`, `error` or `warning`), a common translation key
in `message`, and optional `subject` and `quantity`. Treat subject text as data, never HTML.
Use `context.i18n.forLanguage(language).common(message)` for the message.

The application keeps up to three notifications across route changes and expires them automatically.
Themes choose placement and appearance, call `dismiss(id)` for a close button, and call
`pause(id, true/false)` while a notification is hovered or focused. Release any pause when unmounting.
Subscription cleanup is handled by the theme host. Notifications do not replace persistent errors,
uncertain-action guards or form validation; render those from the corresponding state fields.

### Shared state

`application.state` and the value delivered immediately by `application.subscribe(listener)` have the
same `ThemeState` contract. It includes route, grids, raw resources, selection, preferences,
capabilities, icon-pack status, refresh status, last update time, crafting, CPUs, home, history and
network settings. State is borrowed readonly data, not a separately cloned snapshot. Derive new
arrays when presenting it. `subscribe` returns an early-unsubscribe function; host cleanup also removes
the subscription.

| Service                                                                    | Purpose                                                   |
| -------------------------------------------------------------------------- | --------------------------------------------------------- |
| `refresh({ reloadDetail: true })`                                          | Explicit refresh, including an open detail view           |
| `search(value)`, `select(resource)`                                        | Set terminal search and selection                         |
| `preference(name, value)`                                                  | Save a shared browser preference                          |
| `displayIcons(enabled)`                                                    | Tell the data layer whether this presentation needs icons |
| `crafting.create(resource, quantity, automatic, lightMode, ignoreMissing)` | Calculate a plan using supported server options           |
| `crafting.recalculate(automatic, ignoreMissing)`                           | Recalculate the current plan request                      |
| `crafting.selectCpu(key)`, `submit()`, `remove()`                          | Choose CPU, start or cancel a plan                        |
| `crafting.search(value)`, `sort(value)`, `reverse()`                       | Set standard plan presentation preferences                |
| `cpus.pause(key, paused)`, `cpus.cancel(key)`                              | Operate on CPU work                                       |
| `settings.edit(field, value)`, `settings.save()`                           | Edit and save network settings                            |

Actions retain the shared validation, capability checks, pending state and API error handling.
Render the appropriate state error fields and pending indicators. Automatic polling must not disable
unrelated actions or clear existing content. A theme supplies its own dialogs and confirmation flow.

Navigation uses the application's existing hash routes, for example `#/`,
`#/grids/<encoded-key>/items` and `#/web-settings`. `Route` and `ThemeView` expose the supported route
union. URL-encode keys. A theme's declared views affect its navigation; they do not disable server
authorization or eliminate all background data requests.

## Optional helpers

Available through `context.helpers`:

| Helper                                    | Behavior                                            |
| ----------------------------------------- | --------------------------------------------------- |
| `registryId(resource)`                    | Readable native registry identifier, if known       |
| `filterResources(resources, options)`     | Search plus stored/craftable and item/fluid filters |
| `sortResources(resources, options)`       | Name, quantity or identifier ordering               |
| `selectResources(resources, options)`     | Apply those filters and ordering together           |
| `selectPlanResources(resources, options)` | Search and standard plan grouping/ordering          |

Resource filtering options are `search`, `language`, `filter` (`all`, `stored`, `craftable`),
`showItems` and `showFluids`. Ordering options are `language`, `sort` (`name`, `quantity`, `id`) and
`sortOrder` (`ascending`, `descending`). Plan options use `search`, `language`, `sort`
(`name`, `quantity`, `steps`) and `sortOrder`.

Array helpers accept readonly inputs, return new arrays and preserve resource object references and
additional theme fields. They do not mutate application state and can be used independently.

## Context services

`context.user` supplies username/admin status; `modVersion` supplies the reported mod version or null.
`logout()` performs the shared session action. Catch failures in the calling event handler.

`context.settings.get(key, fallback)`, `set(key, jsonValue)` and `remove(key)` manage theme-local browser
preferences. `set` reports whether saving succeeded; browser storage can be unavailable. Validate read
values before using them, since they can come from older theme versions or manual browser edits.

`context.i18n.register(dictionaries)` registers theme translations. `forLanguage(language)` returns
the translator/formatting service. The generated `Dictionaries` and `Translator` types describe the
available calls; raw server text must be rendered as text, not interpolated as trusted HTML.

`context.updateNotice` exposes update metadata, visibility, pending/error state, subscription and
`dismiss(snooze?)` / `skip()` actions. The theme chooses notice placement and presentation.

## Icons

Use `application.icons.observe(container, paint)` to obtain an `IconView`. Pass visible targets and
their response metadata to `update(targets, metadata)`. The host manages observation, atlas loading and
cleanup. The paint callback receives the target element and `IconSprite | null`; it supplies rendering
coordinates and image URL, while the theme chooses CSS/layout and its missing-icon appearance.
Do not persist temporary blob URLs. Explicit `dispose()` permits early release; mount cleanup also
releases observers. Call `displayIcons(false)` if your theme never renders resource icons.

## Theme selection and package assets

`context.themes?.open()` opens the theme picker. Provide an entry in your settings or navigation.
The picker offers the built-in theme, server-installed themes and the server default.
See [recovery](packages.md#recovery) for access when a theme is unusable.

`context.assets?.url('images/logo.png')` is an optional convenience for resolving a path from the
package root, for example when setting an image's `src` from JavaScript. A bare relative DOM URL resolves
against the page URL, not the theme module. You can also use `new URL('./images/logo.png', import.meta.url)`
for a module-relative URL. Relative ES module imports and stylesheet `url(...)`/`@import` references
resolve normally from their containing files; include the referenced files in the package.

## Development host

The SDK's `./host` export provides `boot(root, definition, base?, builtinAssetBase?)` for local previews.
It initializes the API session and returns a cleanup function. The optional fourth argument sets the
asset directory for the initial theme. Use the [preview tool](../../tools/theme-preview/README.md) to
run a theme locally.
