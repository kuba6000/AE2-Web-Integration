const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {
    gridA,
    gridB,
    iron,
    quartz,
    cpu,
    cpuWork,
    historyDetail,
    refreshingFixture,
    fixture,
    clickTab,
    openMobilePane,
    closeMobilePane,
    settleFrames,
    settleResponse,
    poll,
    atlasFixture
} = require('./fixtures.cjs');

test('server configuration is hidden from players and a direct route never requests it', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/server-settings`);
    await page
        .locator('#server-settings [role="status"]')
        .filter({ hasText: /permission|access/i })
        .waitFor();
    assert.equal(await page.getByRole('link', { name: 'Server settings', exact: true }).isVisible(), false);
    assert.equal(await page.locator('#server-settings form').isVisible(), false);
    assert.equal(
        options.requests.some((request) => request.path === '/api/server/settings'),
        false
    );
});

test('server configuration phone form preserves focus and drafts, detects conflicts and saves explicitly', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '/ae2', {
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true
    });
    options.isAdmin = true;
    await page.goto(`${base}#/server-settings`);
    const rate = page.getByRole('spinbutton', { name: 'Unauthenticated requests per minute', exact: true });
    await rate.waitFor();
    for (const field of ['port', 'adminPasswordConfigured', 'publicMode', 'allowNoPasswordOnLocalhost']) {
        assert.equal(await page.locator(`#server-file-${field}`).isDisabled(), true);
    }
    assert.equal(
        await page.locator('#server-file-port').inputValue(),
        String(options.serverConfigurationReadOnly.port)
    );
    assert.equal(await page.locator('#server-file-adminPasswordConfigured').getAttribute('type'), 'password');
    assert.ok((await page.locator('#server-file-adminPasswordConfigured').inputValue()).length > 0);
    const fileOnly = page.locator('.server-setting-file-only').first();
    await fileOnly.focus();
    await page
        .locator('#resource-tooltip')
        .filter({ hasText: /config\.toml/ })
        .waitFor();
    assert.equal(await page.locator('#server-ntfy-password').getAttribute('type'), 'password');
    await rate.fill('37');
    const reads = options.requests.filter((request) => request.path === '/api/server/settings').length;
    await page.clock.fastForward(15000);
    assert.equal(await rate.evaluate((input) => document.activeElement === input), true);
    assert.equal(await rate.inputValue(), '37');
    assert.equal(options.requests.filter((request) => request.path === '/api/server/settings').length, reads);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    const save = page.getByRole('button', { name: 'Save and apply', exact: true });
    options.serverConfigurationRevision++;
    await save.click();
    await page
        .locator('#server-settings [role="status"]')
        .filter({ hasText: /changed on the server/ })
        .waitFor();
    assert.equal(await rate.inputValue(), '37');
    assert.equal(await save.isDisabled(), true);
    await page.getByRole('button', { name: 'Discard changes and reload', exact: true }).click();
    await page.waitForFunction(
        () => document.querySelector('#server-general-maxRequestsBeforeLoggedInPerMinute')?.value === '20'
    );
    await rate.fill('38');
    await save.click();
    await page
        .locator('#server-settings [role="status"]')
        .filter({ hasText: /saved and applied/ })
        .waitFor();
    assert.equal(options.serverConfiguration.general.maxRequestsBeforeLoggedInPerMinute, 38);
    assert.equal(await save.isDisabled(), true);
});

test('server settings keep neighboring inputs aligned when labels wrap at different widths', async (t) => {
    const { page, options, base } = await fixture(t);
    options.isAdmin = true;
    await page.goto(`${base}#/server-settings`);
    const duration = page.getByRole('spinbutton', { name: 'Minimum crafting duration (seconds)', exact: true });
    const amount = page.getByRole('spinbutton', { name: 'Minimum crafted amount', exact: true });
    await duration.waitFor();
    for (const width of [1000, 1150, 1300]) {
        await page.setViewportSize({ width, height: 900 });
        await settleFrames(page);
        const first = await duration.boundingBox();
        const second = await amount.boundingBox();
        assert.ok(first && second);
        assert.ok(Math.abs(first.y - second.y) < 1, `Inputs must align at viewport width ${width}`);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    }
});

test('crafting retention is independent per network and persists while collection is disabled', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const retention = page.getByRole('spinbutton', { name: /Crafting history retention.*days/i });
    await retention.waitFor({ timeout: 5000 });
    assert.equal(await retention.inputValue(), '7');
    await retention.fill('12');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.equal(options.settings[gridA].craftingHistoryRetentionDays, 12);
    assert.equal(options.settings[gridA].historyRetentionDays, 7);
    assert.equal(options.settings[gridA].isTracked, false);
    assert.equal(options.settings[gridB].craftingHistoryRetentionDays, 7);
    await page.reload();
    await retention.waitFor();
    assert.equal(await retention.inputValue(), '12');
    const patches = options.requests.filter((request) => request.method === 'PATCH').length;
    await retention.fill('16');
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, patches);
});

test('machine crafting tracking is saved per network and retained while crafting history is disabled', async (t) => {
    const { page, options, base } = await fixture(t);
    options.settings[gridB].isTracked = true;
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    const machines = page.getByRole('checkbox', { name: 'Include machine-requested crafting', exact: true });
    await tracking.waitFor();
    await machines.waitFor();
    assert.equal(await machines.isDisabled(), true);
    assert.equal(await machines.isChecked(), false);
    await tracking.check();
    await machines.check();
    const save = page.getByRole('button', { name: 'Save settings', exact: true });
    await save.click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.equal(options.settings[gridA].trackMachineCrafting, true);
    assert.equal(options.settings[gridB].trackMachineCrafting, false);
    await page.getByRole('button', { name: 'Dismiss notification', exact: true }).click();
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await machines.waitFor();
    assert.equal(await machines.isChecked(), false);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await machines.waitFor();
    assert.equal(await machines.isChecked(), true);
    await tracking.uncheck();
    assert.equal(await machines.isDisabled(), true);
    assert.equal(await machines.isChecked(), true);
    await save.click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await page.reload();
    await machines.waitFor();
    assert.equal(await machines.isDisabled(), true);
    assert.equal(await machines.isChecked(), true);
    await tracking.check();
    await machines.uncheck();
    await save.click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.equal(options.settings[gridA].trackMachineCrafting, false);
});

test('tracking notices use the current network, update after saving, and retain previously recorded history', async (t) => {
    const { page, options, base } = await fixture(t);
    options.settings[gridA].isTracked = false;
    options.settings[gridB].isTracked = true;
    await page.goto(`${base}#/grids/${gridA}/items`);
    const enable = page.getByRole('link', { name: 'Enable tracking', exact: true });
    await enable.waitFor();
    assert.equal(await enable.getAttribute('href'), `#/grids/${gridA}/settings`);
    await enable.click();
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).check();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await page.getByRole('button', { name: 'Dismiss notification', exact: true }).click();
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.count(), 0);
    await clickTab(page, 'Network');
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).uncheck();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await clickTab(page, 'History');
    await page.getByRole('link', { name: /Iron Ingot.*#1/ }).waitFor();
    await enable.waitFor();
    assert.equal(await enable.getAttribute('href'), `#/grids/${gridA}/settings`);
    await page
        .getByRole('region', { name: 'History', exact: true })
        .getByText(/tracking is disabled/i)
        .waitFor();
    await clickTab(page, 'Home');
    await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
    assert.equal(await enable.count(), 0);
    await page.goto(`${base}#/grids/${gridB}/cpus`);
    await page
        .getByRole('link', { name: /Assembler/ })
        .first()
        .waitFor();
    assert.equal(await enable.count(), 0);
});

// A discovery snapshot predating a PATCH cannot undo the confirmed metadata in another view.
test('late discovery cannot undo a confirmed name or tracking save', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Factory');
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).check();
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/grids', (route) => release(route));
    await poll(page);
    const oldDiscovery = await captured;
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await oldDiscovery.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: [
                { key: gridA, name: '', owner: 'Alpha', cpuCount: 2, isTrackingEnabled: false, accessSources: {} },
                { key: gridB, name: '', owner: 'Beta', cpuCount: 1, isTrackingEnabled: false, accessSources: {} }
            ]
        })
    });
    await settleResponse(page, oldDiscovery.request());
    await clickTab(page, 'Home');
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Factory', exact: true }).waitFor();
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('link', { name: 'Enable tracking', exact: true }).count(), 0);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 1);
});

test('Network keeps field drafts across grids, refreshes unedited fields, and rejects invalid names before HTTP', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await name.fill('Draft A');
    options.settings[gridA].isTracked = true;
    const read = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await settleResponse(page, (await read).request());
    assert.equal(await name.inputValue(), 'Draft A');
    assert.equal(await tracking.isChecked(), true);
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await name.fill('Draft B');
    await page.goBack();
    await name.waitFor();
    assert.equal(await name.inputValue(), 'Draft A');
    const save = page.getByRole('button', { name: 'Save settings', exact: true });
    for (const invalid of ['🙂'.repeat(65), 'Invalid\u0001name']) {
        await name.fill(invalid);
        assert.equal(await save.isDisabled(), true);
    }
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 0);
    await name.fill('Draft A');
    await save.click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, { name: 'Draft A' });
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await name.waitFor();
    assert.equal(await name.inputValue(), 'Draft B');
});

test('Network pending and uncertain name saves retain source identity and notify Home after navigation', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Requested');
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/settings', (route) =>
        route.request().method() === 'PATCH' ? release(route) : route.continue()
    );
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    const pending = await captured;
    await clickTab(page, 'Home');
    options.settings[gridA] = { ...options.settings[gridA], name: 'Canonical', isTracked: true };
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: options.settings[gridA] })
    });
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Canonical', exact: true }).waitFor();
    await page.unroute('**/settings');
    await clickTab(page, 'Network');
    await name.fill('Unconfirmed');
    options.fault = { operation: 'settings', status: 'INTERNAL_ERROR' };
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /not confirmed/i })
        .waitFor();
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await name.waitFor();
    assert.equal(await name.inputValue(), '');
    await page.goBack();
    await page
        .getByRole('status')
        .filter({ hasText: /not confirmed/i })
        .waitFor();
    assert.equal(await name.inputValue(), 'Unconfirmed');
    assert.equal(
        options.requests.filter((request) => request.method === 'PATCH').length,
        1,
        'The intercepted first save is outside fixture logging; failed PATCH must not replay'
    );
    options.fault = null;
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .filter({ hasText: 'Unconfirmed' })
        .waitFor();
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, {
        name: 'Unconfirmed'
    });
});

