const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {
    gridA,
    gridB,
    defaultSettingsLimits,
    iron,
    quartz,
    readyPlan,
    cpu,
    cpuWork,
    historyEntry,
    historyDetail,
    refreshingFixture,
    fixture,
    clickTab,
    openMobilePane,
    settleFrames,
    settleResponse,
    poll,
    seedTerminalPageSize,
    textStyle
} = require('./fixtures.cjs');

// Public seam: global settings are navigable without a grid, preserve the selected grid's
// links, and persist browser preferences without pretending to mutate server settings.
test('global settings tabs preserve grid navigation and browser preferences', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    assert.match(page.url(), /#\/web-settings$/);
    const header = page.locator('#app header');
    await header.getByText(options.username, { exact: true }).waitFor();
    await header.getByRole('button', { name: 'Log out', exact: true }).waitFor();
    assert.equal(await header.getByRole('combobox').count(), 0);
    await page.getByRole('button', { name: 'Choose a theme' }).click();
    const theme = page.getByRole('combobox', { name: 'Choose a theme', exact: true });
    assert.equal(await theme.isEnabled(), true);
    assert.equal(await theme.inputValue(), '');
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    const appearance = page.getByRole('combobox', { name: 'Appearance', exact: true });
    await appearance.selectOption('dark');
    await clickTab(page, 'Server settings');
    assert.match(page.url(), /#\/server-settings$/);
    await page.getByRole('heading', { name: 'Server settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Appearance', exact: true }).isVisible(), false);
    assert.equal(
        await page.getByRole('link', { name: 'Network', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/settings`
    );
    await clickTab(page, 'Network');
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).waitFor();
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.reload();
    const language = page.getByRole('combobox', { name: 'Język', exact: true });
    await language.waitFor();
    assert.equal(await language.inputValue(), 'pl');
    assert.equal(await page.getByRole('combobox', { name: 'Wygląd', exact: true }).inputValue(), 'dark');
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

test('global settings direct routes work without an available grid', async (t) => {
    const { page, options, base } = await fixture(t);
    options.empty = true;
    for (const [route, heading] of [
        ['web-settings', 'Web settings'],
        ['server-settings', 'Server settings']
    ]) {
        await page.goto(`${base}#/${route}`);
        await page.getByRole('heading', { name: heading, exact: true }).waitFor();
        assert.match(page.url(), new RegExp(`#/${route}$`));
    }
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/grids/')).length, 0);
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

// Public seam: About identifies the running mod and the resources actually included in
// this theme; it is a read-only global destination and does not discard grid navigation.
test('About shows runtime metadata and resource links while retaining the selected grid', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('link', { name: 'About', exact: true }).click();
    assert.match(page.url(), /#\/about$/);
    const about = page.getByRole('heading', { name: 'About', exact: true }).locator('..');
    await about.getByText(options.modVersion).waitFor();
    await about.getByText('AE2 Web Integration', { exact: true }).waitFor();
    await about.getByText('kuba6000').first().waitFor();
    await about.getByText('LGPL-3.0-or-later').first().waitFor();
    await about.getByText('Default', { exact: true }).waitFor();
    await about.getByText('Monocraft', { exact: true }).waitFor();
    for (const family of ['Monocraft', 'Lato']) {
        await about
            .getByRole('row')
            .filter({ has: page.getByRole('link', { name: family, exact: true }) })
            .getByText(/SIL Open Font License\s*1\.1/)
            .waitFor();
    }
    await about
        .getByRole('row')
        .filter({ hasText: 'HackerNoon Pixel Icon Library' })
        .getByText('MIT', { exact: true })
        .waitFor();
    for (const href of [
        'https://github.com/kuba6000/AE2-Web-Integration',
        'https://github.com/kuba6000/AE2-Web-Integration/issues',
        'https://github.com/IdreesInc/Monocraft',
        'https://github.com/google/fonts/tree/main/ofl/lato',
        'https://github.com/hackernoon/pixel-icon-library'
    ])
        assert.ok(await about.locator(`a[href="${href}"]`).count(), `About must link to ${href}`);
    await clickTab(page, 'Network');
    assert.match(page.url(), new RegExp(`#/grids/${gridA}/settings$`));
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).waitFor();
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

test('About opens without a grid and remains usable in a translated narrow viewport', async (t) => {
    const { page, options, base } = await fixture(t);
    options.empty = true;
    await page.setViewportSize({ width: 375, height: 740 });
    await page.goto(`${base}#/about`);
    await page.getByRole('heading', { name: 'About', exact: true }).waitFor();
    await page.getByText(options.modVersion).waitFor();
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/grids/')).length, 0);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    const aboutLink = page.locator('a[href="#/about"]');
    assert.notEqual(await aboutLink.innerText(), 'About');
    await page.getByRole('button', { name: 'Nawigacja', exact: true }).click();
    await aboutLink.click();
    await page.getByText(options.modVersion).waitFor();
    assert.equal(await page.locator('html').getAttribute('lang'), 'pl');
    assert.equal(await page.getByRole('heading', { name: 'Included resources', exact: true }).count(), 0);
    assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
        true,
        'About metadata and navigation must fit a narrow viewport without page-level horizontal scrolling'
    );
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

test('Home retains the selected network and owns switching while Web settings owns automatic refresh', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await clickTab(page, 'Home');
    await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
    const network = page.getByRole('button', { name: 'Choose a network', exact: true });
    assert.equal(
        await page.getByRole('link', { name: 'Terminal', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/items`
    );
    assert.equal(
        await page.getByRole('link', { name: 'Network', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/settings`
    );
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Refresh', exact: true }).count(), 1);
    assert.equal(
        await page.locator('#app header').getByRole('button', { name: 'Refresh', exact: true }).isVisible(),
        true
    );
    assert.equal(await page.getByRole('checkbox', { name: 'Automatic updates' }).count(), 0);
    assert.equal(await page.locator('#app header').getByRole('combobox').count(), 0);
    assert.equal(await page.locator('#selected-network').isVisible(), false);
    await page.locator('#home-network-panel').getByRole('heading', { name: gridA, exact: true }).waitFor();
    const scopedReads = options.requests.filter(
        (request) => request.path.endsWith('/items') || /\/cpus\/[^/]+$/.test(request.path)
    ).length;
    const gridReads = options.requests.filter((request) => request.path.endsWith('/api/grids')).length;
    const cards = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/resource-pins'));
    await poll(page);
    await settleResponse(page, (await cards).request());
    assert.equal(options.requests.filter((request) => request.path.endsWith('/api/grids')).length, gridReads);
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/items') || /\/cpus\/[^/]+$/.test(request.path))
            .length,
        scopedReads
    );
    await network.click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridB) })
        .click();
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await network.count(), 0, 'The network selector belongs only to Home');
    await clickTab(page, 'Web settings');
    const automatic = page.getByRole('checkbox', { name: 'Automatic updates' });
    await automatic.uncheck();
    await page.reload();
    await automatic.waitFor();
    assert.equal(await automatic.isChecked(), false);
    const apiReads = options.requests.filter((request) => request.path.startsWith('/api/')).length;
    await page.clock.fastForward(6000);
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/')).length, apiReads);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

test('selected network survives global page reloads and direct routes take precedence', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
    const terminal = page.getByRole('link', { name: 'Terminal', exact: true });
    for (const route of ['/', '/web-settings', '/server-settings', '/about']) {
        await page.goto(`${base}#${route}`);
        await terminal.waitFor();
        assert.equal(await terminal.getAttribute('href'), `#/grids/${gridA}/items`);
        await page.reload();
        await terminal.waitFor();
        assert.equal(await terminal.getAttribute('href'), `#/grids/${gridA}/items`);
    }
    await page.goto(`${base}#/grids/${gridB}/cpus`);
    await page.getByRole('heading', { name: 'CPUs', exact: true }).waitFor();
    await page.goto(`${base}#/web-settings`);
    await terminal.waitFor();
    assert.equal(await terminal.getAttribute('href'), `#/grids/${gridB}/items`);
    options.grids = [];
    await page.reload();
    await page.getByRole('checkbox', { name: 'Automatic updates' }).waitFor();
    assert.equal(await terminal.isVisible(), false, 'Remembered identity does not grant network access');
    options.grids = undefined;
    await page.reload();
    await terminal.waitFor();
    assert.equal(await terminal.getAttribute('href'), `#/grids/${gridB}/items`);
});

test('real page browses API resources under a proxy prefix, with search and filters', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('quartz');
    await page.getByRole('button', { name: /Certus Quartz Crystal/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('');
    await page.getByRole('button', { name: /^Availability:/ }).click();
    await page.getByRole('button', { name: /^Availability:/ }).click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: /Certus Quartz Crystal/ }).count(), 0);
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/items')).length,
        1,
        'Local search/filter changes reuse the loaded inventory'
    );
});

test('Minecraft item formatting renders readable names and resets decorations on colors and reset codes', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [
        {
            ...iron,
            displayName:
                'Base §aGreen §lBold §oItalic §nUnderlined §mStruck §BColorCleared §L§O§N§MHeavy §RRestored §qUnknown tail§'
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', {
        name: /^Base Green Bold Italic Underlined Struck ColorCleared Heavy Restored §qUnknown tail§/
    });
    await item.waitFor();
    const normal = await textStyle(item, 'Base');
    assert.equal((await textStyle(item, 'Green')).color, 'rgb(85, 255, 85)');
    assert.ok(Number((await textStyle(item, 'Bold')).weight) >= 700);
    assert.equal((await textStyle(item, 'Italic')).italic, 'italic');
    assert.match((await textStyle(item, 'Underlined')).decoration, /underline/);
    const combined = await textStyle(item, 'Struck');
    assert.equal(combined.color, 'rgb(85, 255, 85)');
    assert.ok(Number(combined.weight) >= 700);
    assert.equal(combined.italic, 'italic');
    assert.match(combined.decoration, /underline/);
    assert.match(combined.decoration, /line-through/);
    assert.deepEqual(await textStyle(item, 'ColorCleared'), { ...normal, color: 'rgb(85, 255, 255)' });
    assert.ok(Number((await textStyle(item, 'Heavy')).weight) >= 700);
    assert.deepEqual(await textStyle(item, 'Restored'), normal);
    assert.match(await item.innerText(), /§qUnknown tail§/);
});

