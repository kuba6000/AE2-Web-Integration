const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {
    gridA,
    gridB,
    iron,
    quartz,
    readyPlan,
    cpu,
    cpuWork,
    refreshingFixture,
    fixture,
    clickTab,
    openMobilePane,
    closeMobilePane,
    settleFrames,
    settleResponse,
    poll,
    seedTerminalPageSize,
    atlasFixture
} = require('./fixtures.cjs');

test('plan recalculation preserves the order and lite mode, supports autostart and locks uncertain replacement', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.capabilities.craftingLightMode = true;
    options.plan = { ...readyPlan, isSimulating: true };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('textbox', { name: 'Craft quantity' }).fill('32');
    await page.getByRole('checkbox', { name: 'Lite mode', exact: true }).check();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const start = page.getByRole('button', { name: 'Start', exact: true });
    await start.waitFor();
    assert.ok(await start.isDisabled());
    const more = page.getByRole('button', { name: 'Recalculate options', exact: true });
    await more.click();
    const menu = page.getByRole('menu', { name: 'Recalculate options', exact: true });
    assert.equal(await menu.getByRole('menuitem').count(), 2, 'unsupported ignore-missing variants are absent');
    options.nextPlanId = 8;
    await menu.getByRole('menuitem', { name: 'Recalculate', exact: true }).click();
    await page.waitForURL(/\/plans\/8$/);
    assert.deepEqual(
        options.requests.filter((entry) => entry.method !== 'GET').map((entry) => entry.method),
        ['POST', 'DELETE', 'POST']
    );
    assert.deepEqual(options.requests.filter((entry) => entry.path.endsWith('/crafting-plans')).at(-1).body, {
        itemKey: 'iron',
        quantity: 32,
        lightMode: true
    });
    await more.click();
    options.plan = readyPlan;
    options.nextPlanId = 9;
    await menu.getByRole('menuitem', { name: 'Recalculate with autostart', exact: true }).click();
    await page.waitForURL(/\/items$/);
    assert.ok(options.requests.some((entry) => entry.path.endsWith('/crafting-plans/9/submit')));
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    options.nextPlanId = 10;
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.waitForURL(/\/plans\/10$/);
    await more.click();
    options.fault = { operation: 'delete', status: 'TIMEOUT' };
    await menu.getByRole('menuitem', { name: 'Recalculate', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    assert.ok(await more.isDisabled());
    assert.ok(await start.isDisabled());
    const count = options.requests.filter((entry) => entry.method !== 'GET').length;
    await poll(page);
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, count);
});

test('ignore-missing recalculation keeps original options first and never retries uncertain creation', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.capabilities.craftingLightMode = true;
    options.capabilities.craftingIgnoreMissing = true;
    options.submitStatus = 'FAIL';
    options.submitReason = 'Ingredients changed before starting';
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Start automatically', exact: true }).check();
    await page.getByRole('checkbox', { name: 'Lite mode', exact: true }).check();
    await page.getByRole('checkbox', { name: 'Ignore missing ingredients', exact: true }).check();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.waitForURL(/\/plans\/7$/);
    assert.deepEqual(options.requests.find((entry) => entry.path.endsWith('/crafting-plans')).body, {
        itemKey: 'iron',
        quantity: 1,
        lightMode: true,
        ignoreMissing: true,
        autoStart: true
    });
    await page.getByText(options.submitReason, { exact: true }).waitFor();
    await page.reload();
    await page.getByText(/Iron Ingot × 1/).waitFor();
    await poll(page);
    assert.equal(options.requests.filter((entry) => entry.path.endsWith('/submit')).length, 1);
    const more = page.getByRole('button', { name: 'Recalculate options', exact: true });
    await more.click();
    const menu = page.getByRole('menu', { name: 'Recalculate options', exact: true });
    assert.equal(await menu.getByRole('menuitem').count(), 4);
    assert.equal(await menu.getByRole('menuitem').first().innerText(), 'Recalculate ignoring missing + autostart');
    await menu.getByRole('menuitem', { name: 'Recalculate ignoring missing', exact: true }).focus();
    await poll(page);
    assert.equal(
        await menu
            .getByRole('menuitem', { name: 'Recalculate ignoring missing', exact: true })
            .evaluate((node) => node === document.activeElement),
        true
    );
    options.nextPlanId = 8;
    await page.keyboard.press('Enter');
    await page.waitForURL(/\/plans\/8$/);
    assert.deepEqual(options.requests.filter((entry) => entry.path.endsWith('/crafting-plans')).at(-1).body, {
        itemKey: 'iron',
        quantity: 1,
        lightMode: true,
        ignoreMissing: true
    });
    await more.click();
    assert.equal(await menu.getByRole('menuitem').first().innerText(), 'Recalculate ignoring missing');
    options.fault = { operation: 'create', status: 'TIMEOUT' };
    await menu.getByRole('menuitem', { name: 'Recalculate', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /calculation.*unknown/i })
        .waitFor();
    assert.ok(await more.isDisabled());
    const count = options.requests.filter((entry) => entry.method !== 'GET').length;
    await poll(page);
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, count);
});

test('plan tiles omit zero metrics and show ordered counts with whole storage percentages', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingPlanSteps = true;
    options.plan = {
        ...readyPlan,
        plan: [
            { ...readyPlan.plan[0], displayName: 'Combined resource', missing: 2, requested: 12, steps: 3, stored: 4 },
            {
                ...readyPlan.plan[0],
                displayName: 'Stored resource',
                missing: 0,
                requested: 0,
                steps: 0,
                stored: 15,
                usedPercent: 0.376
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resources = page.getByRole('region', { name: 'Plan resources', exact: true });
    const combined = resources.getByRole('button', { name: /^Combined resource/ });
    await combined.waitFor();
    assert.match(await combined.innerText(), /Missing\s+2\s+Requested\s+12\s+Steps\s+3\s+Stored\s+4/);
    const stored = resources.getByRole('button', { name: /^Stored resource/ });
    assert.match(await stored.innerText(), /Stored\s+15\s+\(38%\)/);
    assert.doesNotMatch(await stored.innerText(), /Missing|Requested|Steps/);
    await stored.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Stored resource', { exact: true }).waitFor();
    assert.match(await tooltip.innerText(), /Stored: 15 \(38%\)/);
    assert.doesNotMatch(await tooltip.innerText(), /Missing|Requested|Crafting steps/);
});

test('unsupported plan steps fall back without overwriting the saved choice and omit unavailable metrics', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingPlanSteps = true;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Crafting steps', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
    options.capabilities.craftingPlanSteps = false;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).isVisible());
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.equal(await tooltip.getByText(/Crafting steps/).count(), 0);
    options.capabilities.craftingPlanSteps = true;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Crafting steps', exact: true }).waitFor();
    options.capabilities.craftingPlanSteps = false;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    options.capabilities.craftingPlanSteps = true;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).isVisible());
});

test('crafting plan resources search and sort while CPU choice and actions stay available outside scroll', async (t) => {
    const { page, options, base } = await fixture(t);
    options.plan = {
        ...readyPlan,
        plan: Array.from({ length: 80 }, (_, index) => ({
            ...readyPlan.plan[0],
            displayName: `Resource ${String(index).padStart(2, '0')}`,
            stored: index + 1,
            requested: 20
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resources = page.getByRole('list', { name: 'Plan resources', exact: true });
    await resources.getByRole('button', { name: /Resource 00/ }).waitFor();
    const cpus = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
    await cpus.getByRole('radio', { name: /cpu-b/ }).check();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    assert.match(await resources.getByRole('button').first().getAttribute('aria-label'), /Resource 00/);
    await page.getByRole('button', { name: /Sort order: Ascending/ }).click();
    assert.match(await resources.getByRole('button').first().getAttribute('aria-label'), /Resource 79/);
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('Resource 03');
    assert.equal(await resources.getByRole('button').count(), 1);
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('');
    const start = page.getByRole('button', { name: 'Start', exact: true });
    const before = await start.boundingBox();
    await resources.evaluate((list) => {
        list.parentElement.scrollTop = list.parentElement.scrollHeight;
    });
    const after = await start.boundingBox();
    assert.deepEqual(after, before);
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).isChecked(), true);
    await start.click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    assert.equal(options.requests.find((request) => request.path.endsWith('/submit')).body.cpuKey, 'cpu-b');
});

test('quantity expressions and adjustments send exact positive integers through HTTP', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    const dialog = page.getByRole('dialog');
    const quantity = dialog.getByRole('textbox', { name: 'Craft quantity' });
    await quantity.fill('10.5m + (100k / 2) - 64');
    await dialog.getByRole('button', { name: '+64', exact: true }).click();
    assert.equal(await quantity.inputValue(), '10550000');
    await quantity.fill('1');
    await dialog.getByRole('button', { name: '-1000', exact: true }).click();
    assert.equal(await quantity.inputValue(), '1');
    for (const invalid of ['1/3', 'abc']) {
        await quantity.fill(invalid);
        await dialog.getByRole('button', { name: 'Calculate plan', exact: true }).click();
        assert.equal(await quantity.getAttribute('aria-invalid'), 'true');
    }
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 0);
    await quantity.fill('-(2-3) * (10,5m + 100k) / 2');
    await dialog.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.waitForURL(/\/plans\/7$/);
    assert.equal(options.requests.find((request) => request.path.endsWith('/crafting-plans')).body.quantity, 5300000);
});

test('quantity editing survives refresh and only positive safe integer quantities reach HTTP', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    const quantity = page.getByRole('textbox', { name: 'Craft quantity' });
    await quantity.fill('37');
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/items'));
    await poll(page);
    await (await refreshed).finished();
    await settleFrames(page);
    assert.equal(await quantity.evaluate((input) => input === document.activeElement), true);
    assert.equal(await quantity.inputValue(), '37');
    for (const invalid of ['0', '1.5']) {
        await quantity.fill(invalid);
        await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    }
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 0);
    await quantity.fill('9007199254740991');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    assert.equal(
        options.requests.find((request) => request.path.endsWith('/crafting-plans')).body.quantity,
        9007199254740991
    );
});