test('Network groups explicit people with collapsed source lists and keeps disclosure and focus during polling', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const first = { uuid: 'person-a', name: 'Alice' };
    const second = { uuid: 'person-b', name: 'Bob' };
    const source = (player, x) => ({
        player,
        kind: 'security_terminal',
        reason: 'security_card',
        position: { dimensionId: 'minecraft:overworld', x, y: 64, z: -32 },
        side: null
    });
    options.accessSources = {
        [first.uuid]: [source(first, 120)],
        [second.uuid]: Array.from({ length: 18 }, (_, x) => source(second, x + 200))
    };
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.getByText('Bob', { exact: true }).waitFor();
    const alice = page.locator('details').filter({ has: page.getByText('Alice', { exact: true }) });
    const bob = page.locator('details').filter({ has: page.getByText('Bob', { exact: true }) });
    assert.equal(await page.getByText(/minecraft:overworld/).count(), 0);
    await bob.locator('summary').click();
    await bob.getByRole('listitem').last().waitFor();
    assert.equal(await bob.getByRole('listitem').count(), 18);
    assert.equal(await alice.getByRole('listitem').count(), 0);
    await bob.locator('summary').focus();
    options.accessSources = {
        ...options.accessSources,
        [second.uuid]: [...options.accessSources[second.uuid], source(second, 999)]
    };
    const read = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await settleResponse(page, (await read).request());
    assert.equal(await bob.getByRole('listitem').count(), 19);
    assert.equal(await bob.locator('summary').evaluate((node) => node === document.activeElement), true);
    await clickTab(page, 'Home');
    assert.equal(
        await page
            .locator('#home, #home-network-panel, #selected-network')
            .getByText(/minecraft:overworld/)
            .count(),
        0
    );
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    assert.equal(
        await page
            .getByRole('dialog')
            .getByText(/minecraft:overworld/)
            .count(),
        0
    );
});

test('Network details scroll long access lists under a fixed heading and keep mobile settings usable', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 844 } });
    options.settings[gridA].name = 'LongNetworkName'.repeat(8);
    const player = { uuid: 'source-owner', name: 'LongPlayerName'.repeat(8) };
    options.accessSources = {
        [player.uuid]: Array.from({ length: 30 }, (_, x) => ({
            player,
            kind: 'security_terminal',
            reason: 'security_card',
            position: { dimensionId: 'minecraft:overworld', x, y: 64, z: -32 },
            side: null
        }))
    };
    const appearance = 'dark';
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const heading = page.getByRole('heading', { name: 'Network details', exact: true });
    await heading.waitFor();
    const current = page.locator('#app header').getByText(options.settings[gridA].name, { exact: true });
    await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
    await settleFrames(page);
    await current.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor();
    assert.ok((await tooltip.innerText()).includes(options.settings[gridA].name));
    for (const target of [current, page.locator('#app header').getByRole('button', { name: 'Log out', exact: true })]) {
        const box = await target.boundingBox();
        assert.ok(box.width > 0 && box.x >= 0 && box.x + box.width <= 390);
    }
    await page.keyboard.press('Tab');
    const logout = page.locator('#app header').getByRole('button', { name: 'Log out', exact: true });
    assert.ok(await logout.evaluate((node) => node === document.activeElement));
    await tooltip.getByText(await logout.getAttribute('aria-label'), { exact: true }).waitFor();
    assert.equal((await tooltip.innerText()).includes(options.settings[gridA].name), false);
    await page.keyboard.press('Escape');
    assert.equal(await tooltip.isVisible(), false);
    const before = await heading.boundingBox();
    const person = page.locator('details').filter({ has: page.getByText(player.name, { exact: true }) });
    if (!(await person.evaluate((node) => node.open))) await person.locator('summary').click();
    await person.getByRole('listitem').last().waitFor();
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.scrollIntoViewIfNeeded();
    assert.deepEqual(await heading.boundingBox(), before);
    await name.fill(`Mobile ${appearance}`);
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: `Mobile ${appearance}` })
        .waitFor();
    const region = page.getByRole('region', { name: 'Network details', exact: true });
    assert.ok(await region.evaluate((node) => node.scrollWidth <= node.clientWidth));
    assert.ok(
        await page.evaluate(
            () =>
                document.documentElement.scrollWidth <= innerWidth &&
                document.documentElement.scrollHeight <= innerHeight
        )
    );
});

for (const height of [844, 600]) {
    test(`icon and tracking notices coexist without hiding the terminal at mobile height ${height}`, async (t) => {
        const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height } });
        await atlasFixture(page, options);
        await page.addInitScript(() =>
            localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ terminalDisplay: 'names' }))
        );
        await page.goto(`${base}#/grids/${gridA}/items`);
        const icons = page.getByRole('button', { name: 'Enable icons', exact: true });
        const tracking = page.getByRole('link', { name: 'Enable tracking', exact: true });
        await icons.waitFor();
        await tracking.waitFor();
        const search = page.getByRole('searchbox', { name: 'Search resources', exact: true });
        await search.fill('Iron');
        const region = page.getByRole('region', { name: 'Resources', exact: true });
        const bounds = await region.boundingBox();
        assert.ok(bounds.height >= 44, `Notices must leave a usable resource viewport at ${height}px`);
        assert.ok(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight));
        await page.getByRole('button', { name: /Iron Ingot/ }).click();
        await closeMobilePane(page);
        const information = page.getByRole('region', { name: 'Information', exact: true });
        await page.waitForFunction((node) => node.scrollHeight > node.clientHeight, await information.elementHandle());
        await settleFrames(page);
        await information.focus();
        await page.keyboard.press('End');
        // Finish the End-key scroll before focus starts another browser scroll.
        await page.waitForFunction(
            (node) => node.scrollTop > 0 && node.scrollTop + node.clientHeight >= node.scrollHeight - 1,
            await information.elementHandle()
        );
        for (const control of [icons, tracking]) {
            await control.focus();
            await page.waitForFunction(
                ([control, region]) => {
                    const box = control.getBoundingClientRect(),
                        area = region.getBoundingClientRect();
                    return box.y >= area.y && box.bottom <= area.bottom;
                },
                [await control.elementHandle(), await information.elementHandle()]
            );
        }
        await page.getByRole('button', { name: 'Dismiss icon suggestion', exact: true }).click();
        assert.equal(await icons.count(), 0);
        await tracking.waitFor();
        assert.equal(await tracking.getAttribute('href'), `#/grids/${gridA}/settings`);
        const searchBounds = await search.boundingBox();
        assert.ok(searchBounds.y >= 0 && searchBounds.y + searchBounds.height <= height);
        await tracking.focus();
        await page.keyboard.press('Enter');
        await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).waitFor();
        assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/settings`);
    });
}

test('Network refetch publishes remote name and tracking changes with polling disabled', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'Enable tracking', exact: true }).waitFor();
    options.settings[gridA] = { ...options.settings[gridA], name: 'Remote factory', isTracked: true };
    await clickTab(page, 'Network');
    await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
    assert.equal(await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).isChecked(), true);
    await clickTab(page, 'Home');
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Remote factory', exact: true }).waitFor();
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('link', { name: 'Enable tracking', exact: true }).count(), 0);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

// Public browser geometry: center the main frame, keeping its attachments on screen.
for (const deviceScaleFactor of [1, 1.5]) {
    test(`main terminal centers independently of its attachments at DPR ${deviceScaleFactor}`, async (t) => {
        const { page, base } = await fixture(t, '', { deviceScaleFactor }, { mockClock: false });
        for (const width of [1920, 1600, 1500, 1400, 1184, 800, 600, 390]) {
            await page.setViewportSize({ width, height: 900 });
            await page.goto(`${base}#/grids/${gridA}/items`);
            await page.getByRole('button', { name: /Iron Ingot/ }).click();
            await page.evaluate(() => document.fonts.ready);
            const frame = await page.locator('#window').boundingBox();
            assert.ok(frame.x >= 0 && frame.x + frame.width <= width, `main frame fits at ${width}`);
            if (width > 600) {
                const details = await page.getByRole('complementary').filter({ visible: true }).boundingBox();
                const tools = await page.getByRole('group', { name: 'Resources', exact: true }).boundingBox();
                assert.ok(details.x >= 0 && details.x + details.width <= width, `attached details fit at ${width}`);
                assert.ok(tools.x >= 0 && tools.x + tools.width <= frame.x + 1, `tools fit beside frame at ${width}`);
                assert.ok(Math.abs(details.x - (frame.x + frame.width)) <= 3, `details remain docked at ${width}`);
            } else {
                const details = page.getByRole('dialog', { name: 'Resource details', exact: true });
                const sheet = await details.boundingBox();
                assert.ok(sheet.x >= 0 && sheet.x + sheet.width <= width, `details sheet fits at ${width}`);
                assert.ok(sheet.y >= 0 && sheet.y + sheet.height <= 900, 'details stay inside the viewport');
                await closeMobilePane(page);
                assert.deepEqual(
                    await page.locator('#window').boundingBox(),
                    frame,
                    'closing details preserves the terminal frame'
                );
                await openMobilePane(page, 'Filters and sorting');
                const panel = await page
                    .getByRole('dialog', { name: 'Filters and sorting', exact: true })
                    .boundingBox();
                const tools = await page.getByRole('group', { name: 'Resources', exact: true }).boundingBox();
                assert.ok(
                    tools.x >= panel.x && tools.x + tools.width <= panel.x + panel.width,
                    'filters stay inside their sheet'
                );
                assert.ok(tools.y >= panel.y && tools.y + tools.height <= panel.y + panel.height);
                await closeMobilePane(page);
                assert.deepEqual(
                    await page.locator('#window').boundingBox(),
                    frame,
                    'closing filters preserves the terminal frame'
                );
            }
            if (width >= 1600) {
                assert.ok(
                    Math.abs(frame.x + frame.width / 2 - width / 2) < 1,
                    `main frame center ${frame.x + frame.width / 2} matches viewport center ${width / 2}`
                );
            }
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        }
        await page.setViewportSize({ width: 1920, height: 900 });
        for (const name of ['CPUs', 'History', 'Network', 'Web settings', 'About', 'Home', 'Terminal']) {
            await page.getByRole('navigation').getByRole('link', { name, exact: true }).click();
            const frame = page.locator(name === 'Home' ? '#home' : '#window');
            await frame.waitFor();
            const bounds = await frame.boundingBox();
            assert.ok(Math.abs(bounds.x + bounds.width / 2 - 960) < 1, `${name} frame retains shared center`);
            const header = await page.getByRole('navigation').boundingBox();
            assert.ok(Math.abs(header.x + header.width / 2 - 960) < 1, `${name} header retains shared center`);
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        }
    });
}

