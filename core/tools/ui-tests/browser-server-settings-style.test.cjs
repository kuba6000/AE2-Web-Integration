const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture } = require('./fixtures.cjs');

test('Discord crafting style reads, saves each choice and discards unsaved changes on reload', async (t) => {
    const { page, options, base } = await fixture(t);
    options.isAdmin = true;
    await page.goto(`${base}#/server-settings`);
    const style = page.getByRole('combobox', { name: 'Crafting notification style', exact: true });
    await style.waitFor({ timeout: 5000 });
    assert.equal(await style.inputValue(), 'IMAGE_CARD');
    assert.deepEqual(await style.locator('option').evaluateAll((options) => options.map((option) => option.value)), [
        'SIMPLE',
        'SMALL_ICON',
        'IMAGE_CARD'
    ]);
    const save = page.getByRole('button', { name: 'Save and apply', exact: true });
    assert.equal(await save.isDisabled(), true);
    for (const value of ['SIMPLE', 'SMALL_ICON', 'IMAGE_CARD']) {
        await style.selectOption(value);
        assert.equal(
            options.serverConfiguration.discord.craftingStyle,
            value === 'SIMPLE' ? 'IMAGE_CARD' : value === 'SMALL_ICON' ? 'SIMPLE' : 'SMALL_ICON'
        );
        await save.click();
        await page.waitForFunction(() => document.querySelector('#server-settings button[type="submit"]').disabled);
        assert.equal(options.serverConfiguration.discord.craftingStyle, value);
        const saved = options.requests
            .filter((request) => request.path === '/api/server/settings' && request.method === 'POST')
            .at(-1);
        assert.equal(saved.body.settings.discord.craftingStyle, value);
        await page.reload();
        await style.waitFor();
        assert.equal(await style.inputValue(), value);
    }
    await style.selectOption('SIMPLE');
    await page.getByRole('button', { name: 'Discard changes and reload', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#server-discord-craftingStyle')?.value === 'IMAGE_CARD');
    assert.equal(await save.isDisabled(), true);
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
});
