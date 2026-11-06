const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, gridA, iron, quartz } = require('./fixtures.cjs');

test('resource details and tracking expose the exact key without retaining it for unkeyed resources', async (t) => {
    const { page, options, base } = await fixture(t);
    const key = 'abcdefghijklmnopqrstuv';
    options.itemsA = [
        { ...iron, itemKey: key },
        { ...quartz, itemKey: null }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.locator('#details').getByRole('heading', { name: 'Iron Ingot', exact: true }).waitFor();
    assert.equal(await page.locator('#details').getByText(key, { exact: true }).isVisible(), true);
    await page.goto(`${base}#/grids/${gridA}/tracking/${key}`);
    await page.locator('.tracking-view').waitFor();
    assert.equal(await page.locator('.tracking-view code').getByText(key, { exact: true }).isVisible(), true);
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/tracking/${key}`);
    await page.locator('.tracking-view').waitFor();
    assert.equal(await page.locator('.tracking-view code').getByText(key, { exact: true }).isVisible(), true);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Certus Quartz/ }).click();
    await page
        .locator('#details')
        .getByRole('heading', { name: /Certus Quartz/ })
        .waitFor();
    assert.equal(await page.locator('#details').getByText(key, { exact: true }).count(), 0);
});
