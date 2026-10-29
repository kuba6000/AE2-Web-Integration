const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, gridA, iron, clickTab, settleFrames } = require('./fixtures.cjs');
const { iconColor, waitIconColor } = require('./icon-pixels.cjs');

async function animatedFixture(t, contextOptions = {}, autoRefresh = false) {
    const result = await fixture(t, '', contextOptions, { autoRefresh });
    const { page, options } = result;
    await atlasFixture(page, options);
    options.icons.pages.push({ digest: 'c'.repeat(64), width: 128, height: 64 });
    options.icons.animations = [
        {
            page: 1,
            frames: [
                { x: 64, y: 0, durationMillis: 1000 },
                { x: 0, y: 0, durationMillis: 2000 }
            ]
        }
    ];
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0, animation: 0 } }];
    await page.goto(`${result.base}#/web-settings`);
    await page.getByRole('slider', { name: 'Animation update interval', exact: true }).press('Home');
    return result;
}

test('terminal plays frame holds without replacing its resource tile or making per-frame requests', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const tile = page.getByRole('button', { name: /Iron Ingot/ });
    await tile.waitFor();
    const original = await tile.elementHandle();
    const position = () => iconColor(page, tile.locator('.resource-icon'));
    await waitIconColor(page, page.locator('#items .resource-icon').first(), 'green');
    await page.clock.pauseAt(new Date());
    assert.equal(await position(), 'green');
    await page.clock.runFor(1100);
    assert.equal(await position(), 'red');
    await page.clock.runFor(1200);
    assert.equal(await position(), 'red', 'the second frame has a longer hold');
    await page.clock.runFor(800);
    assert.equal(await position(), 'green');
    assert.equal(await tile.evaluate((current, original) => current === original, original), true);
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 2);
});

test('pinning and unpinning an animated resource keeps its frames advancing', async (t) => {
    const { page, base } = await animatedFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const moving = (selector) => waitIconColor(page, page.locator(selector), 'green');
    await moving('#items .resource-icon');
    for (const [from, action, to] of [
        ['#items', 'Pin in terminal', '#pinned-items'],
        ['#pinned-items', 'Unpin from terminal', '#items']
    ]) {
        await page.locator(`${from} .item`).click({ button: 'right' });
        await page.getByRole('menuitem', { name: action, exact: true }).click();
        await moving(`${to} .resource-icon`);
        await waitIconColor(page, page.locator(`${to} .resource-icon`), 'red');
    }
});

test('pinning a visible animated resource does not strand it behind other visible atlases', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    await page.setViewportSize({ width: 1400, height: 1400 });
    const digests = Array.from({ length: 10 }, (_, index) => `e${index}`.padEnd(64, '0'));
    options.icons.pages = [options.icons.pages[0], ...digests.map((digest) => ({ digest, width: 128, height: 64 }))];
    options.icons.animations = digests.map((_, index) => ({ ...options.icons.animations[0], page: index + 1 }));
    options.itemsA = digests.map((_, index) => ({
        ...iron,
        itemKey: `resource-${index}`,
        displayName: `Resource ${index}`,
        resourceType: index % 2 ? 'FLUID' : 'ITEM',
        icon: { page: 0, x: 0, y: 0, animation: index }
    }));
    await page.route('**/api/icon-packs/**/pages/e*', (route) =>
        route.fulfill({ status: 200, contentType: 'image/png', body: options.atlas })
    );
    const advances = async (container, name) => {
        const icon = page
            .locator(container)
            .getByRole('button', { name: new RegExp(name) })
            .locator('.resource-icon');
        await waitIconColor(page, icon, 'green');
        await page.clock.pauseAt(await page.evaluate(() => Date.now()));
        await page.clock.runFor(1100);
        assert.equal(await iconColor(page, icon), 'red');
        await page.clock.resume();
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await advances('#items', 'Resource 0');
    for (const name of ['Resource 0', 'Resource 1']) {
        await page
            .locator('#items')
            .getByRole('button', { name: new RegExp(name) })
            .click({ button: 'right' });
        await page.getByRole('menuitem', { name: 'Pin in terminal', exact: true }).click();
        await advances('#pinned-items', name);
    }
    await page
        .locator('#pinned-items')
        .getByRole('button', { name: /Resource 0/ })
        .click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Unpin from terminal', exact: true }).click();
    await advances('#items', 'Resource 0');
    await advances('#pinned-items', 'Resource 1');
    await page.goto(`${base}#/grids/${gridA}/tracking/resource-0`);
    await page.locator('.tracking-view').waitFor();
    await clickTab(page, 'Terminal');
    await advances('#items', 'Resource 0');
    await advances('#pinned-items', 'Resource 1');
    await advances('#items', 'Resource 9');
});