test('deletion rejection makes unavailable or denied plans non-actionable', async (t) => {
    const { page, options, base } = await fixture(t);
    let planId = 20;
    for (const status of ['NO_PERMISSIONS', 'GRID_NOT_FOUND', 'INVALID_ID']) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId++}`);
        await page
            .getByRole('list', { name: 'Plan resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor();
        options.fault = { operation: 'delete', status };
        await page.getByRole('button', { name: 'Cancel', exact: true }).click();
        await page
            .getByRole('list', { name: 'Plan resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor({ state: 'hidden' });
        assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    }
});

test('pending submission resolves after route reentry and older CPU reads cannot overwrite its result', async (t) => {
    const { page, base } = await refreshingFixture(t);
    let completeSubmit;
    await page.route('**/submit', (route) => {
        completeSubmit = (status) =>
            route.fulfill({
                status: status === 'OK' ? 200 : 503,
                contentType: 'application/json',
                body: JSON.stringify({ status, data: null })
            });
    });
    for (const [planId, status] of [
        [30, 'OK'],
        [31, 'TIMEOUT']
    ]) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId}`);
        await page.getByRole('button', { name: 'Start', exact: true }).click();
        await clickTab(page, 'Terminal');
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await page.getByRole('heading', { name: 'Crafting plan', exact: true }).waitFor();
        await completeSubmit(status);
        await page
            .getByRole('status')
            .filter({ hasText: status === 'OK' ? /submitted/i : /outcome.*unknown/i })
            .waitFor();
        await poll(page);
        await page
            .getByRole('status')
            .filter({ hasText: status === 'OK' ? /submitted/i : /outcome.*unknown/i })
            .waitFor();
    }
    await page.unroute('**/submit');
    await page.goto(`${base}#/grids/${gridA}/plans/32`);
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    let releaseCpus;
    const captured = new Promise((resolve) => {
        releaseCpus = resolve;
    });
    await page.route('**/cpus', (route) => releaseCpus(route));
    await poll(page);
    const cpuRequest = await captured;
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    await cpuRequest.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'NO_PERMISSIONS', data: null })
    });
    await settleResponse(page, cpuRequest.request());
    assert.match(page.url(), new RegExp(`/grids/${gridA}/items$`));
});

for (const initialScheme of ['light', 'dark']) {
    test(`login initializes from ${initialScheme} system mode once and remembers its own toggle`, async (t) => {
        const { page, options, base } = await fixture(t, '/ae2');
        options.loggedOut = true;
        await page.emulateMedia({ colorScheme: initialScheme });
        await page.goto(base);
        await page.getByLabel('Username', { exact: true }).waitFor();
        const other = initialScheme === 'light' ? 'dark' : 'light';
        const toggle = (target) => page.getByRole('button', { name: `Switch to ${target} mode`, exact: true });
        assert.equal(await toggle(other).count(), 1);
        const panelColor = () => page.getByRole('main').evaluate((node) => getComputedStyle(node).backgroundColor);
        const initialColor = await panelColor();
        await page.emulateMedia({ colorScheme: other });
        assert.equal(await panelColor(), initialColor, 'system changes do not override the initial choice');
        await page.reload();
        await page.getByLabel('Username', { exact: true }).waitFor();
        assert.equal(await panelColor(), initialColor, 'the initial choice survives reloading');
        await toggle(other).click();
        const changedColor = await panelColor();
        assert.notEqual(changedColor, initialColor);
        await page.reload();
        await page.getByLabel('Username', { exact: true }).waitFor();
        assert.equal(await panelColor(), changedColor);
        assert.equal(await toggle(initialScheme).count(), 1);
        await toggle(initialScheme).click();
        assert.equal(await panelColor(), initialColor, 'the same button toggles back');
        await page.getByRole('button', { name: 'Register', exact: true }).click();
        await page.getByLabel('Username (in-game)', { exact: true }).waitFor();
        assert.equal(await panelColor(), initialColor);
        await page.setViewportSize({ width: 320, height: 640 });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        if (initialScheme === 'dark' && process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await page.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-dark-mobile.png'),
                fullPage: true
            });
            await page.getByRole('button', { name: 'Log in', exact: true }).first().click();
            await page.setViewportSize({ width: 1280, height: 900 });
            await page.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-dark-desktop.png'),
                fullPage: true
            });
        }
    });
}
test('mobile login shows one usable form at a time and submits the existing registration fields', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.loggedOut = true;
    await page.setViewportSize({ width: 320, height: 640 });
    await page.goto(base);
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(await page.getByLabel('Username (in-game)', { exact: true }).isVisible(), false);
    assert.equal(await page.getByRole('dialog').count(), 0);
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-mobile.png'), fullPage: true });
        await page.setViewportSize({ width: 1280, height: 800 });
        await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-desktop.png'), fullPage: true });
        await page.setViewportSize({ width: 320, height: 640 });
    }
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByLabel('Username (in-game)', { exact: true }).fill('TestPlayer');
    const password = page.getByLabel('Password', { exact: true }).filter({ visible: true });
    await password.fill('test-password');
    await page.getByRole('button', { name: 'Show password', exact: true }).filter({ visible: true }).click();
    assert.equal(await password.getAttribute('type'), 'text');
    await page.setViewportSize({ width: 320, height: 360 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
    await page.route(base, async (route) => {
        if (route.request().method() !== 'POST') return route.continue();
        const body = new URLSearchParams(route.request().postData());
        assert.equal(body.get('register'), 'TestPlayer');
        assert.equal(body.get('password'), 'test-password');
        await route.fulfill({ status: 200, body: 'Registered' });
    });
    const submitted = page.waitForRequest((request) => request.method() === 'POST');
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await submitted;
});

for (const error of ['INVALID_USER', 'INVALID_PASSWORD', 'NOT_ONLINE']) {
    test(`login displays ${error} without losing the destination`, async (t) => {
        const { page, options, base } = await fixture(t, '/ae2');
        options.loggedOut = true;
        await page.goto(`${base}?${error}#/grids/${gridA}/items`);
        await page.getByRole('alert').waitFor();
        assert.ok((await page.getByRole('alert').innerText()).length > 0);
        assert.equal(new URL(page.url()).search, '');
        assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    });
}

test('registration confirmation keeps the command readable and supports manual copy without clipboard access', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.loggedOut = true;
    await page.addInitScript(() => Object.defineProperty(navigator, 'clipboard', { value: undefined }));
    await page.setViewportSize({ width: 390, height: 700 });
    const token = 'sample-token-<img src=x onerror=alert(1)>';
    await page.goto(`${base}?confirmregistration&token=${encodeURIComponent(token)}#/grids/${gridA}/items`);
    const command = page.getByRole('textbox', { name: 'Registration command' });
    await command.waitFor();
    assert.equal(await command.inputValue(), `/ae2webintegration auth ${token}`);
    assert.equal(await page.getByLabel('Username', { exact: true }).isVisible(), false);
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    await page.getByRole('button', { name: 'Copy command' }).click();
    assert.equal(
        await command.evaluate((node) => node.selectionEnd - node.selectionStart),
        (await command.inputValue()).length
    );
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({
            path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-confirmation.png'),
            fullPage: true
        });
    }
    await page.getByRole('button', { name: 'Back to login' }).click();
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(await command.isVisible(), false);
    assert.equal(
        (await page.context().cookies()).some((cookie) => cookie.name === 'cookiesAccepted'),
        false
    );
});

test('login errors clear transient parameters and preserve destination without loading a theme', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}?INVALID_PASSWORD#/grids/${gridA}/items`);
    await page.waitForFunction(() => !location.search.includes('INVALID_PASSWORD'));
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    assert.equal(
        options.requests.some((request) => /\/themes\/.*\.(js|css)$/.test(request.path)),
        false
    );
});

test('logout returns to the login document and keeps the current route as its destination', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    // The destination may equal the route the logged-out document opens with or differ from it.
    for (const hash of ['#/', '#/web-settings']) {
        options.loggedOut = false;
        await page.goto('about:blank');
        await page.goto(`${base}${hash}`);
        await page.getByRole('button', { name: 'Log out', exact: true }).click();
        await page.getByPlaceholder('Enter password', { exact: true }).first().waitFor();
        assert.equal(new URL(page.url()).pathname, '/ae2/');
        assert.equal(new URL(page.url()).search, '');
        assert.equal(new URL(page.url()).hash, hash);
    }
});