// Public browser/HTTP seam: large histories remain bounded while every resource and exact interval is reachable.
test('large history analysis paginates resources and exact intervals without dropping the tail', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    const started = historyDetail.timeStarted;
    options.historyDetail = {
        ...historyDetail,
        timeDone: started + 200000,
        interfaceShare: [
            {
                ...historyDetail.interfaceShare[0],
                location: Array.from({ length: 60 }, (_, index) => ({
                    dimensionId: 'test:world',
                    x: index,
                    y: 1,
                    z: 2
                }))
            }
        ],
        items: Array.from({ length: 61 }, (_, index) => ({
            ...historyDetail.items[0],
            displayName: `Resource ${String(index).padStart(2, '0')}`,
            craftedTotal: index + 1,
            timeSpentOn: (index + 1) * 1000,
            timings: Array.from({ length: index === 60 ? 10001 : 3 }, (_, interval) => ({
                started: started + interval * 10,
                ended: started + interval * 10 + 5
            }))
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const resources = page.getByRole('list', { name: 'Resources', exact: true });
    await resources.waitFor();
    assert.equal(await resources.getByRole('listitem').count(), 25);
    assert.equal(await resources.getByRole('heading').first().innerText(), 'Resource 60');
    assert.equal(await resources.locator('time').count(), 0, 'exact intervals stay unmounted until expansion');
    const pagination = page.getByRole('navigation', { name: 'History pages', exact: true });
    await pagination.getByRole('button', { name: 'Next page', exact: true }).click();
    await pagination.getByRole('button', { name: 'Next page', exact: true }).click();
    assert.equal(await resources.getByRole('heading').count(), 11);
    assert.equal(await resources.getByRole('heading').last().innerText(), 'Resource 00');
    await page.getByRole('searchbox', { name: 'Search history', exact: true }).fill('Resource 60');
    const target = resources.getByRole('region', { name: 'Resource 60', exact: true });
    const overview = target.getByRole('img', { name: 'Activity overview', exact: true });
    await page.waitForFunction(() => document.querySelector('[aria-label="Activity overview"][aria-busy="false"] svg'));
    assert.ok(
        (await overview.locator('*').count()) < 20,
        'overview geometry is bounded despite ten thousand intervals'
    );
    await target.locator('summary').click();
    const intervals = target.getByRole('list', { name: 'Exact intervals', exact: true });
    await intervals.waitFor();
    assert.equal(await intervals.getByRole('listitem').count(), 50);
    await target.getByRole('button', { name: 'Last page', exact: true }).click();
    assert.equal(await intervals.getByRole('listitem').count(), 1);
    assert.equal(
        await intervals.locator('time').last().getAttribute('datetime'),
        new Date(started + 100005).toISOString()
    );
    await target.locator('summary').click();
    await target.locator('time').first().waitFor({ state: 'detached' });
    assert.equal(await target.locator('time').count(), 0, 'closing releases mounted exact intervals');
    await page.getByRole('searchbox', { name: 'Search history', exact: true }).fill('');
    await page.getByRole('combobox', { name: 'Sort by', exact: true }).selectOption('name');
    assert.equal(await resources.getByRole('heading').first().innerText(), 'Resource 00');
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    const provider = page.getByRole('region', { name: 'Smelter', exact: true });
    await provider.waitFor();
    assert.equal(await resources.count(), 0);
    await provider.locator('summary').click();
    await provider.getByRole('list', { name: 'Exact intervals', exact: true }).waitFor();
    const locations = provider.getByRole('navigation', { name: 'Location pages', exact: true });
    await locations.getByRole('button', { name: 'Last page', exact: true }).click();
    await provider.getByText(/test:world.*59.*1.*2/).waitFor();
    assert.equal(
        await provider.getByRole('list', { name: 'Exact intervals', exact: true }).getByRole('listitem').count(),
        1
    );
});

// Public browser/HTTP seam: local calendar boundaries determine when history needs a date.
test('history timestamps use contextual dates and 24-hour whole seconds', async (t) => {
    const { page, options, base } = await fixture(t, '', { timezoneId: 'Europe/Warsaw' });
    const started = Date.parse('2025-12-31T22:58:00.123Z');
    const timings = [
        { started: started + 1000, ended: started + 2000 },
        { started: Date.parse('2025-12-31T22:59:59.456Z'), ended: Date.parse('2025-12-31T23:00:01.789Z') },
        { started: Date.parse('2025-12-31T23:01:00.123Z'), ended: Date.parse('2025-12-31T23:01:30.456Z') }
    ];
    options.historyDetail = {
        ...historyDetail,
        timeStarted: started,
        timeDone: Date.parse('2025-12-31T23:02:00.789Z'),
        items: [{ ...historyDetail.items[0], timings }]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const row = page.getByRole('region', { name: 'Iron Ingot', exact: true });
    await row.locator('summary').click();
    const times = row.locator('time');
    assert.equal(await times.nth(0).innerText(), '23:58:01');
    assert.equal(await times.nth(1).innerText(), '23:58:02');
    assert.equal(await times.nth(2).innerText(), '23:59:59');
    assert.match(await times.nth(3).innerText(), /2026.*00:00:01/);
    assert.match(await times.nth(4).innerText(), /2026.*00:01:00/);
    assert.equal(await times.nth(5).innerText(), '00:01:30');
    assert.equal(await times.nth(3).getAttribute('datetime'), '2025-12-31T23:00:01.789Z');
    const startedLabel = 'Started';
    const finishedLabel = 'Finished';
    const summaryValue = (label) =>
        page
            .locator('dl > div')
            .filter({ has: page.getByText(label, { exact: true }) })
            .locator('dd');
    assert.match(await summaryValue(startedLabel).innerText(), /2025.*23:58:00/);
    assert.match(await summaryValue(finishedLabel).innerText(), /2026.*00:02:00/);
    options.historyDetail.timeDone = started + 30000;
    await page.reload();
    await row.locator('summary').waitFor();
    assert.equal(await summaryValue(finishedLabel).innerText(), '23:58:30');
});

test('history resource summaries pair processing time with its share of total elapsed crafting time', async (t) => {
    const { page, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const summary = page.getByRole('region', { name: 'Iron Ingot', exact: true }).locator('summary');
    await summary.waitFor();
    assert.match(await summary.innerText(), /5 s\s*\(50%\)/);
    assert.doesNotMatch(await summary.innerText(), /40%/);
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    const provider = page.getByRole('region', { name: 'Smelter', exact: true }).locator('summary');
    assert.doesNotMatch(await provider.innerText(), /%/);
});

test('history preserves subsecond timeline scale and distinguishes very slow production from zero', async (t) => {
    const { page, options, base } = await fixture(t);
    options.historyDetail = {
        ...historyDetail,
        timeDone: historyDetail.timeStarted + 500,
        items: [{ ...historyDetail.items[0], craftsPerSec: 0.00001 }]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const row = page.getByRole('region', { name: 'Iron Ingot', exact: true });
    await row.locator('summary').click();
    await row.getByText('<0.001/s', { exact: true }).waitFor();
    const axis = page.getByLabel('Elapsed time from the start of this craft', { exact: true });
    assert.deepEqual(await axis.innerText(), '0 s\n0.25 s\n0.5 s');
    options.historyDetail.items[0].craftsPerSec = 0;
    await page.reload();
    await row.locator('summary').click();
    await row.getByText('0/s', { exact: true }).waitFor();
});

// Public settings/UI seam: saved preferred columns constrain resource grids without forcing mobile overflow.
test('preferred resource columns persist and adapt across terminal CPU and plan grids', async (t) => {
    const { page, base } = await fixture(t, '/ae2', { viewport: { width: 1600, height: 1000 } }, { mockClock: false });
    await page.goto(`${base}#/web-settings`);
    const columns = page.getByRole('combobox', { name: /Resource columns/ });
    await columns.waitFor();
    assert.equal(await columns.inputValue(), 'auto');
    await columns.selectOption('3');
    await page.reload();
    assert.equal(await columns.inputValue(), '3');
    for (const [route, selector] of [
        [`items`, '#items'],
        [`cpus/cpu-a`, '.cpu-terminal .cpu-items'],
        [`plans/17`, '.crafting-plan .cpu-items']
    ]) {
        await page.goto(`${base}#/grids/${gridA}/${route}`);
        const grid = page.locator(selector);
        await grid.waitFor();
        await page.waitForFunction(
            (selector) =>
                getComputedStyle(document.querySelector(selector)).gridTemplateColumns.split(' ').length === 3,
            selector
        );
        await page.setViewportSize({ width: 390, height: 844 });
        await page.waitForFunction(
            (selector) => getComputedStyle(document.querySelector(selector)).gridTemplateColumns.split(' ').length < 3,
            selector
        );
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        await page.setViewportSize({ width: 1600, height: 1000 });
        await page.waitForFunction(
            (selector) =>
                getComputedStyle(document.querySelector(selector)).gridTemplateColumns.split(' ').length === 3,
            selector
        );
    }
    await page.goto(`${base}#/web-settings`);
    assert.equal(await columns.inputValue(), '3');
    await columns.selectOption('auto');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(
        () => getComputedStyle(document.querySelector('#items')).gridTemplateColumns.split(' ').length > 3
    );
});

test('outdated mod notice is admin only and supports session close and seven day snooze', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.isOutdated = true;
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('heading', { name: 'Web settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('link', { name: 'View releases' }).count(), 0);
    options.isAdmin = true;
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    const releases = page.getByRole('link', { name: 'View releases' });
    await releases.waitFor();
    assert.equal(await releases.getAttribute('href'), options.availableUpdate.releaseUrl);
    await page.getByRole('button', { name: 'Dismiss update notice' }).click();
    assert.equal(await releases.isVisible(), false);
    await clickTab(page, 'Home');
    assert.equal(await releases.isVisible(), false);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    await releases.waitFor();
    await page.getByRole('button', { name: 'Remind me later' }).click();
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await releases.isVisible(), false);
    const now = await page.evaluate(() => Date.now());
    await page.clock.setSystemTime(now + 6 * 24 * 60 * 60 * 1000);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await releases.isVisible(), false);
    await page.clock.setSystemTime(now + 7 * 24 * 60 * 60 * 1000 + 1000);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    await releases.waitFor();
    options.isOutdated = false;
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await releases.isVisible(), false);
});

test('preferred columns preserve square icon slots and valid empty rows while rejecting invalid saved choices', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '/ae2',
        { viewport: { width: 1600, height: 1000 } },
        { mockClock: false }
    );
    await page.goto(base);
    await atlasFixture(page, options);
    await page.evaluate(() =>
        localStorage.setItem(
            'ae2web:/ae2/:theme:default',
            JSON.stringify({ resourceColumns: 100, terminalDisplay: 'icons' })
        )
    );
    await page.reload();
    await page.goto(`${base}#/web-settings`);
    const columns = page.getByRole('combobox', { name: /Resource columns/ });
    assert.equal(await columns.inputValue(), 'auto');
    for (const count of [1, 8]) {
        await columns.selectOption(String(count));
        await clickTab(page, 'Home');
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.locator('#items[data-display="icons"] .item').first().waitFor();
        await page.waitForFunction((count) => {
            const grid = document.querySelector('#items');
            const tracks = getComputedStyle(grid).gridTemplateColumns.split(' ').length;
            return (
                tracks === count &&
                grid.children.length % tracks === 0 &&
                (count === 1 || grid.querySelector('.empty-slot'))
            );
        }, count);
        const cells = await page.locator('#items > li').evaluateAll((nodes) =>
            nodes.map((node) => {
                const bounds = node.getBoundingClientRect();
                return { width: bounds.width, height: bounds.height };
            })
        );
        for (const cell of cells)
            assert.ok(Math.abs(cell.width - cell.height) < 1, 'Occupied and empty icon slots remain square');
        await page.goto(`${base}#/web-settings`);
    }
});

test('update notice keeps close top right and adjacent actions bottom right across screen sizes', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 500, height: 850 } },
        { mockClock: false }
    );
    options.isOutdated = true;
    options.isAdmin = true;
    await page.goto(`${base}#/web-settings`);
    const close = page.getByRole('button', { name: 'Dismiss update notice' });
    await close.waitFor();
    for (const [width, height] of [
        [1280, 720],
        [500, 850],
        [390, 850]
    ]) {
        await page.setViewportSize({ width, height });
        const notice = page.getByRole('region', { name: 'Information', exact: true });
        const bounds = await notice.boundingBox();
        const closeBounds = await close.boundingBox();
        const text = await page.locator('#update-notice > p').first().boundingBox();
        const snooze = await page.getByRole('button', { name: 'Remind me later' }).boundingBox();
        assert.ok(
            closeBounds.y >= bounds.y && closeBounds.y + closeBounds.height <= bounds.y + bounds.height,
            'Close stays visible without scrolling the notice region'
        );
        const skip = await page.getByRole('button', { name: 'Skip this version', exact: true }).boundingBox();
        assert.ok(snooze.y >= text.y + text.height - 1, 'Actions stay below the message');
        assert.ok(Math.abs(snooze.y - skip.y) < 1, 'Both actions remain on the same row');
        assert.ok(skip.x > snooze.x, 'Skip follows snooze');
        assert.ok(
            skip.y + skip.height <= bounds.y + bounds.height,
            'Both actions are visible without scrolling the notice'
        );
        assert.ok(
            Math.abs(skip.x + skip.width - closeBounds.x - closeBounds.width) < 1,
            'Actions align to the right edge with X'
        );
        assert.ok(closeBounds.x >= text.x + text.width - 1, 'Close stays at the right of the message');
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    }
    await close.click();
    assert.equal(await close.isVisible(), false);
});

