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
navigation and mutation lifetimes. Tests do not inspect private application state
or production source, and do not require Minecraft.
Set `BROWSER_CHANNEL=msedge` to use an installed Microsoft Edge instead of downloaded
Chromium. Playwright is a development dependency and is not included in mod JARs.