test('changing networks cannot publish a delayed response from the previous network', async (t) => {
    const { page, options, base } = await fixture(t);
    options.delayA = 400;
    await page.goto(base);
    const requested = page.waitForRequest((request) => request.url().includes(gridA + '/items'));
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .click();
    await requested;
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    // Let the deliberately delayed old response reach the browser before observing the final view.
    await settleResponse(page, await requested);
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.match(page.url(), new RegExp(gridB));
    await page.goBack();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.match(page.url(), new RegExp(gridA));
    await clickTab(page, 'Home');
    await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
    assert.equal(
        await page.getByRole('link', { name: 'Terminal', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/items`
    );
});

// A sample theme consumes only public helpers; assertions observe its controls and rendered values.
async function themeFixture(t, script, markup = '') {
    const current = await fixture(t, '/ae2');
    const url = new URL('theme-fixture', current.base).href;
    await current.page.route(url, (route) =>
        route.fulfill({
            contentType: 'text/html',
            body: `<!doctype html><html lang="en"><body>${markup}<output aria-label="Result"></output>
                <script type="module">
                    try {
                        const { createThemeContext } = await import('./assets/web/app/theme-context.js');
                        const base = new URL('./', location.href);
                        const result = document.querySelector('output');
                        ${script}
                        document.body.dataset.ready = 'true';
                    } catch (error) {
                        document.querySelector('output').textContent = String(error);
                        document.body.dataset.ready = 'error';
                    }
                </script></body></html>`
        })
    );
    const open = async () => {
        if (current.page.url() === url) await current.page.reload();
        else await current.page.goto(url);
        await current.page.locator('body[data-ready]').waitFor();
        assert.equal(
            await current.page.locator('body').getAttribute('data-ready'),
            'true',
            await current.page.getByLabel('Result').textContent()
        );
    };
    return { ...current, open };
}

test('theme settings isolate deployments and themes and round-trip JSON through reload and removal', async (t) => {
    const { page, open } = await themeFixture(
        t,
        `
        const primary = createThemeContext(base, 'sample').settings;
        const otherTheme = createThemeContext(base, 'other').settings;
        const otherDeployment = createThemeContext(new URL('../second/', base), 'sample').settings;
        function render() {
            result.textContent = JSON.stringify([
                primary.get('layout', 'default'), otherTheme.get('layout', 'default'),
                otherDeployment.get('layout', 'default')
            ]);
        }
        document.querySelector('#save').onclick = () => {
            primary.set('layout', { compact: false, columns: 0, labels: ['ore', 'ingot'], note: null });
            otherTheme.set('layout', 'other theme');
            otherDeployment.set('layout', 'other deployment');
            render();
        };
        document.querySelector('#remove').onclick = () => { primary.remove('layout'); render(); };
        render();
    `,
        '<button id="save">Save layouts</button><button id="remove">Reset layout</button>'
    );
    await open();
    assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), ['default', 'default', 'default']);
    await page.getByRole('button', { name: 'Save layouts' }).click();
    await open();
    assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), [
        { compact: false, columns: 0, labels: ['ore', 'ingot'], note: null },
        'other theme',
        'other deployment'
    ]);
    await page.getByRole('button', { name: 'Reset layout' }).click();
    await open();
    assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), [
        'default',
        'other theme',
        'other deployment'
    ]);
});

test('theme settings recover from corrupt storage and remain usable when browser storage is blocked', async (t) => {
    for (const fault of ['corrupt', 'unavailable', 'quota']) {
        await t.test(fault, async (t) => {
            const { page, open } = await themeFixture(
                t,
                `
                const settings = createThemeContext(base, 'sample').settings;
                const render = () => { result.textContent = JSON.stringify([
                    settings.get('layout', 'default'), settings.get('unset')
                ]); };
                document.querySelector('#save').onclick = () => { settings.set('layout', false); render(); };
                document.querySelector('#remove').onclick = () => { settings.remove('layout'); render(); };
                render();
            `,
                '<button id="save">Save layout</button><button id="remove">Reset layout</button>'
            );
            await page.addInitScript((fault) => {
                if (fault === 'unavailable') {
                    Object.defineProperty(window, 'localStorage', {
                        get() {
                            throw new DOMException('Blocked', 'SecurityError');
                        }
                    });
                } else if (fault === 'quota') {
                    Storage.prototype.setItem = () => {
                        throw new DOMException('Full', 'QuotaExceededError');
                    };
                } else {
                    const getItem = Storage.prototype.getItem;
                    Storage.prototype.getItem = function (key) {
                        return getItem.call(this, key) ?? '{invalid';
                    };
                }
            }, fault);
            await open();
            assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), ['default', null]);
            await page.getByRole('button', { name: 'Save layout' }).click();
            assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), [false, null]);
            await page.getByRole('button', { name: 'Reset layout' }).click();
            assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), ['default', null]);
        });
    }
});

test('theme translations register independently and separate own and common fallbacks', async (t) => {
    const { page, open } = await themeFixture(
        t,
        `
        const own = createThemeContext(base, 'sample').i18n;
        const other = createThemeContext(base, 'other').i18n;
        const commonHome = own.forLanguage('pl').common('home');
        document.querySelector('button').onclick = () => {
            own.register({
                en: { home: 'Theme home', greeting: 'Hello {name}', units: {one: '{count} item', other: '{count} items'} },
                pl: { greeting: 'Witaj {name}', units: {one: '{count} rzecz', few: '{count} rzeczy', many: '{count} rzeczy', other: '{count} rzeczy'} }
            });
            own.register({en: {extra: 'Additional message'}});
            const polish = own.forLanguage('pl');
            const french = own.forLanguage('fr');
            result.textContent = JSON.stringify({
                ownHome: polish.t('home'), commonHome, afterRegister: polish.common('home'),
                selected: polish.t('greeting', {name: 'Ada'}), plural: polish.t('units', {count: 2}),
                englishFallback: french.t('units', {count: 0}), extra: polish.t('extra'),
                missing: polish.t('missing-key'), commonMissing: polish.common('missing-key'),
                isolated: other.forLanguage('pl').t('home'),
                commonFallback: french.common('cpuCount', {count: 0}),
                englishCommon: own.forLanguage('en').common('cpuCount', {count: 0}),
                number: own.forLanguage('en').number(1234.5)
            });
        };
    `,
        '<button>Register theme translations</button>'
    );
    await open();
    await page.getByRole('button', { name: 'Register theme translations' }).click();
    const output = JSON.parse(await page.getByLabel('Result').textContent());
    assert.equal(output.ownHome, 'Theme home');
    assert.equal(output.afterRegister, output.commonHome, 'Theme keys cannot overwrite the common namespace');
    assert.notEqual(output.afterRegister, output.ownHome);
    assert.equal(output.selected, 'Witaj Ada');
    assert.equal(output.plural, '2 rzeczy');
    assert.equal(output.englishFallback, '0 items', 'English fallback uses English plural rules');
    assert.equal(output.extra, 'Additional message');
    assert.equal(output.missing, 'missing-key');
    assert.equal(output.commonMissing, 'missing-key');
    assert.equal(output.isolated, 'home');
    assert.equal(output.commonFallback, output.englishCommon);
    assert.equal(output.number, '1,234.5');
});

test('a theme can persist its own supported language in shared preferences', async (t) => {
    const { page, open, base } = await themeFixture(
        t,
        `
        const { createPreferences } = await import('./assets/web/app/preferences.js');
        const preferences = createPreferences(base);
        const i18n = createThemeContext(base, 'french-theme').i18n;
        i18n.register({en: {welcome: 'Welcome'}, fr: {welcome: 'Bienvenue'}});
        function render() {
            const translator = i18n.forLanguage(preferences.values.language);
            result.textContent = JSON.stringify([
                translator.t('welcome'), translator.number(1234.5), translator.common('cpuCount', {count: 0})
            ]);
        }
        document.querySelector('button').onclick = () => { preferences.set('language', 'fr'); render(); };
        render();
    `,
        '<button>Use French</button>'
    );
    await open();
    const englishCommon = JSON.parse(await page.getByLabel('Result').textContent())[2];
    await page.getByRole('button', { name: 'Use French' }).click();
    await open();
    const output = JSON.parse(await page.getByLabel('Result').textContent());
    assert.equal(output[0], 'Bienvenue');
    assert.equal(output[1].replace(/\s/g, ' '), '1 234,5');
    assert.equal(output[2], englishCommon);
    await page.goto(`${base}#/web-settings`);
    const language = page.getByRole('combobox', { name: 'Language' });
    await language.waitFor();
    assert.equal(await language.inputValue(), 'fr', 'Returning to the default theme retains the shared language');
});

test('appearance, language and terminal preferences survive reload and direct links', async (t) => {
    const { page, base } = await refreshingFixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('button', { name: /^Sort by:/ }).click();
    await page.getByRole('button', { name: /^Sort order:/ }).click();
    await clickTab(page, 'Web settings');
    await page.getByRole('checkbox', { name: 'Automatic updates' }).uncheck();
    await page.getByRole('combobox', { name: 'Appearance' }).selectOption('dark');
    await page.getByRole('combobox', { name: 'Language' }).selectOption('pl');
    await page.reload();
    await page.getByRole('combobox', { name: 'Wygląd' }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Wygląd' }).inputValue(), 'dark');
    assert.equal(await page.getByRole('checkbox', { name: 'Automatyczne aktualizacje' }).isChecked(), false);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Sortuj według: Ilości', exact: true }).isVisible(), true);
    await page.getByRole('navigation').locator('a[href="#/"]').click();
    await page.getByRole('button', { name: 'Wybierz sieć', exact: true }).waitFor();
    assert.equal(
        await page.getByRole('link', { name: 'Terminal', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/items`
    );
});

test('the default theme ignores obsolete shared appearance, preserves current choices and reset, and survives a storage write failure', async (t) => {
    const { page, base, open } = await themeFixture(
        t,
        `
        const settings = createThemeContext(base, 'default').settings;
        document.querySelector('button').onclick = () => { settings.remove('appearance'); result.textContent = 'Reset'; };
    `,
        '<button>Reset default appearance</button>'
    );
    // Appearance is owned by the theme; old shared values must not affect current defaults.
    await page.addInitScript(() => {
        const key = 'ae2web:/ae2/:ui';
        const stored = JSON.parse(localStorage.getItem(key));
        if (stored.appearance === undefined)
            localStorage.setItem(key, JSON.stringify({ ...stored, appearance: 'dark', language: 'en' }));
    });
    await page.goto(`${base}#/web-settings`);
    const appearance = page.getByRole('combobox', { name: 'Appearance' });
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'system');
    await appearance.selectOption('light');
    await page.reload();
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'light');
    await open();
    await page.getByRole('button', { name: 'Reset default appearance' }).click();
    await page.goto(`${base}#/web-settings`);
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'system', 'Reset must not adopt the obsolete shared appearance');
    // A failing storage write must keep the current persisted choice without adopting the shared appearance.
    await page.addInitScript(() => {
        if (sessionStorage.getItem('storageFailureSeeded')) return;
        sessionStorage.setItem('storageFailureSeeded', 'true');
        localStorage.setItem('ae2web:/ae2/:ui', JSON.stringify({ appearance: 'dark', language: 'en' }));
        localStorage.setItem('ae2web:/ae2/:theme:default', JSON.stringify({ appearance: 'light' }));
        Storage.prototype.setItem = function () {
            throw new DOMException('Full', 'QuotaExceededError');
        };
    });
    await page.reload();
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'light');
    await appearance.selectOption('system');
    assert.equal(await appearance.inputValue(), 'system');
    await page.reload();
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'light', 'Failed writes must preserve the current persisted choice');
});

test('lost access clears resource data and subsequent polling recovers', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    options.status = 403;
    await poll(page);
    await page.locator('#item-message').getByText('You no longer have access to this network.').waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('heading', { name: 'Iron Ingot' }).count(), 0);
    options.status = 200;
    await poll(page);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
});