test('administrator skips the displayed update only after confirmed response and server state survives browser reset', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.isOutdated = true;
    options.isAdmin = true;
    let release;
    const held = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/updates/skip', async (route) => {
        await held;
        await route.continue();
    });
    await page.goto(`${base}#/web-settings`);
    const skip = page.getByRole('button', { name: 'Skip this version', exact: true });
    const snooze = page.getByRole('button', { name: 'Remind me later', exact: true });
    await skip.waitFor();
    await page.evaluate(() => document.fonts.ready);
    await settleFrames(page);
    const notice = page.locator('#update-notice');
    const before = await notice.boundingBox();
    const request = page.waitForRequest((request) => request.url().endsWith('/api/updates/skip'));
    await skip.click();
    await request;
    assert.equal(await skip.isDisabled(), true);
    assert.equal(await snooze.isDisabled(), true);
    assert.equal(await notice.isVisible(), true);
    assert.equal(await page.locator('#refresh').getAttribute('aria-busy'), 'true');
    await settleFrames(page);
    assert.deepEqual(await notice.boundingBox(), before, 'Pending mutation does not insert a loading row');
    release();
    await notice.waitFor({ state: 'hidden' });
    const sent = options.requests.filter((request) => request.path === '/api/updates/skip');
    assert.equal(sent.length, 1);
    assert.equal(sent[0].method, 'POST');
    assert.equal(sent[0].headers['x-ae2-request'], 'true');
    assert.deepEqual(sent[0].body, { tag: options.availableUpdate.tag });
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await notice.isVisible(), false);
    options.availableUpdate = { ...options.availableUpdate, tag: '1.7.10-10.0.1', version: '10.0.1', skipped: false };
    await page.reload();
    await skip.waitFor();
    assert.match(await notice.innerText(), /10\.0\.1/);
});

test('failed and stale update skip keep notice usable and never retry the mutation automatically', async (t) => {
    const { page, options, base } = await fixture(t);
    options.isOutdated = true;
    options.isAdmin = true;
    await page.goto(`${base}#/web-settings`);
    const notice = page.locator('#update-notice');
    const skip = page.getByRole('button', { name: 'Skip this version', exact: true });
    for (const status of ['INTERNAL_ERROR', 'UPDATE_CHANGED']) {
        options.skipUpdateStatus = status;
        await skip.click();
        await notice.getByRole('alert').waitFor();
        assert.equal(await skip.isEnabled(), true);
        assert.equal(await page.getByRole('button', { name: 'Remind me later' }).isEnabled(), true);
        assert.equal(await notice.isVisible(), true);
        const count = options.requests.filter((request) => request.path === '/api/updates/skip').length;
        await page.clock.fastForward(6000);
        assert.equal(options.requests.filter((request) => request.path === '/api/updates/skip').length, count);
        assert.equal(options.availableUpdate.skipped, false);
    }
    await page.getByRole('button', { name: 'Dismiss update notice' }).click();
    assert.equal(await notice.isVisible(), false);
});

test('update notice needs release metadata and an unconfirmed skip is reconciled by reloading server state', async (t) => {
    const { page, options, base } = await fixture(t);
    options.isOutdated = true;
    options.isAdmin = true;
    const update = options.availableUpdate;
    options.availableUpdate = null;
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    const notice = page.locator('#update-notice');
    assert.equal(await notice.isVisible(), false);
    options.availableUpdate = update;
    await page.reload();
    const skip = page.getByRole('button', { name: 'Skip this version', exact: true });
    await skip.waitFor();
    await page.route('**/api/updates/skip', async (route) => {
        await route.fetch();
        await route.fulfill({ status: 200, contentType: 'text/html', body: 'Broken proxy response' });
    });
    await skip.click();
    await notice.getByRole('alert').waitFor();
    assert.match(await notice.getByRole('alert').innerText(), /confirm/);
    assert.equal(await notice.isVisible(), true);
    assert.equal(options.availableUpdate.skipped, true);
    await page.clock.fastForward(6000);
    assert.equal(options.requests.filter((request) => request.path === '/api/updates/skip').length, 1);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await notice.isVisible(), false);
});

