const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, gridA, iron, clickTab, settleFrames } = require('./fixtures.cjs');

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
    return result;
}

test('terminal plays frame holds without replacing its resource tile or making per-frame requests', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const tile = page.getByRole('button', { name: /Iron Ingot/ });
    await tile.waitFor();
    const original = await tile.elementHandle();
    const position = () =>
        tile.locator('.resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX);
    await page.waitForFunction(
        () => getComputedStyle(document.querySelector('#items .resource-icon')).backgroundPositionX === '-32px'
    );
    await page.clock.pauseAt(new Date());
    assert.equal(await position(), '-32px');
    await page.clock.runFor(1100);
    assert.equal(await position(), '0px');
    await page.clock.runFor(1200);
    assert.equal(await position(), '0px', 'the second frame has a longer hold');
    await page.clock.runFor(800);
    assert.equal(await position(), '-32px');
    assert.equal(await tile.evaluate((current, original) => current === original, original), true);
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 2);
});

test('live response-local animation tables preserve the selected sequence and its current hold', async (t) => {
    const { page, options, base } = await animatedFixture(t, {}, true);
    options.liveView = { generation: '1', revision: '1' };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    await page.waitForFunction(
        () => getComputedStyle(document.querySelector('#items .resource-icon')).backgroundPositionX === '-32px'
    );
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
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '0px'
    );
    await page.clock.runFor(2000);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '-32px'
    );
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 2);
});

test('animation preference and reduced motion retain the static icon without fetching animation pages', async (t) => {
    const { page, options, base } = await animatedFixture(t, { reducedMotion: 'reduce' });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 1);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.waitForFunction(
        () => getComputedStyle(document.querySelector('#items .resource-icon')).backgroundPositionX === '-32px'
    );
    await clickTab(page, 'Web settings');
    await page.getByRole('checkbox', { name: 'Animated resource icons' }).uncheck();
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    await page.clock.runFor(4000);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '0px'
    );
    const reads = options.requests.filter((request) => request.path.includes(`/pages/${'c'.repeat(64)}`)).length;
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
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
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    await page.clock.pauseAt(new Date());
    await page.clock.runFor(2500);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '0px'
    );
    await pending.fulfill({ status: 200, contentType: 'image/png', body: options.atlas });
    await page.clock.resume();
    await page.waitForFunction(
        () => getComputedStyle(document.querySelector('#items .resource-icon')).backgroundPositionX === '-32px'
    );
    await page.clock.pauseAt(await page.evaluate(() => Date.now()));
    await page.clock.runFor(900);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '-32px'
    );
    await page.clock.runFor(200);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '0px'
    );
});

test('failed optional animation pages keep static icons and are not retried by refreshes', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    await page.route(`**/pages/${'c'.repeat(64)}`, (route) => route.fulfill({ status: 503, body: 'Unavailable' }));
    let animationReads = 0;
    page.on('request', (request) => {
        if (request.url().includes(`/pages/${'c'.repeat(64)}`)) animationReads++;
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    for (let i = 0; i < 3; i++) {
        options.itemsA[0].quantity++;
        await page.locator('#refresh').click();
        await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    }
    assert.equal(animationReads, 1);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '0px'
    );
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    assert.equal(await page.getByRole('button', { name: 'Craft', exact: true }).isEnabled(), true);
});

for (const [size, admitted] of [
    [128, 8],
    [2048, 4]
]) {
    test(`visible animations respect page and decoded-byte budgets with ${size}px pages and release them when filtered`, async (t) => {
        const { page, options, base } = await animatedFixture(t);
        await page.setViewportSize({ width: 1400, height: 1400 });
        const png = Buffer.from(
            await page.evaluate((size) => {
                const canvas = document.createElement('canvas');
                canvas.width = size;
                canvas.height = size;
                const context = canvas.getContext('2d');
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
        await page.waitForFunction(
            (count) =>
                [...document.querySelectorAll('#items .resource-icon')].filter(
                    (icon) => getComputedStyle(icon).backgroundPositionX === '-32px'
                ).length === count,
            admitted
        );
        assert.equal(reads.length, admitted);
        assert.equal(await page.locator('#items button').count(), 10, 'capacity affects only animation, not resources');
        await page.locator('#search').fill('Resource 9');
        await page.waitForFunction(
            () =>
                document.querySelectorAll('#items button').length === 1 &&
                getComputedStyle(document.querySelector('#items .resource-icon')).backgroundPositionX === '-32px'
        );
        assert.equal(
            reads.filter((url) => url.endsWith(digests[9])).length,
            1,
            'a newly visible waiting page can use released capacity'
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
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('#items .resource-icon')].filter(
                (icon) => getComputedStyle(icon).backgroundPositionX === '-32px'
            ).length === 2
    );
    assert.equal(await page.locator('#items button').count(), 10);
});

test('malformed animation metadata falls back to the valid static crop', async (t) => {
    const { page, options, base } = await animatedFixture(t);
    options.icons.animations[0].frames[1].x = 128;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    await page.clock.runFor(4000);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPositionX),
        '0px'
    );
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 1);
});