test('Minecraft names retain formatting across crafting plans, CPU work and history', async (t) => {
    const { page, options, base } = await fixture(t);
    const displayName = '§aCobalt §lIngot';
    const metadata = {
        displayName,
        registryNamespace: 'example',
        registryPath: 'cobalt_ingot',
        componentCount: 2,
        damage: 7
    };
    const finalOutput = { ...iron, ...metadata, quantity: 12 };
    options.itemsA = [{ ...iron, ...metadata }];
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], ...metadata }] };
    options.cpuDetails = { 'cpu-a': { ...cpuWork, finalOutput, items: [{ ...cpuWork.items[0], ...metadata }] } };
    options.history = [{ ...historyEntry, finalOutput }];
    options.historyDetail = { ...historyDetail, finalOutput, items: [{ ...historyDetail.items[0], ...metadata }] };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Cobalt.*Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('textbox', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const resource = page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Cobalt Ingot/ });
    await resource.waitFor();
    async function assertFormatted(locator) {
        assert.doesNotMatch(await locator.innerText(), /§[0-9a-fk-or]/i);
        assert.equal((await textStyle(locator, 'Cobalt')).color, 'rgb(85, 255, 85)');
        assert.ok(Number((await textStyle(locator, 'Ingot')).weight) >= 700);
    }
    await assertFormatted(resource);
    await resource.hover();
    await page.getByRole('tooltip').getByText('example:cobalt_ingot', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    const planOutput = page.getByRole('paragraph').filter({ hasText: /^Cobalt Ingot × 12$/ });
    await assertFormatted(planOutput);
    assert.notEqual((await textStyle(planOutput, '× 12')).color, 'rgb(85, 255, 85)');
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true, finalOutput } };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const cpuSummary = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-a/ }) });
    await assertFormatted(cpuSummary);
    await cpuSummary.getByRole('link').click();
    const cpuResource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Cobalt Ingot/ });
    await cpuResource.waitFor();
    await assertFormatted(cpuResource);
    await cpuResource.hover();
    assert.equal(await page.getByRole('tooltip').getByText('example:cobalt_ingot', { exact: true }).isVisible(), true);
    const cpuOutput = page.getByRole('paragraph').filter({ hasText: /^Cobalt Ingot\s*× 12$/ });
    await assertFormatted(cpuOutput);
    await clickTab(page, 'History');
    const historyLink = page.getByRole('link', { name: /Cobalt Ingot.*#1/ });
    await historyLink.waitFor();
    await assertFormatted(historyLink);
    await historyLink.click();
    const historyResource = page.getByRole('region', { name: 'Cobalt Ingot', exact: true });
    await historyResource.waitFor();
    await assertFormatted(historyResource);
    await historyResource.locator('summary').click();
    await historyResource.locator('code').waitFor();
    assert.equal(await historyResource.getByText('example:cobalt_ingot', { exact: true }).isVisible(), true);
    await assertFormatted(page.getByRole('heading', { name: 'Cobalt Ingot', level: 3, exact: true }));
    await assertFormatted(historyResource.getByRole('heading', { name: 'Cobalt Ingot', exact: true }));
});

test('Minecraft classic palette, obfuscation and literal HTML remain safe and readable', async (t) => {
    const { page, options, base } = await fixture(t);
    const colors = [
        ['0', 'rgb(0, 0, 0)'],
        ['1', 'rgb(0, 0, 170)'],
        ['2', 'rgb(0, 170, 0)'],
        ['3', 'rgb(0, 170, 170)'],
        ['4', 'rgb(170, 0, 0)'],
        ['5', 'rgb(170, 0, 170)'],
        ['6', 'rgb(255, 170, 0)'],
        ['7', 'rgb(170, 170, 170)'],
        ['8', 'rgb(85, 85, 85)'],
        ['9', 'rgb(85, 85, 255)'],
        ['A', 'rgb(85, 255, 85)'],
        ['B', 'rgb(85, 255, 255)'],
        ['C', 'rgb(255, 85, 85)'],
        ['D', 'rgb(255, 85, 255)'],
        ['E', 'rgb(255, 255, 85)'],
        ['F', 'rgb(255, 255, 255)']
    ];
    const literal = '<img src=x onerror=alert(1)> & <script>alert(2)</script>';
    options.itemsA = [
        { ...iron, displayName: colors.map(([code]) => `§${code}Color${code}`).join(' ') },
        { ...quartz, displayName: `§kSecret Words §aVisible §KMasked §rPlain §qUnknown tail§ ${literal}` }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const palette = page.getByRole('button', { name: /^Color0/ });
    await palette.waitFor();
    for (const [code, color] of colors) assert.equal((await textStyle(palette, `Color${code}`)).color, color);
    const masked = page.getByRole('button', { name: /^Secret Words Visible Masked Plain §qUnknown tail§/ });
    await masked.waitFor();
    const masks = masked.locator('[aria-hidden="true"]').filter({ hasText: '▒' });
    assert.deepEqual(await masks.allTextContents(), ['▒▒▒▒▒▒ ▒▒▒▒▒ ', '▒▒▒▒▒▒ ']);
    assert.equal((await textStyle(masked, 'Visible')).color, 'rgb(85, 255, 85)');
    await masked.click();
    const details = page.getByRole('heading', { name: /^Secret Words Visible Masked Plain §qUnknown tail§/ });
    await details.waitFor();
    assert.ok((await details.textContent()).includes(literal));
    assert.equal(await page.locator('img[src="x"]').count(), 0, 'Markup in names must remain text');
});

test('Minecraft names search and sort as continuous plain text and keep tooltip formatting through polling', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.itemsA = [
        { ...iron, displayName: '§fAl§apha' },
        { ...quartz, displayName: '§0Beta §qliteral' }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const alpha = page.getByRole('button', { name: /^Alpha/ });
    await alpha.waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).waitFor();
    assert.match(
        await page
            .getByRole('button', { name: /^(Alpha|Beta)/ })
            .first()
            .innerText(),
        /^Alpha/
    );
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    await search.fill('alpha');
    await alpha.waitFor();
    assert.equal(await page.getByRole('button', { name: /^Beta/ }).count(), 0);
    await search.fill('§qliteral');
    await page.getByRole('button', { name: /^Beta/ }).waitFor();
    assert.equal(await alpha.count(), 0, 'Unknown codes remain part of the searchable literal name');
    await search.fill('');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
    await alpha.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor({ state: 'visible' });
    assert.equal((await textStyle(tooltip, 'pha')).color, 'rgb(85, 255, 85)');
    options.itemsA = [{ ...iron, displayName: '§cAlpha', quantity: 8765 }, options.itemsA[1]];
    await poll(page);
    await page.getByRole('button', { name: /^Alpha.*8,765/ }).waitFor();
    assert.equal(await tooltip.isVisible(), true);
    assert.match(await tooltip.innerText(), /8,765/);
    assert.equal((await textStyle(tooltip, 'Alpha')).color, 'rgb(255, 85, 85)');
    assert.doesNotMatch(await tooltip.innerText(), /§[0-9a-fk-or]/i);
    await alpha.click();
    const details = page.getByRole('heading', { name: 'Alpha', exact: true });
    await details.waitFor();
    assert.equal((await textStyle(details, 'Alpha')).color, 'rgb(255, 85, 85)');
});

test('search explains its rules and combines mod identifiers with item text', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [
        iron,
        quartz,
        {
            ...iron,
            displayName: 'Gold Ingot',
            itemKey: 'gold',
            registryNamespace: 'minecraft',
            registryPath: 'gold_ingot'
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await search.hover();
    await page
        .getByRole('tooltip')
        .getByText(/@minecraft iron/)
        .waitFor();
    await search.fill('@MINECRAFT iron');
    const resources = page.getByRole('region', { name: 'Resources', exact: true });
    assert.equal(await resources.getByRole('button').count(), 1);
    await resources.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await search.fill('@ae2');
    assert.equal(await resources.getByRole('button').count(), 1);
    await resources.getByRole('button', { name: /Certus Quartz/ }).waitFor();
    await search.fill('minecraft:gold_ingot');
    await resources.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await search.fill('@missing');
    assert.equal(await resources.getByRole('button').count(), 0);
});

test('craftable resources show an accessible crafting marker that follows refreshed availability', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const marker = item.getByRole('img', { name: 'Crafting available', exact: true });
    await marker.waitFor({ state: 'visible' });
    assert.equal(
        await page
            .getByRole('button', { name: /Certus Quartz Crystal/ })
            .getByRole('img')
            .count(),
        0
    );
    await item.hover();
    await page.getByRole('tooltip').getByText('Crafting available', { exact: true }).waitFor();
    options.itemsA = [{ ...iron, craftable: false }, quartz];
    await poll(page);
    await marker.waitFor({ state: 'hidden' });
    await page.getByRole('tooltip').getByText('Not craftable', { exact: true }).waitFor();
});

test('resource quantities abbreviate from ten thousand and retain exact tooltip amounts', async (t) => {
    const { page, options, base } = await fixture(t);
    const samples = [
        [9999, '9,999'],
        [128640, '128k'],
        [2457600, '2.4M'],
        [1000000000000000000, '1E']
    ];
    options.itemsA = samples.map(([quantity], index) => ({
        ...iron,
        quantity,
        displayName: `Resource ${index}`,
        itemKey: `resource-${index}`
    }));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Resource 0/ }).waitFor();
    await page.evaluate(() => document.fonts.ready);
    for (const [index, [quantity, display]] of samples.entries()) {
        await page.getByRole('searchbox', { name: 'Search resources' }).fill(`Resource ${index}`);
        const item = page.getByRole('button', { name: new RegExp(`^Resource ${index} `) });
        const amount = item.getByText(display, { exact: true });
        await amount.waitFor({ state: 'visible' });
        const marker = item.getByRole('img');
        await item.hover();
        await page
            .getByRole('tooltip')
            .getByText(`Stored: ${quantity.toLocaleString('en')}`, { exact: true })
            .waitFor();
        const itemBox = await item.boundingBox();
        const amountBox = await amount.boundingBox();
        const markerBox = await marker.boundingBox();
        assert.ok(amountBox.x + amountBox.width < itemBox.x + itemBox.width, 'quantity stays inside its slot');
        assert.ok(markerBox.x + markerBox.width < amountBox.x, 'crafting marker does not overlap the quantity');
        assert.ok(await amount.evaluate((node) => node.scrollWidth <= node.clientWidth), 'quantity is not clipped');
    }
});