test('phone navigation and resource panels reuse controls with modal focus and crafting transitions', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 390, height: 844 } },
        { mockClock: false }
    );
    await page.goto(`${base}#/grids/${gridA}/items`);
    const menu = page.getByRole('button', { name: 'Navigation', exact: true });
    await menu.waitFor();
    assert.equal(await page.getByRole('link', { name: 'Web settings', exact: true }).isVisible(), false);
    await menu.click();
    await page.getByRole('dialog', { name: 'Navigation', exact: true }).waitFor();
    await clickTab(page, 'Web settings');
    await page.getByRole('heading', { name: 'Web settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('dialog').count(), 0);
    await menu.click();
    await clickTab(page, 'Terminal');
    const item = page.locator('#items .item').filter({ hasText: 'Iron Ingot' });
    await item.click();
    const details = page.getByRole('dialog', { name: 'Resource details', exact: true });
    await details.waitFor();
    await details.getByRole('button', { name: 'Craft', exact: true }).click();
    const craft = page.getByRole('dialog', { name: 'Craft', exact: true });
    await craft.waitFor();
    assert.equal(await details.isVisible(), false);
    assert.equal(await page.getByRole('dialog').count(), 1);
    await page.keyboard.press('Escape');
    await craft.waitFor({ state: 'hidden' });
    const filters = page.getByRole('button', { name: 'Filters and sorting', exact: true });
    await filters.click();
    const filterPanel = page.getByRole('dialog', { name: 'Filters and sorting', exact: true });
    await filterPanel.getByRole('button', { name: /Availability: All/ }).click();
    await page.keyboard.press('Escape');
    assert.equal(await filters.evaluate((node) => document.activeElement === node), true);
    await filters.click();
    await page.setViewportSize({ width: 1280, height: 850 });
    await filterPanel.waitFor({ state: 'hidden' });
    assert.equal(
        await page
            .locator('#terminal-tools')
            .getByRole('button', { name: /Availability: Stored/ })
            .isVisible(),
        true
    );
    assert.equal(await page.getByRole('link', { name: 'Web settings', exact: true }).isVisible(), true);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('phone CPU plan and Home selectors open on demand and reach their last entries', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 390, height: 844 } },
        { mockClock: false }
    );
    options.cpus = Object.fromEntries(
        Array.from({ length: 40 }, (_, index) => [
            `cpu-${index}`,
            { ...cpu, name: `Processor ${String(index).padStart(2, '0')}` }
        ])
    );
    options.cpuDetails['cpu-0'] = cpuWork;
    options.cpuDetails['cpu-39'] = cpuWork;
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-0`);
    const cpus = page.getByRole('button', { name: 'CPUs', exact: true });
    await cpus.click();
    const panel = page.getByRole('dialog', { name: 'CPUs', exact: true });
    await panel.getByRole('link', { name: /Processor 39/ }).click();
    await page.waitForURL(/cpus\/cpu-39$/);
    await panel.waitFor({ state: 'hidden' });
    await page.goto(`${base}#/grids/${gridA}/plans/17`);
    await cpus.click();
    await panel.getByRole('radio', { name: /cpu-39/ }).check();
    await page.getByRole('button', { name: 'Close panel', exact: true }).click();
    assert.equal(await cpus.evaluate((node) => document.activeElement === node), true);
    await cpus.click();
    assert.equal(await panel.getByRole('radio', { name: /cpu-39/ }).isChecked(), true);
    await page.keyboard.press('Escape');
    await page.goto(base);
    await page.getByRole('button', { name: 'Network', exact: true }).click();
    await page
        .getByRole('dialog', { name: 'Network', exact: true })
        .getByRole('button', { name: 'Choose a network', exact: true })
        .click();
    const networks = page.getByRole('dialog', { name: 'Choose a network', exact: true });
    await networks.waitFor();
    assert.equal(await page.getByRole('dialog').count(), 1);
    await networks.getByRole('link').last().click();
    await page.waitForURL(new RegExp(`/grids/${gridB}/items$`));
    await networks.waitFor({ state: 'hidden' });
});

test('phone icon slots use available width and account identity stays readable', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 390, height: 844 } },
        { mockClock: false }
    );
    await page.goto(base);
    await atlasFixture(page, options);
    await page.evaluate(() =>
        localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ terminalDisplay: 'icons' }))
    );
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.reload();
    for (const width of [390, 360]) {
        await page.setViewportSize({ width, height: 844 });
        const item = page.locator('#items[data-display="icons"] .item').first();
        await item.waitFor();
        const slot = await item.boundingBox();
        assert.ok(slot.width >= 48 && slot.width < 64);
        assert.ok(Math.abs(slot.width - slot.height) < 1);
        const icon = await item.locator('.resource-icon').boundingBox();
        assert.equal(icon.width, 32);
        const user = page.locator('#username');
        assert.equal(await user.innerText(), options.username);
        assert.equal(await user.evaluate((node) => node.scrollWidth <= node.clientWidth), true);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        const window = await page.locator('#window').boundingBox();
        assert.ok(window.width >= width - 16);
    }
});

test('phone panels survive polling and dismiss current navigation and backdrop without background actions', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '', { viewport: { width: 390, height: 844 } });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items .item').first().waitFor();
    const menu = page.getByRole('button', { name: 'Navigation', exact: true });
    await menu.click();
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    const filters = page.getByRole('button', { name: 'Filters and sorting', exact: true });
    await filters.click();
    const panel = page.getByRole('dialog', { name: 'Filters and sorting', exact: true });
    const sort = panel.getByRole('button', { name: 'Sort by: Name', exact: true });
    await sort.focus();
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/items'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.equal(await panel.isVisible(), true);
    assert.equal(await sort.evaluate((node) => document.activeElement === node), true);
    const requests = options.requests.length;
    const refresh = await page.locator('#refresh').boundingBox();
    await page.mouse.click(refresh.x + refresh.width / 2, refresh.y + refresh.height / 2);
    await panel.waitFor({ state: 'hidden' });
    assert.equal(options.requests.length, requests, 'A backdrop tap cannot trigger background refresh');
    assert.equal(await filters.evaluate((node) => document.activeElement === node), true);
});

test('phone sheets fit short viewports and keep the last resource action reachable', async (t) => {
    const { page, base } = await fixture(t, '', { viewport: { width: 600, height: 360 } }, { mockClock: false });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items .item').filter({ hasText: 'Iron Ingot' }).click();
    const details = page.getByRole('dialog', { name: 'Resource details', exact: true });
    const craft = details.getByRole('button', { name: 'Craft', exact: true });
    await craft.scrollIntoViewIfNeeded();
    const sheet = await details.boundingBox();
    const action = await craft.boundingBox();
    assert.ok(sheet.y >= 0 && sheet.y + sheet.height <= 360);
    assert.ok(action.y >= sheet.y && action.y + action.height <= sheet.y + sheet.height);
    await craft.click();
    const popup = page.getByRole('dialog', { name: 'Craft', exact: true });
    await popup.getByRole('button', { name: 'Calculate plan', exact: true }).scrollIntoViewIfNeeded();
    const bounds = await popup.boundingBox();
    assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 360);
    await page.keyboard.press('Escape');
    await popup.waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('dialog').count(), 0);
});

test('phone panel icon buttons belong to the window heading with search below', async (t) => {
    const { page, base } = await fixture(t, '', { viewport: { width: 360, height: 800 } }, { mockClock: false });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const filters = page.getByRole('button', { name: 'Filters and sorting', exact: true });
    await filters.waitFor();
    await page.evaluate(() => document.fonts.ready);
    await settleFrames(page);
    const heading = await page.getByRole('heading', { name: 'Terminal', exact: true }).boundingBox();
    const button = await filters.boundingBox();
    const search = await page.getByRole('searchbox', { name: 'Search resources', exact: true }).boundingBox();
    assert.ok(Math.abs(button.y + button.height / 2 - heading.y - heading.height / 2) < 2);
    assert.ok(button.x >= heading.x + heading.width);
    assert.ok(search.y >= button.y + button.height);
    assert.equal((await filters.innerText()).trim(), '', 'The opener is icon-only with an accessible name');
    await page.locator('#items .item').first().click();
    await page.getByRole('button', { name: 'Close panel', exact: true }).click();
    const details = page.getByRole('button', { name: 'Resource details', exact: true });
    assert.equal((await details.innerText()).trim(), '');
    await details.click();
    await page.getByRole('dialog', { name: 'Resource details', exact: true }).waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await details.evaluate((node) => document.activeElement === node), true);
});

// Public theme/browser/HTTP seam: recovery must release presentation resources while preserving
// successful server data and the application operation already started by the user's action.
test('theme partial mount failure releases external nodes and aborts event handlers before retry', async (t) => {
    const { page, options, base } = await fixture(t);
    options.themeEntry = `
        import { boot } from './assets/web/app/boot.js';
        let mounts = 0;
        boot(document.getElementById('app'), {
            id: 'mount-fault', name: 'Mount fault', supportedModVersion: '1.2.x', views: ['home'],
            mount(root, application, context) {
                mounts++;
                const external = document.createElement('aside');
                external.textContent = 'External presentation resource';
                external.dataset.external = String(mounts);
                document.body.append(external);
                context.lifetime.onCleanup(() => external.remove());
                window.addEventListener('theme-probe', () => { document.title += '!'; }, { signal: context.lifetime.signal });
                if (mounts === 1) throw new Error('Test theme partial mount failure');
                const output = document.createElement('output');
                output.setAttribute('aria-label', 'Theme data');
                root.append(output);
                application.subscribe(state => { output.textContent = state.gridStatus; });
            }
        });`;
    await page.goto(base);
    await page.getByRole('dialog').getByRole('alert').waitFor();
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await page.locator('#app').getByRole('alert').waitFor();
    assert.equal(await page.locator('[data-external]').count(), 0);
    await page.evaluate(() => window.dispatchEvent(new Event('theme-probe')));
    assert.equal(await page.title(), 'Theme fixture');
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await page.getByRole('status', { name: 'Theme data' }).filter({ hasText: 'ready' }).waitFor();
    assert.equal(await page.locator('[data-external]').count(), 1);
    await page.evaluate(() => window.dispatchEvent(new Event('theme-probe')));
    assert.equal(await page.title(), 'Theme fixture!');
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 1);
});