test('hovered resource tooltip stays current through polling and closes when the resource disappears', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await page.getByRole('button', { name: /^Sort by:/ }).click();
    await page.getByRole('button', { name: /^Sort order:/ }).click();
    await item.hover();
    await tooltip.waitFor({ state: 'visible' });
    const updatedTooltip = page.evaluate(
        () =>
            new Promise((resolve) => {
                const observer = new MutationObserver(() => {
                    if (
                        ![...document.querySelectorAll('button')].some((button) =>
                            /Iron Ingot.*8,765/.test(button.textContent)
                        )
                    )
                        return;
                    const tooltip = [...document.querySelectorAll('[role="tooltip"]')].find((node) =>
                        node.checkVisibility()
                    );
                    observer.disconnect();
                    resolve({ visible: !!tooltip, content: tooltip?.textContent || '' });
                });
                observer.observe(document.body, {
                    childList: true,
                    subtree: true,
                    characterData: true,
                    attributes: true
                });
            })
    );
    options.itemsA = [{ ...iron, quantity: 8765 }, quartz];
    await poll(page);
    const refreshed = await updatedTooltip;
    assert.equal(refreshed.visible, true, 'Polling must not dismiss a stationary pointer tooltip');
    assert.match(refreshed.content, /8,765/);
    options.itemsA = [{ ...iron, quantity: 1 }, quartz];
    await poll(page);
    await page.getByRole('button', { name: /Iron Ingot.*\b1\b/ }).waitFor();
    assert.ok(
        !(await tooltip.isVisible()) || !(await tooltip.textContent()).includes('Iron Ingot'),
        'A reordered resource must not retain its tooltip under the pointer at its old position'
    );
    await item.hover();
    await tooltip.waitFor({ state: 'visible' });
    options.itemsA = [quartz];
    await poll(page);
    await item.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false, 'A removed resource must not leave its tooltip behind');
});

test('resource tooltips appear on the first hover frame and respect keyboard dismissal across refresh', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await item.waitFor();
    const visibleOnHover = item.evaluate(
        (button) =>
            new Promise((resolve) => {
                button.addEventListener(
                    'pointerenter',
                    () =>
                        requestAnimationFrame(() => {
                            resolve(
                                [...document.querySelectorAll('[role="tooltip"]')].some(
                                    (node) => node.checkVisibility() && node.textContent.includes('Iron Ingot')
                                )
                            );
                        }),
                    { once: true }
                );
            })
    );
    await item.hover();
    assert.equal(await visibleOnHover, true, 'An item tooltip should be visible on the first hover frame');
    await page.mouse.move(0, 0);
    await item.focus();
    await tooltip.waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    options.itemsA = [{ ...iron, displayName: 'A Iron Ingot', quantity: 3456 }, quartz];
    await poll(page);
    await page.getByRole('button', { name: /Iron Ingot.*3,456/ }).waitFor();
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), false, 'Reordering must not reopen a dismissed tooltip');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Shift+Tab');
    await tooltip.waitFor({ state: 'visible' });
    assert.match(await tooltip.textContent(), /3,456/);
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    await item.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false, 'Access loss must remove private tooltip contents');
});

test('hovered tool keeps its tooltip when a keyboard-focused resource refreshes', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    const tool = page.getByRole('button', { name: /^Sort by:/ });
    await item.focus();
    await tool.hover();
    const label = await tooltip.textContent();
    assert.ok(label.includes(await tool.getAttribute('aria-label')));
    options.itemsA = [{ ...iron, quantity: 2345 }, quartz];
    await poll(page);
    await page.getByRole('button', { name: /Iron Ingot.*2,345/ }).waitFor();
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), true);
    assert.equal(
        await tooltip.textContent(),
        label,
        'Polling must not replace the hovered tool tooltip with resource details'
    );
    options.itemsA = [{ ...iron, displayName: 'A Iron Ingot', quantity: 1234 }, quartz];
    await poll(page);
    await page.getByRole('button', { name: /Iron Ingot.*1,234/ }).waitFor();
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), true);
    assert.equal(
        await tooltip.textContent(),
        label,
        'Restoring focus after reordering must not replace the hovered tooltip'
    );
});

test('empty discovery and unavailable bookmarked networks stay usable', async (t) => {
    const { page, options, base } = await fixture(t);
    options.empty = true;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByText('This network is not available.').waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    await clickTab(page, 'Home');
    await page
        .getByRole('status')
        .filter({ hasText: /No accessible networks/ })
        .waitFor();
    options.empty = false;
    // Automatic Home refresh skips network discovery by design; a manual refresh performs it.
    const discovered = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await (await discovered).finished();
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
});

test('home network links keep keyboard focus when a manual refresh updates the list', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    const network = page.getByRole('link', { name: new RegExp(gridA) });
    await network.focus();
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).dispatchEvent('click');
    await (await refreshed).finished();
    await settleFrames(page);
    assert.equal(await network.evaluate((link) => link === document.activeElement), true);
    const secondNetwork = page.getByRole('link', { name: new RegExp(gridB) });
    await secondNetwork.focus();
    options.reverseGrids = true;
    const reordered = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).dispatchEvent('click');
    await (await reordered).finished();
    await settleFrames(page);
    assert.equal(await secondNetwork.evaluate((link) => link === document.activeElement), true);
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
});

test('resource grid stays centered and stationary when scrolling becomes unnecessary', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = Array.from({ length: 90 }, (_, index) => ({
        ...iron,
        displayName: `Resource ${String(index).padStart(3, '0')}`,
        itemKey: `key-${index}`
    }));
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const resources = page.getByRole('region', { name: 'Resources', exact: true });
    const first = resources.getByRole('button', { name: /Resource 000/ });
    await first.waitFor();
    const before = await first.boundingBox();
    assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), true);
    const bounds = await resources.boundingBox();
    const grid = await resources.getByRole('list').boundingBox();
    assert.ok(
        Math.abs(grid.x + grid.width / 2 - (bounds.x + bounds.width / 2)) < 1,
        'The resource grid must be centered independently of its scrollbar'
    );
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('Resource 000');
    assert.equal(await resources.getByRole('button').count(), 1);
    assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), false);
    const filledGrid = await resources.getByRole('list').boundingBox();
    assert.ok(
        Math.abs(filledGrid.height - (await resources.evaluate((node) => node.clientHeight))) < 1,
        'The empty grid continues to the bottom of the viewport without adding scrolling'
    );
    const filtered = await first.boundingBox();
    assert.equal(filtered.x, before.x);
    assert.equal(filtered.width, before.width);
    const populatedViewport = await resources.boundingBox();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('no matching resources');
    assert.equal(await resources.getByRole('button').count(), 0);
    assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), false);
    assert.deepEqual(await resources.boundingBox(), populatedViewport, 'No matches must not move or shrink the grid');
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('');
    const restored = await first.boundingBox();
    assert.equal(restored.x, before.x);
    assert.equal(restored.width, before.width);
});

test('terminal scrolls resources inside the viewport while search and navigation remain reachable', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedTerminalPageSize(page, base, 100);
    options.itemsA = Array.from({ length: 105 }, (_, index) => ({
        ...iron,
        displayName: `Resource ${String(index).padStart(3, '0')}`,
        itemKey: `key-${index}`
    }));
    for (const viewport of [
        { width: 1280, height: 800 },
        { width: 390, height: 844 },
        { width: 844, height: 390 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Resource 000/ }).waitFor();
        const resources = page.getByRole('region', { name: 'Resources', exact: true });
        await resources.waitFor();
        const search = page.getByRole('searchbox', { name: 'Search resources' });
        const before = await search.boundingBox();
        const grid = await resources.getByRole('list').boundingBox();
        const heading = await page.getByRole('heading', { name: 'Terminal', exact: true }).boundingBox();
        assert.ok(Math.abs(heading.x - grid.x) < 1, 'Terminal title aligns with the first slot');
        assert.ok(Math.abs(before.x + before.width - grid.x - grid.width) < 1, 'Search aligns with the last slot');
        await resources.focus();
        await page.keyboard.press('End');
        const last = resources.getByRole('button').last();
        await page.waitForFunction(
            ([region, item]) => {
                const bounds = region.getBoundingClientRect();
                const target = item.getBoundingClientRect();
                return bounds.height > 0 && target.bottom > bounds.top && target.bottom <= bounds.bottom + 1;
            },
            [await resources.elementHandle(), await last.elementHandle()]
        );
        assert.deepEqual(await search.boundingBox(), before, 'Scrolling items must not move the search field');
        const documentSize = await page.evaluate(() => ({
            width: document.documentElement.scrollWidth,
            height: document.documentElement.scrollHeight,
            top: window.scrollY,
            left: window.scrollX
        }));
        assert.ok(documentSize.width <= viewport.width, 'The page must fit the viewport horizontally');
        assert.ok(documentSize.height <= viewport.height, 'The page must fit the viewport vertically');
        assert.equal(documentSize.top, 0);
        assert.equal(documentSize.left, 0);
        const next = page.getByRole('button', { name: 'Next page' });
        const nextBounds = await next.boundingBox();
        assert.ok(nextBounds.y >= 0 && nextBounds.y + nextBounds.height <= viewport.height);
        await next.click();
        await page.getByRole('button', { name: /Resource 104/ }).waitFor();
        await page.getByRole('button', { name: 'Previous page' }).click();
    }
});