// Public browser seam: one availability control cycles locally and retains keyboard focus.
test('terminal availability cycles through all stored and craftable with current tooltips', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [iron, quartz, { ...iron, displayName: 'Gold Ingot', itemKey: 'gold', quantity: 0 }];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const availability = page.getByRole('button', { name: /^Availability:/ });
    const tooltip = page.getByRole('tooltip');
    await availability.waitFor();
    assert.equal(await availability.count(), 1);
    assert.equal(await availability.getAttribute('aria-pressed'), null);
    await availability.focus();
    assert.match(await tooltip.textContent(), /All.*Stored/s);
    await page.keyboard.press('Enter');
    assert.match(await availability.getAttribute('aria-label'), /Stored/);
    assert.equal(await page.getByRole('button', { name: /Gold Ingot/ }).count(), 0);
    assert.match(await tooltip.textContent(), /Stored.*Craftable/s);
    await page.keyboard.press('Space');
    assert.equal(await page.getByRole('button', { name: /Certus Quartz Crystal/ }).count(), 0);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await availability.evaluate((button) => document.activeElement === button), true);
    await availability.click();
    await page.getByRole('button', { name: /Certus Quartz Crystal/ }).waitFor();
    assert.match(await availability.getAttribute('aria-label'), /All/);
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
});

test('terminal displays structured registry and optional resource metadata without inventing values', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [
        {
            displayName: 'Damaged Tool',
            registryNamespace: 'example',
            registryPath: 'tool',
            componentCount: 3,
            damage: 7,
            quantity: 2,
            craftable: false,
            resourceType: 'ITEM',
            itemKey: 'tool'
        },
        {
            displayName: 'Plain Tool',
            registryNamespace: 'example',
            registryPath: 'plain',
            componentCount: 0,
            damage: 0,
            quantity: 1,
            craftable: false,
            resourceType: 'ITEM',
            itemKey: 'plain'
        },
        {
            displayName: 'Water',
            registryNamespace: 'minecraft',
            registryPath: 'water',
            componentCount: 0,
            damage: 0,
            quantity: 1000,
            craftable: false,
            resourceType: 'FLUID',
            itemKey: 'water'
        },
        {
            displayName: 'Essentia',
            registryNamespace: null,
            registryPath: null,
            componentCount: 0,
            damage: 0,
            quantity: 12,
            craftable: false,
            resourceType: 'OTHER',
            itemKey: null
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const tool = page.getByRole('button', { name: /Damaged Tool/ });
    await tool.waitFor();
    await tool.hover();
    const tooltip = page.getByRole('tooltip');
    assert.equal(await tooltip.getByText('example:tool', { exact: true }).isVisible(), true);
    assert.match(await tooltip.innerText(), /Components:\s*3/);
    assert.match(await tooltip.innerText(), /Damage:\s*7/);
    await tool.click();
    const details = page.locator('#details');
    assert.equal(await details.getByText('example:tool', { exact: true }).isVisible(), true);
    assert.match(await details.innerText(), /Components:\s*3/);
    assert.match(await details.innerText(), /Damage:\s*7/);
    const plain = page.getByRole('button', { name: /Plain Tool/ });
    await plain.hover();
    assert.doesNotMatch(await tooltip.innerText(), /Components:|Damage:/);
    await plain.click();
    assert.doesNotMatch(await details.innerText(), /Components:|Damage:/);
    await page.getByRole('button', { name: /Water/ }).click();
    assert.doesNotMatch(await details.innerText(), /Components:|Damage:/);
    await page.getByRole('button', { name: /Essentia/ }).click();
    assert.doesNotMatch(await details.innerText(), /Components:|Damage:|null|undefined/);
    assert.equal(await details.locator('code').count(), 0);
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    const names = () => page.locator('#items strong').allTextContents();
    await search.fill('@example');
    assert.deepEqual(await names(), ['Damaged Tool', 'Plain Tool']);
    await search.fill('example:tool');
    assert.deepEqual(await names(), ['Damaged Tool']);
    await search.fill('@tool');
    assert.deepEqual(await names(), [], 'Mod search uses the namespace, not the display name or registry path');
    await search.fill('essentia');
    assert.deepEqual(await names(), ['Essentia']);
    await search.fill('');
    const sort = page.getByRole('button', { name: /^Sort by:/ });
    await sort.click();
    await sort.click();
    assert.deepEqual(await names(), ['Essentia', 'Plain Tool', 'Damaged Tool', 'Water']);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Plain Tool/ }).click();
    assert.doesNotMatch(await details.innerText(), /Komponenty:|Uszkodzenie:/);
    await page.getByRole('button', { name: /Damaged Tool/ }).click();
    assert.match(await details.innerText(), /Komponenty:\s*3/);
    assert.match(await details.innerText(), /Uszkodzenie:\s*7/);
});

test('CPU work, plans and history retain resources with unavailable registry metadata', async (t) => {
    const { page, options, base } = await fixture(t);
    const metadata = {
        displayName: 'Essentia',
        registryNamespace: null,
        registryPath: null,
        componentCount: 0,
        damage: 0
    };
    const finalOutput = { ...metadata, itemKey: null, icon: null, quantity: 12 };
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], ...metadata }] };
    options.cpuDetails = { 'cpu-a': { ...cpuWork, finalOutput, items: [{ ...cpuWork.items[0], ...metadata }] } };
    options.history = [{ ...historyEntry, finalOutput }];
    options.historyDetail = { ...historyDetail, finalOutput, items: [{ ...historyDetail.items[0], ...metadata }] };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resource = page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Essentia/ });
    await resource.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Essentia', { exact: true }).waitFor();
    assert.equal(await tooltip.locator('code').count(), 0);
    assert.doesNotMatch(await tooltip.innerText(), /null|undefined/);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuResource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Essentia/ });
    await cpuResource.hover();
    assert.equal(await tooltip.getByText('Essentia', { exact: true }).isVisible(), true);
    assert.equal(await tooltip.locator('code').count(), 0);
    assert.doesNotMatch(await tooltip.innerText(), /null|undefined/);
    const search = page.getByRole('searchbox', { name: 'Search CPU resources' });
    await search.fill('@example');
    assert.equal(await cpuResource.count(), 0);
    await search.fill('essentia');
    await cpuResource.waitFor();
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const historyResource = page.getByRole('region', { name: 'Essentia', exact: true });
    await historyResource.locator('summary').click();
    await historyResource.getByRole('list', { name: 'Exact intervals', exact: true }).waitFor();
    assert.equal(await historyResource.locator('code').count(), 0);
    await page.getByRole('heading', { name: 'Essentia', level: 3, exact: true }).waitFor();
    assert.doesNotMatch(await historyResource.innerText(), /null|undefined/);
});

test('terminal sort criterion cycles independently of order and persists both', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.itemsA = [
        { ...iron, displayName: 'Alpha', registryNamespace: 'test', registryPath: 'z', itemKey: 'a', quantity: 20 },
        { ...iron, displayName: 'Beta', registryNamespace: 'test', registryPath: 'y', itemKey: 'b', quantity: 30 },
        { ...iron, displayName: 'Gamma', registryNamespace: 'test', registryPath: 'x', itemKey: 'c', quantity: 10 }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Alpha/ }).waitFor();
    const names = () => page.locator('#items strong').allTextContents();
    const sort = page.getByRole('button', { name: /^Sort by:/ });
    const order = page.getByRole('button', { name: /^Sort order:/ });
    assert.equal(await sort.count(), 1);
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Gamma']);
    await order.waitFor();
    await sort.focus();
    await page.keyboard.press('Enter');
    assert.match(await sort.getAttribute('aria-label'), /Quantity/);
    assert.deepEqual(await names(), ['Gamma', 'Alpha', 'Beta']);
    await order.click();
    assert.deepEqual(await names(), ['Beta', 'Alpha', 'Gamma']);
    await sort.click();
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Gamma']);
    await sort.click();
    assert.deepEqual(await names(), ['Gamma', 'Beta', 'Alpha']);
    await page.reload();
    await page.getByRole('button', { name: /Alpha/ }).waitFor();
    assert.deepEqual(await names(), ['Gamma', 'Beta', 'Alpha']);
    assert.equal(await sort.getAttribute('aria-pressed'), null);
    assert.equal(await order.getAttribute('aria-pressed'), null);
});