test('theme render failure preserves loaded resources and pending crafting without replay on retry', async (t) => {
    const { page, options, base } = await fixture(t);
    let release;
    const pending = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/grids/*/crafting-plans', async (route) => {
        await pending;
        await route.continue();
    });
    options.themeEntry = `
        import { boot } from './assets/web/app/boot.js';
        let mounts = 0;
        boot(document.getElementById('app'), {
            id: 'render-fault', name: 'Render fault', supportedModVersion: '1.2.x', views: ['items', 'plan'],
            mount(root, application) {
                mounts++;
                const output = document.createElement('output');
                output.setAttribute('aria-label', 'Theme data');
                const calculate = document.createElement('button');
                calculate.textContent = 'Calculate';
                calculate.onclick = () => application.crafting.create(application.state.items[0], 12);
                root.append(output, calculate);
                application.subscribe(state => {
                    if (mounts === 1 && state.crafting.mutation === 'create') throw new Error('Test renderer failure');
                    output.textContent = JSON.stringify({
                        itemStatus: state.itemStatus, items: state.items.map(item => item.displayName),
                        grids: state.grids.length, mutation: state.crafting.mutation, craftingStatus: state.crafting.status
                    });
                    calculate.disabled = state.itemStatus !== 'ready' || !!state.crafting.mutation;
                });
            }
        });`;
    await page.goto(`${base}#/grids/${gridA}/items`);
    const data = page.getByRole('status', { name: 'Theme data' });
    await data.filter({ hasText: 'Iron Ingot' }).waitFor();
    const request = page.waitForRequest(
        (request) => request.method() === 'POST' && request.url().endsWith('/crafting-plans')
    );
    await page.getByRole('button', { name: 'Calculate', exact: true }).click();
    await request;
    await page.getByRole('dialog').getByRole('alert').waitFor();
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await page.locator('#app').getByRole('alert').waitFor();
    const reads = options.requests.filter(
        (request) => request.path.startsWith('/api/') && request.method === 'GET'
    ).length;
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    const snapshot = JSON.parse(await data.textContent());
    assert.equal(snapshot.itemStatus, 'ready');
    assert.deepEqual(snapshot.items, [iron.displayName, quartz.displayName]);
    assert.equal(snapshot.grids, 2);
    assert.equal(snapshot.mutation, 'create');
    assert.equal(await page.getByRole('button', { name: 'Calculate', exact: true }).isDisabled(), true);
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method === 'GET').length,
        reads
    );
    release();
    await page.waitForURL(`**/#/grids/${gridA}/plans/7`);
    await data.filter({ hasText: '"craftingStatus":"ready"' }).waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 1);
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 1);
});

test('a restored cached page reloads its disposed application', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('searchbox', { name: 'Search resources', exact: true }).waitFor();
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
    const reload = page.waitForRequest((request) => new URL(request.url()).pathname === '/api/context');
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
    await reload;
    await page.getByRole('searchbox', { name: 'Search resources', exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 2);
});

test('network history sliders use server policy and save exact independent values on phone and desktop', async (t) => {
    const { page, options, base } = await fixture(t);
    for (const width of [390, 1280]) {
        // Each width saves different values, so the second save is never a no-op.
        const [stock, flow, retention] = width === 390 ? [123, 19, 29] : [124, 20, 30];
        await page.setViewportSize({ width, height: 900 });
        options.settings[gridA] = {
            ...options.settings[gridA],
            isStorageTracked: true,
            isFlowTracked: true,
            limits: {
                stockSampleIntervalSeconds: { min: 5, max: 900, defaultValue: 120 },
                historyMaxRowsPerGrid: 250000,
                flowFlushIntervalSeconds: { min: 7, max: 120, defaultValue: 60 },
                historyRetentionDays: { min: 2, max: 45, defaultValue: 14 }
            }
        };
        await page.goto('about:blank');
        await page.goto(`${base}#/grids/${gridA}/settings`);
        await page.locator('#network-name').waitFor();
        const sliders = page.getByRole('slider');
        assert.equal(await sliders.count(), 4);
        for (const [index, min, max] of [
            [0, 5, 900],
            [1, 7, 120],
            [2, 2, 45]
        ]) {
            const slider = sliders.nth(index);
            assert.equal(await slider.getAttribute('min'), String(min));
            assert.equal(await slider.getAttribute('max'), String(max));
            await slider.focus();
            await slider.press('End');
            assert.equal(await slider.inputValue(), String(max));
            await slider.press('Home');
            assert.equal(await slider.inputValue(), String(min));
        }
        await page.locator('#network-stock-interval').fill(String(stock));
        await page.locator('#network-flow-interval').fill(String(flow));
        await page.locator('#network-history-retention').fill(String(retention));
        assert.deepEqual(await sliders.evaluateAll((nodes) => nodes.map((node) => node.value)), [
            String(stock),
            String(flow),
            String(retention),
            String(options.settings[gridA].craftingHistoryRetentionDays)
        ]);
        assert.match(await sliders.first().getAttribute('aria-valuetext'), new RegExp(`2.*${stock % 60}`));
        const panel = page.locator('.network-tracking-controls');
        await panel.scrollIntoViewIfNeeded();
        const geometry = await panel.evaluate((node) => ({ width: node.clientWidth, scroll: node.scrollWidth }));
        assert.ok(geometry.scroll <= geometry.width + 1, 'Policy controls fit the available width');
        if (process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await panel.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, `network-policy-${width}.png`) });
            await page.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, `network-policy-page-${width}.png`),
                fullPage: true
            });
        }
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('#network-flow-interval')?.disabled === false);
        assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, {
            stockSampleIntervalSeconds: stock,
            flowFlushIntervalSeconds: flow,
            historyRetentionDays: retention
        });
        await page.reload();
        await page.locator('#network-name').waitFor();
        assert.equal(await page.locator('#network-flow-interval').inputValue(), String(flow));
        assert.equal(await page.locator('#network-history-retention').inputValue(), String(retention));
    }
});

test('history policy drafts survive navigation and stricter limits without leaking into another grid', async (t) => {
    const { page, options, base } = await fixture(t);
    options.settings[gridA].isFlowTracked = true;
    options.settings[gridB].isFlowTracked = true;
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const flow = page.locator('#network-flow-interval');
    const retention = page.locator('#network-history-retention');
    const save = page.getByRole('button', { name: 'Save settings', exact: true });
    await flow.fill('110');
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await page.waitForFunction(() => document.querySelector('#network-flow-interval')?.disabled === false);
    assert.equal(await flow.inputValue(), '60', 'A focused numeric draft belongs only to its source grid');
    await retention.fill('12');
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.waitForFunction(() => document.querySelector('#network-flow-interval')?.disabled === false);
    assert.equal(await flow.inputValue(), '110');
    assert.equal(await retention.inputValue(), '7');
    options.settings[gridA].limits.flowFlushIntervalSeconds.max = 90;
    options.settings[gridA].flowFlushIntervalSeconds = 70;
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await page.locator('#refresh').click();
    await refreshed;
    assert.equal(await flow.inputValue(), '110', 'New bounds must not silently replace an edited value');
    assert.equal(await flow.getAttribute('aria-invalid'), 'true');
    assert.equal(await page.locator('#network-flow-interval-range').getAttribute('max'), '90');
    assert.equal(await save.isDisabled(), true);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 0);
    await flow.fill('89');
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    await page.route('**/settings', (route) =>
        route.request().method() === 'PATCH' ? capture(route) : route.continue()
    );
    await save.click();
    const pending = await captured;
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await page.waitForFunction(() => document.querySelector('#network-flow-interval')?.disabled === false);
    assert.equal(await retention.inputValue(), '12');
    await page.goBack();
    assert.equal(await flow.isDisabled(), true);
    options.settings[gridA].flowFlushIntervalSeconds = 85;
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: options.settings[gridA] })
    });
    await page.waitForFunction(() => document.querySelector('#network-flow-interval')?.disabled === false);
    assert.equal(await flow.inputValue(), '85', 'The confirmed response owns the accepted value');
    await page.unroute('**/settings');
    await retention.fill('20');
    options.fault = { operation: 'settings', status: 'INTERNAL_ERROR' };
    await save.click();
    await page
        .getByRole('status')
        .filter({ hasText: /not confirmed/i })
        .waitFor();
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await page.waitForFunction(() => document.querySelector('#network-flow-interval')?.disabled === false);
    await page.goBack();
    await page
        .getByRole('status')
        .filter({ hasText: /not confirmed/i })
        .waitFor();
    assert.equal(await retention.inputValue(), '20');
    assert.equal(
        options.requests.filter((request) => request.method === 'PATCH').length,
        1,
        'Uncertain edits are never replayed'
    );
});

test('resource tracking is independent of CPU history and renders exact flow counts', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.locator('#network-flow-tracking').check();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#network-flow-tracking')?.disabled === false);
    assert.equal(options.settings[gridA].isFlowTracked, true);
    assert.equal(options.settings[gridA].isStorageTracked, false);
    await page.locator('#network-storage-tracking').check();
    await page.locator('#network-stock-interval').fill('60');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#network-storage-tracking')?.disabled === false);
    assert.equal(options.settings[gridA].isStorageTracked, true);
    assert.equal(options.settings[gridA].isTracked, false);
    assert.equal(options.settings[gridA].stockSampleIntervalSeconds, 60);
    await page.locator('#network-stock-interval').fill('0');
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.waitForFunction(() => document.querySelector('#network-flow-tracking')?.disabled === false);
    assert.equal(await page.locator('#network-flow-tracking').isChecked(), true);
    assert.equal(await page.locator('#network-stock-interval').inputValue(), '0');
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#network-flow-tracking')?.disabled === false);
    assert.equal(await page.locator('#network-stock-interval').inputValue(), '60');
    options.resourceHistory = {
        baseline: {
            flowFrom: null,
            observedAt: 1690000000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '1234567890123456789' },
            input: '0',
            output: '0'
        },
        points: [
            {
                flowFrom: null,
                observedAt: 1700000000000,
                generation: 'run',
                kind: 'GAP',
                resource: null,
                input: '0',
                output: '0'
            },
            {
                flowFrom: 1700000000000 - 5000,
                observedAt: 1700000000000,
                generation: 'run',
                kind: 'FLOW',
                resource: null,
                input: '9223372036854775807',
                output: '0'
            }
        ],
        complete: false,
        truncated: true
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items button').first().click();
    await page.locator('.resource-history canvas').waitFor();
    assert.equal(await page.locator('.resource-history ol').count(), 0);
    assert.equal(await page.locator('.resource-history .hint').first().isVisible(), true);
    options.resourceHistory.points = [];
    options.resourceHistory.complete = true;
    options.resourceHistory.truncated = false;
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('.resource-history .hint').hidden);
    await page.locator('.resource-history canvas').waitFor();
    assert.equal(await page.locator('.resource-history .hint').first().isVisible(), false);
});