for (const width of [390, 1280]) {
    test(`terminal page size persists and all mode follows filtering and live updates at ${width}px`, async (t) => {
        const { page, options, base } = await refreshingFixture(t, '', { viewport: { width, height: 844 } });
        options.liveView = { generation: '1', revision: '1' };
        options.itemsA = Array.from({ length: 275 }, (_, index) => ({
            ...iron,
            itemKey: `resource-${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`,
            quantity: 275 - index
        }));
        if (width === 390) await seedTerminalPageSize(page, base, 37);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 275);
        await clickTab(page, 'Web settings');
        const pageSize = page.getByRole('combobox', { name: 'Resources per page', exact: true });
        await pageSize.waitFor();
        assert.equal(await pageSize.inputValue(), '500');
        await pageSize.selectOption('50');
        await page.reload();
        assert.equal(await pageSize.inputValue(), '50');
        await clickTab(page, 'Terminal');
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 50);
        const reads = options.requests.filter((request) => request.path.endsWith('/items')).length;
        for (let index = 0; index < 5; index++)
            await page.getByRole('button', { name: 'Next page', exact: true }).click();
        assert.equal(await page.locator('#items button').count(), 25);
        assert.equal(await page.locator('#page-count').innerText(), '6 / 6');
        const removed = options.itemsA.splice(250).map((item) => item.itemKey);
        options.itemStreams
            .at(-1)
            .write(
                `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed, icons: null })}\n\n`
            );
        await page.locator('#items button').first().getByText('0', { exact: true }).waitFor();
        assert.equal(await page.locator('#page-count').innerText(), '6 / 6');
        await page.locator('#items button').first().focus();
        await page.getByRole('tooltip').getByText('Stored: 0', { exact: true }).waitFor();
        await page.clock.fastForward(30000);
        await page.waitForFunction(() => document.querySelector('#page-count')?.textContent === '5 / 5');
        assert.equal(await page.getByRole('tooltip').isVisible(), false);
        assert.equal(await page.locator('#items button').count(), 50);
        assert.equal(await page.getByRole('button', { name: 'Next page', exact: true }).isDisabled(), true);
        await page.getByRole('searchbox', { name: 'Search resources', exact: true }).fill('Resource 000');
        assert.equal(await page.locator('#items button').count(), 1);
        assert.equal(await page.locator('#pages').isVisible(), false);
        await page.getByRole('searchbox', { name: 'Search resources', exact: true }).fill('');
        assert.equal(await page.locator('#page-count').innerText(), '1 / 5');
        assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, reads);
        await clickTab(page, 'Web settings');
        await pageSize.selectOption('all');
        await clickTab(page, 'Terminal');
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 250);
        assert.equal(await page.locator('#pages').isVisible(), false);
        await openMobilePane(page, 'Filters and sorting');
        await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
        await closeMobilePane(page);
        assert.ok((await page.locator('#items button').first().innerText()).includes('Resource 249'));
        const newItem = { ...iron, itemKey: 'new-live-resource', displayName: 'New resource', quantity: 999 };
        options.itemsA.push(newItem);
        options.itemStreams
            .at(-1)
            .write(
                `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [newItem], removed: [], icons: null })}\n\n`
            );
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 251);
        assert.equal(await page.locator('#pages').isVisible(), false);
        await page.reload();
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 251);
        assert.equal(await page.locator('#pages').isVisible(), false);
        const documentWidth = await page.evaluate(() => document.documentElement.scrollWidth);
        assert.ok(documentWidth <= width, 'Page-size changes do not introduce horizontal overflow');
        await clickTab(page, 'Web settings');
        assert.equal(await pageSize.inputValue(), 'all');
        await pageSize.selectOption('100');
        await clickTab(page, 'Terminal');
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 100);
        assert.equal(await page.locator('#page-count').innerText(), '1 / 3');
    });
}

test('resource details are text, keyboard accessible and paging does not require new requests', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedTerminalPageSize(page, base, 100);
    const untrustedName = '<img src=x onerror=alert(1)> Quartz';
    options.itemsA = [
        { ...quartz, displayName: untrustedName },
        ...Array.from({ length: 105 }, (_, index) => ({
            ...iron,
            displayName: `Resource ${String(index).padStart(3, '0')}`,
            itemKey: `key-${index}`
        }))
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: untrustedName + ' 42' });
    await item.focus();
    await page.getByRole('tooltip').waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await page.getByRole('tooltip').waitFor({ state: 'hidden' });
    await item.click();
    await page.getByRole('heading', { name: untrustedName, exact: true }).waitFor();
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    await page.getByRole('button', { name: 'Next page' }).click();
    await page.getByRole('button', { name: /Resource 104/ }).waitFor();
    await page.getByRole('button', { name: 'Previous page' }).click();
    assert.equal(await item.getAttribute('aria-pressed'), 'true');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
});

// HTTP/bootstrap and visible controls are the seam: no terminal requests before runtime identity is known.
test('bootstrap failure keeps the terminal unmounted and retry loads current server metadata', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.bootstrapStatus = 503;
    await page.goto(`${base}#/about`);
    await page.getByRole('button', { name: 'Try again', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Choose a network', exact: true }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.path === '/api/grids'),
        false
    );
    options.bootstrapStatus = 200;
    options.username = 'AfterRetry';
    options.modVersion = 'server-after-retry';
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await page.getByText('AfterRetry', { exact: true }).waitFor();
    await page.getByText(/Version: server-after-retry/).waitFor();
    assert.equal(new URL(page.url()).hash, '#/about');
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 2);
});

test('login waits for bootstrap and offers private mode only after retry succeeds', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.loggedOut = true;
    options.bootstrapStatus = 503;
    options.publicMode = false;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: 'Retry', exact: true }).waitFor();
    assert.equal(await page.getByLabel('Username', { exact: true }).isVisible(), false);
    assert.equal(await page.getByPlaceholder('Enter password', { exact: true }).first().isVisible(), false);
    options.bootstrapStatus = 200;
    await page.getByRole('button', { name: 'Retry', exact: true }).click();
    await page.getByPlaceholder('Enter password', { exact: true }).first().waitFor();
    assert.equal(await page.getByLabel('Username', { exact: true }).inputValue(), 'Admin');
    assert.equal(await page.getByLabel('Username', { exact: true }).getAttribute('readonly'), '');
    assert.equal(await page.getByRole('button', { name: 'Continue', exact: true }).isVisible(), false);
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
});

test('login bootstrap keeps public forms hidden until the server mode arrives', async (t) => {
    const { page, options, base } = await fixture(t);
    options.loggedOut = true;
    let release;
    const held = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/context', async (route) => {
        await held;
        await route.continue();
    });
    await page.goto(base);
    assert.equal(await page.getByLabel('Username', { exact: true }).isVisible(), false);
    assert.equal(await page.getByRole('button', { name: 'Continue', exact: true }).isVisible(), false);
    release();
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(await page.getByLabel('Username', { exact: true }).getAttribute('readonly'), null);
    assert.equal(await page.getByRole('button', { name: 'Continue', exact: true }).isVisible(), false);
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByRole('button', { name: 'Continue', exact: true }).waitFor();
});