test('live response-local animation tables preserve the selected sequence and its current hold', async (t) => {
    const { page, options, base } = await animatedFixture(t, {}, true);
    options.liveView = { generation: '1', revision: '1' };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    await waitIconColor(page, page.locator('#items .resource-icon').first(), 'green');
    await page.clock.pauseAt(new Date());
    await page.clock.runFor(800);
    const definition = { ...options.itemsA[0], quantity: 777, icon: { page: 1, x: 0, y: 0, animation: 1 } };
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '1', revision: '2' },
            definitions: [definition],
            updates: [],
            removed: [],
            unkeyed: null,
            icons: {
                ...options.icons,
                pages: [...options.icons.pages].reverse(),
                animations: [
                    {
                        page: 0,
                        frames: [
                            { x: 0, y: 0, durationMillis: 2000 },
                            { x: 64, y: 0, durationMillis: 1000 }
                        ]
                    },
                    { ...options.icons.animations[0], page: 0 }
                ]
            }
        })}\n\n`
    );
    await page.getByRole('button', { name: /Iron Ingot.*777/ }).waitFor();
    await page.clock.runFor(300);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'red');
    await page.clock.runFor(2000);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'green');
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 2);
});

test('animation preference and reduced motion retain the static icon without fetching animation pages', async (t) => {
    const { page, options, base } = await animatedFixture(t, { reducedMotion: 'reduce' });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await waitIconColor(page, page.locator('#items .resource-icon'), 'red');
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 1);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await waitIconColor(page, page.locator('#items .resource-icon').first(), 'green');
    await clickTab(page, 'Web settings');
    await page.getByRole('checkbox', { name: 'Animated resource icons' }).uncheck();
    await page.goto(`${base}#/grids/${gridA}/items`);
    await waitIconColor(page, page.locator('#items .resource-icon'), 'red');
    await page.clock.runFor(4000);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'red');
    const reads = options.requests.filter((request) => request.path.includes(`/pages/${'c'.repeat(64)}`)).length;
    await page.reload();
    await waitIconColor(page, page.locator('#items .resource-icon'), 'red');
    await settleFrames(page);
    assert.equal(options.requests.filter((request) => request.path.includes(`/pages/${'c'.repeat(64)}`)).length, reads);
});

test('slow animation pages keep the static fallback and begin with a full first-frame hold when ready', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route(`**/pages/${'c'.repeat(64)}`, (route) => release(route));
    await page.goto(`${base}#/grids/${gridA}/items`);
    const pending = await captured;
    await waitIconColor(page, page.locator('#items .resource-icon'), 'red');
    await page.clock.pauseAt(new Date());
    await page.clock.runFor(2500);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'red');
    await pending.fulfill({ status: 200, contentType: 'image/png', body: options.atlas });
    await page.clock.resume();
    await waitIconColor(page, page.locator('#items .resource-icon').first(), 'green');
    await page.clock.pauseAt(await page.evaluate(() => Date.now()));
    await page.clock.runFor(900);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'green');
    await page.clock.runFor(200);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'red');
});

test('failed optional animation pages keep static icons and are not retried by refreshes', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    await page.route(`**/pages/${'c'.repeat(64)}`, (route) => route.fulfill({ status: 503, body: 'Unavailable' }));
    let animationReads = 0;
    page.on('request', (request) => {
        if (request.url().includes(`/pages/${'c'.repeat(64)}`)) animationReads++;
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await waitIconColor(page, page.locator('#items .resource-icon'), 'red');
    for (let i = 0; i < 3; i++) {
        options.itemsA[0].quantity++;
        await page.locator('#refresh').click();
        await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    }
    assert.equal(animationReads, 1);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'red');
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    assert.equal(await page.getByRole('button', { name: 'Craft', exact: true }).isEnabled(), true);
});

for (const size of [128, 2048]) {
    const admitted = 10;
    test(`all visible animations play with ${size}px pages and retain their page when filtered`, async (t) => {
        const { page, options, base } = await animatedFixture(t);
        await page.setViewportSize({ width: 1400, height: 1400 });
        const png = Buffer.from(
            await page.evaluate((size) => {
                const canvas = document.createElement('canvas');
                canvas.width = size;
                canvas.height = size;
                const context = canvas.getContext('2d');
                context.fillStyle = '#ff0000';
                context.fillRect(0, 0, 64, 64);
                context.fillStyle = '#00ff00';
                context.fillRect(64, 0, 64, 64);
                return canvas.toDataURL().split(',')[1];
            }, size),
            'base64'
        );
        const digests = Array.from({ length: 10 }, (_, index) => `d${index}`.padEnd(64, '0'));
        options.icons.pages = [
            options.icons.pages[0],
            ...digests.map((digest) => ({ digest, width: size, height: size }))
        ];
        options.icons.animations = digests.map((_, index) => ({
            page: index + 1,
            frames: [
                { x: 64, y: 0, durationMillis: 5000 },
                { x: 0, y: 0, durationMillis: 5000 }
            ]
        }));
        options.itemsA = digests.map((_, index) => ({
            ...iron,
            itemKey: `resource-${index}`,
            displayName: `Resource ${index}`,
            icon: { page: 0, x: 0, y: 0, animation: index }
        }));
        const reads = [];
        await page.route('**/api/icon-packs/**/pages/d*', (route) => {
            reads.push(route.request().url());
            return route.fulfill({ status: 200, contentType: 'image/png', body: png });
        });
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 10);
        for (const icon of await page.locator('#items .resource-icon').all()) await waitIconColor(page, icon, 'green');
        assert.equal(reads.length, admitted);
        assert.equal(await page.locator('#items button').count(), 10, 'capacity affects only animation, not resources');
        await page.locator('#search').fill('Resource 9');
        await page.waitForFunction(() => document.querySelectorAll('#items button').length === 1);
        await waitIconColor(page, page.locator('#items .resource-icon'), 'green');
        assert.equal(
            reads.filter((url) => url.endsWith(digests[9])).length,
            1,
            'the remaining visible resource reuses its loaded page'
        );
    });
}

