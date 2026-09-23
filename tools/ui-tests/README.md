# Browser tests

Run the new terminal against a controlled HTTP API in a real browser:

```sh
npm ci
npx playwright install chromium
npm test
```

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