test('a session lost before bootstrap returns to login with the destination intact', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.route('**/api/context', async (route) => {
        options.loggedOut = true;
        await route.continue();
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    assert.equal(
        options.requests.some((request) => request.path === '/api/grids'),
        false
    );
});

// Public rendered geometry: empty inventory cells follow the occupied grid, without becoming resources.
test('empty slot frames share occupied geometry without phantom resources at DPR 1.5', async (t) => {
    // Native animation frames must follow ResizeObserver delivery in this layout test.
    const { page, options, base } = await fixture(t, '', { deviceScaleFactor: 1.5 }, { mockClock: false });
    await atlasFixture(page, options);
    options.itemsA = [iron, quartz, { ...iron, itemKey: 'gold', displayName: 'Gold Ingot' }];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const grid = page.locator('#items');
    await grid.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.evaluate(() => document.fonts.ready);
    for (const display of ['both', 'icons', 'names']) {
        await clickTab(page, 'Web settings');
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption(display);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await grid.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        for (const width of [997, 1031, 391]) {
            await page.setViewportSize({ width, height: 844 });
            await settleFrames(page);
            const geometry = await grid.evaluate((list) => {
                const bounds = list.getBoundingClientRect();
                const viewport = list.parentElement;
                const cells = [...list.children].map((cell) => {
                    const control = cell.querySelector('button');
                    const frame = control || cell;
                    const rect = frame.getBoundingClientRect();
                    const style = getComputedStyle(frame);
                    return {
                        x: rect.x - bounds.x,
                        y: rect.y - bounds.y,
                        width: rect.width,
                        height: rect.height,
                        real: !!control,
                        hidden: cell.getAttribute('aria-hidden'),
                        edges: [
                            style.borderTopWidth,
                            style.borderRightWidth,
                            style.borderBottomWidth,
                            style.borderLeftWidth,
                            style.borderTopColor,
                            style.borderRightColor,
                            style.borderBottomColor,
                            style.borderLeftColor
                        ]
                    };
                });
                return {
                    cells,
                    width: bounds.width,
                    viewport: viewport.getBoundingClientRect().height,
                    content: viewport.scrollHeight
                };
            });
            const real = geometry.cells.filter((cell) => cell.real);
            const empty = geometry.cells.filter((cell) => !cell.real);
            assert.equal(real.length, 3, 'decorative cells do not add resources');
            assert.ok(empty.length > 0, 'the unoccupied viewport contains real decorative frames');
            for (const cell of empty) {
                assert.equal(cell.hidden, 'true', 'empty cells are excluded from accessibility');
                assert.deepEqual(cell.edges, real[0].edges, 'empty and occupied frames use the same borders');
            }
            for (const cell of geometry.cells) {
                const row = geometry.cells.find((candidate) => Math.abs(candidate.y - cell.y) < 0.03);
                assert.ok(
                    Math.abs(cell.y + cell.height - row.y - row.height) < 0.03,
                    'occupied and decorative frames in a row share their bottom edge'
                );
            }
            const firstRow = geometry.cells.filter((cell) => Math.abs(cell.y) < 0.03);
            for (let index = 1; index < firstRow.length; index++) {
                assert.ok(
                    Math.abs(firstRow[index].x - firstRow[index - 1].x - firstRow[index - 1].width) < 0.03,
                    'adjacent real and empty cells meet without gaps or overlap'
                );
            }
            assert.ok(
                Math.abs(firstRow.at(-1).x + firstRow.at(-1).width - geometry.width) < 0.03,
                'decorative frames follow the same final column edge'
            );
            const last = geometry.cells.at(-1);
            assert.ok(
                last.y + last.height <= geometry.viewport + 0.1,
                'only complete empty rows fit inside the viewport'
            );
            assert.equal(await grid.getByRole('button').count(), 3);
            assert.equal(await grid.getByRole('listitem').count(), 3);
            const stable = await grid.evaluate((list) => ({
                count: list.children.length,
                height: list.parentElement.scrollHeight
            }));
            await settleFrames(page);
            assert.deepEqual(
                await grid.evaluate((list) => ({
                    count: list.children.length,
                    height: list.parentElement.scrollHeight
                })),
                stable,
                'fillers cannot increase their own required row count'
            );
        }
    }
});

test('empty slots remain decorative through selection, zero matches and CPU row-height changes', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const grid = page.locator('#items');
    const ironButton = grid.getByRole('button', { name: /Iron Ingot/ });
    await ironButton.click();
    await page.getByRole('heading', { name: 'Iron Ingot', exact: true }).waitFor();
    const empty = grid.locator('li[aria-hidden="true"]').first();
    await empty.waitFor({ state: 'visible' });
    const bounds = await empty.boundingBox();
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    assert.equal(await ironButton.getAttribute('aria-pressed'), 'true', 'empty cells cannot change selection');
    assert.equal(await page.getByRole('tooltip').isVisible(), false, 'empty cells do not create tooltips');
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    await search.fill('no matches');
    assert.equal(await grid.getByRole('button').count(), 0);
    await page.setViewportSize({ width: 1031, height: 844 });
    await settleFrames(page);
    assert.ok((await grid.locator('li[aria-hidden="true"]').count()) > 0, 'an empty result still has decorative rows');
    assert.equal(await grid.getByRole('listitem').count(), 0);
    assert.equal(
        await grid.evaluate((list) => list.parentElement.scrollHeight > list.parentElement.clientHeight),
        false
    );
    await search.fill('');
    await ironButton.waitFor();
    assert.equal(await grid.getByRole('button').count(), 2);

    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuResources = page.getByRole('region', { name: 'CPU resources', exact: true });
    const cpuButton = cpuResources.getByRole('button', { name: /Iron Ingot/ });
    await cpuButton.waitFor();
    const trackedHeight = (await cpuButton.boundingBox()).height;
    await cpuResources.locator('li[aria-hidden="true"]').first().waitFor({ state: 'visible' });
    const cpuGeometry = () =>
        cpuResources.evaluate((region) => {
            const cells = [...region.querySelector('ul').children];
            const control = cells[0].querySelector('button');
            return {
                real: control.getBoundingClientRect().height,
                empty: cells
                    .filter((cell) => cell.getAttribute('aria-hidden') === 'true')
                    .map((cell) => cell.getBoundingClientRect().height)
            };
        });
    let measured = await cpuGeometry();
    assert.ok(measured.empty.length > 0, 'The sparse CPU grid must contain decorative slots');
    assert.ok(measured.empty.every((height) => Math.abs(height - measured.real) < 0.03));
    options.cpuDetails['cpu-a'] = { ...cpuWork, hasTrackingInfo: false };
    const updated = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await updated).request());
    measured = await cpuGeometry();
    assert.ok(measured.real < trackedHeight, 'untracked CPU rows preserve their more compact layout');
    assert.ok(measured.empty.length > 0, 'Changing row height must retain decorative slots');
    assert.ok(measured.empty.every((height) => Math.abs(height - measured.real) < 0.03));
    assert.equal(await cpuResources.getByRole('button').count(), 1);
    await clickTab(page, 'Web settings');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await ironButton.waitFor();
    assert.equal(await grid.getByRole('button').count(), 2, 'returning to a hidden grid does not duplicate resources');
});

test('CPU overview keeps names primary and exposes identity without extra detail reads', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus = {
        'cpu-a': {
            ...cpu,
            name: '§aAssembler',
            isBusy: true,
            supportsPause: true,
            usedStorage: 2048,
            finalOutput: { ...iron, quantity: 12 }
        },
        'cpu-b': { ...cpu, name: '§aAssembler', isBusy: true, isPaused: true, supportsPause: true, usedStorage: -1 },
        'cpu-unnamed': { ...cpu, name: '' }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const first = page.getByRole('link', { name: /Assembler.*cpu-a/ });
    await first.waitFor();
    assert.equal((await first.textContent()).includes('cpu-a'), false, 'opaque identity is not the visible CPU title');
    const row = page.getByRole('listitem').filter({ has: first });
    await row.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.match(await row.textContent(), /2 KB/);
    assert.match(await row.textContent(), /8 KB/);
    await row.getByText('CPU identifier', { exact: true }).click();
    await row.getByText('cpu-a', { exact: true }).waitFor({ state: 'visible' });
    const unnamed = page.locator(`a[href$="/cpus/cpu-unnamed"]`);
    assert.ok((await unnamed.textContent()).trim().length > 0, 'unnamed CPUs have a visible fallback');
    assert.equal((await unnamed.textContent()).includes('cpu-unnamed'), false);
    assert.equal(
        options.requests.some((request) => /\/cpus\/[^/]+$/.test(request.path)),
        false
    );
    await first.focus();
    options.cpus = {
        'cpu-unnamed': options.cpus['cpu-unnamed'],
        'cpu-b': options.cpus['cpu-b'],
        'cpu-a': { ...options.cpus['cpu-a'], usedStorage: 3072 }
    };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.equal(await first.evaluate((link) => link === document.activeElement), true);
    await page.keyboard.press('Enter');
    await page.getByRole('region', { name: 'CPU resources', exact: true }).waitFor();
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/cpus/cpu-a`);
});

test('CPU overview opens from product, metrics and summary padding while identifier and actions stay independent', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true, finalOutput: { ...iron, quantity: 12 } }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const link = page.getByRole('link', { name: /Assembler.*cpu-a/ });
    const card = page.getByRole('listitem').filter({ has: link });
    await link.waitFor();
    for (const open of [
        () => card.getByText('Iron Ingot', { exact: true }).click(),
        () => card.getByText('8 KB', { exact: true }).click(),
        () => card.click({ position: { x: 6, y: 6 } })
    ]) {
        await open();
        await page.waitForURL(`**#/grids/${gridA}/cpus/cpu-a`);
        await page.getByRole('region', { name: 'CPU resources', exact: true }).waitFor();
        await clickTab(page, 'CPUs');
        await link.waitFor();
    }
    await card.getByText('CPU identifier', { exact: true }).click();
    await card.getByText('cpu-a', { exact: true }).waitFor({ state: 'visible' });
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/cpus`);
    await card.getByRole('button', { name: /Pause.*cpu-a/ }).click();
    await card.getByRole('button', { name: /Resume.*cpu-a/ }).waitFor();
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/cpus`);
});

test('CPU overview panels keep long names and uncertain controls reachable in dark mode and Polish', async (t) => {
    const { page, options, base } = await fixture(t);
    const appearance = 'dark';
    options.cpus = {
        'cpu-a': {
            ...cpu,
            name: '§bA long assembler name that must wrap inside its own panel',
            isBusy: true,
            supportsPause: true,
            usedStorage: 1024,
            finalOutput: { ...iron, quantity: 123456 }
        },
        'cpu-b': { ...cpu, name: '§a   ', isBusy: true, supportsPause: true, isPaused: true, usedStorage: -1 },
        unsupported: { ...cpu, name: 'Assembler', isBusy: true, finalOutput: quartz },
        idle: { ...cpu, name: 'Assembler' }
    };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const link = page.locator('a[href$="/cpus/cpu-a"]');
    await link.waitFor();
    const card = page.getByRole('listitem').filter({ has: link });
    for (const viewport of [
        { width: 1280, height: 800 },
        { width: 390, height: 844 }
    ]) {
        await page.setViewportSize(viewport);
        for (const row of await page
            .getByRole('listitem')
            .filter({ has: page.locator('a[href*="/cpus/"]') })
            .all()) {
            const geometry = await row.evaluate((node) => ({
                width: node.clientWidth,
                content: node.scrollWidth,
                height: node.clientHeight,
                contentHeight: node.scrollHeight
            }));
            assert.ok(geometry.content <= geometry.width + 1, 'card content does not scroll horizontally');
            assert.ok(
                geometry.contentHeight <= geometry.height + 1,
                'cards grow instead of introducing internal scroll areas'
            );
            for (const button of await row.getByRole('button').all()) {
                await button.scrollIntoViewIfNeeded();
                const bounds = await button.boundingBox();
                assert.ok(
                    bounds.x >= 0 && bounds.x + bounds.width <= viewport.width,
                    'each work control stays reachable'
                );
            }
        }
    }
    options.fault = { operation: 'pause', status: 'NETWORK_ERROR' };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await card.getByRole('button', { name: /(?:Pause|Wstrzymaj).*cpu-a/ }).click();
    await settleResponse(page, (await refreshed).request());
    await card
        .getByRole('status')
        .filter({ hasText: /unknown|nieznany/ })
        .waitFor({ state: 'visible' });
    for (const button of await card.getByRole('button').all()) assert.equal(await button.isDisabled(), true);
    const after = await card.evaluate((node) => ({ height: node.clientHeight, content: node.scrollHeight }));
    assert.ok(after.content <= after.height + 1, 'an uncertainty notice expands the panel without clipping');
    await link.scrollIntoViewIfNeeded();
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({
            path: path.join(process.env.UI_SCREENSHOT_DIR, `cpu-overview-mobile-${appearance}.png`)
        });
        await card.screenshot({
            path: path.join(process.env.UI_SCREENSHOT_DIR, `cpu-overview-card-${appearance}.png`)
        });
    }
    assert.equal(
        options.requests.some((request) => /\/cpus\/[^/]+$/.test(request.path)),
        false
    );
});

