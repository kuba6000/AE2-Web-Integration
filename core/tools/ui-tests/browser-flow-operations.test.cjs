const { test } = require('node:test');
const assert = require('node:assert/strict');
const { gridA, refreshingFixture, fixture } = require('./fixtures.cjs');

test('recent flow displays operation counts and rates over the measured period, withholding rates across gaps', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const now = Date.now();
    options.liveView = { generation: '1', revision: '1' };
    options.recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: null,
        flowSince: now - 30000,
        stockSince: null,
        flowComplete: true,
        stockComplete: false,
        resources: {
            iron: { input: '38400', output: '960', inputOperations: '600', outputOperations: '30', stockChange: null }
        }
    };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    const item = page.locator('#items button').filter({ hasText: 'Iron' }).first();
    await item.hover();
    const tooltip = page.locator('#resource-tooltip');
    const input = tooltip.locator('[data-flow-operations="input"]');
    const output = tooltip.locator('[data-flow-operations="output"]');
    await input.waitFor({ timeout: 5000 });
    assert.match(await input.innerText(), /600/);
    assert.match(await input.innerText(), /20\/s/);
    assert.match(await output.innerText(), /30/);
    assert.match(await output.innerText(), /1\/s/);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [], recent: { ...options.recent, flowComplete: false, resources: { iron: { ...options.recent.resources.iron, inputOperations: '601' } } } })}\n\n`
    );
    await page.waitForFunction(() =>
        document.querySelector('#resource-tooltip [data-flow-operations="input"]')?.textContent.includes('601')
    );
    assert.doesNotMatch(await input.innerText(), /\/s/);
    await item.click();
    const details = page.locator('#details [data-flow-operations="input"]');
    await details.waitFor();
    assert.match(await details.innerText(), /601/);
    assert.doesNotMatch(await details.innerText(), /\/s/);
});

test('history totals retain exact operation counts separately from transferred quantities', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.setViewportSize({ width: 390, height: 844 });
    const now = Date.now();
    options.resourceHistory = {
        baseline: null,
        complete: true,
        truncated: false,
        points: [
            {
                flowFrom: now - 10000,
                observedAt: now - 5000,
                generation: '1',
                kind: 'FLOW',
                resource: null,
                input: '90071992547409930',
                output: '640',
                inputOperations: '9007199254740993',
                outputOperations: '10'
            },
            {
                flowFrom: now - 5000,
                observedAt: now,
                generation: '1',
                kind: 'FLOW',
                resource: null,
                input: '64',
                output: '128',
                inputOperations: '1',
                outputOperations: '2'
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    const totals = page.locator('.tracking-view .tracking-totals');
    const input = totals.locator('[data-flow-operations="input"]');
    await input.waitFor({ timeout: 5000 });
    assert.match((await input.innerText()).replace(/,/g, ''), /9007199254740994/);
    assert.match(await totals.locator('[data-flow-operations="output"]').innerText(), /12/);
    assert.doesNotMatch(await input.innerText(), /\/s/);
    assert.ok(
        await totals.evaluate((node) => node.scrollWidth <= node.clientWidth),
        'Exact counts fit the phone summary'
    );
});