test('terminal item and fluid switches intersect filters and preserve other resource kinds', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.itemsA = [
        iron,
        quartz,
        {
            ...iron,
            displayName: 'Water',
            registryNamespace: 'minecraft',
            registryPath: 'water',
            itemKey: 'water',
            resourceType: 'FLUID',
            damage: 0
        },
        {
            ...iron,
            displayName: 'Steam',
            registryNamespace: 'addon',
            registryPath: 'steam',
            itemKey: 'steam',
            resourceType: 'FLUID',
            damage: 0,
            quantity: 0
        },
        {
            ...iron,
            displayName: 'Energy',
            registryNamespace: 'addon',
            registryPath: 'energy',
            itemKey: 'energy',
            resourceType: 'OTHER',
            componentCount: 0,
            damage: 0
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const items = page.getByRole('button', { name: /^Items:/ });
    const fluids = page.getByRole('button', { name: /^Fluids:/ });
    await items.waitFor();
    assert.equal(await items.getAttribute('aria-pressed'), 'true');
    assert.equal(await fluids.getAttribute('aria-pressed'), 'true');
    await items.click();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    await page.getByRole('button', { name: /Water/ }).waitFor();
    await page.getByRole('button', { name: /^Availability:/ }).click();
    assert.equal(await page.getByRole('button', { name: /Steam/ }).count(), 0);
    await page.getByRole('searchbox').fill('@minecraft');
    assert.deepEqual(await page.locator('#items strong').allTextContents(), ['Water']);
    await fluids.focus();
    await page.keyboard.press('Space');
    assert.equal(await page.locator('#items button').count(), 0);
    await page.getByRole('searchbox').fill('');
    assert.deepEqual(await page.locator('#items strong').allTextContents(), ['Energy']);
    await page.reload();
    await page.getByRole('button', { name: /Energy/ }).waitFor();
    assert.equal(await items.getAttribute('aria-pressed'), 'false');
    assert.equal(await fluids.getAttribute('aria-pressed'), 'false');
    assert.deepEqual(await page.locator('#items strong').allTextContents(), ['Energy']);
    await items.click();
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    assert.equal(await page.getByRole('heading', { name: 'Iron Ingot', exact: true }).isVisible(), true);
    assert.equal(await fluids.getAttribute('aria-pressed'), 'false');
});

// Missing or invalid saved directions use the current default, independent of the criterion.
for (const saved of [
    { sort: 'quantity', filter: 'craftable', expected: ['Certus Quartz Crystal', 'Iron Ingot'] },
    { sort: 'quantity', filter: 'craftable', sortOrder: 'sideways', expected: ['Certus Quartz Crystal', 'Iron Ingot'] },
    {
        sort: 'quantity',
        filter: 'craftable',
        sortOrder: 'descending',
        expected: ['Iron Ingot', 'Certus Quartz Crystal']
    },
    { sort: 'name', filter: 'stored', expected: ['Certus Quartz Crystal', 'Iron Ingot'] },
    { sort: 'id', filter: 'all', expected: ['Certus Quartz Crystal', 'Iron Ingot'] }
]) {
    test(`terminal restores current ${saved.sort} preferences with ${saved.sortOrder ?? 'missing'} direction`, async (t) => {
        const { page, options, base } = await fixture(t, '/ae2');
        options.itemsA = [iron, { ...quartz, craftable: true }];
        await page.addInitScript(({ sort, filter, sortOrder }) => {
            const key = 'ae2web:/ae2/:ui';
            const stored = JSON.parse(localStorage.getItem(key));
            if (stored.sort === undefined)
                localStorage.setItem(key, JSON.stringify({ ...stored, sort, filter, sortOrder }));
        }, saved);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        assert.deepEqual(await page.locator('#items strong').allTextContents(), saved.expected);
        const order = page.getByRole('button', { name: /^Sort order:/ });
        assert.match(
            await order.getAttribute('aria-label'),
            saved.sortOrder === 'descending' ? /Descending/ : /Ascending/
        );
        await page.getByRole('button', { name: /^Sort by:/ }).click();
        await page.reload();
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        assert.match(
            await order.getAttribute('aria-label'),
            saved.sortOrder === 'descending' ? /Descending/ : /Ascending/
        );
    });
}

test('terminal control focus and dismissed tooltips survive polling and filtering resets pagination', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await seedTerminalPageSize(page, base, 100);
    options.itemsA = Array.from({ length: 102 }, (_, index) => ({
        ...iron,
        displayName: `Item ${String(index).padStart(3, '0')}`,
        itemKey: `item-${index}`,
        quantity: index
    }));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Item 000/ }).waitFor();
    await page.getByRole('button', { name: 'Next page', exact: true }).click();
    await page.getByRole('button', { name: /Item 101/ }).waitFor();
    const availability = page.getByRole('button', { name: /^Availability:/ });
    await availability.focus();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Item 001/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Previous page', exact: true }).isDisabled(), true);
    const tooltip = page.getByRole('tooltip');
    const label = await tooltip.textContent();
    await poll(page);
    assert.equal(await availability.evaluate((button) => document.activeElement === button), true);
    assert.equal(await tooltip.textContent(), label);
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
});

// Public seam: browser controls plus emitted HTTP. A calculation must be explicit, keep its
// quantity/identity, finish without inventory auto-refresh, and submit the chosen stable CPU key.
test('crafting calculates a quantity, polls, preserves CPU identity and submits explicitly', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.pendingReads = 1;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('textbox', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    assert.match(page.url(), /\/plans\/7$/);
    await clickTab(page, 'Web settings');
    await page.getByRole('checkbox', { name: 'Automatic updates' }).check();
    await page.goBack();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    await page
        .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
        .getByRole('radio', { name: /cpu-b/ })
        .check();
    options.cpus = { 'cpu-b': cpu, 'cpu-a': cpu };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await (await refreshed).finished();
    assert.equal(
        await page
            .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
            .getByRole('radio', { name: /cpu-b/ })
            .isChecked(),
        true
    );
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    const create = options.requests.filter(
        (request) => request.method === 'POST' && request.path.endsWith('/crafting-plans')
    );
    assert.equal(create.length, 1);
    assert.deepEqual(create[0].body, { itemKey: 'iron', quantity: 12 });
    assert.equal(create[0].headers['x-ae2-request'], 'true');
    assert.match(create[0].headers['content-type'], /application\/json/);
    assert.deepEqual(
        options.requests.filter((request) => request.path.endsWith('/submit')).map((request) => request.body),
        [{ cpuKey: 'cpu-b' }]
    );
    assert.equal(await page.getByRole('link', { name: 'Inspect CPU work', exact: true }).count(), 0);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
});