// Public geometry seam: the CPU overview shares terminal alignment and keeps scrolling inside its viewport.
test('CPU overview aligns with the terminal and scrolls only real processor panels', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const many = Object.fromEntries(
        Array.from({ length: 18 }, (_, index) => [`cpu-${index}`, { ...cpu, name: `Processor ${index}` }])
    );
    const measure = async (locator) =>
        locator.evaluate((node) => {
            const rect = node.getBoundingClientRect();
            const style = getComputedStyle(node);
            return {
                x: rect.x,
                y: innerWidth <= 600 ? rect.y - node.closest('#window').getBoundingClientRect().y : rect.y,
                height: rect.height,
                right: rect.right,
                fontSize: style.fontSize,
                fontWeight: style.fontWeight,
                fontFamily: style.fontFamily,
                color: style.color
            };
        });
    for (const viewport of [
        { width: 1184, height: 900 },
        { width: 390, height: 844 },
        { width: 1184, height: 540 },
        { width: 390, height: 540 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const heading = await measure(page.getByRole('heading', { name: 'Terminal', exact: true }));
        const resources = await measure(page.getByRole('region', { name: 'Resources', exact: true }));
        const items = await measure(page.locator('#items'));
        options.cpus = many;
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Processor 0 .*cpu-0/ }).waitFor();
        const cpuHeading = page.getByRole('heading', { name: 'CPUs', exact: true });
        const actual = await measure(cpuHeading);
        for (const key of viewport.width > 600 ? ['x', 'y'] : ['x'])
            assert.ok(
                Math.abs(actual[key] - heading[key]) < 0.1,
                `${key} heading position matches at ${viewport.width}x${viewport.height}`
            );
        for (const key of ['fontSize', 'fontWeight', 'fontFamily', 'color']) assert.equal(actual[key], heading[key]);
        assert.equal(await page.getByRole('link', { name: 'Back to resources', exact: true }).count(), 0);
        const region = page.getByRole('region', { name: 'CPUs', exact: true });
        const bounds = await measure(region);
        for (const key of viewport.width > 600 ? ['x', 'y', 'right'] : ['x', 'right'])
            assert.ok(Math.abs(bounds[key] - resources[key]) < 0.1, `${key} scroll viewport matches`);
        assert.ok(actual.y >= 0 && bounds.y >= actual.y + actual.height, 'CPU list stays below its heading');
        const cards = region.getByRole('listitem');
        assert.equal(await cards.count(), 18);
        const first = await measure(cards.first());
        assert.ok(Math.abs(first.x - items.x) < 0.1, 'left card edge matches items');
        const right = await cards.evaluateAll((nodes) =>
            Math.max(...nodes.map((node) => node.getBoundingClientRect().right))
        );
        assert.ok(Math.abs(right - items.right) < 0.1, 'right card edge matches items');
        await cards.last().getByRole('link').scrollIntoViewIfNeeded();
        assert.ok(await region.evaluate((node) => node.scrollTop > 0));
        assert.deepEqual(await measure(cpuHeading), actual, 'heading stays fixed while cards scroll');
        assert.deepEqual(await page.evaluate(() => ({ x: scrollX, y: scrollY })), { x: 0, y: 0 });
        assert.ok(
            await page.evaluate(
                () =>
                    document.documentElement.scrollWidth <= innerWidth &&
                    document.documentElement.scrollHeight <= innerHeight
            )
        );
        options.cpus = { 'cpu-0': many['cpu-0'] };
        const updated = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
        await poll(page);
        await settleResponse(page, (await updated).request());
        assert.equal(await cards.count(), 1, 'sparse overview has no decorative CPU placeholders');
    }
});

// Public geometry seam: the selected CPU keeps the same terminal header and resource viewport.
test('selected CPU aligns its heading search and resource viewport with the main terminal', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true, supportsPause: true } };
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        supportsPause: true,
        items: Array.from({ length: 80 }, (_, index) => ({
            ...cpuWork.items[0],
            registryNamespace: 'test',
            registryPath: `part_${index}`,
            displayName: `Part ${String(index).padStart(3, '0')}`
        }))
    };
    const measure = (locator) =>
        locator.evaluate((node) => {
            const r = node.getBoundingClientRect(),
                s = getComputedStyle(node);
            return {
                x: r.x,
                y: r.y,
                right: r.right,
                height: r.height,
                fontSize: s.fontSize,
                fontWeight: s.fontWeight,
                fontFamily: s.fontFamily,
                color: s.color
            };
        });
    for (const viewport of [
        { width: 1184, height: 900 },
        { width: 390, height: 844 },
        { width: 1184, height: 540 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const normalHeading = await measure(page.getByRole('heading', { name: 'Terminal', exact: true }));
        const normalSearch = await measure(page.getByRole('searchbox', { name: 'Search resources', exact: true }));
        const normalScroll = await measure(page.getByRole('region', { name: 'Resources', exact: true }));
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const region = page.getByRole('region', { name: 'CPU resources', exact: true });
        await region.getByRole('button', { name: /Part 000/ }).waitFor();
        const heading = page.getByRole('heading', { name: 'Assembler', level: 2, exact: true });
        const actualHeading = await measure(heading);
        for (const key of ['x', 'y'])
            assert.ok(Math.abs(actualHeading[key] - normalHeading[key]) < 0.1, `${key} heading matches`);
        for (const key of ['fontSize', 'fontWeight', 'fontFamily', 'color'])
            assert.equal(actualHeading[key], normalHeading[key]);
        const search = page.getByRole('searchbox', { name: 'Search CPU resources', exact: true });
        const actualSearch = await measure(search);
        for (const key of ['y', 'right', 'height'])
            assert.ok(Math.abs(actualSearch[key] - normalSearch[key]) < 0.1, `${key} search matches`);
        const actualScroll = await measure(region);
        for (const key of ['x', 'y', 'right'])
            assert.ok(Math.abs(actualScroll[key] - normalScroll[key]) < 0.1, `${key} resources match`);
        assert.equal(await page.getByRole('link', { name: 'Back to CPUs', exact: true }).count(), 0);
        await region.getByRole('button').last().scrollIntoViewIfNeeded();
        assert.ok(await region.evaluate((node) => node.scrollTop > 0));
        assert.deepEqual(await measure(heading), actualHeading);
        assert.deepEqual(await measure(search), actualSearch);
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
    }
    options.cpus['cpu-a'].name = 'A very long processor name that must not push the search outside the terminal';
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const longHeading = page.getByRole('heading', { name: options.cpus['cpu-a'].name, level: 2, exact: true });
    await longHeading.waitFor();
    const search = page.getByRole('searchbox', { name: 'Search CPU resources', exact: true });
    await search.fill('Part 000');
    assert.equal(await page.getByRole('region', { name: 'CPU resources', exact: true }).getByRole('button').count(), 1);
    const nameBox = await measure(longHeading),
        searchBox = await measure(search);
    assert.ok(nameBox.y + nameBox.height <= searchBox.y, 'search stays below the long title on mobile');
    const toolsBox = await page.getByRole('button', { name: 'Filters and sorting', exact: true }).boundingBox();
    assert.ok(nameBox.right <= toolsBox.x, 'long title does not overlap the mobile panel buttons');
    assert.ok(searchBox.right - searchBox.x >= 80, 'search remains usable on mobile');
    assert.ok(
        await page.evaluate(
            () =>
                document.documentElement.scrollWidth <= innerWidth &&
                document.documentElement.scrollHeight <= innerHeight
        )
    );
});

// Home discovery must not enumerate crafting CPUs until the user opens a network's CPU view, and refreshing
// discovery or opening the chooser must never fan out to per-network CPU reads.
test('Home discovery, manual automatic and icon preference refreshes do not request CPU lists', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
    await settleFrames(page);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    options.requests.length = 0;
    const manual = page.waitForResponse((response) => response.url().endsWith('/api/grids'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await settleResponse(page, (await manual).request());
    await settleResponse(page, await poll(page));
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridB) })
        .waitFor();
    await settleResponse(page, await poll(page));
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    await page.keyboard.press('Escape');
    for (const preference of ['both', 'names']) {
        await clickTab(page, 'Web settings');
        const display = page.getByRole('combobox', { name: 'Terminal display', exact: true });
        await page.waitForFunction(
            (select) => !select.querySelector('option[value="both"]').disabled,
            await display.elementHandle()
        );
        await display.selectOption(preference);
        await clickTab(page, 'Home');
        await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
        const refreshed = page.waitForResponse((response) => response.url().endsWith('/api/grids'));
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
        await settleResponse(page, (await refreshed).request());
        assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    }
});

