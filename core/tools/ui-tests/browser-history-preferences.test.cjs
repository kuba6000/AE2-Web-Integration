const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, gridA, historyDetail } = require('./fixtures.cjs');

test('history timeline sorting survives reload and new snapshots', async (t) => {
    const { page, options, base } = await fixture(t);
    options.historyDetail = {
        ...historyDetail,
        items: [
            { ...historyDetail.items[0], itemKey: 'zulu', displayName: 'Zulu', timeSpentOn: 4000 },
            { ...historyDetail.items[0], itemKey: 'alpha', displayName: 'Alpha', timeSpentOn: 1000 }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const sort = page.getByRole('combobox', { name: 'Sort by', exact: true });
    await sort.selectOption('name');
    await page.reload();
    await sort.waitFor();
    assert.equal(await sort.inputValue(), 'name');
    const regions = page.locator('.history-analysis').getByRole('region');
    assert.deepEqual(await regions.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('aria-label'))), [
        'Alpha',
        'Zulu'
    ]);
    await page.goto(`${base}#/grids/${gridA}/history/2`);
    await sort.waitFor();
    assert.equal(await sort.inputValue(), 'name');
});