// Public seam: history links retain runtime entry identity; completed detail is read-only
// and its snapshot need not be refetched for ordinary polling.
test('history rows keep product names primary and scroll beneath a stationary heading', async (t) => {
    const { page, options, base } = await fixture(t);
    options.history = Array.from({ length: 35 }, (_, index) => ({
        ...historyEntry,
        id: 100 + index,
        wasCancelled: index % 2 === 0,
        finalOutput: { ...iron, displayName: '§bLong precision assembly component', quantity: 12 + index }
    }));
    await page.goto(`${base}#/grids/${gridA}/history`);
    const first = page.getByRole('link', { name: /Long precision assembly component.*#100/ });
    await first.waitFor();
    assert.doesNotMatch(await first.innerText(), /#100/, 'opaque job identity is not the primary title');
    const region = page.getByRole('region', { name: 'History', exact: true });
    const title = page.getByRole('heading', { name: 'History', exact: true });
    const before = await title.boundingBox();
    await region.hover();
    await page.mouse.wheel(0, 900);
    await page.waitForFunction(() => document.querySelector('[role="region"][aria-label="History"]').scrollTop > 0);
    const after = await title.boundingBox();
    assert.ok(Math.abs(before.y - after.y) < 1, 'history heading stays above its contained scroll');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    assert.equal(options.requests.filter((request) => /crafting-history\/\d+$/.test(request.path)).length, 0);
    await first.focus();
    await page.keyboard.press('Enter');
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.match(page.url(), /\/history\/100$/);
    await clickTab(page, 'History');
    await first.waitFor();
});

test('history and CPU share readable millisecond durations and entire history rows navigate', async (t) => {
    const { page, options, base } = await fixture(t);
    const durations = [0, 59999, 60000, 3600000, 86400000, 93784005, 1728000000, 60001, 61999, 3599999];
    const expected = [
        '0 s',
        '59.999 s',
        '1 min',
        '1 h',
        '1 d',
        '1 d 2 h 3 min 4 s',
        '20 d',
        '1 min',
        '1 min 1 s',
        '59 min 59 s'
    ];
    options.history = durations.map((duration, index) => ({
        ...historyEntry,
        id: index + 1,
        timeDone: historyEntry.timeStarted + duration
    }));
    options.historyDetail = {
        ...historyDetail,
        timeDone: historyEntry.timeStarted + 93784005,
        items: [
            {
                ...historyDetail.items[0],
                timeSpentOn: 93784005,
                timings: [{ started: historyEntry.timeStarted, ended: historyEntry.timeStarted + 93784005 }]
            }
        ],
        interfaceShare: [{ ...historyDetail.interfaceShare[0], timingsCombined: 93784005 }]
    };
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true } };
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        timeElapsed: 93784005,
        items: [{ ...cpuWork.items[0], timeSpentCrafting: 93784005 }]
    };
    await page.goto(`${base}#/grids/${gridA}/history`);
    const rows = page.getByRole('listitem');
    await page.getByRole('link', { name: /Iron Ingot.*#7/ }).waitFor();
    for (let index = 0; index < durations.length; index++)
        assert.equal(await rows.nth(index).locator('dd').last().innerText(), expected[index]);
    const row = rows.nth(5);
    await row.locator('time').click();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.match(page.url(), /history\/6$/);
    assert.ok(
        (await page.getByRole('region', { name: 'Iron Ingot', exact: true }).locator('summary').innerText()).includes(
            expected[5]
        )
    );
    const resource = page.getByRole('region', { name: 'Iron Ingot', exact: true });
    await resource.locator('summary').click();
    assert.ok((await resource.innerText()).includes(expected[5]));
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    assert.ok((await page.getByRole('region', { name: 'Smelter', exact: true }).innerText()).includes(expected[5]));
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    await item.waitFor();
    assert.ok((await page.getByRole('region', { name: 'Current job', exact: true }).innerText()).includes(expected[5]));
    await item.focus();
    assert.ok((await page.getByRole('tooltip').innerText()).includes(expected[5]));
    await page.goto(`${base}#/grids/${gridA}/history`);
    const first = page.getByRole('link', { name: /Iron Ingot.*#1$/ });
    await first.waitFor();
    const box = await first.boundingBox();
    await first.click({ position: { x: box.width - 5, y: box.height - 5 } });
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.match(page.url(), /history\/1$/);
    await page.goto(`${base}#/grids/${gridA}/history`);
    await first.focus();
    await page.keyboard.press('Enter');
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.match(page.url(), /history\/1$/);
});

test('history preserves entry identity and opens measured cancelled work through direct routes', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '/ae2');
    options.history = [{ ...historyEntry, id: 2 }, historyEntry];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await page.getByRole('link', { name: /Iron Ingot.*#2/ }).click();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.match(page.url(), /\/history\/2$/);
    await page
        .getByRole('status')
        .filter({ hasText: /cancelled/i })
        .waitFor();
    assert.equal(
        await page.getByRole('region', { name: 'Iron Ingot', exact: true }).getByText('10', { exact: true }).count(),
        1
    );
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await poll(page);
    await (await refreshed).finished();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/crafting-history/2')).length, 1);
    await page.reload();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
});

test('history opens a deeply scrolled job at the start of its detail and preserves ordinary reading position', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.history = Array.from({ length: 30 }, (_, index) => ({ ...historyEntry, id: index + 1 }));
    options.historyDetail = { ...historyDetail, items: Array.from({ length: 20 }, () => historyDetail.items[0]) };
    await page.goto(`${base}#/grids/${gridA}/history`);
    const last = page.getByRole('link', { name: /Iron Ingot.*#30$/ });
    await last.scrollIntoViewIfNeeded();
    const region = page.getByRole('region', { name: 'History', exact: true });
    assert.ok(await region.evaluate((node) => node.scrollTop > 0));
    await last.click();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.equal(await region.evaluate((node) => node.scrollTop), 0, 'new detail starts at its summary');
    await page.getByRole('region', { name: 'Iron Ingot', exact: true }).last().scrollIntoViewIfNeeded();
    const readingPosition = await region.evaluate((node) => node.scrollTop);
    assert.ok(readingPosition > 0);
    await poll(page);
    assert.equal(
        await region.evaluate((node) => node.scrollTop),
        readingPosition,
        'polling does not reset reading position'
    );
    await clickTab(page, 'History');
    await page.getByRole('link', { name: /Iron Ingot.*#1$/ }).waitFor();
    assert.equal(await region.evaluate((node) => node.scrollTop), 0, 'return to the list starts at its beginning');
});

test('history list keeps its terminal alignment and readable rows on mobile in dark mode', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const appearance = 'dark';
    options.settings[gridA].isTracked = true;
    options.history = Array.from({ length: 12 }, (_, index) => ({
        ...historyEntry,
        id: 100 + index,
        wasCancelled: index % 2 === 0,
        finalOutput: {
            ...iron,
            displayName: '§bPrecision assembly component with a long formatted name',
            quantity: 12 + index
        }
    }));
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
    const measure = (locator) =>
        locator.evaluate((node) => {
            const box = node.getBoundingClientRect(),
                style = getComputedStyle(node);
            return {
                x: box.x,
                y: innerWidth <= 600 ? box.y - node.closest('#window').getBoundingClientRect().y : box.y,
                height: box.height,
                right: box.right,
                fontSize: style.fontSize,
                color: style.color
            };
        });
    for (const viewport of [
        { width: 1184, height: 900 },
        { width: 390, height: 844 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const expected = await measure(page.getByRole('heading', { name: 'Terminal', exact: true }));
        const expectedScroll = await measure(page.getByRole('region', { name: 'Resources', exact: true }));
        await clickTab(page, 'History');
        const first = page.getByRole('link', { name: /Precision assembly.*#100/ });
        await first.waitFor();
        const heading = await measure(page.getByRole('heading', { name: 'History', exact: true }));
        for (const key of viewport.width > 600 ? ['x', 'y'] : ['x'])
            assert.ok(Math.abs(heading[key] - expected[key]) < 0.1, `${key} history heading matches terminal`);
        for (const key of ['fontSize', 'color']) assert.equal(heading[key], expected[key]);
        const region = page.getByRole('region', { name: 'History', exact: true });
        const actualScroll = await measure(region);
        for (const key of viewport.width > 600 ? ['x', 'y', 'right'] : ['x', 'right'])
            assert.ok(Math.abs(actualScroll[key] - expectedScroll[key]) < 0.1, `${key} scroll viewport matches`);
        assert.ok(
            heading.y >= 0 && actualScroll.y >= heading.y + heading.height,
            'History content stays below its heading'
        );
        const rows = region.getByRole('listitem');
        assert.equal(await rows.count(), 12);
        assert.deepEqual(
            await rows.getByRole('link').evaluateAll((links) => links.map((link) => link.hash.split('/').at(-1))),
            options.history.map((entry) => String(entry.id))
        );
        assert.equal(await rows.first().locator('time').getAttribute('datetime'), '2023-11-14T22:13:30.000Z');
        for (const row of await rows.all()) {
            const bounds = await row.evaluate((node) => ({ width: node.clientWidth, content: node.scrollWidth }));
            assert.ok(bounds.content <= bounds.width + 1, 'long names and metadata fit their row');
        }
        await first.focus();
        await poll(page);
        assert.equal(await first.evaluate((node) => node === document.activeElement), true);
        assert.equal(await page.getByRole('link', { name: 'Back to resources', exact: true }).count(), 0);
        assert.ok(
            await page.evaluate(
                () =>
                    document.documentElement.scrollWidth <= innerWidth &&
                    document.documentElement.scrollHeight <= innerHeight
            )
        );
    }
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({
            path: path.join(process.env.UI_SCREENSHOT_DIR, `history-mobile-${appearance}.png`)
        });
    }
    await page.getByRole('link', { name: /Precision assembly.*#100/ }).click();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    const historyRegion = page.getByRole('region', { name: 'History', exact: true });
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    const provider = page.getByRole('region', { name: 'Smelter', exact: true });
    await provider.locator('summary').click();
    await provider.locator('time').last().scrollIntoViewIfNeeded();
    assert.ok(await historyRegion.evaluate((node) => node.scrollTop > 0));
    assert.equal(await provider.locator('time').count(), 2);
    assert.ok(
        await page.evaluate(
            () =>
                document.documentElement.scrollWidth <= innerWidth &&
                document.documentElement.scrollHeight <= innerHeight
        )
    );
    if (process.env.UI_SCREENSHOT_DIR) {
        await page.getByRole('heading', { name: /Iron Ingot/, level: 3 }).scrollIntoViewIfNeeded();
        await page.screenshot({
            path: path.join(process.env.UI_SCREENSHOT_DIR, `history-detail-mobile-${appearance}.png`)
        });
    }
    options.history = [options.history[0]];
    await clickTab(page, 'History');
    await page.getByRole('link', { name: /Precision assembly.*#100/ }).waitFor();
    await page.reload();
    await page.getByRole('link', { name: /Precision assembly.*#100/ }).waitFor();
    assert.equal(await page.getByRole('region', { name: 'History', exact: true }).getByRole('listitem').count(), 1);
    options.history = [];
    await page.reload();
    await page
        .getByRole('status')
        .filter({ hasText: /No crafting history/ })
        .waitFor();
    assert.equal(await page.getByRole('region', { name: 'History', exact: true }).getByRole('listitem').count(), 0);
});

// Public seam: an authorized grid user edits a draft and saves one explicit PATCH. Access
// sources are grouped informational server text, never markup or an ownership gate.
test('grid settings preserve the draft and save explicitly while safely showing grouped access sources', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '/ae2');
    const player = { name: '<img src=x onerror=alert(1)> Alex', uuid: '11111111-1111-1111-1111-111111111111' };
    options.accessSources = {
        [player.uuid]: [
            {
                player,
                kind: 'security_terminal',
                reason: 'security_card',
                position: { dimensionId: 'minecraft:overworld', x: 120, y: 64, z: -32 },
                side: 'north'
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'Network', exact: true }).click();
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    const networkName = page.getByRole('textbox', { name: 'Network name', exact: true });
    await networkName.waitFor();
    await page.evaluate(() => document.fonts.ready);
    const formPosition = () =>
        networkName.evaluate((input) => input.getBoundingClientRect().top + input.closest('[role="region"]').scrollTop);
    const cleanPosition = await formPosition();
    await tracking.check();
    assert.equal(await formPosition(), cleanPosition, 'An unsaved notice must not move the form content');
    const draftStatus = page.getByRole('status').filter({ hasText: /unsaved/i });
    await draftStatus.waitFor();
    const saveBounds = await page.getByRole('button', { name: 'Save settings', exact: true }).boundingBox();
    assert.ok((await draftStatus.boundingBox()).y >= saveBounds.y, 'Draft feedback belongs by the save action');
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await (await refreshed).finished();
    assert.equal(await tracking.isChecked(), true);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 0);
    await page.getByText(player.name, { exact: true }).waitFor();
    await page.getByText(player.name, { exact: true }).click();
    await page.getByText(player.uuid, { exact: true }).waitFor();
    await page.getByText(/minecraft:overworld.*120.*64.*-32/).waitFor();
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    const patches = options.requests.filter((request) => request.method === 'PATCH');
    assert.equal(patches.length, 1);
    assert.deepEqual(patches[0].body, { isTracked: true });
    assert.equal(patches[0].headers['x-ae2-request'], 'true');
    assert.match(patches[0].headers['content-type'], /application\/json/);
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
    await page.reload();
    await tracking.waitFor();
    assert.equal(await tracking.isChecked(), true);
});

// Public seam: interval details preserve each server resource row and provider group. Provider
// positions belong to the group; interval bounds are absolute timestamps, even at zero duration.
test('history exposes resource and provider intervals with their correct locations', async (t) => {
    const { page, options, base } = await fixture(t);
    options.historyDetail = {
        ...historyDetail,
        items: [...historyDetail.items, { ...historyDetail.items[0], timings: [] }]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('list', { name: 'Resources', exact: true }).waitFor();
    assert.equal(await page.getByRole('region', { name: 'Iron Ingot', exact: true }).count(), 2);
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    const provider = page.getByRole('region', { name: 'Smelter', exact: true });
    await provider.locator('summary').click();
    await provider.getByText(/minecraft:overworld.*120.*64.*-32/).waitFor();
    assert.deepEqual(await provider.locator('time').evaluateAll((times) => times.map((time) => time.dateTime)), [
        '2023-11-14T22:13:21.000Z',
        '2023-11-14T22:13:26.000Z'
    ]);
    await page.getByRole('button', { name: 'Resources', exact: true }).click();
    assert.equal(await page.getByRole('region', { name: 'Iron Ingot', exact: true }).count(), 2);
    options.historyDetail = { ...historyDetail, timeDone: historyDetail.timeStarted, items: [], interfaceShare: [] };
    await page.reload();
    await provider.waitFor({ state: 'hidden' });
    assert.doesNotMatch(await page.locator('body').innerText(), /NaN|Infinity/);
    await page.getByText(/No processing intervals/).waitFor();
});

// A failed persistence response can leave the in-memory value changed. GET agreement must
// not be labelled a saved result; only a deliberate successful PATCH confirms saving.
test('uncertain settings saves preserve the draft and remain unconfirmed until explicit retry succeeds', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    for (const status of ['INTERNAL_ERROR', 'TIMEOUT', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        options.settings[gridA] = {
            flowFlushIntervalSeconds: 60,
            historyRetentionDays: 7,
            limits: structuredClone(defaultSettingsLimits),
            isTracked: false,
            name: '',
            isStorageTracked: false,
            isFlowTracked: false,
            stockSampleIntervalSeconds: 300
        };
        await page.goto(`${base}?case=${status}#/grids/${gridA}/settings`);
        const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
        await tracking.check();
        options.settings[gridA] = {
            flowFlushIntervalSeconds: 60,
            historyRetentionDays: 7,
            limits: structuredClone(defaultSettingsLimits),
            isTracked: true,
            name: '',
            isStorageTracked: false,
            isFlowTracked: false,
            stockSampleIntervalSeconds: 300
        };
        options.fault = { operation: 'settings', status };
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /save.*not confirmed/i })
            .waitFor();
        const count = options.requests.filter((request) => request.method === 'PATCH').length;
        await poll(page);
        await clickTab(page, 'Terminal');
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await tracking.waitFor();
        await page
            .getByRole('status')
            .filter({ hasText: /save.*not confirmed/i })
            .waitFor();
        assert.equal(await tracking.isChecked(), true);
        assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, count);
        options.fault = null;
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /settings saved/i })
            .waitFor();
        assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, count + 1);
    }
});

test('settings drafts survive transient discovery failures and pending saves recover after access loss', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await tracking.check();
    options.gridError = 'NETWORK_ERROR';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /Cannot connect/ })
        .first()
        .waitFor();
    options.gridError = null;
    const recovered = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await (await recovered).finished();
    await page.getByRole('button', { name: 'Save settings', exact: true }).waitFor();
    assert.equal(await tracking.isChecked(), true);
    let complete;
    const pending = new Promise((resolve) => {
        complete = resolve;
    });
    await page.route('**/settings', (route) =>
        route.request().method() === 'PATCH' ? complete(route) : route.continue()
    );
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    const save = await pending;
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    await tracking.waitFor({ state: 'hidden' });
    options.settings[gridA] = {
        flowFlushIntervalSeconds: 60,
        historyRetentionDays: 7,
        limits: structuredClone(defaultSettingsLimits),
        isTracked: true,
        name: '',
        isStorageTracked: false,
        isFlowTracked: false,
        stockSampleIntervalSeconds: 300
    };
    await save.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: {
                flowFlushIntervalSeconds: 60,
                historyRetentionDays: 7,
                limits: structuredClone(defaultSettingsLimits),
                isTracked: true,
                name: '',
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300
            }
        })
    });
    assert.equal(await tracking.count(), 0);
    options.gridError = null;
    await poll(page);
    await tracking.waitFor();
    assert.equal(await tracking.isChecked(), true);
});

test('history reentry during grid discovery clears unavailable snapshots', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    let held = false;
    await page.route('**/api/grids', (route) => {
        if (held) return route.continue();
        held = true;
        release(route);
    });
    await poll(page);
    const discovery = await captured;
    await clickTab(page, 'History');
    await page.getByRole('link', { name: /Iron Ingot.*#1/ }).waitFor();
    options.historyError = 'TRACKING_NOT_FOUND';
    await page.getByRole('link', { name: /Iron Ingot.*#1/ }).click();
    await discovery.continue();
    await page
        .getByRole('status')
        .filter({ hasText: /history entry.*available/i })
        .waitFor();
    assert.equal(await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).count(), 0);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

test('settings save remains single-flight across grids and uses the returned value on reentry', async (t) => {
    const { page, options, base } = await fixture(t);
    const player = { name: 'Alex', uuid: '11111111-1111-1111-1111-111111111111' };
    options.accessSources = {
        [player.uuid]: [
            {
                player,
                kind: 'controller',
                reason: 'node_owner',
                position: { dimensionId: 'minecraft:overworld', x: 1, y: 64, z: 2 },
                side: null
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await tracking.check();
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    await page.route('**/settings', (route) =>
        route.request().method() === 'PATCH' ? capture(route) : route.continue()
    );
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    const pending = await captured;
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await tracking.waitFor();
    assert.equal(await tracking.isChecked(), false);
    await page.goBack();
    await page
        .getByRole('status')
        .filter({ hasText: /Saving settings/i })
        .waitFor();
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: {
                flowFlushIntervalSeconds: 60,
                historyRetentionDays: 7,
                limits: structuredClone(defaultSettingsLimits),
                isTracked: false,
                name: '',
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300
            }
        })
    });
    await page
        .getByRole('status')
        .filter({ hasText: /Settings saved/i })
        .waitFor();
    assert.equal(await tracking.isChecked(), false);
    await page.getByText('Alex', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
});

test('old settings reads cannot replace saved data and denied saves clear access sources', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await tracking.check();
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    let held = false;
    await page.route('**/settings', (route) => {
        if (held || route.request().method() !== 'GET') return route.continue();
        held = true;
        capture(route);
    });
    await poll(page);
    const delayed = await captured;
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /Settings saved/i })
        .waitFor();
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: {
                flowFlushIntervalSeconds: 60,
                historyRetentionDays: 7,
                limits: structuredClone(defaultSettingsLimits),
                isTracked: false,
                name: '',
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300
            }
        })
    });
    await settleResponse(page, delayed.request());
    assert.equal(await tracking.isChecked(), true);
    options.settingsError = 'NO_PERMISSIONS';
    await tracking.uncheck();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await tracking.count(), 0);
    assert.equal(await page.getByRole('heading', { name: 'Network access', exact: true }).count(), 0);
});

