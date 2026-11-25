const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, gridA, iron } = require('./fixtures.cjs');

test('Tracking finds a historical resource absent from the terminal and preserves the search on Back', async (t) => {
    const { page, base, options } = await fixture(t);
    const archived = { ...iron, itemKey: 'archived', displayName: 'Archived Coil', registryPath: 'archived_coil' };
    options.itemsA = [];
    options.resourceHistory = {
        baseline: null,
        points: [
            {
                kind: 'STOCK',
                observedAt: Date.now(),
                generation: '1',
                resource: { ...archived, quantity: '0' },
                input: '0',
                output: '0',
                inputOperations: '0',
                outputOperations: '0',
                flowFrom: null
            }
        ],
        complete: true,
        enabled: true,
        truncated: false
    };
    await page.route('**/tracking/resources?**', (route) =>
        route.fulfill({
            json: {
                status: 'OK',
                data: new URL(route.request().url()).searchParams.get('search') === 'coil' ? [archived] : [iron],
                nextAfter: null
            }
        })
    );
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.getByRole('link', { name: 'Tracking', exact: true }).click({ timeout: 4000 });
    const search = page.getByRole('searchbox');
    await search.fill('coil');
    await search.press('Enter');
    await page.getByRole('link', { name: /Archived Coil/ }).click();
    await page.locator('.tracking-view').getByRole('heading', { name: 'Archived Coil' }).waitFor();
    await page
        .locator('.tracking-view')
        .getByRole('link', { name: /Tracking/ })
        .click({ timeout: 3000 });
    await page.getByRole('link', { name: /Archived Coil/ }).waitFor();
    assert.equal(await search.inputValue(), 'coil');
    assert.equal(
        options.requests.some((request) => request.path.endsWith('/items')),
        false
    );
});

test('an archived flow-only resource can be viewed beyond the last 24 hours', async (t) => {
    const { page, base } = await fixture(t);
    const observedAt = Date.now() - 3 * 86400000;
    const point = {
        kind: 'FLOW',
        observedAt,
        flowFrom: observedAt - 60000,
        generation: '1',
        resource: { ...iron, quantity: '0' },
        input: '0',
        output: '12345',
        inputOperations: '0',
        outputOperations: '1'
    };
    await page.route('**/resource-history?**', (route) => {
        const from = Number(new URL(route.request().url()).searchParams.get('from'));
        return route.fulfill({
            json: {
                status: 'OK',
                data: {
                    points: from <= observedAt ? [point] : [],
                    baseline: null,
                    complete: true,
                    enabled: false,
                    truncated: false
                }
            }
        });
    });
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.getByRole('combobox', { name: 'Period' }).selectOption('604800000', { timeout: 3000 });
    await page.locator('.tracking-view').getByRole('heading', { name: iron.displayName }).waitFor();
    assert.equal(await page.locator('.tracking-view .tracking-totals [aria-label="12,345"]').count(), 1);
});
