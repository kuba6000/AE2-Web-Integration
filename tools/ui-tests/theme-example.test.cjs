const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright');

test('standalone theme uses shared data, optional helpers, persistent settings and explicit unsupported views', async (t) => {
    const { startServer } = await import('../theme-preview/server.mjs');
    const themeDirectory = await fs.mkdtemp(path.join(os.tmpdir(), 'ae2wi-theme-'));
    t.after(() => fs.rm(themeDirectory, { recursive: true, force: true }));
    await fs.cp(path.resolve(__dirname, '../../examples/theme-starter/dist'), themeDirectory, { recursive: true });
    const server = startServer({ port: 0, themeDirectory });
    await new Promise((resolve) => server.once('listening', resolve));
    t.after(() => new Promise((resolve) => server.close(resolve)));
    const browser = await chromium.launch({
        headless: true,
        ...(process.env.BROWSER_CHANNEL ? { channel: process.env.BROWSER_CHANNEL } : {})
    });
    t.after(() => browser.close());
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(String(error)));
    const base = `http://127.0.0.1:${server.address().port}/`;
    await page.goto(base);
    await page.getByRole('link', { name: 'Example network' }).click();
    const rows = page.getByRole('list', { name: 'Resources' }).getByRole('button');
    await rows.nth(2).waitFor();
    assert.deepEqual(await rows.allTextContents(), ['Crafting Table — 0', 'Gold Ingot — 32', 'Iron Ingot — 128']);
    await page.getByLabel('Search', { exact: true }).fill('iron');
    await page.waitForFunction(() => document.querySelectorAll('.items button').length === 1);
    await rows.first().click();
    assert.equal(await page.locator('.selection').textContent(), 'Selected: Iron Ingot');
    await page.getByLabel('Use standard search and ordering').uncheck();
    await rows.nth(2).waitFor();
    assert.deepEqual(await rows.allTextContents(), ['Iron Ingot — 128', 'Gold Ingot — 32', 'Crafting Table — 0']);
    await page.reload();
    await rows.nth(2).waitFor();
    assert.equal(await page.getByLabel('Use standard search and ordering').isChecked(), false);
    assert.equal(await page.getByLabel('Search', { exact: true }).isDisabled(), true);
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('heading', { name: 'View unavailable in this theme' }).waitFor();
    assert.deepEqual(await page.locator('nav a').allTextContents(), ['Networks']);
    await page.getByRole('button', { name: 'Log out' }).click();
    await page.getByRole('link', { name: 'Restart preview' }).waitFor();
    assert.deepEqual(errors, []);
});