test('failed animation pages release admission so healthy visible resources can still animate', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    await page.setViewportSize({ width: 1400, height: 1400 });
    const digests = Array.from({ length: 10 }, (_, index) => `d${index}`.padEnd(64, '0'));
    options.icons.pages = [options.icons.pages[0], ...digests.map((digest) => ({ digest, width: 128, height: 64 }))];
    options.icons.animations = digests.map((_, index) => ({ ...options.icons.animations[0], page: index + 1 }));
    options.itemsA = digests.map((_, index) => ({
        ...iron,
        itemKey: `resource-${index}`,
        displayName: `Resource ${index}`,
        resourceType: index % 2 ? 'FLUID' : 'ITEM',
        icon: { page: 0, x: 0, y: 0, animation: index }
    }));
    await page.route('**/api/icon-packs/**/pages/d*', (route) => {
        const index = digests.findIndex((digest) => route.request().url().endsWith(digest));
        return route.fulfill(
            index < 8
                ? { status: 503, body: 'Unavailable' }
                : { status: 200, contentType: 'image/png', body: options.atlas }
        );
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    for (let index = 0; index < 10; index++)
        await waitIconColor(page, page.locator('#items .resource-icon').nth(index), index < 8 ? 'red' : 'green');
    assert.equal(await page.locator('#items button').count(), 10);
});

test('malformed animation metadata falls back to the valid static crop', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    options.icons.animations[0].frames[1].x = 128;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await waitIconColor(page, page.locator('#items .resource-icon'), 'red');
    await page.clock.runFor(4000);
    assert.equal(await iconColor(page, page.locator('#items .resource-icon')), 'red');
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 1);
});

for (const deviceScaleFactor of [1.25, 2])
    test(`terminal icons remain aligned after scrolling and resizing at pixel density ${deviceScaleFactor}`, async (t) => {
        const { page, options, base } = await animatedFixture(t, {
            deviceScaleFactor,
            viewport: { width: 900, height: 650 }
        });
        options.itemsA = Array.from({ length: 120 }, (_, index) => ({
            ...options.itemsA[0],
            itemKey: `resource-${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`
        }));
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
        await page.goto(`${base}#/grids/${gridA}/items`);
        const first = page.getByRole('button', { name: /Resource 000/ });
        await waitIconColor(page, first.locator('.resource-icon'), 'green');
        const last = page.getByRole('button', { name: /Resource 119/ });
        await last.scrollIntoViewIfNeeded();
        await waitIconColor(page, last.locator('.resource-icon'), 'green');
        await page.setViewportSize({ width: 720, height: 580 });
        await last.scrollIntoViewIfNeeded();
        await waitIconColor(page, last.locator('.resource-icon'), 'green');
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Font', exact: true }).selectOption('lato');
        await page.getByRole('combobox', { name: 'Resource columns', exact: true }).selectOption('3');
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
        await page.goto(`${base}#/grids/${gridA}/items`);
        await last.scrollIntoViewIfNeeded();
        await waitIconColor(page, last.locator('.resource-icon'), 'green');
        await last.click({ button: 'right' });
        await page.getByRole('menuitem', { name: 'Pin in terminal', exact: true }).click();
        const pin = page.locator('#pinned-items').getByRole('button', { name: /Resource 119/ });
        await pin.scrollIntoViewIfNeeded();
        await waitIconColor(page, pin.locator('.resource-icon'), 'green');
        await pin.click({ button: 'right' });
        await page.getByRole('menuitem', { name: 'Unpin from terminal', exact: true }).click();
        await pin.waitFor({ state: 'detached' });
        await last.scrollIntoViewIfNeeded();
        await waitIconColor(page, last.locator('.resource-icon'), 'green');
    });

test('terminal keeps animated icons visible when a drawing surface is unavailable', async (t) => {
    const { page, base } = await animatedFixture(t);
    await page.addInitScript(() => {
        const getContext = HTMLCanvasElement.prototype.getContext;
        window.restoreCanvasContext = () => {
            HTMLCanvasElement.prototype.getContext = getContext;
        };
        HTMLCanvasElement.prototype.getContext = () => null;
    });
    await page.reload();
    await page.goto(`${base}#/grids/${gridA}/items`);
    const icon = page.locator('#items .resource-icon');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.evaluate(() => window.restoreCanvasContext());
    await waitIconColor(page, icon, 'green');
    await page.clock.pauseAt(new Date());
    await page.clock.runFor(1100);
    assert.equal(await iconColor(page, icon), 'red');
});
