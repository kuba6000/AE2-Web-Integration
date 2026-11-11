const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, settleFrames } = require('./fixtures.cjs');

async function discoverPack(page, action) {
    const discovery = page.waitForResponse((response) => response.url().endsWith('/api/icon-pack'));
    await action();
    await (await discovery).finished();
    await settleFrames(page);
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
}

test('admins with a confirmed missing icon pack get a local setup guide in Web settings', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 1280, height: 1600 } });
    options.isAdmin = true;
    options.pack = { available: true, hasAnimations: false, packId: 'a'.repeat(64), width: 64, height: 64 };
    await discoverPack(page, () => page.goto(`${base}#/web-settings`));
    const tip = page.locator('#web-settings').getByRole('note');
    assert.equal(await tip.isVisible(), false, 'An installed static pack does not need installation advice');
    const display = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await display.selectOption('names');
    await discoverPack(page, () => page.reload());
    assert.equal(await display.inputValue(), 'names');
    options.pack = { available: false, hasAnimations: false, packId: null, width: 0, height: 0 };
    await discoverPack(page, () => page.getByRole('button', { name: 'Refresh', exact: true }).click());
    assert.equal(await tip.count(), 1, 'Missing icons have a local informational tip for the signed-in admin');
    assert.equal(await tip.isVisible(), true);
    assert.equal(await tip.getByRole('link').getAttribute('href'), 'https://ae2web.kuba6000.pl/icon-export');
    const englishTip = await tip.textContent();
    assert.ok(englishTip.trim().length > 0);
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    assert.notEqual(await tip.textContent(), englishTip, 'The installation tip follows the selected language');
    options.packError = true;
    await discoverPack(page, () => page.locator('#refresh').click());
    assert.equal(await tip.isVisible(), false, 'A failed lookup does not assert that the server has no icon pack');
    options.packError = false;
    await discoverPack(page, () => page.locator('#refresh').click());
    assert.equal(await tip.isVisible(), true, 'A confirmed missing pack restores installation advice');
    assert.equal(
        options.requests.some((request) => request.path.endsWith('/items')),
        false,
        'The tip needs no inventory read'
    );
    await page.goto(`${base}#/`);
    assert.equal(
        await page.locator('a[href="https://ae2web.kuba6000.pl/icon-export"]').isVisible(),
        false,
        'The installation tip stays within Web settings'
    );
});

test('animation settings remain unavailable when the server has no icon pack', async (t) => {
    const { page, base } = await fixture(t);
    await discoverPack(page, () => page.goto(`${base}#/web-settings`));
    assert.equal(
        await page.locator('#web-settings').getByRole('note').isVisible(),
        false,
        'Players do not get admin installation advice'
    );
    const animations = page.getByRole('checkbox', { name: 'Animated resource icons', exact: true });
    const interval = page.getByRole('slider', { name: 'Animation update interval', exact: true });
    assert.equal(await animations.isDisabled(), true);
    assert.equal(await interval.isDisabled(), true);
    assert.equal(await animations.isChecked(), true, 'Unavailable playback retains the default preference');
});

test('static icon packs explain disabled animation settings with keyboard and pointer help', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 1280, height: 1600 } });
    options.pack = { available: true, hasAnimations: false, packId: 'a'.repeat(64), width: 64, height: 64 };
    await discoverPack(page, () => page.goto(`${base}#/web-settings`));
    const group = page.locator('#animation-settings');
    await page.getByRole('group', { name: 'Animated resource icons', exact: true }).waitFor();
    const help = group.locator('#animation-availability');
    assert.ok(
        (await help.textContent()).trim().length > 0,
        'A static pack explains why animation settings are disabled'
    );
    assert.equal(await group.getByRole('checkbox').isDisabled(), true);
    assert.equal(await group.getByRole('slider').isDisabled(), true);
    await group.scrollIntoViewIfNeeded();
    await settleFrames(page);
    await group.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor();
    assert.equal(await tooltip.textContent(), await help.textContent());
    const englishHelp = await help.textContent();
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await group.hover();
    await tooltip.waitFor();
    assert.notEqual(await help.textContent(), englishHelp, 'The availability explanation is translated');
    assert.equal(await tooltip.textContent(), await help.textContent(), 'Pointer help follows the current language');
});