test('tracking cold links load their own atlas mappings and respect names-only through reload and pack replacement', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.historyIcons = { ...options.icons, pages: [{ digest: 'c'.repeat(64), width: 128, height: 64 }] };
    options.packDelay = 150;
    options.resourceHistory = {
        baseline: {
            flowFrom: null,
            observedAt: Date.now() - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '10', icon: { page: 0, x: 64, y: 0 } }
        },
        points: [],
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    const icon = page.locator('.tracking-view .resource-icon');
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    const reads = () => options.requests.filter((request) => request.path.endsWith('/resource-history'));
    assert.equal(new URLSearchParams(reads()[0].query).has('icons'), false);
    assert.equal(new URLSearchParams(reads().at(-1).query).get('icons'), 'true');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 0);
    assert.ok(
        options.requests.some((request) => request.path.includes(`/pages/${'c'.repeat(64)}`)),
        'Uses history page table rather than terminal page table'
    );
    await page.reload();
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    assert.equal(await icon.isVisible(), true);
    options.packDelay = 0;
    options.pack = { ...options.pack, packId: 'd'.repeat(64) };
    options.historyIcons = { ...options.historyIcons, packId: options.pack.packId };
    const replaced = page.waitForRequest((request) => request.url().includes(`/icon-packs/${'d'.repeat(64)}/`));
    await poll(page);
    await replaced;
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    const atlasReads = options.requests.filter((request) => request.path.includes('/icon-packs/')).length;
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    assert.equal(await icon.isVisible(), false);
    assert.equal(new URLSearchParams(reads().at(-1).query).has('icons'), false);
    assert.equal(options.requests.filter((request) => request.path.includes('/icon-packs/')).length, atlasReads);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 0);
});

test('late tracking icon responses cannot restore images after names-only selection', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    const history = {
        baseline: {
            flowFrom: null,
            observedAt: Date.now() - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '10', icon: { page: 0, x: 0, y: 0 } }
        },
        points: [],
        complete: true,
        truncated: false,
        enabled: true
    };
    options.resourceHistory = history;
    let delayed;
    const intercepted = new Promise((resolve) => {
        page.route('**/resource-history?**', (route) => {
            if (new URL(route.request().url()).searchParams.get('icons') === 'true' && !delayed) {
                delayed = route;
                resolve();
            } else void route.continue();
        });
    });
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await intercepted;
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.evaluate((key) => {
        location.hash = `/grids/${key}/tracking/iron`;
    }, gridA);
    await page.locator('.tracking-view canvas').waitFor();
    await delayed.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: history, icons: options.icons })
    });
    await settleFrames(page);
    assert.equal(await page.locator('.tracking-view .resource-icon:visible').count(), 0);
    assert.equal(options.requests.filter((request) => request.path.includes('/icon-packs/')).length, 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 0);
});

test('tracking uses historical mappings after terminal entry and clears them on denied history reads', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.historyIcons = { ...options.icons, pages: [{ digest: 'c'.repeat(64), width: 128, height: 64 }] };
    options.resourceHistory = {
        baseline: {
            flowFrom: null,
            observedAt: Date.now() - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '10', icon: { page: 0, x: 64, y: 0 } }
        },
        points: [],
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items button').first().click();
    await page.locator('.resource-history canvas').waitFor();
    await page.locator('.resource-history .tracking-open').click();
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    assert.ok(options.requests.some((request) => request.path.includes(`/pages/${'c'.repeat(64)}`)));
    assert.equal(
        await page
            .locator('.tracking-view .resource-icon')
            .evaluate((node) => getComputedStyle(node).backgroundPositionX),
        '-32px'
    );
    options.resourceHistoryError = 'NO_PERMISSIONS';
    await page.locator('#refresh').click();
    await page.waitForFunction(() => !document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    assert.equal(await page.locator('.tracking-view canvas:visible').count(), 0);
});

for (const touch of [false, true]) {
    test(`compact stock trend uses one hour and preserves measured values and gaps (${touch ? 'touch' : 'mouse'})`, async (t) => {
        const { page, options, base } = await fixture(
            t,
            '',
            touch ? { viewport: { width: 390, height: 844 }, hasTouch: true } : {}
        );
        const now = Date.now();
        const stock = (age, quantity) => ({
            flowFrom: null,
            observedAt: now - age,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity }
        });
        options.resourceHistory = {
            baseline: stock(3700000, '9223372036854775807'),
            points: [
                stock(1800000, '9223372036854775808'),
                { flowFrom: null, observedAt: now - 900000, generation: '1', kind: 'GAP', resource: null },
                stock(300000, '42')
            ],
            enabled: true,
            complete: false,
            truncated: false
        };
        await page.goto(`${base}#/grids/${gridA}/items`);
        const request = page.waitForRequest((request) => request.url().includes('/resource-history?'));
        await page.locator('#items button').first().click();
        const first = await request;
        const from = Number(new URL(first.url()).searchParams.get('from'));
        assert.ok(Math.abs(now - from - 3600000) < 5000, 'The first selected-item history request covers one hour');
        if (touch && !(await page.getByRole('dialog', { name: 'Resource details', exact: true }).isVisible()))
            await openMobilePane(page, 'Resource details');
        const chart = page.locator('.resource-history .tracking-chart');
        await chart.locator('canvas').waitFor();
        assert.equal(await page.locator('.resource-history select:visible').count(), 0);
        assert.equal(await chart.locator('.u-axis:visible, .u-legend:visible').count(), 0);
        const overlay = chart.locator('.u-over');
        await overlay.scrollIntoViewIfNeeded();
        const bounds = await overlay.boundingBox();
        const host = await chart.boundingBox();
        assert.ok(bounds.width > host.width * 0.9, 'The sparkline uses its width without axis gutters');
        const selectAt = async (fraction) => {
            const position = { x: bounds.x + bounds.width * fraction, y: bounds.y + bounds.height / 2 };
            if (touch) await page.touchscreen.tap(position.x, position.y);
            else await page.mouse.move(position.x, position.y);
        };
        await selectAt(0.25);
        const readout = chart.locator('.tracking-sparkline-readout');
        await readout.waitFor();
        assert.deepEqual(await chart.boundingBox(), host, 'Hovering the trend does not change its layout');
        assert.ok((await readout.innerText()).replace(/[^0-9]/g, '').includes('9223372036854775807'));
        assert.equal(await readout.locator('time').getAttribute('datetime'), new Date(now - 3700000).toISOString());
        if (touch) {
            await page.mouse.move(1, 1);
            assert.equal(await readout.isVisible(), true);
        }
        await selectAt(0.85);
        assert.match(await readout.innerText(), /—/);
        assert.equal(await readout.locator('time').count(), 0);
        await selectAt(0.97);
        assert.ok((await readout.innerText()).includes('42'));
        assert.equal(await readout.locator('time').getAttribute('datetime'), new Date(now - 300000).toISOString());
        assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 1);
        await page.locator('.resource-history .tracking-open').click();
        await page.locator('.tracking-view canvas').waitFor();
        await Promise.all([
            page.waitForResponse((response) => response.url().includes('/resource-history?')),
            page.locator('.tracking-view select').first().selectOption('86400000')
        ]);
        await page.locator('.tracking-view .tracking-back').click();
        if (touch && !(await page.getByRole('dialog', { name: 'Resource details', exact: true }).isVisible()))
            await openMobilePane(page, 'Resource details');
        await page.locator('.resource-history canvas').waitFor();
        await page.locator('.resource-history .tracking-open').click();
        await page.locator('.tracking-view canvas').waitFor();
        assert.equal(await page.locator('.tracking-view select').first().inputValue(), '86400000');
        const requests = options.requests.filter((request) => request.path.endsWith('/resource-history'));
        const windows = requests.map((request) =>
            Math.round((now - Number(new URLSearchParams(request.query).get('from'))) / 60000)
        );
        assert.deepEqual(windows, [60, 15, 1440, 60, 1440], 'Each view fetches its intended period once');
    });
}

test('tracking opens from resources, retains terminal state, and survives a direct-link refresh', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    options.recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: now,
        flowSince: now - 120000,
        stockSince: now - 3600000,
        flowComplete: true,
        stockComplete: true,
        resources: { iron: { input: '9223372036854775807', output: '8', stockChange: '12' } }
    };
    options.resourceHistory = {
        baseline: {
            flowFrom: null,
            observedAt: now - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '12' }
        },
        points: [
            {
                flowFrom: now - 10000 - 5000,
                observedAt: now - 10000,
                generation: '1',
                kind: 'FLOW',
                input: '9223372036854775807',
                output: '8',
                resource: null
            }
        ],
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#search').fill('iron');
    const item = page.locator('#items button').first();
    await item.hover();
    await page.locator('#resource-tooltip .recent-flow').waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 0);
    assert.equal(await page.locator('#resource-tooltip [aria-label="+9223372036854775807"]').count(), 1);
    await item.click();
    await page.locator('.resource-history canvas').waitFor();
    await page.locator('.resource-history .tracking-open').click();
    await page.locator('.tracking-view canvas').waitFor();
    assert.ok(
        (await page.locator('.tracking-view .tracking-totals').innerText())
            .replace(/[^0-9]/g, '')
            .includes('9223372036854775807')
    );
    await Promise.all([
        page.waitForResponse((response) => response.url().includes('/resource-history?')),
        page.locator('.tracking-view select').first().selectOption('3600000')
    ]);
    await page.locator('.tracking-view select').nth(1).selectOption('smooth');
    await page.locator('.tracking-view .tracking-back').click();
    assert.equal(await page.locator('#search').inputValue(), 'iron');
    await page.locator('#items button').first().click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'View tracking' }).click();
    await page.locator('.tracking-view canvas').waitFor();
    await page.reload();
    await page.locator('.tracking-view canvas').waitFor();
    assert.equal(await page.locator('.tracking-view select').nth(1).inputValue(), 'smooth');
    assert.ok((await page.locator('.tracking-view .tracking-resource-name').innerText()).includes('Iron'));
});