test('late history details cannot enter another grid and denied reads remove the selected snapshot', async (t) => {
    const { page, options, base } = await fixture(t);
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route(`**/${gridA}/crafting-history/1`, (route) => release(route));
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const delayed = await captured;
    options.historyDetail = {
        ...historyDetail,
        finalOutput: { ...quartz, quantity: 9 },
        items: [],
        interfaceShare: []
    };
    await page.goto(`${base}#/grids/${gridB}/history/2`);
    await page.getByRole('heading', { name: /Certus Quartz Crystal/ }).waitFor();
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: historyDetail })
    });
    await settleResponse(page, delayed.request());
    assert.equal(await page.getByRole('heading', { name: /Iron Ingot/ }).count(), 0);
    options.historyError = 'NO_PERMISSIONS';
    await page.reload();
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await page.getByRole('heading', { name: /Certus Quartz Crystal/ }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

for (const selected of [true, false])
    test(`CPU ${selected ? 'footer' : 'overview'} stays stable through a delayed action and authoritative read`, async (t) => {
        const { page, options, base } = await fixture(t);
        await page.setViewportSize({ width: 1280, height: 800 });
        options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
        options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
        await page.goto(`${base}#/grids/${gridA}/cpus${selected ? '/cpu-a' : ''}`);
        const pause = page.getByRole('button', { name: /^Pause current work/ });
        await pause.waitFor();
        const surface = selected
            ? page.getByRole('region', { name: 'CPU resources', exact: true })
            : page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /Assembler.*cpu-a/ }) });
        const geometry = async () => [
            await surface.boundingBox(),
            await pause.boundingBox(),
            await page.getByRole('link', { name: 'Home', exact: true }).boundingBox(),
            await page.getByRole('heading', { name: 'AE2 Web Integration', exact: true }).boundingBox()
        ];
        const before = await geometry();
        let captureMutation;
        const mutationRequest = new Promise((resolve) => {
            captureMutation = resolve;
        });
        await page.route('**/pause', (route) => captureMutation(route));
        await pause.click();
        const mutation = await mutationRequest;
        assert.equal(await pause.isDisabled(), true);
        assert.deepEqual(await geometry(), before, 'Pending work must not insert a footer or card status line');
        const activity = page.getByRole('status', { name: 'Activity', exact: true });
        await activity.waitFor();
        if (selected && process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'cpu-action-progress.png') });
        }
        let captureRead;
        const readRequest = new Promise((resolve) => {
            captureRead = resolve;
        });
        await page.route(
            (url) => url.pathname.endsWith('/cpus'),
            (route) => captureRead(route)
        );
        options.cpus['cpu-a'].isPaused = true;
        options.cpuDetails['cpu-a'].isPaused = true;
        await mutation.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'OK', data: null }) });
        const read = await readRequest;
        assert.equal(await activity.isVisible(), true);
        assert.equal(await pause.isDisabled(), true);
        assert.deepEqual(await geometry(), before, 'The authoritative read keeps the same geometry and locks');
        await read.continue();
        const resume = page.getByRole('button', { name: /^Resume current work/ });
        await resume.waitFor();
        assert.equal(await resume.isEnabled(), true);
        await activity.waitFor({ state: 'hidden' });
    });

test('CPU cancellation waits for the authoritative read before showing completion', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-a'] = { ...cpu, isBusy: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    let captureRead;
    const readRequest = new Promise((resolve) => {
        captureRead = resolve;
    });
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).waitFor();
    await page.route(
        (url) => url.pathname.endsWith('/cpus'),
        (route) => captureRead(route)
    );
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    const read = await readRequest;
    const completed = page.getByRole('status').filter({ hasText: /Cancellation completed/i });
    assert.equal(await completed.isVisible(), false, 'Success requires the post-mutation snapshot');
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
    await read.continue();
    await completed.waitFor();
});

