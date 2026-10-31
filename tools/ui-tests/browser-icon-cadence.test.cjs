const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, gridA, iron } = require('./fixtures.cjs');
const { iconColor, waitIconColor } = require('./icon-pixels.cjs');

test('animation update interval defaults to 200 ms and preserves an explicit Uncapped preference', async (t) => {
    const { page, base } = await fixture(t);
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('heading', { name: 'Web settings', exact: true }).waitFor();
    const interval = page.getByRole('slider', { name: 'Animation update interval', exact: true });
    assert.equal(await interval.count(), 1, 'Animation settings expose the update interval');
    assert.equal(await interval.inputValue(), '200');
    assert.equal(await interval.getAttribute('min'), '0');
    assert.equal(await interval.getAttribute('max'), '1000');
    assert.equal(await interval.getAttribute('step'), '10');
    assert.equal(await page.getByText('200 ms', { exact: true }).isVisible(), true);
    await interval.fill('230');
    assert.equal(await page.getByText('230 ms', { exact: true }).isVisible(), true);
    await page.reload();
    assert.equal(await interval.inputValue(), '230');
    assert.equal(await page.getByText('230 ms', { exact: true }).isVisible(), true);
    const animations = page.getByRole('checkbox', { name: 'Animated resource icons', exact: true });
    await animations.uncheck();
    assert.equal(await interval.isDisabled(), true);
    await animations.check();
    assert.equal(await interval.inputValue(), '230', 'Toggling playback preserves the selected interval');
    await interval.fill('0');
    assert.equal(await page.getByText('Uncapped', { exact: true }).isVisible(), true);
    await page.reload();
    assert.equal(await interval.inputValue(), '0');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    const polishInterval = page.getByRole('slider', { name: 'Odstęp aktualizacji animacji', exact: true });
    await page.getByText('Bez limitu', { exact: true }).waitFor();
    assert.equal(await polishInterval.getAttribute('aria-valuetext'), 'Bez limitu');
    await polishInterval.fill('200');
    assert.equal(await page.getByText('200 ms', { exact: true }).isVisible(), true);
    await polishInterval.fill('0');
    assert.equal(await page.getByText('Bez limitu', { exact: true }).isVisible(), true);
});

test('animation update interval holds frames between batches while preserving elapsed animation time', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.icons.pages.push({ digest: 'c'.repeat(64), width: 128, height: 64 });
    options.icons.animations = [
        {
            page: 1,
            frames: [
                { x: 64, y: 0, durationMillis: 100 },
                { x: 0, y: 0, durationMillis: 600 }
            ]
        }
    ];
    options.icons.pages.push({ digest: 'd'.repeat(64), width: 128, height: 64 });
    options.icons.animations.push({ ...options.icons.animations[0], page: 2 });
    options.itemsA = [
        { ...iron, icon: { page: 0, x: 0, y: 0, animation: 0 } },
        { ...iron, itemKey: 'second', displayName: 'Second Resource', icon: { page: 0, x: 0, y: 0, animation: 1 } }
    ];
    let releasePage;
    const delayedPage = new Promise((resolve) => {
        releasePage = resolve;
    });
    await page.route('**/api/icon-packs/**/pages/d*', async (route) => {
        await delayedPage;
        await route.fulfill({ status: 200, contentType: 'image/png', body: options.atlas });
    });
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('slider', { name: 'Animation update interval', exact: true }).fill('1000');
    await page.goto(`${base}#/grids/${gridA}/items`);
    const icon = page.getByRole('button', { name: /Iron Ingot/ }).locator('.resource-icon');
    const lateIcon = page.getByRole('button', { name: /Second Resource/ }).locator('.resource-icon');
    await waitIconColor(page, icon, 'green');
    await page.clock.pauseAt(await page.evaluate(() => Date.now()));
    await page.clock.runFor(200);
    assert.equal(await iconColor(page, icon), 'green', 'The visible frame waits for the selected update interval');
    const loaded = page.waitForResponse((response) => response.url().includes(`/pages/${'d'.repeat(64)}`));
    releasePage();
    await (await loaded).finished();
    await page.clock.runFor(50);
    assert.equal(await iconColor(page, icon), 'green', 'A newly loaded atlas cannot advance the shared batch early');
    assert.equal(await iconColor(page, lateIcon), 'red', 'The newly loaded animation waits with the existing icons');
    await page.clock.runFor(850);
    assert.equal(await iconColor(page, icon), 'red', 'The next batch displays the frame at elapsed animation time');
    assert.equal(await iconColor(page, lateIcon), 'green', 'The new animation joins the same update batch');
    await page.clock.runFor(1000);
    assert.equal(await iconColor(page, icon), 'red', 'Skipped intermediate frames do not slow the animation cycle');
    await page.clock.resume();
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('slider', { name: 'Animation update interval', exact: true }).fill('0');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await waitIconColor(page, icon, 'green');
    await page.clock.pauseAt(await page.evaluate(() => Date.now()));
    await page.clock.runFor(200);
    assert.equal(await iconColor(page, icon), 'red', 'Uncapped restores playback at the authored frame duration');
});
