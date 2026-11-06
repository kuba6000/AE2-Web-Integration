const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, gridA, iron, pauseClock } = require('./fixtures.cjs');
const { iconColor, waitIconColor } = require('./icon-pixels.cjs');

async function scrollingFixture(t) {
    const result = await fixture(t, '', { viewport: { width: 1000, height: 650 } });
    const { page, options } = result;
    await atlasFixture(page, options);
    options.icons.pages.push({ digest: 'c'.repeat(64), width: 128, height: 64 });
    options.icons.animations = [
        {
            page: 1,
            frames: [
                { x: 64, y: 0, durationMillis: 5000 },
                { x: 0, y: 0, durationMillis: 5000 }
            ]
        }
    ];
    options.itemsA = Array.from({ length: 120 }, (_, index) => ({
        ...iron,
        itemKey: `resource-${index}`,
        displayName: `Resource ${String(index).padStart(3, '0')}`,
        icon: { page: 0, x: 0, y: 0, animation: 0 }
    }));
    await page.goto(`${result.base}#/web-settings`);
    await page.getByRole('slider', { name: 'Animation update interval', exact: true }).press('Home');
    return result;
}

async function assertFramesAdvance(page, ...icons) {
    await pauseClock(page);
    // Align to a newly observed green frame with virtual time, independent of prior wall time.
    for (const color of ['red', 'green']) {
        let actual = await iconColor(page, icons[0]);
        for (let step = 0; actual !== color && step < 12; step++) {
            await page.clock.runFor(1000);
            actual = await iconColor(page, icons[0]);
        }
        assert.equal(actual, color, 'the icon reaches its next frame');
    }
    await page.clock.runFor(5100);
    for (const icon of icons) assert.equal(await iconColor(page, icon), 'red');
}

test('visible resources follow reduced motion after scrolling and resume their frames', async (t) => {
    const { page, base } = await scrollingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const first = page.getByRole('button', { name: /Resource 000/ }).locator('.resource-icon');
    await waitIconColor(page, first, 'green');
    const middle = page.getByRole('button', { name: /Resource 060/ }).locator('.resource-icon');
    const neighbor = page.getByRole('button', { name: /Resource 061/ }).locator('.resource-icon');
    await middle.scrollIntoViewIfNeeded();
    await waitIconColor(page, middle, 'green');
    await waitIconColor(page, neighbor, 'green');

    await page.emulateMedia({ reducedMotion: 'reduce' });
    await waitIconColor(page, middle, 'red');
    await waitIconColor(page, neighbor, 'red');
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await waitIconColor(page, middle, 'green');
    await waitIconColor(page, neighbor, 'green');
    await assertFramesAdvance(page, middle, neighbor);
});

test('resources return to animation after emulated document visibility changes', async (t) => {
    const { page, base } = await scrollingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const first = page.getByRole('button', { name: /Resource 000/ }).locator('.resource-icon');
    const second = page.getByRole('button', { name: /Resource 001/ }).locator('.resource-icon');
    await waitIconColor(page, first, 'green');
    await waitIconColor(page, second, 'green');
    // Headless Chromium does not hide a page when another tab is brought to the front.
    const setDocumentHidden = (hidden) =>
        page.evaluate((hidden) => {
            Object.defineProperty(document, 'hidden', { configurable: true, value: hidden });
            document.dispatchEvent(new Event('visibilitychange'));
        }, hidden);
    await setDocumentHidden(true);
    await waitIconColor(page, first, 'red');
    await waitIconColor(page, second, 'red');
    await setDocumentHidden(false);
    await waitIconColor(page, first, 'green');
    await waitIconColor(page, second, 'green');
    await assertFramesAdvance(page, first, second);
});

test('an animation page delayed past scrolling away can load when its resource returns', async (t) => {
    const { page, options, base } = await scrollingFixture(t);
    options.icons.pages.push({ digest: 'd'.repeat(64), width: 128, height: 64 });
    options.icons.animations.push({ ...options.icons.animations[0], page: 2 });
    for (let index = 1; index < options.itemsA.length; index++) options.itemsA[index].icon.animation = 1;
    let releaseFirst;
    const firstRequest = new Promise((resolve) => {
        releaseFirst = resolve;
    });
    let requests = 0;
    await page.route(`**/pages/${'c'.repeat(64)}`, (route) => {
        if (++requests === 1) releaseFirst(route);
        else return route.fulfill({ status: 200, contentType: 'image/png', body: options.atlas });
    });
    const started = page.waitForRequest(`**/pages/${'c'.repeat(64)}`);
    const canceled = page.waitForEvent('requestfailed', (request) => request.url().endsWith('c'.repeat(64)));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await started;
    const pending = await firstRequest;
    const first = page.getByRole('button', { name: /Resource 000/ }).locator('.resource-icon');
    await waitIconColor(page, first, 'red');
    const last = page.getByRole('button', { name: /Resource 119/ }).locator('.resource-icon');
    await last.scrollIntoViewIfNeeded();
    await waitIconColor(page, last, 'green');
    await canceled;
    await pending.fulfill({ status: 200, contentType: 'image/png', body: options.atlas }).catch(() => {});
    await first.scrollIntoViewIfNeeded();
    await waitIconColor(page, first, 'green');
    await assertFramesAdvance(page, first);
});