test('header icon controls refresh the current history entry and retain dirty settings drafts', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    const header = page.locator('#app header');
    const refresh = header.getByRole('button', { name: 'Refresh', exact: true });
    await refresh.waitFor();
    assert.equal(await header.getByRole('button', { name: 'Log out', exact: true }).innerText(), '');
    const before = options.requests.filter((request) => request.path.endsWith('/crafting-history/1')).length;
    options.historyDetail = { ...historyDetail, finalOutput: quartz };
    await refresh.click();
    await page.getByRole('heading', { name: 'Certus Quartz Crystal', level: 3, exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/crafting-history/1')).length, before + 1);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Unsaved workshop');
    await refresh.click();
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    assert.equal(await name.inputValue(), 'Unsaved workshop');
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('header refresh stays active through a history detail read queued behind automatic discovery', async (t) => {
    const { page, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    let discoveryRequests = 0;
    let captureGrids;
    const gridsRequest = new Promise((resolve) => {
        captureGrids = resolve;
    });
    let captureDetail;
    const detailRequest = new Promise((resolve) => {
        captureDetail = resolve;
    });
    await page.route(
        (url) => url.pathname.endsWith('/api/grids'),
        (route) => {
            discoveryRequests++;
            captureGrids(route);
        }
    );
    await page.route(
        (url) => url.pathname.endsWith('/crafting-history/1'),
        (route) => captureDetail(route)
    );
    await poll(page);
    const grids = await gridsRequest;
    const refresh = page.locator('#app header').getByRole('button', { name: 'Refresh', exact: true });
    await refresh.click();
    await grids.continue();
    const detail = await detailRequest;
    assert.equal(
        await refresh.getAttribute('aria-busy'),
        'true',
        'Queued detail loading remains part of visible activity'
    );
    assert.equal(await refresh.isEnabled(), true);
    await detail.continue();
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    assert.equal(discoveryRequests, 1, 'Refreshing history detail reuses discovery already in flight');
});

test('CPU background refresh signals activity without disabling or dimming existing controls', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
    options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const pause = page.getByRole('button', { name: 'Pause current work', exact: true });
    await pause.waitFor();
    const refresh = page.locator('#app header').getByRole('button', { name: 'Refresh', exact: true });
    const controls = [
        pause,
        page.getByRole('button', { name: 'Cancel current work', exact: true }),
        page.getByRole('searchbox', { name: 'Search CPU resources', exact: true }),
        page.getByRole('button', { name: 'Hide stored-only resources', exact: true }),
        refresh
    ];
    const appearance = async () =>
        Promise.all(
            controls.map(async (control) => ({
                enabled: await control.isEnabled(),
                box: await control.boundingBox(),
                opacity: await control.evaluate((node) => getComputedStyle(node).opacity)
            }))
        );
    const before = await appearance();
    let captureRead;
    const readRequest = new Promise((resolve) => {
        captureRead = resolve;
    });
    let reads = 0;
    await page.route(
        (url) => url.pathname.endsWith('/cpus'),
        (route) => {
            if (reads++ === 0) captureRead(route);
            else return route.continue();
        }
    );
    await poll(page);
    const read = await readRequest;
    const activity = page.getByRole('status', { name: 'Activity', exact: true });
    await activity.waitFor();
    assert.equal(await activity.getAttribute('aria-live'), 'off');
    assert.deepEqual(await appearance(), before, 'Automatic refresh must keep the ready UI fully interactive');
    const requestsBefore = options.requests.length;
    await refresh.click();
    await refresh.click();
    assert.equal(options.requests.length, requestsBefore, 'Repeated refresh waits for the in-flight refresh');
    await read.continue();
    await activity.waitFor({ state: 'hidden' });
    assert.deepEqual(await appearance(), before);
});

test('header refresh keeps icon controls stable on phone and desktop and honors reduced motion', async (t) => {
    const { page, options, base } = await fixture(t);
    for (const width of [390, 1280]) {
        await page.setViewportSize({ width, height: 844 });
        await page.goto('about:blank');
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const header = page.locator('#app header');
        const refresh = header.getByRole('button', { name: 'Refresh', exact: true });
        const logout = header.getByRole('button', { name: 'Log out', exact: true });
        const geometry = async () => [
            await refresh.boundingBox(),
            await logout.boundingBox(),
            await (
                width <= 600
                    ? page.getByRole('button', { name: 'Navigation', exact: true })
                    : page.getByRole('link', { name: 'Home', exact: true })
            ).boundingBox()
        ];
        const before = await geometry();
        assert.ok(before[0].x >= before[1].x + before[1].width, 'Refresh sits to the right of logout');
        assert.ok(before[0].x + before[0].width <= width);
        let capture;
        const requested = new Promise((resolve) => {
            capture = resolve;
        });
        const heldRead = (url) => url.pathname.endsWith('/api/grids');
        const hold = (route) => capture(route);
        await page.route(heldRead, hold);
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await refresh.click();
        const read = await requested;
        assert.equal(await refresh.isEnabled(), true);
        assert.equal(await refresh.getAttribute('aria-busy'), 'true');
        const visibleIcon = refresh.locator('svg:visible');
        assert.equal(await visibleIcon.count(), 1, 'Only the current refresh glyph is visible');
        assert.equal(await visibleIcon.evaluate((node) => getComputedStyle(node).animationName), 'none');
        assert.deepEqual(await geometry(), before);
        await page.emulateMedia({ reducedMotion: 'no-preference' });
        assert.notEqual(await visibleIcon.evaluate((node) => getComputedStyle(node).animationName), 'none');
        await logout.focus();
        await page
            .getByRole('tooltip')
            .filter({ hasText: /^Log out$/ })
            .waitFor();
        await page.keyboard.press('Escape');
        await read.continue();
        await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
        assert.deepEqual(await geometry(), before);
        await page.unroute(heldRead, hold);
        assert.equal(
            options.requests.some((request) => request.method !== 'GET'),
            false
        );
    }
});

test('pending CPU cancellation settles on the revisited CPU and older reads cannot override it', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    let complete;
    await page.route('**/cancel', (route) => {
        complete = (status) =>
            route.fulfill({
                status: status === 'OK' ? 200 : 503,
                contentType: 'application/json',
                body: JSON.stringify({ status, data: null })
            });
    });
    for (const result of ['OK', 'TIMEOUT']) {
        options.cpuDetails['cpu-a'] = cpuWork;
        await page.goto(`${base}?case=${result}#/grids/${gridA}/cpus/cpu-a`);
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /Cancelling/i })
            .waitFor();
        if (result === 'OK')
            options.cpuDetails['cpu-a'] = { ...cpuWork, isBusy: false, items: null, finalOutput: null };
        await complete(result);
        await page
            .getByRole('status')
            .filter({ hasText: result === 'OK' ? /^Idle$/i : /outcome.*unknown/i })
            .waitFor();
        await poll(page);
        await page
            .getByRole('status')
            .filter({ hasText: result === 'OK' ? /^Idle$/i : /outcome.*unknown/i })
            .waitFor();
    }
    await page.unroute('**/cancel');
    options.cpuDetails['cpu-a'] = cpuWork;
    await page.goto(`${base}?case=late-read#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    let held = false;
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-a'),
        (route) => {
            if (held) return route.continue();
            held = true;
            capture(route);
        }
    );
    await poll(page);
    const delayed = await captured;
    const cancelled = page.waitForResponse(
        (response) =>
            response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/cpus/cpu-a/cancel')
    );
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    const cancellation = await cancelled;
    assert.equal(cancellation.status(), 200);
    await cancellation.finished();
    // The dispatched live read must settle before its replacement can reclaim a returned subscription.
    // Deliver its stale data after cancellation is acknowledged, then await the authoritative idle snapshot.
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: cpuWork })
    });
    await settleResponse(page, delayed.request());
    await page
        .getByRole('status')
        .filter({ hasText: /^Idle$/i })
        .waitFor();
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
});

// Public seam: reopening a calculation reads its runtime identity; cancelling it is a single
// explicit DELETE and navigation never repeats either mutation.
test('direct plan entry only reads, and explicit cancellation stops calculation polling', async (t) => {
    const { page, options, base } = await fixture(t);
    options.pendingReads = 100;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    const reads = options.requests.filter(
        (request) => request.path.endsWith('/crafting-plans/7') && request.method === 'GET'
    ).length;
    await page.clock.fastForward(5000);
    await settleFrames(page);
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/crafting-plans/7') && request.method === 'GET')
            .length,
        reads
    );
    assert.deepEqual(
        options.requests.filter((request) => request.method !== 'GET').map((request) => request.method),
        ['DELETE']
    );
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 1);
});

// Public seam: CPU option availability and selection across live data and full reload. Variant
// identity and capacity determine merging; a name or ordinal cannot identify a selected CPU.
test('busy CPU eligibility uses known output identity and missing selections require a new choice', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus = {
        'cpu-a': cpu,
        'cpu-b': { ...cpu, isBusy: true, usedStorage: 2048, finalOutput: { ...iron, itemKey: 'iron' } },
        'other-output': { ...cpu, isBusy: true, finalOutput: { ...iron, itemKey: 'other-variant' } },
        'unknown-storage': { ...cpu, isBusy: true, usedStorage: -1, finalOutput: { ...iron, itemKey: 'iron' } }
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const cpus = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).evaluate((option) => option.disabled), false);
    assert.equal(await cpus.getByRole('radio', { name: /other-output/ }).evaluate((option) => option.disabled), true);
    assert.equal(
        await cpus.getByRole('radio', { name: /unknown-storage/ }).evaluate((option) => option.disabled),
        true
    );
    await cpus.getByRole('radio', { name: /cpu-b/ }).check();
    delete options.cpus['cpu-b'];
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await (await refreshed).finished();
    await cpus.getByRole('radio', { name: /cpu-b/ }).waitFor({ state: 'detached' });
    assert.equal(await cpus.getByRole('radio', { checked: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.cpus['cpu-b'] = { ...cpu, isBusy: true, finalOutput: { ...iron, itemKey: 'iron' } };
    await page.reload();
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).evaluate((option) => option.disabled), false);
    assert.equal(await cpus.getByRole('radio', { name: /other-output/ }).isDisabled(), true);
    assert.equal(await cpus.getByRole('radio', { name: /unknown-storage/ }).isDisabled(), true);
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 1);
});

// Public seam: selecting a CPU opens its terminal, with separate resource tools and CPU controls.
test('CPU memory uses binary units through exabytes in overview and current work', async (t) => {
    const { page, options, base } = await fixture(t);
    const capacities = [0, 1572864, JSON.parse('9223372036854775807')];
    const expected = ['0 B', '1.5 MB', '8 EB'];
    options.cpus = Object.fromEntries(
        capacities.map((availableStorage, index) => [`cpu-${index}`, { ...cpu, availableStorage, usedStorage: 18432 }])
    );
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    for (let index = 0; index < capacities.length; index++) {
        const card = page.getByRole('link', { name: `Assembler · cpu-${index}`, exact: true });
        await card.waitFor();
        assert.equal(await card.locator('dd').first().innerText(), expected[index]);
        assert.equal(await card.locator('dd').nth(1).innerText(), '18 KB');
    }
    options.cpuDetails['cpu-1'] = cpuWork;
    await page.getByRole('link', { name: 'Assembler · cpu-1', exact: true }).click();
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    await page
        .getByRole('status')
        .filter({ hasText: /^Busy$/ })
        .waitFor();
    assert.match(await panel.getByRole('link', { name: /cpu-1/ }).innerText(), /1\.5 MB/);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-1`);
    await page
        .getByRole('complementary', { name: 'CPU', exact: true })
        .getByRole('link', { name: /cpu-1.*1,5 MB/ })
        .waitFor();
});

test('CPU lists show native selection modes and hide indicators when unsupported on phone and desktop', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    for (const width of [390, 1280]) {
        await page.setViewportSize({ width, height: 844 });
        await page.goto('about:blank');
        options.capabilities.cpuSelectionMode = true;
        options.cpus = {
            'cpu-player': { ...cpu, selectionMode: 'PLAYER_ONLY' },
            'cpu-machine': { ...cpu, selectionMode: 'AUTOMATION_ONLY' },
            'cpu-both': { ...cpu, selectionMode: 'ALL' },
            'cpu-unknown': { ...cpu, selectionMode: null }
        };
        options.cpuDetails['cpu-player'] = { ...cpuWork, selectionMode: 'PLAYER_ONLY' };
        for (const route of ['cpus', 'cpus/cpu-player', 'plans/7']) {
            await page.goto(`${base}#/grids/${gridA}/${route}`);
            if (width === 390 && route !== 'cpus')
                await page.getByRole('button', { name: 'CPUs', exact: true }).click();
            for (const name of ['Players only', 'Automation only', 'Players and automation']) {
                await page.getByRole('img', { name: `Automatic selection: ${name}`, exact: true }).waitFor();
            }
            assert.equal(await page.getByRole('img', { name: /Automatic selection:/ }).count(), 3);
            if (process.env.UI_SCREENSHOT_DIR) {
                await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
                await page.screenshot({
                    path: path.join(
                        process.env.UI_SCREENSHOT_DIR,
                        `cpu-modes-${width}-${route.replaceAll('/', '-')}.png`
                    )
                });
            }
        }
        options.cpus['cpu-player'].selectionMode = null;
        await poll(page);
        await page
            .getByRole('img', { name: 'Automatic selection: Players only', exact: true })
            .waitFor({ state: 'hidden' });
        assert.equal(await page.getByRole('img', { name: /Automatic selection:/ }).count(), 2);
        options.capabilities.cpuSelectionMode = false;
        await page.reload();
        if (width === 390) await page.getByRole('button', { name: 'CPUs', exact: true }).click();
        await page.getByRole('radio', { name: /cpu-player/ }).waitFor();
        assert.equal(await page.getByRole('img', { name: /Automatic selection:/ }).count(), 0);
    }
});