test('pack changes and reinstalls preserve disabled playback and its saved interval', async (t) => {
    const { page, options, base } = await fixture(t);
    options.pack = { available: true, hasAnimations: true, packId: 'a'.repeat(64), width: 64, height: 64 };
    await discoverPack(page, () => page.goto(`${base}#/web-settings`));
    const animations = page.getByRole('checkbox', { name: 'Animated resource icons', exact: true });
    const interval = page.getByRole('slider', { name: 'Animation update interval', exact: true });
    await interval.fill('230');
    await animations.uncheck();
    const refresh = () => discoverPack(page, () => page.getByRole('button', { name: 'Refresh', exact: true }).click());

    options.pack = { available: false, hasAnimations: false, packId: null, width: 0, height: 0 };
    await refresh();
    assert.equal(await animations.isDisabled(), true);
    assert.equal(await animations.isChecked(), false);
    assert.equal(await interval.isDisabled(), true);
    assert.equal(await interval.inputValue(), '230');
    await discoverPack(page, () => page.reload());
    assert.equal(await animations.isChecked(), false, 'Reloading without a pack retains disabled playback');

    options.pack = { available: true, hasAnimations: false, packId: 'b'.repeat(64), width: 64, height: 64 };
    await refresh();
    assert.equal(await animations.isDisabled(), true, 'A replacement static pack cannot animate');
    options.pack = { available: true, hasAnimations: true, packId: 'c'.repeat(64), width: 64, height: 64 };
    await refresh();
    assert.equal(await animations.isEnabled(), true, 'An animated pack enables settings without visiting inventory');
    assert.equal(await animations.isChecked(), false, 'Reinstalling does not enable playback against the saved choice');
    assert.equal(await interval.isDisabled(), true);
    await animations.check();
    assert.equal(await interval.isEnabled(), true);
    assert.equal(await interval.inputValue(), '230');
    await discoverPack(page, () => page.reload());
    assert.equal(await animations.isChecked(), true);
    assert.equal(await interval.inputValue(), '230');

    options.packError = true;
    await refresh();
    assert.equal(await animations.isEnabled(), true, 'A transient discovery error retains confirmed capability');
    assert.equal(await interval.isEnabled(), true);
});

test('pending and failed discovery keep animation settings disabled until capability is confirmed', async (t) => {
    const { page, options, base } = await fixture(t);
    options.isAdmin = true;
    let intercepted;
    const held = new Promise((resolve) => {
        intercepted = resolve;
    });
    await page.route('**/api/icon-pack', (route) => intercepted(route));
    await page.goto(`${base}#/web-settings`);
    const pending = await held;
    const animations = page.getByRole('checkbox', { name: 'Animated resource icons', exact: true });
    const interval = page.getByRole('slider', { name: 'Animation update interval', exact: true });
    const help = page.locator('#animation-availability');
    await animations.waitFor();
    const installTip = page.locator('#web-settings').getByRole('note');
    assert.equal(await installTip.isVisible(), false, 'Pending discovery does not mean an icon pack is missing');
    assert.equal(await animations.isDisabled(), true);
    assert.equal(await interval.isDisabled(), true);
    const loadingHelp = await help.textContent();
    assert.ok(loadingHelp.trim().length > 0);
    const failure = page.waitForResponse((response) => response.url().endsWith('/api/icon-pack'));
    await pending.fulfill({
        status: 503,
        contentType: 'application/json',
        body: '{"status":"INTERNAL_ERROR","data":null}'
    });
    await (await failure).finished();
    await settleFrames(page);
    assert.equal(await installTip.isVisible(), false, 'Failed initial discovery does not mean an icon pack is missing');
    assert.equal(await animations.isDisabled(), true);
    assert.equal(await interval.isDisabled(), true);
    assert.notEqual(await help.textContent(), loadingHelp, 'A discovery failure is distinct from pending discovery');
    await page.unroute('**/api/icon-pack');
    options.pack = { available: true, hasAnimations: true, packId: 'a'.repeat(64), width: 64, height: 64 };
    await discoverPack(page, () => page.getByRole('button', { name: 'Refresh', exact: true }).click());
    assert.equal(await animations.isEnabled(), true);
    assert.equal(await animations.isChecked(), true);
    assert.equal(await interval.isEnabled(), true);
});
