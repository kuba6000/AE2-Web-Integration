const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, cpu, gridA, clickTab, settleFrames } = require('./fixtures.cjs');

async function overviewFixture(t) {
    const result = await fixture(t);
    result.options.capabilities.cpuSelectionMode = true;
    result.options.cpus = {
        'cpu-key-z': { ...cpu, name: '§aZinc', availableStorage: 4096, isBusy: true, selectionMode: 'AUTOMATION_ONLY' },
        alpha: {
            ...cpu,
            name: 'Alpha',
            availableStorage: 8192,
            isBusy: true,
            isPaused: true,
            selectionMode: 'PLAYER_ONLY'
        },
        delta: { ...cpu, name: 'Delta', availableStorage: 2048, selectionMode: 'ALL' },
        beta: { ...cpu, name: 'Beta', availableStorage: 16384 }
    };
    await result.page.goto(`${result.base}#/grids/${gridA}/cpus`);
    const overview = result.page.getByRole('region', { name: 'CPUs', exact: true });
    await overview.getByRole('heading', { name: 'Alpha', level: 3, exact: true }).waitFor();
    return { ...result, overview, names: () => overview.getByRole('heading', { level: 3 }).allTextContents() };
}

test('CPU overview applies and restores sorting and status filters while search stays temporary', async (t) => {
    const { page, base, options, overview, names } = await overviewFixture(t);
    await page.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).click();
    assert.deepEqual(await names(), ['Delta', 'Zinc', 'Alpha', 'Beta']);
    await page.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).click();
    assert.deepEqual(await names(), ['Beta', 'Alpha', 'Zinc', 'Delta']);
    await page.getByRole('button', { name: 'Show: All', exact: true }).click();
    assert.deepEqual(await names(), ['Alpha', 'Zinc'], 'Paused work is included among busy CPUs');
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).click();
    const search = page.getByRole('searchbox', { name: 'Search CPUs', exact: true });
    await search.fill('ZINC');
    assert.deepEqual(await names(), ['Zinc']);
    await search.fill('cpu-key-z');
    assert.deepEqual(await names(), ['Zinc'], 'CPU identifiers are searchable independently of display names');
    await search.fill('missing');
    assert.deepEqual(await names(), []);
    await page.reload();
    await overview.getByRole('heading', { name: 'Alpha', level: 3, exact: true }).waitFor();
    assert.deepEqual(await names(), ['Alpha', 'Zinc']);
    assert.equal(await page.getByRole('button', { name: 'Sort CPUs: Capacity', exact: true }).isVisible(), true);
    assert.equal(await page.getByRole('button', { name: 'CPU sort order: Descending', exact: true }).isVisible(), true);
    await page.getByRole('button', { name: 'Show: Busy', exact: true }).click();
    assert.deepEqual(await names(), ['Beta', 'Delta']);
    await page.getByRole('button', { name: 'Show: Idle', exact: true }).click();
    options.cpus['cpu-key-z'].availableStorage = 32768;
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await refreshed;
    await settleFrames(page);
    assert.deepEqual(await names(), ['Zinc', 'Beta', 'Alpha', 'Delta'], 'Refresh reorders existing cards');
    await clickTab(page, 'Terminal');
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    await overview.getByRole('heading', { name: 'Alpha', level: 3, exact: true }).waitFor();
    assert.deepEqual(await names(), ['Zinc', 'Beta', 'Alpha', 'Delta']);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('CPU overview preserves automation sorting across capability changes and fits a phone viewport', async (t) => {
    const { page, options, overview, names } = await overviewFixture(t);
    await page.setViewportSize({ width: 390, height: 720 });
    for (const criterion of ['Name', 'Capacity', 'Coprocessors', 'Busy state'])
        await page.getByRole('button', { name: `Sort CPUs: ${criterion}`, exact: true }).click();
    assert.deepEqual(await names(), ['Alpha', 'Delta', 'Zinc', 'Beta'], 'Unsupported selection modes sort last');
    options.capabilities.cpuSelectionMode = false;
    await page.reload();
    await overview.getByRole('heading', { name: 'Alpha', level: 3, exact: true }).waitFor();
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Delta', 'Zinc'], 'Unsupported automation sorting uses names');
    options.capabilities.cpuSelectionMode = true;
    await page.reload();
    await overview.getByRole('heading', { name: 'Alpha', level: 3, exact: true }).waitFor();
    assert.deepEqual(
        await names(),
        ['Alpha', 'Delta', 'Zinc', 'Beta'],
        'Capability fallback preserves the saved choice'
    );
    await page.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).click();
    assert.deepEqual(await names(), ['Zinc', 'Delta', 'Alpha', 'Beta'], 'Unknown modes stay last in both directions');
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).click();
    await page.getByRole('searchbox', { name: 'Search CPUs', exact: true }).fill('zinc');
    assert.deepEqual(await names(), ['Zinc']);
    const controls = page.getByRole('group', { name: 'CPUs', exact: true });
    for (const control of await controls.getByRole('button').all()) {
        const bounds = await control.boundingBox();
        assert.ok(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 390, 'CPU controls stay within the viewport');
    }
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
});
