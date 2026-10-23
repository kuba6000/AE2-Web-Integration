const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, gridA, cpuWork, historyDetail } = require('./fixtures.cjs');

test('CPU and completed crafting details show identified requesters safely and omit unknown attribution', async (t) => {
    const { page, options, base } = await fixture(t);
    const requesters = [
        { source: 'PLAYER', player: { uuid: '11111111-1111-1111-1111-111111111111', name: 'Alex' } },
        { source: 'PLAYER', player: { uuid: '22222222-2222-2222-2222-222222222222', name: '<img src=x>' } },
        { source: 'AUTOMATION', player: null },
        { source: 'WEB', player: null }
    ];
    options.cpuDetails['cpu-a'] = { ...cpuWork, requesters };
    options.historyDetail = { ...historyDetail, requesters };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const current = page.getByRole('region', { name: 'Current job', exact: true });
    await current.getByText('Alex, <img src=x>, Automation, Web', { exact: false }).waitFor({ timeout: 5000 });
    assert.equal(await current.locator('img').count(), 0, 'Names must render as text');
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByText('Alex, <img src=x>, Automation, Web', { exact: true }).waitFor({ timeout: 5000 });
    options.historyDetail = { ...historyDetail, requesters: [] };
    await page.reload();
    await page.getByRole('heading', { name: 'Iron Ingot', exact: true, level: 3 }).waitFor();
    assert.equal(await page.getByText('Alex, <img src=x>, Automation, Web', { exact: true }).count(), 0);
    options.cpuDetails['cpu-a'] = { ...cpuWork, requesters: [] };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await current.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.equal(await current.getByText('Requested by:', { exact: false }).count(), 0);
});