test('Home keyboard chooser selects a network and CPUs only load for the selected network', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpusByGrid = {
        [gridA]: { 'cpu-a': { ...cpu, name: 'Alpha processor' } },
        [gridB]: { 'cpu-b': { ...cpu, name: 'Beta processor' } }
    };
    await page.goto(base);
    const choose = page.getByRole('button', { name: 'Choose a network', exact: true });
    await choose.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    assert.ok((await dialog.boundingBox()).height < page.viewportSize().height * 0.9);
    await page.keyboard.press('Escape');
    assert.equal(await choose.evaluate((node) => node === document.activeElement), true);
    await choose.click();
    await dialog.getByRole('link', { name: new RegExp(gridB) }).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await dialog.isVisible(), false);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Beta processor/ }).waitFor();
    const cpuRequests = options.requests.filter((request) => request.path.endsWith('/cpus'));
    assert.ok(cpuRequests.length > 0);
    assert.ok(cpuRequests.every((request) => request.path === `/api/grids/${gridB}/cpus`));
    await clickTab(page, 'Home');
    await choose.waitFor();
    await settleFrames(page);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, cpuRequests.length);
});

test('Home keeps denied and empty discovery explicit and supports retry', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('link', { name: new RegExp(gridA) }).waitFor();
    options.gridError = 'NO_PERMISSIONS';
    await page.getByRole('button', { name: 'Refresh', exact: true }).dispatchEvent('click');
    await dialog.getByRole('status').waitFor();
    assert.equal(await dialog.getByRole('link').count(), 0);
    options.gridError = null;
    await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
    await dialog.getByRole('link', { name: new RegExp(gridA) }).waitFor();
    await settleFrames(page);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    // An empty successful discovery still needs an explicit recovery action with polling disabled.
    await page.keyboard.press('Escape');
    options.empty = true;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /No accessible networks/ })
        .waitFor();
    options.empty = false;
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .waitFor();
});

test('Home and its chooser contain long identities on mobile', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 844 } });
    const longName = 'LongUnbrokenIdentity'.repeat(8);
    options.grids = [
        { key: gridA, name: longName, owner: 'Owner', cpuCount: 1, accessSources: {} },
        { key: gridB, name: longName, owner: 'Owner', cpuCount: 1, accessSources: {} }
    ];
    options.settings[gridA].name = longName;
    options.settings[gridB].name = longName;
    const appearance = 'dark';
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
    await clickTab(page, 'Home');
    await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.ok(await page.locator('#workspace').evaluate((node) => node.scrollWidth <= node.clientWidth));
    await openMobilePane(page, 'Network');
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    const dialog = page.getByRole('dialog');
    const firstChoice = dialog
        .locator('.network-choice')
        .filter({ has: page.locator(`a[href="#/grids/${gridA}/items"]`) });
    if (!(await firstChoice.getByText(gridA, { exact: true }).isVisible()))
        await firstChoice.getByText('Network identifier', { exact: true }).click();
    await firstChoice.getByText(gridA, { exact: true }).waitFor();
    const network = dialog.locator(`a[href="#/grids/${gridB}/items"]`);
    await network.focus();
    options.grids = options.grids.toReversed();
    const changedDiscovery = page.waitForResponse((response) =>
        new URL(response.url()).pathname.endsWith('/api/grids')
    );
    await page.getByRole('button', { name: 'Refresh', exact: true }).dispatchEvent('click');
    await (await changedDiscovery).finished();
    assert.equal(await network.evaluate((node) => node === document.activeElement), true);
    assert.equal(await dialog.getByText(gridA, { exact: true }).isVisible(), true);
    assert.ok(await dialog.evaluate((node) => node.scrollWidth <= node.clientWidth));
    const bounds = await dialog.boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({
            path: path.join(process.env.UI_SCREENSHOT_DIR, `home-chooser-mobile-${appearance}.png`)
        });
    }
    await page.keyboard.press('Escape');
    if (page.viewportSize().width <= 600) await page.getByRole('button', { name: 'Network', exact: true }).waitFor();
    else await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
});

test('Home keeps failed logout visible and allows an explicit retry', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    options.logoutError = 'INTERNAL_ERROR';
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByRole('alert').waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/auth/logout').length, 1);
    options.logoutError = null;
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/auth/logout').length, 2);
});

// Public settings/HTTP seam: the server-backed name is shared presentation, independent of tracking.
test('current network preserves desktop alignment and shares the second phone row with account actions', async (t) => {
    const { page, options, base } = await fixture(t);
    for (const width of [1280, 800, 560, 390]) {
        await page.setViewportSize({ width, height: 844 });
        let previous;
        for (const name of ['Factory', 'VeryLongNetworkName'.repeat(6)]) {
            options.settings[gridA].name = name;
            await page.goto(`${base}#/grids/${gridA}/settings`);
            await page.reload();
            const network = page.locator('#app header').getByText(name, { exact: true });
            await network.waitFor();
            await page.evaluate(() => document.fonts.ready);
            await settleFrames(page);
            const brand = await page.getByRole('heading', { name: 'AE2 Web Integration' }).boundingBox();
            const account = await page.getByRole('button', { name: 'Log out', exact: true }).boundingBox();
            const label = await network.boundingBox();
            const header = await page.locator('#app header').boundingBox();
            if (width === 1280)
                assert.ok(
                    await page
                        .getByText(options.username, { exact: true })
                        .evaluate((node) => node.scrollWidth <= node.clientWidth),
                    'The account name must use available header space before being shortened'
                );
            if (width > 600) {
                assert.ok(
                    Math.abs(label.y + label.height / 2 - brand.y - brand.height / 2) < 2,
                    `brand alignment at ${width}`
                );
                assert.ok(
                    Math.abs(label.y + label.height / 2 - account.y - account.height / 2) < 2,
                    `account alignment at ${width}`
                );
                assert.ok(label.x >= brand.x + brand.width && label.x + label.width <= account.x);
                assert.ok(
                    label.x - (brand.x + brand.width) <= 24,
                    'The brand must not reserve empty space after its visible text'
                );
            } else {
                const user = await page.getByText(options.username, { exact: true }).boundingBox();
                const menu = await page.getByRole('button', { name: 'Navigation', exact: true }).boundingBox();
                assert.ok(Math.abs(user.y + user.height / 2 - menu.y - menu.height / 2) < 2);
                assert.ok(brand.x >= menu.x + menu.width && user.x >= brand.x + brand.width);
                assert.ok(account.y >= brand.y + brand.height);
                assert.ok(Math.abs(label.y + label.height / 2 - account.y - account.height / 2) < 2);
                assert.ok(label.x + label.width <= account.x);
            }
            assert.ok(label.width > 0 && account.x + account.width <= width);
            if (previous)
                assert.deepEqual(
                    { brand, account, header },
                    previous,
                    'A long network name cannot move the surrounding header'
                );
            previous = { brand, account, header };
        }
    }
});

test('network identity uses its name or full identifier, with scoped header and shared tooltip', async (t) => {
    const { page, options, base } = await fixture(t);
    options.settings[gridB].name = 'Remote factory';
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const header = page.locator('#app header');
    const current = header.getByText(gridA, { exact: true });
    await current.waitFor();
    assert.doesNotMatch(await header.innerText(), /Alpha/);
    assert.equal(await current.getAttribute('title'), null);
    await current.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor();
    assert.match(await tooltip.innerText(), /Current network/);
    assert.match(await tooltip.innerText(), new RegExp(gridA));
    await page.keyboard.press('Escape');
    assert.equal(await tooltip.isVisible(), false);
    for (const route of ['cpus', 'cpus/cpu-a', 'history', 'history/1', 'plans/1', 'settings']) {
        await page.goto(`${base}#/grids/${gridA}/${route}`);
        await current.waitFor();
        assert.equal(await current.innerText(), gridA);
    }
    for (const route of ['/', '/web-settings', '/server-settings', '/about']) {
        await page.goto(`${base}#${route}`);
        assert.equal(await current.isVisible(), false, `Network context is not shown on ${route}`);
    }
    await page.goto(`${base}#/`);
    const panel = page.locator('#home-network-panel');
    await panel.getByText(gridA, { exact: true }).waitFor();
    assert.equal(await panel.getByText(gridA, { exact: true }).count(), 1);
    assert.doesNotMatch(await panel.innerText(), /Alpha/);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    const dialog = page.getByRole('dialog');
    assert.doesNotMatch(await dialog.innerText(), /Alpha|Beta/);
    await dialog.getByRole('link', { name: /Remote factory/ }).click();
    await header.getByText('Remote factory', { exact: true }).waitFor();
    await clickTab(page, 'Network');
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Renamed network');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await header.getByText('Renamed network', { exact: true }).waitFor();
    await page
        .getByRole('region', { name: 'Network details', exact: true })
        .getByText('Beta', { exact: true })
        .waitFor();
    await header.getByText('Renamed network', { exact: true }).hover();
    await tooltip.waitFor();
    assert.match(await tooltip.innerText(), new RegExp(gridB));
    await page.keyboard.press('Escape');
    await name.fill('');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await header.getByText(gridB, { exact: true }).waitFor();
    await clickTab(page, 'Home');
    await panel.getByText(gridB, { exact: true }).waitFor();
});

test('Network details save and clear a name without resending tracking, and update Home immediately', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('  Main factory  ');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.equal(await name.inputValue(), 'Main factory');
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, {
        name: 'Main factory'
    });
    await page.getByRole('button', { name: 'Dismiss notification', exact: true }).click();
    const navigation = page.locator('nav').getByRole('link');
    assert.deepEqual((await navigation.allTextContents()).slice(0, 3), ['Home', 'Network', 'Terminal']);
    await clickTab(page, 'Home');
    const panel = page.locator('#home-network-panel');
    await panel.getByText('Main factory', { exact: true }).waitFor();
    assert.equal(await panel.getByText('Owner: Alpha', { exact: true }).count(), 0);
    await panel.getByText(gridA, { exact: true }).waitFor();
    await panel.getByRole('link', { name: 'Network details', exact: true }).click();
    await name.fill('');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, { name: '' });
    await page.reload();
    await name.waitFor();
    assert.equal(await name.inputValue(), '');
});
