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

test('CPU overview and side tooltip show known authors without requiring tracking', async (t) => {
    const { page, options, base } = await fixture(t);
    const mixed = [
        { source: 'PLAYER', player: { uuid: '11111111-1111-1111-1111-111111111111', name: '<Alex>' } },
        { source: 'AUTOMATION', player: null },
        { source: 'WEB', player: null }
    ];
    const native = [];
    options.cpus = {
        'cpu-a': {
            ...options.cpus['cpu-a'],
            ...cpuWork,
            name: 'Mixed',
            requesters: mixed,
            nativeRequesterName: 'IgnoredNativeName'
        },
        'cpu-b': {
            ...options.cpus['cpu-b'],
            ...cpuWork,
            name: 'Native',
            hasTrackingInfo: false,
            requesters: native,
            nativeRequesterName: 'NativePlayer'
        },
        'cpu-c': { ...options.cpus['cpu-a'], ...cpuWork, name: 'Unknown', hasTrackingInfo: false, requesters: [] },
        'cpu-d': { ...options.cpus['cpu-a'], name: 'Idle', requesters: mixed }
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, requesters: mixed };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const overview = page.getByRole('region', { name: 'CPUs', exact: true });
    await overview.getByText('Requested by: <Alex>, Automation, Web', { exact: true }).waitFor({ timeout: 5000 });
    await overview.getByText('Requested by: NativePlayer', { exact: true }).waitFor();
    assert.equal(await overview.getByText('Requested by:', { exact: false }).count(), 2);
    assert.equal(await overview.locator('alex').count(), 0, 'Requester names remain literal text');
    assert.equal(options.requests.filter((request) => /\/cpus\/[^/]+$/.test(request.path)).length, 0);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const selector = page.getByRole('region', { name: 'CPU selector', exact: true });
    const tooltip = page.getByRole('tooltip');
    await selector.getByRole('link', { name: /Native.*cpu-b/ }).hover();
    await tooltip.getByText('Requested by: NativePlayer', { exact: true }).waitFor();
    await selector.getByRole('link', { name: /Unknown.*cpu-c/ }).hover();
    await tooltip.getByText('Unknown', { exact: true }).waitFor();
    assert.equal(await tooltip.getByText('Requested by:', { exact: false }).count(), 0);
    await selector.getByRole('link', { name: /Mixed.*cpu-a/ }).focus();
    await tooltip.getByText('Requested by: <Alex>, Automation, Web', { exact: true }).waitFor();
    assert.equal(
        options.requests.some((request) => request.path.endsWith('/cpus/cpu-b')),
        false
    );
    options.cpuDetails['cpu-b'] = {
        ...cpuWork,
        hasTrackingInfo: false,
        requesters: [],
        nativeRequesterName: 'NativePlayer'
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    await page
        .getByRole('region', { name: 'Current job', exact: true })
        .getByText('Requested by: NativePlayer', { exact: true })
        .waitFor();
});