test('tracking chart keeps missing periods blank and pixel rendering survives narrow zoom', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    const stock = (age, quantity) => ({
        flowFrom: null,
        observedAt: now - age,
        generation: '1',
        kind: 'STOCK',
        resource: { ...iron, quantity: String(quantity) }
    });
    options.resourceHistory = {
        baseline: stock(1000000, 10),
        complete: false,
        truncated: false,
        points: [
            stock(750000, 20),
            stock(600000, 50),
            { flowFrom: null, observedAt: now - 500000, generation: '1', kind: 'GAP' },
            stock(250000, 60),
            stock(100000, 30)
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    await page.evaluate(() => document.fonts.ready);
    await settleFrames(page);
    const raster = await page.locator('.tracking-view .uplot').evaluate((root) => {
        const canvas = root.querySelector('canvas'),
            box = canvas.getBoundingClientRect(),
            plot = root.querySelector('.u-over').getBoundingClientRect();
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        const scale = canvas.width / box.width;
        let gold = 0,
            gap = 0;
        for (let y = 0; y < canvas.height; y++)
            for (let x = 0; x < canvas.width; x++) {
                const i = (y * canvas.width + x) * 4;
                if (pixels[i] === 214 && pixels[i + 1] === 155 && pixels[i + 2] === 40) {
                    gold++;
                    const fraction = (x / scale + box.left - plot.left) / plot.width;
                    if (fraction > 0.52 && fraction < 0.65) gap++;
                }
            }
        return { gold, gap };
    });
    assert.ok(raster.gold > 10, 'Known stock is rendered');
    assert.equal(raster.gap, 0, 'No data stroke may cross the missing interval');
    const bounds = await page.locator('.tracking-view .u-over').boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.2, bounds.y + 50);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.2 + 2, bounds.y + 50);
    await page.mouse.up();
    await page.locator('.tracking-view select').nth(1).selectOption('smooth');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
});

test('tracking legend choices survive automatic history refresh and chart rebuilds', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const now = Date.now();
    options.resourceHistory = {
        baseline: {
            flowFrom: null,
            observedAt: now - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '20' }
        },
        complete: true,
        truncated: false,
        points: [
            {
                flowFrom: now - 10000 - 5000,
                observedAt: now - 10000,
                generation: '1',
                kind: 'FLOW',
                input: '12',
                output: '4'
            },
            { flowFrom: now - 5000 - 5000, observedAt: now - 5000, generation: '1', kind: 'FLOW_OBSERVATION' }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    const label = (name) => page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('th');
    await label('Stored').waitFor();
    for (const name of ['Stored', 'Input']) await label(name).click();
    const choices = async () =>
        Promise.all(
            ['Input', 'Output', 'Stored', 'Net'].map((name) =>
                label(name).evaluate((element) => Number(getComputedStyle(element).opacity) >= 1)
            )
        );
    assert.deepEqual(await choices(), [false, true, false, true]);
    const refreshed = page.waitForResponse((response) => response.url().includes('/resource-history?'));
    await page.clock.fastForward(31000);
    await (await refreshed).finished();
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    assert.deepEqual(await choices(), [false, true, false, true], 'Automatic refresh preserves each legend choice');
    await page.locator('.tracking-view select').nth(1).selectOption('smooth');
    assert.deepEqual(await choices(), [false, true, false, true], 'Stroke changes preserve visibility');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(
        () => document.querySelector('.tracking-view canvas').getBoundingClientRect().width < 390
    );
    assert.deepEqual(await choices(), [false, true, false, true], 'Responsive rebuilds preserve visibility');
    const bounds = await page.locator('.tracking-view .u-over').boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.25, bounds.y + 30);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.75, bounds.y + 30);
    await page.mouse.up();
    assert.deepEqual(await choices(), [false, true, false, true], 'Zoom regrouping preserves visibility');
    for (const name of ['Stored', 'Input']) await label(name).click();
    await page.getByRole('button', { name: 'Reset zoom', exact: true }).click();
    assert.deepEqual(await choices(), [true, true, true, true], 'Re-enabled series also survive reset');
});

test('flow grouping adapts from original counters on zoom reset and resize while Pixel Net stays crisp', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Math.floor(Date.now() / 30000) * 30000;
    await page.clock.setFixedTime(now);
    const amount = 100000000000000003n;
    const points = [];
    for (let age = 900000, index = 0; age >= 0; age -= 5000, index++) {
        const common = { observedAt: now - age, generation: '1' };
        points.push({
            flowFrom: common.observedAt - 5000,
            ...common,
            kind: 'FLOW',
            input: String(amount),
            output: String(amount - BigInt(7 * ((index % 3) + 1)))
        });
        points.push({ flowFrom: common.observedAt - 5000, ...common, kind: 'FLOW_OBSERVATION' });
        if (age === 600000)
            points.push({ flowFrom: null, ...common, kind: 'STOCK', resource: { ...iron, quantity: '60' } });
    }
    options.resourceHistory = {
        baseline: {
            flowFrom: null,
            observedAt: now - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '20' }
        },
        points,
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.waitForFunction(
        () =>
            document.querySelector('.tracking-view .uplot') &&
            document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false'
    );
    await page.evaluate(() => document.fonts.ready);
    const area = page.locator('.tracking-view .u-over');
    const value = async (name) =>
        BigInt(
            (
                await page
                    .locator('.tracking-view .u-legend tr')
                    .filter({ hasText: name })
                    .locator('.u-value')
                    .innerText()
            ).replace(/[^0-9-]/g, '')
        );
    const hover = async (position) => {
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        const box = await area.boundingBox();
        await page.mouse.move(box.x + box.width * position, box.y + 40);
        return box;
    };
    let box = await hover(0.5);
    const coarse = await value('Input');
    assert.ok(coarse > amount && coarse % amount === 0n, 'The full range groups multiple raw flushes');
    const inputCoarse = coarse;
    await page.mouse.move(box.x + box.width * 0.48, box.y + 40);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.52, box.y + 40);
    await page.mouse.up();
    await hover(0.5);
    assert.equal(await value('Input'), amount, 'Zoom exposes original five-second counters, not sums of previous bins');
    assert.ok([7n, 14n, 21n].includes(await value('Net')));
    box = await hover(0.5);
    await page.mouse.click(box.x + box.width * 0.5, box.y + 40);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.querySelector('.tracking-view .tracking-chart-pinned'));
    assert.equal(await value('Input'), amount);
    await page.locator('.tracking-view .tracking-toolbar button').click();
    await hover(0.5);
    const phone = await value('Input');
    assert.ok(phone > inputCoarse && phone % amount === 0n, 'A narrow full-range chart uses wider, exact bins');
    await page.setViewportSize({ width: 1280, height: 900 });
    await hover(0.5);
    assert.equal(await value('Input'), inputCoarse, 'Returning to desktop reaggregates the original counters');
    options.resourceHistory = {
        ...options.resourceHistory,
        points: points.map((point) =>
            point.kind === 'FLOW'
                ? { ...point, input: '100', output: String(20 + (((point.observedAt - now + 900000) / 5000) % 7) * 10) }
                : point
        )
    };
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    for (const name of ['Input', 'Output'])
        await page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('th').click();
    const raster = async () =>
        page.locator('.tracking-view canvas').evaluate((canvas) => {
            const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
            let blue = 0,
                translucent = 0;
            for (let i = 0; i < data.length; i += 4)
                if (
                    Math.abs(data[i] - 128) <= 2 &&
                    Math.abs(data[i + 1] - 201) <= 2 &&
                    Math.abs(data[i + 2] - 237) <= 2 &&
                    data[i + 3] > 0
                ) {
                    blue++;
                    if (data[i + 3] < 255) translucent++;
                }
            return { blue, translucent };
        });
    let pixels = await raster();
    assert.ok(pixels.blue > 20);
    assert.equal(pixels.translucent, 0, 'Pixel Net has a hard-edged raster');
    await page.locator('.tracking-view select').nth(1).selectOption('smooth');
    pixels = await raster();
    assert.ok(pixels.blue > 20 && pixels.translucent > 0, `Smooth Net retains antialiasing: ${JSON.stringify(pixels)}`);
    for (const name of ['Input', 'Output'])
        await page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('th').click();
    options.resourceHistory = {
        ...options.resourceHistory,
        complete: false,
        points: [
            {
                flowFrom: now - 900000 - 5000,
                observedAt: now - 900000,
                generation: '1',
                kind: 'FLOW',
                input: '11',
                output: '0'
            },
            { flowFrom: null, observedAt: now - 600000, generation: '1', kind: 'GAP' },
            ...[450000, 447000].flatMap((age, index) => [
                {
                    flowFrom: now - age - 3000,
                    observedAt: now - age,
                    generation: '1',
                    kind: 'FLOW',
                    input: String(index ? 7 : 5),
                    output: '0'
                },
                { flowFrom: now - age - 3000, observedAt: now - age, generation: '1', kind: 'FLOW_OBSERVATION' }
            ])
        ]
    };
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    assert.equal(
        await page.locator('.tracking-view .tracking-count-full').first().innerText(),
        '12',
        'Full-range totals exclude the flush ending exactly at the range start'
    );
    box = await hover(0.48);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.52, box.y + 40);
    await page.mouse.up();
    box = await hover(0.55);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.57, box.y + 40);
    await page.mouse.up();
    await hover(0.5);
    assert.equal(await value('Input'), 7n, 'The first cropped bin excludes the previous flush at its left boundary');
    const intervalLabel = await page
        .locator('.tracking-view .u-legend tr')
        .filter({ hasText: 'Time' })
        .locator('.u-value')
        .innerText();
    const times = [...intervalLabel.matchAll(/(\d{2}):(\d{2}):(\d{2})/g)].map(
        (match) => Number(match[1]) * 3600 + Number(match[2]) * 60 + Number(match[3])
    );
    assert.equal(
        (times[1] - times[0] + 86400) % 86400,
        3,
        'Fine zoom reaches an observed cadence outside the nice-interval list'
    );
    assert.equal(
        await page.locator('.tracking-view .u-legend tr').filter({ hasText: 'Stored' }).locator('.u-value').innerText(),
        '—',
        'Cropping after an offscreen gap never revives an earlier stock baseline'
    );
});
