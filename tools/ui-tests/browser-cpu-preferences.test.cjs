const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, cpuWork, gridA, clickTab } = require('./fixtures.cjs');

async function cpuPreferencesFixture(t) {
    const result = await fixture(t);
    const items = [
        ['Alpha', 1, 5, 0, 1000],
        ['Zinc', 1, 30, 0, 9000],
        ['Beta', 0, 0, 100, 500],
        ['Omega', 0, 90, 0, 100]
    ].map(([displayName, active, pending, stored, timeSpentCrafting]) => ({
        ...cpuWork.items[0],
        itemKey: displayName.toLowerCase(),
        displayName,
        active,
        pending,
        stored,
        timeSpentCrafting,
        shareInCraftingTime: timeSpentCrafting / 10600,
        crafting: false
    }));
    result.options.cpuDetails['cpu-a'] = { ...cpuWork, items };
    await result.page.goto(`${result.base}#/grids/${gridA}/cpus/cpu-a`);
    const resources = result.page.getByRole('region', { name: 'CPU resources', exact: true });
    await resources.getByRole('button', { name: /Omega/ }).waitFor();
    return { ...result, resources, names: () => resources.locator('button strong').allTextContents() };
}

test('CPU resource sorting and filtering survive reload independently of terminal preferences', async (t) => {
    const { page, options, resources, names } = await cpuPreferencesFixture(t);
    const hide = page.getByRole('button', { name: 'Hide stored-only resources', exact: true });
    const priority = page.getByRole('button', { name: 'Active first: active, pending, stored', exact: true });
    const pending = page.getByRole('button', { name: 'Sort by: Pending quantity', exact: true });
    await hide.click();
    await priority.click();
    await pending.click();
    assert.deepEqual(await names(), ['Zinc', 'Alpha', 'Omega']);
    await page.reload();
    await resources.getByRole('button', { name: /Omega/ }).waitFor();
    assert.deepEqual(await names(), ['Zinc', 'Alpha', 'Omega']);
    for (const control of [hide, priority, pending]) assert.equal(await control.getAttribute('aria-pressed'), 'true');
    await hide.click();
    await priority.click();
    await page.getByRole('button', { name: 'Sort by: Stored quantity', exact: true }).click();
    await page.reload();
    await resources.getByRole('button', { name: /Omega/ }).waitFor();
    assert.deepEqual(await names(), ['Beta', 'Alpha', 'Omega', 'Zinc']);
    for (const control of [hide, priority]) assert.equal(await control.getAttribute('aria-pressed'), 'false');
    await clickTab(page, 'Terminal');
    assert.equal(await page.getByRole('button', { name: 'Sort by: Name', exact: true }).isVisible(), true);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

test('CPU processing-share sort survives loading and temporarily unavailable tracking', async (t) => {
    const { page, base, options, resources, names } = await cpuPreferencesFixture(t);
    options.cpuDetails['cpu-b'] = { ...options.cpuDetails['cpu-a'], hasTrackingInfo: false };
    const share = page.getByRole('button', { name: 'Sort by: Share of processing time', exact: true });
    const name = page.getByRole('button', { name: 'Sort by: Name', exact: true });
    await share.click();
    assert.deepEqual(await names(), ['Zinc', 'Alpha', 'Beta', 'Omega']);
    await page.reload();
    await resources.getByRole('button', { name: /Omega/ }).waitFor();
    assert.equal(await share.getAttribute('aria-pressed'), 'true');
    assert.deepEqual(await names(), ['Zinc', 'Alpha', 'Beta', 'Omega']);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    await resources.getByRole('button', { name: /Omega/ }).waitFor();
    await share.waitFor({ state: 'hidden' });
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Omega', 'Zinc']);
    assert.equal(await name.getAttribute('aria-pressed'), 'true', 'Untracked CPUs use available name sorting');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await share.waitFor();
    assert.deepEqual(await names(), ['Zinc', 'Alpha', 'Beta', 'Omega']);
    assert.equal(
        await share.getAttribute('aria-pressed'),
        'true',
        'Tracking availability does not overwrite the saved choice'
    );
});