// Public browser/HTTP seam: navigation keeps a bounded selector and never mutates CPU work.
test('CPU automation sorting uses native modes, keeps unknown last, and preserves saved intent without capability', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.capabilities.cpuSelectionMode = true;
    options.cpus = {
        'cpu-c': { ...cpu, name: 'Alpha', selectionMode: 'AUTOMATION_ONLY', acceptsPlayerJobs: true },
        'cpu-a': { ...cpu, name: 'Beta', selectionMode: 'PLAYER_ONLY', acceptsPlayerJobs: false },
        'cpu-d': { ...cpu, name: 'Gamma', selectionMode: 'ALL' },
        'cpu-b': { ...cpu, name: 'Gamma', selectionMode: 'ALL' },
        'cpu-z': { ...cpu, name: 'Aardvark', selectionMode: null },
        'cpu-y': { ...cpu, name: 'Zulu', selectionMode: null }
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, selectionMode: 'PLAYER_ONLY' };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    const links = panel.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link');
    const order = () => links.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('href').split('/').at(-1)));
    for (const criterion of ['Name', 'Capacity', 'Coprocessors', 'Busy state'])
        await panel.getByRole('button', { name: `Sort CPUs: ${criterion}`, exact: true }).click();
    await panel.getByRole('button', { name: 'Sort CPUs: Automation', exact: true }).waitFor();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-d', 'cpu-c', 'cpu-z', 'cpu-y']);
    await panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-b', 'cpu-d', 'cpu-a', 'cpu-z', 'cpu-y']);
    await page.mouse.move(0, 0);
    await panel.getByRole('link', { name: /cpu-c/ }).scrollIntoViewIfNeeded();
    await settleFrames(page);
    await panel.getByRole('link', { name: /cpu-c/ }).focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /Automatic selection: Automation only/ })
        .waitFor();
    assert.match(await page.getByRole('tooltip').innerText(), /not permission to submit work/);
    await panel.getByRole('link', { name: /cpu-z/ }).scrollIntoViewIfNeeded();
    await settleFrames(page);
    await panel.getByRole('link', { name: /cpu-z/ }).focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /Automatic selection: Unsupported or unknown/ })
        .waitFor();
    options.capabilities.cpuSelectionMode = false;
    await page.reload();
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).waitFor();
    assert.equal(await panel.getByRole('button', { name: 'Sort CPUs: Automation', exact: true }).count(), 0);
    await links.first().waitFor();
    assert.deepEqual(await order(), ['cpu-y', 'cpu-b', 'cpu-d', 'cpu-a', 'cpu-c', 'cpu-z']);
    await panel.getByRole('link', { name: /cpu-y/ }).focus();
    await page.getByRole('tooltip').waitFor();
    assert.doesNotMatch(await page.getByRole('tooltip').innerText(), /Automatic selection/);
    options.capabilities.cpuSelectionMode = true;
    await page.reload();
    await panel.getByRole('button', { name: 'Sort CPUs: Automation', exact: true }).waitFor();
    await links.first().waitFor();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-b', 'cpu-d', 'cpu-a', 'cpu-z', 'cpu-y']);
    options.cpus['cpu-a'].selectionMode = 'AUTOMATION_ONLY';
    options.cpuDetails['cpu-a'].selectionMode = 'ALL';
    await poll(page);
    await page.getByRole('region', { name: 'Current job', exact: true }).focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /Automatic selection: Players and automation/ })
        .waitFor();
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('CPU selector cycles summary sorting with stable ties and remembers criterion and direction', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus = {
        'cpu-c': { ...cpu, name: 'Beta', availableStorage: 8192, coProcessors: 1 },
        'cpu-b': { ...cpu, name: 'Alpha', availableStorage: 16384, coProcessors: 2, isBusy: true },
        'cpu-d': { ...cpu, name: 'Delta', availableStorage: 4096, coProcessors: 2 },
        'cpu-a': { ...cpu, name: '§aAlpha', availableStorage: 8192, coProcessors: 3, isBusy: true, isPaused: true }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    const links = panel.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link');
    const order = () => links.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('href').split('/').at(-1)));
    const initialRequests = options.requests.length;
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).waitFor();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-d', 'cpu-a', 'cpu-c', 'cpu-b']);
    await panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-b', 'cpu-a', 'cpu-c', 'cpu-d']);
    await panel.getByRole('button', { name: 'Sort CPUs: Capacity', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-d', 'cpu-c']);
    await panel.getByRole('button', { name: 'CPU sort order: Descending', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-b', 'cpu-d', 'cpu-a']);
    await panel.getByRole('button', { name: 'Sort CPUs: Coprocessors', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-d', 'cpu-a', 'cpu-b']);
    const direction = panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true });
    assert.equal(await direction.getAttribute('aria-pressed'), null);
    await direction.click();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    assert.equal(options.requests.length, initialRequests, 'Client sorting must not trigger HTTP work');
    await page.reload();
    await panel.getByRole('button', { name: 'Sort CPUs: Busy state', exact: true }).waitFor();
    await links.first().waitFor();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    assert.equal(
        await panel.getByRole('button', { name: 'CPU sort order: Descending', exact: true }).isVisible(),
        true
    );
    options.cpus = Object.fromEntries(Object.entries(options.cpus).reverse());
    await poll(page);
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    assert.equal(await panel.getByRole('link', { name: /cpu-a/ }).getAttribute('aria-current'), 'page');
    assert.match(page.url(), /\/cpus\/cpu-a$/);
});

test('CPU selector ignores unsupported saved sorting choices', async (t) => {
    const { page, base } = await fixture(t);
    await page.addInitScript(() =>
        localStorage.setItem(
            'ae2web:/:theme:default',
            JSON.stringify({ cpuSelectorSort: 'progress', cpuSelectorSortOrder: 'sideways' })
        )
    );
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).waitFor();
    assert.equal(await panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).isVisible(), true);
    await panel.getByRole('link', { name: /cpu-a/ }).waitFor();
    assert.equal(await panel.getByRole('link', { name: /cpu-a/ }).getAttribute('aria-current'), 'page');
});

test('CPU search opens from the magnifier, focuses the field and clears its filter when closed', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-b'] = { ...cpu, name: 'Other processor' };
    for (const width of [390, 1280]) {
        await page.setViewportSize({ width, height: 844 });
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        await openMobilePane(page, 'CPUs');
        const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
        const search = panel.getByRole('searchbox', { name: 'Search CPUs', exact: true });
        const toggle = panel.getByRole('button', { name: 'Search CPUs', exact: true });
        await toggle.waitFor();
        assert.equal(await search.isVisible(), false);
        const before = await toggle.boundingBox();
        await toggle.click();
        assert.equal(await search.evaluate((node) => node === document.activeElement), true);
        await search.fill('Other');
        assert.equal(await panel.getByRole('link').count(), 1);
        const listBounds = await panel.getByRole('region', { name: 'CPU selector', exact: true }).boundingBox();
        const rowBounds = await panel.getByRole('link').boundingBox();
        assert.ok(listBounds.height >= rowBounds.height, 'Expanded search leaves a complete CPU result readable');
        assert.ok(
            (await page.getByRole('region', { name: 'CPU resources', exact: true }).boundingBox()).height > 100,
            'Search must not take the resource viewport away'
        );
        await panel.getByRole('button', { name: 'Close CPU search', exact: true }).click();
        assert.equal(await search.isVisible(), false);
        assert.equal(await panel.getByRole('link').count(), 2);
        assert.deepEqual(await toggle.boundingBox(), before);
        await toggle.click();
        assert.equal(await search.inputValue(), '');
        await search.fill('cpu-a');
        await search.press('Escape');
        assert.equal(await search.isVisible(), false);
        assert.equal(await toggle.evaluate((node) => node === document.activeElement), true);
        assert.equal(await panel.getByRole('link').count(), 2);
        assert.ok(await panel.evaluate((node) => node.scrollWidth <= node.clientWidth));
    }
});

test('CPU sorting preserves keyboard focus and reading position when polling moves the focused CPU', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.setViewportSize({ width: 1280, height: 650 });
    options.cpus = {
        'cpu-a': { ...cpu, name: 'Alpha', isBusy: true },
        'cpu-b': { ...cpu, name: 'Bravo', isBusy: true },
        'cpu-c': { ...cpu, name: 'Charlie' },
        'cpu-d': { ...cpu, name: 'Delta' }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    for (const criterion of ['Name', 'Capacity', 'Coprocessors'])
        await panel.getByRole('button', { name: `Sort CPUs: ${criterion}`, exact: true }).click();
    const selector = panel.getByRole('region', { name: 'CPU selector', exact: true });
    const target = selector.getByRole('link', { name: /cpu-a/ });
    await page.mouse.move(5, 5);
    await target.focus();
    await page.keyboard.press('Escape');
    const readingPosition = await selector.evaluate((node) => node.scrollTop);
    options.cpus['cpu-a'].isBusy = false;
    await poll(page);
    await page.waitForFunction(() =>
        document.querySelector('[aria-label="CPU selector"] a')?.getAttribute('href')?.endsWith('/cpu-a')
    );
    assert.equal(await target.evaluate((node) => node === document.activeElement), true);
    assert.equal(await selector.evaluate((node) => node.scrollTop), readingPosition);
    assert.equal(
        await page.getByRole('tooltip').isVisible(),
        false,
        'A dismissed tooltip must not reopen while restoring focus'
    );
});
