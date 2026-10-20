const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
    gridA,
    gridB,
    iron,
    quartz,
    readyPlan,
    cpu,
    cpuWork,
    refreshingFixture,
    fixture,
    clickTab,
    openMobilePane,
    closeMobilePane,
    settleFrames,
    settleResponse,
    poll,
    atlasFixture,
    textStyle
} = require('./fixtures.cjs');

test('CPU selector searches forty processors and preserves reading position across selection and polling', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.setViewportSize({ width: 1280, height: 800 });
    options.cpus = Object.fromEntries(
        Array.from({ length: 40 }, (_, index) => [
            `processor-${index}`,
            {
                ...cpu,
                name: index === 39 ? '' : `§aProduction ${String(index).padStart(2, '0')}`,
                isBusy: true,
                supportsPause: true
            }
        ])
    );
    options.cpuDetails = Object.fromEntries(
        Object.keys(options.cpus).map((key) => [key, { ...cpuWork, supportsPause: true }])
    );
    await page.goto(`${base}#/grids/${gridA}/cpus/processor-0`);
    const selector = page.getByRole('region', { name: 'CPU selector', exact: true });
    const search = page.getByRole('searchbox', { name: 'Search CPUs', exact: true });
    await selector.getByRole('link', { name: /Production 00/ }).waitFor();
    assert.equal(await selector.getByRole('link').count(), 40);
    assert.ok(await selector.evaluate((node) => node.scrollHeight > node.clientHeight));
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).click();
    await search.fill('Production');
    assert.equal(await selector.getByRole('link').count(), 39);
    const target = selector.getByRole('link', { name: /Production 25/ });
    await target.scrollIntoViewIfNeeded();
    const before = await target.boundingBox();
    const scrollBefore = await selector.evaluate((node) => node.scrollTop);
    await target.click();
    await page.getByRole('heading', { name: 'Production 25', level: 2, exact: true }).waitFor();
    assert.equal(await target.getAttribute('aria-current'), 'page');
    assert.equal(await search.inputValue(), 'Production');
    assert.equal(await selector.evaluate((node) => node.scrollTop), scrollBefore);
    assert.deepEqual(await target.boundingBox(), before, 'Selection must not expand or shift rows');
    await poll(page);
    assert.equal(await search.inputValue(), 'Production');
    assert.equal(await selector.evaluate((node) => node.scrollTop), scrollBefore);
    const resources = await page.getByRole('region', { name: 'CPU resources', exact: true }).boundingBox();
    const listBounds = await selector.boundingBox();
    const job = await page.getByRole('region', { name: 'Current job', exact: true }).boundingBox();
    const cancel = await page.getByRole('button', { name: 'Cancel current work', exact: true }).boundingBox();
    assert.ok(listBounds.x >= resources.x + resources.width - 1);
    assert.ok(job.y >= resources.y + resources.height - 1);
    assert.ok(cancel.y >= resources.y + resources.height - 1);
    assert.ok(job.y + job.height <= 800 && cancel.y + cancel.height <= 800);
    await search.fill('processor-39');
    assert.equal(await selector.getByRole('link').count(), 1);
    await selector.getByRole('link', { name: /processor-39/ }).click();
    await page.getByRole('heading', { name: 'processor-39', level: 2, exact: true }).waitFor();
    await page.goBack();
    await page.getByRole('heading', { name: 'Production 25', level: 2, exact: true }).waitFor();
    await search.fill('no-such-processor');
    assert.equal(await selector.getByRole('link').count(), 0);
    assert.match(await selector.textContent(), /No matching/);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Production 00/ }).waitFor();
    assert.equal(await search.isVisible(), false);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
    assert.ok(
        options.requests
            .filter((request) => /\/cpus\/[^/]+$/.test(request.path))
            .every((request) => /processor-(0|25|39)$/.test(request.path)),
        'Only selected CPUs need detail requests'
    );
});

test('CPU list fills its attachment and current job stays in a low footer even with long output', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.setViewportSize({ width: 1280, height: 720 });
    options.cpus = Object.fromEntries(
        Array.from({ length: 40 }, (_, index) => [
            `cpu-${index}`,
            { ...cpu, name: `Processor ${index}`, isBusy: true, supportsPause: true }
        ])
    );
    options.cpuDetails['cpu-0'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-0`);
    const details = page.getByRole('region', { name: 'Current job', exact: true });
    const selector = page.getByRole('region', { name: 'CPU selector', exact: true });
    const output = details.getByText(/^Iron Ingot/);
    await output.waitFor();
    const detailsBounds = await details.boundingBox();
    const outputBounds = await output.boundingBox();
    assert.ok(
        outputBounds.y >= detailsBounds.y &&
            outputBounds.y + outputBounds.height <= detailsBounds.y + detailsBounds.height,
        'An ordinary current job is visible without scrolling past hardware metadata'
    );
    const listBounds = await selector.boundingBox();
    const resources = await page.getByRole('region', { name: 'CPU resources', exact: true }).boundingBox();
    assert.ok(detailsBounds.y >= resources.y + resources.height - 1);
    assert.ok(listBounds.height >= resources.height * 0.7, 'CPU list uses the attachment height');
    assert.match(await selector.getByRole('link').first().innerText(), /8 KB/);
    options.cpuDetails['cpu-0'] = {
        ...cpuWork,
        supportsPause: true,
        finalOutput: { ...iron, displayName: 'Long product '.repeat(60) }
    };
    await poll(page);
    await details.getByText(/^Long product/).waitFor();
    assert.deepEqual(await selector.boundingBox(), listBounds);
    assert.deepEqual(await details.boundingBox(), detailsBounds);
    assert.ok(
        await details.evaluate((node) => node.scrollHeight <= node.clientHeight),
        'The job footer does not gain internal scrolling'
    );
    await details.focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: 'Long product '.repeat(60).trim() })
        .waitFor();
    await page.keyboard.press('Escape');
    const cancel = page.getByRole('button', { name: 'Cancel current work', exact: true });
    const actionBounds = await cancel.boundingBox();
    assert.ok(actionBounds.y + actionBounds.height <= 720);
    await page.setViewportSize({ width: 390, height: 844 });
    await openMobilePane(page, 'CPUs');
    const last = selector.getByRole('link', { name: /Processor 39/ });
    await last.scrollIntoViewIfNeeded();
    assert.ok(await selector.evaluate((node) => node.scrollTop > 0));
    const search = page.getByRole('searchbox', { name: 'Search CPUs', exact: true });
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).click();
    await search.fill('Processor 39');
    assert.equal(await selector.getByRole('link').count(), 1);
    await closeMobilePane(page);
    await details.scrollIntoViewIfNeeded();
    assert.ok((await details.boundingBox()).height < 80, 'The narrow job footer remains low');
    await cancel.scrollIntoViewIfNeeded();
    assert.deepEqual(
        await page.evaluate(() => ({
            x: scrollX,
            y: scrollY,
            width: document.documentElement.scrollWidth,
            height: document.documentElement.scrollHeight
        })),
        { x: 0, y: 0, width: 390, height: 844 }
    );
});

test('CPU oversized tooltip stays inside the viewport and exposes its end through keyboard scrolling', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.setViewportSize({ width: 800, height: 650 });
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        finalOutput: { ...iron, displayName: 'Oversized product '.repeat(400) }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const work = page.getByRole('region', { name: 'Current job', exact: true });
    await work.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor();
    const bounds = await tooltip.boundingBox();
    assert.ok(
        bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= 800 && bounds.y + bounds.height <= 650,
        'Long CPU details stay inside the viewport'
    );
    await page.keyboard.press('Tab');
    assert.equal(await tooltip.evaluate((node) => node === document.activeElement), true);
    await page.keyboard.press('End');
    await page.waitForFunction(() => document.querySelector('[role="tooltip"]')?.scrollTop > 0);
    const started = tooltip.getByText(/^Started:/);
    await started.scrollIntoViewIfNeeded();
    const lastBounds = await started.boundingBox();
    assert.ok(lastBounds.y >= bounds.y && lastBounds.y + lastBounds.height <= bounds.y + bounds.height);
    const readingPosition = await tooltip.evaluate((node) => node.scrollTop);
    await poll(page);
    assert.equal(await tooltip.evaluate((node) => node.scrollTop), readingPosition);
    await page.keyboard.press('Escape');
    assert.equal(await tooltip.isVisible(), false);
    assert.equal(await work.evaluate((node) => node === document.activeElement), true);
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
});

test('CPU selector keeps paused localized state and capacity inside intermediate-width attachments', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-a'] = {
        ...cpu,
        isBusy: true,
        isPaused: true,
        supportsPause: true,
        finalOutput: iron,
        availableStorage: JSON.parse('9223372036854775807')
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, isPaused: true, supportsPause: true };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const selector = page.getByRole('region', { name: 'Wybór CPU', exact: true });
    const row = selector.getByRole('link', { name: /cpu-a.*Wstrzymany.*8 EB/ });
    await row.waitFor();
    for (const width of [601, 800, 900]) {
        await page.setViewportSize({ width, height: 800 });
        const bounds = await selector.boundingBox();
        const capacity = await row.getByText('· 8 EB', { exact: true }).boundingBox();
        assert.ok(
            await selector.evaluate((node) => node.scrollWidth <= node.clientWidth),
            `Selector must not scroll horizontally at ${width}px`
        );
        assert.ok(
            capacity.x >= bounds.x && capacity.x + capacity.width <= bounds.x + bounds.width,
            'Capacity remains visible'
        );
    }
});

test('CPU tooltips expose live hardware and recorded start without extra detail reads and clear on filter or access loss', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus['cpu-b'] = {
        ...cpu,
        name: 'Molecular processor',
        isBusy: true,
        finalOutput: iron,
        usedStorage: 2048,
        coProcessors: 3,
        hasTrackingInfo: true,
        timeStarted: cpuWork.timeStarted
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const row = page.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link', { name: /cpu-b/ });
    await row.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.filter({ hasText: 'Molecular processor' }).waitFor();
    assert.match(await tooltip.innerText(), /Used storage: 2 KB/);
    assert.match(await tooltip.innerText(), /Coprocessors: 3/);
    assert.match(await tooltip.innerText(), /Started: .*2023/);
    assert.equal(await row.getAttribute('aria-describedby'), await tooltip.getAttribute('id'));
    assert.equal(
        options.requests.some((request) => request.path.endsWith('/cpus/cpu-b')),
        false
    );
    options.cpus['cpu-b'] = { ...options.cpus['cpu-b'], name: 'Updated processor', usedStorage: 4096, coProcessors: 7 };
    await poll(page);
    await tooltip.filter({ hasText: 'Updated processor' }).waitFor();
    assert.match(await tooltip.innerText(), /Used storage: 4 KB/);
    assert.match(await tooltip.innerText(), /Coprocessors: 7/);
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('searchbox', { name: 'Search CPUs', exact: true }).focus();
    await row.focus();
    await tooltip.filter({ hasText: 'Updated processor' }).waitFor();
    await page.getByRole('searchbox', { name: 'Search CPUs', exact: true }).fill('cpu-a');
    await row.waitFor({ state: 'hidden' });
    assert.equal(await tooltip.isVisible(), false);
    const work = page.getByRole('region', { name: 'Current job', exact: true });
    await work.focus();
    await tooltip.filter({ hasText: 'Iron Ingot' }).waitFor();
    options.detailError = 'NO_PERMISSIONS';
    await poll(page);
    await work.waitFor({ state: 'hidden' });
    assert.equal(await tooltip.isVisible(), false);
    assert.equal(await page.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link').count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('CPU terminal shows selected resources beside CPU navigation and returns to the overview', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-b'] = {
        ...cpu,
        name: 'Selected assembler',
        isBusy: true,
        supportsPause: true,
        usedStorage: 2048,
        coProcessors: 3
    };
    options.cpuDetails['cpu-b'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    await page.getByRole('link', { name: /Selected assembler.*cpu-b/ }).click();
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).waitFor();
    assert.equal(
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).count(),
        1,
        'Opening current work keeps other CPUs available in the selector'
    );
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    await resources.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('searchbox', { name: 'Search CPU resources' }).isVisible(), true);
    for (const name of [
        'Hide stored-only resources',
        'Active first: active, pending, stored',
        'Sort by: Name',
        'Sort by: Active quantity',
        'Sort by: Pending quantity',
        'Sort by: Stored quantity'
    ]) {
        assert.equal(await page.getByRole('button', { name, exact: true }).isVisible(), true);
    }
    const details = page.getByRole('complementary', { name: 'CPUs', exact: true });
    assert.equal(
        await page.getByRole('heading', { name: 'Selected assembler', level: 2, exact: true }).isVisible(),
        true
    );
    assert.match(await details.textContent(), /8 KB/);
    await details.getByRole('link', { name: /Selected assembler/ }).focus();
    assert.match(await page.getByRole('tooltip').textContent(), /Used storage: 2 KB/);
    assert.match(await page.getByRole('tooltip').textContent(), /Coprocessors: 3/);
    assert.match(await page.getByRole('region', { name: 'Current job', exact: true }).textContent(), /Iron Ingot.*12/);
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isEnabled(), true);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
    assert.match(page.url(), /\/cpus$/);
    await resources.waitFor({ state: 'hidden' });
    assert.equal(await resources.isVisible(), false);
    assert.equal(await details.isVisible(), false);
});

// Public seam: controls select and order the current job's actual categories without server mutations.
test('CPU terminal hides stored-only resources, sorts explicit quantities and prioritizes crafting states', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        items: [
            {
                ...cpuWork.items[0],
                displayName: 'Zinc',
                registryNamespace: 'minecraft',
                registryPath: 'zinc',
                active: 10,
                pending: 0,
                stored: 0
            },
            {
                ...cpuWork.items[0],
                displayName: 'Alpha',
                registryNamespace: 'minecraft',
                registryPath: 'unique_pending',
                active: 0,
                pending: 30,
                stored: 0
            },
            {
                ...cpuWork.items[0],
                displayName: 'Beta',
                registryNamespace: 'ae2',
                registryPath: 'beta',
                active: 0,
                pending: 0,
                stored: 20
            },
            {
                ...cpuWork.items[0],
                displayName: '§aMixed',
                registryNamespace: 'other',
                registryPath: 'mixed',
                active: 3,
                pending: 8,
                stored: 50
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    await resources.getByRole('button', { name: /Mixed/ }).waitFor();
    const names = async () =>
        (await resources.getByRole('button').allTextContents()).map((text) => text.match(/Alpha|Beta|Mixed|Zinc/)[0]);
    const hideStored = page.getByRole('button', { name: 'Hide stored-only resources', exact: true });
    const activeFirst = page.getByRole('button', { name: 'Active first: active, pending, stored', exact: true });
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Mixed', 'Zinc']);
    for (const [category, expected] of [
        ['Active', ['Zinc', 'Mixed', 'Alpha', 'Beta']],
        ['Pending', ['Alpha', 'Mixed', 'Beta', 'Zinc']],
        ['Stored', ['Mixed', 'Beta', 'Alpha', 'Zinc']]
    ]) {
        const sort = page.getByRole('button', { name: `Sort by: ${category} quantity`, exact: true });
        await sort.click();
        assert.equal(await sort.getAttribute('aria-pressed'), 'true');
        assert.deepEqual(await names(), expected, `${category} must sort by its own quantity`);
    }
    await activeFirst.click();
    assert.equal(await activeFirst.getAttribute('aria-pressed'), 'true');
    assert.deepEqual(await names(), ['Mixed', 'Zinc', 'Alpha', 'Beta']);
    await page.getByRole('button', { name: 'Sort by: Active quantity', exact: true }).click();
    assert.deepEqual(await names(), ['Zinc', 'Mixed', 'Alpha', 'Beta']);
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    assert.deepEqual(await names(), ['Mixed', 'Zinc', 'Alpha', 'Beta']);
    await hideStored.click();
    assert.equal(await hideStored.getAttribute('aria-pressed'), 'true');
    assert.deepEqual(await names(), ['Mixed', 'Zinc', 'Alpha'], 'Mixed resources must remain visible');
    await activeFirst.click();
    assert.deepEqual(await names(), ['Alpha', 'Mixed', 'Zinc']);
    await hideStored.click();
    assert.equal(await resources.getByRole('button').count(), 4);
    const search = page.getByRole('searchbox', { name: 'Search CPU resources' });
    await search.fill('mIxEd');
    assert.deepEqual(await names(), ['Mixed']);
    await search.fill('unique_pending');
    assert.deepEqual(await names(), ['Alpha']);
    await search.fill('@ae2');
    assert.deepEqual(await names(), ['Beta']);
    await search.fill('@minecraft');
    assert.deepEqual(await names(), ['Alpha', 'Zinc']);
    await search.fill('no matching resource');
    assert.equal(await resources.getByRole('button').count(), 0);
    await search.fill('');
    assert.equal(await resources.getByRole('button').count(), 4);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a')).length, 1);
    await clickTab(page, 'Terminal');
    assert.equal(
        await page.getByRole('button', { name: 'Sort by: Name', exact: true }).isVisible(),
        true,
        'CPU sort controls must not overwrite inventory preferences'
    );
});

test('CPU tiles show processing shares and sort by precise shares through refresh and tracking changes', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const items = [
        {
            ...cpuWork.items[0],
            displayName: 'Alpha',
            registryNamespace: 'example',
            registryPath: 'alpha',
            shareInCraftingTime: 0.12441
        },
        {
            ...cpuWork.items[0],
            displayName: 'Beta',
            registryNamespace: 'example',
            registryPath: 'beta',
            shareInCraftingTime: 0.12449
        },
        {
            ...cpuWork.items[0],
            displayName: 'Stored',
            registryNamespace: 'example',
            registryPath: 'stored',
            active: 0,
            pending: 0,
            shareInCraftingTime: 0.7511
        },
        {
            ...cpuWork.items[0],
            displayName: 'Waiting',
            registryNamespace: 'example',
            registryPath: 'waiting',
            active: 0,
            shareInCraftingTime: 0
        }
    ];
    options.cpuDetails['cpu-a'] = { ...cpuWork, items };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    const alpha = resources.getByRole('button', { name: /^Alpha/ });
    await alpha.waitFor();
    assert.match(await alpha.innerText(), /12\.4%/);
    assert.match(await alpha.getAttribute('aria-label'), /Share of processing time: 12\.4%/);
    assert.match(await resources.getByRole('button', { name: /^Waiting/ }).innerText(), /0%/);
    const names = async () =>
        (await resources.getByRole('button').allTextContents()).map(
            (text) => text.match(/Alpha|Beta|Stored|Waiting/)[0]
        );
    const sortShare = page.getByRole('button', { name: 'Sort by: Share of processing time', exact: true });
    await sortShare.click();
    assert.deepEqual(await names(), ['Stored', 'Beta', 'Alpha', 'Waiting']);
    const activeFirst = page.getByRole('button', { name: 'Active first: active, pending, stored', exact: true });
    await activeFirst.click();
    assert.deepEqual(await names(), ['Beta', 'Alpha', 'Waiting', 'Stored']);
    await activeFirst.click();
    options.cpuDetails['cpu-a'].items = items.map((item, i) => ({
        ...item,
        shareInCraftingTime: [0.9, 0.01, 0.09, 0][i]
    }));
    let refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.deepEqual(await names(), ['Alpha', 'Stored', 'Beta', 'Waiting']);
    assert.match(await alpha.innerText(), /90%/);
    options.cpuDetails['cpu-a'].hasTrackingInfo = false;
    refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.equal(await sortShare.isVisible(), false);
    assert.doesNotMatch(await alpha.innerText(), /%/);
    assert.doesNotMatch(await alpha.getAttribute('aria-label'), /Share/);
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Stored', 'Waiting']);
    assert.equal(
        await page.getByRole('button', { name: 'Sort by: Name', exact: true }).getAttribute('aria-pressed'),
        'true'
    );
});

test('CPU resources omit zero counts and update visible categories during polling', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const item = { ...cpuWork.items[0], active: 0, pending: 12, stored: 0 };
    options.cpuDetails['cpu-a'] = { ...cpuWork, hasTrackingInfo: false, items: [item] };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await resource.waitFor();
    assert.match(await resource.innerText(), /Pending\s*12/);
    assert.doesNotMatch(await resource.innerText(), /Active|Stored/);
    assert.doesNotMatch(await resource.getAttribute('aria-label'), /Active|Stored/);
    await resource.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor({ state: 'visible' });
    assert.match(await tooltip.innerText(), /Pending: 12/);
    assert.doesNotMatch(await tooltip.innerText(), /Active|Stored/);

    options.cpuDetails['cpu-a'].items = [{ ...item, active: 3, pending: 0, stored: 7 }];
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.match(await resource.innerText(), /Active\s*3/);
    assert.match(await resource.innerText(), /Stored[^\n]*\s+7/);
    assert.doesNotMatch(await resource.innerText(), /Pending/);
    assert.doesNotMatch(await resource.getAttribute('aria-label'), /Pending/);
    assert.equal(await tooltip.isVisible(), true);
    assert.match(await tooltip.innerText(), /Active: 3/);
    assert.match(await tooltip.innerText(), /Stored[^\n]*: 7/);
    assert.doesNotMatch(await tooltip.innerText(), /Pending/);
});

test('CPU resource tooltip preserves exact counts and tracking through polling and clears on route or access changes', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const item = { ...cpuWork.items[0], active: 12345, pending: 23456, stored: 34567 };
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [item] };
    options.cpuDetails['cpu-b'] = {
        ...cpuWork,
        hasTrackingInfo: false,
        items: [{ ...item, active: 91, pending: 0, stored: 0 }]
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    const resource = resources.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await resource.hover();
    await tooltip.waitFor({ state: 'visible' });
    for (const metric of [
        /minecraft:iron_ingot/,
        /Active.*12,345/,
        /Pending.*23,456/,
        /Stored.*34,567/,
        /Processing time.*5/,
        /Crafted total.*10/,
        /Produced per second.*2\/s/,
        /Share of elapsed time.*50%/,
        /Share of processing time.*40%/
    ]) {
        assert.match(await tooltip.textContent(), metric);
    }
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...item, active: 45678, craftedTotal: 99 }] };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.equal(await tooltip.isVisible(), true, 'Polling must keep the hovered resource tooltip visible');
    assert.match(await tooltip.textContent(), /Active.*45,678/);
    assert.match(await tooltip.textContent(), /Crafted total.*99/);
    await page.mouse.move(0, 0);
    await resource.focus();
    await tooltip.waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    await clickTab(page, 'CPUs');
    assert.equal(await tooltip.isVisible(), false);
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await resource.focus();
    await tooltip.waitFor({ state: 'visible' });
    assert.match(await tooltip.textContent(), /Active.*91/);
    assert.doesNotMatch(await tooltip.textContent(), /45,678|Crafted total|Processing time|Share of/);
    options.detailError = 'NO_PERMISSIONS';
    await poll(page);
    await resource.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false, 'Lost access must clear private metrics');
});

test('CPU terminal scrolls resources within desktop and mobile viewports while controls remain usable', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        supportsPause: true,
        items: Array.from({ length: 120 }, (_, index) => ({
            ...cpuWork.items[0],
            registryNamespace: 'example',
            registryPath: `resource_${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`
        }))
    };
    for (const viewport of [
        { width: 1280, height: 800 },
        { width: 390, height: 844 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
        await resources.getByRole('button', { name: /Resource 000/ }).waitFor();
        const search = page.getByRole('searchbox', { name: 'Search CPU resources' });
        await search.fill('');
        const before = await search.boundingBox();
        const bounds = await resources.boundingBox();
        const panel = await page.locator('#cpu-panel').boundingBox();
        assert.ok(bounds.height > 100, 'Resources need a usable scroll viewport');
        if (viewport.width > 600) assert.ok(panel.x >= bounds.x + bounds.width - 1, 'CPU list sits beside resources');
        else assert.equal(panel, null, 'Phone CPU list is opened on demand');
        await resources.getByRole('button').last().scrollIntoViewIfNeeded();
        assert.ok(
            await resources.evaluate((node) => node.scrollTop > 0),
            `The resource region itself must scroll at ${viewport.width}px: ${JSON.stringify(await resources.evaluate((node) => ({ top: node.scrollTop, height: node.clientHeight, content: node.scrollHeight })))}`
        );
        assert.deepEqual(await search.boundingBox(), before);
        const documentSize = await page.evaluate(() => ({
            width: document.documentElement.scrollWidth,
            height: document.documentElement.scrollHeight,
            top: window.scrollY,
            left: window.scrollX
        }));
        assert.ok(documentSize.width <= viewport.width, 'The CPU page must not overflow horizontally');
        assert.ok(documentSize.height <= viewport.height, 'The CPU page must not overflow vertically');
        assert.equal(documentSize.top, 0);
        assert.equal(documentSize.left, 0);
        for (const control of [
            search,
            viewport.width <= 600
                ? page.getByRole('button', { name: 'CPUs', exact: true })
                : page.getByRole('link', { name: 'CPUs', exact: true }),
            viewport.width <= 600
                ? page.getByRole('button', { name: 'Filters and sorting', exact: true })
                : page.getByRole('button', { name: 'Hide stored-only resources', exact: true }),
            page.getByRole('button', { name: 'Cancel current work', exact: true })
        ]) {
            await control.scrollIntoViewIfNeeded();
            const box = await control.boundingBox();
            assert.ok(
                box.x >= 0 &&
                    box.y >= 0 &&
                    box.x + box.width <= viewport.width + 1 &&
                    box.y + box.height <= viewport.height + 1,
                `Navigation, filtering and actions must remain reachable at ${viewport.width}px: ${(await control.getAttribute('aria-label')) || (await control.textContent())} ${JSON.stringify(box)}`
            );
        }
        assert.deepEqual(
            await page.evaluate(() => ({ top: window.scrollY, left: window.scrollX })),
            { top: 0, left: 0 },
            'Reaching CPU actions may scroll the panel, but must not move the document'
        );
        const pause = page.getByRole('button', { name: /^(Pause|Resume) current work$/ });
        const previous = await pause.textContent();
        await pause.click();
        await page
            .getByRole('button', {
                name: previous.startsWith('Pause') ? 'Resume current work' : 'Pause current work',
                exact: true
            })
            .waitFor();
        await search.fill('Resource 000');
        assert.equal(await resources.getByRole('button').count(), 1);
        assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), false);
    }
    assert.equal(options.requests.filter((request) => request.path.endsWith('/pause')).length, 2);
});

test('CPU resource reordering does not leave a stale hover tooltip or reopen keyboard-dismissed metrics', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const second = {
        ...cpuWork.items[0],
        registryNamespace: 'example',
        registryPath: 'copper',
        displayName: 'Copper',
        active: 50,
        pending: 0,
        stored: 0
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...cpuWork.items[0], active: 100 }, second] };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page.getByRole('button', { name: 'Sort by: Active quantity', exact: true }).click();
    const item = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await item.hover();
    const refreshedTooltip = page.evaluate(
        () =>
            new Promise((resolve) => {
                const observer = new MutationObserver(() => {
                    const item = [...document.querySelectorAll('button')].find((button) =>
                        /Iron Ingot.*Active: 1 ·/.test(button.getAttribute('aria-label'))
                    );
                    if (!item) return;
                    observer.disconnect();
                    const tooltip = [...document.querySelectorAll('[role="tooltip"]')].find((node) =>
                        node.checkVisibility()
                    );
                    resolve({ visible: !!tooltip, text: tooltip?.textContent || '' });
                });
                observer.observe(document.body, {
                    subtree: true,
                    childList: true,
                    attributes: true,
                    characterData: true
                });
            })
    );
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...cpuWork.items[0], active: 1 }, second] };
    await poll(page);
    const refreshed = await refreshedTooltip;
    assert.ok(
        !refreshed.visible || !refreshed.text.includes('Iron Ingot'),
        'A resource that moves away from a stationary pointer must not keep its old hover tooltip'
    );
    await page.mouse.move(0, 0);
    await item.focus();
    await tooltip.waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...cpuWork.items[0], active: 200 }, second] };
    const read = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await read).request());
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), false, 'Polling and sorting must preserve keyboard dismissal');
});

// Public seam: CPU links and current-work controls plus HTTP. Display names may duplicate;
// navigation and cancellation must continue to address the selected stable CPU key.
test('CPU list and detail pause controls use capability, busy state and stable CPU identity', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '/ae2');
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true },
        unsupported: { ...cpu, isBusy: true },
        idle: { ...cpu, supportsPause: true }
    };
    options.cpuDetails['cpu-b'] = { ...cpuWork, supportsPause: true };
    options.cpuDetails.unsupported = cpuWork;
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = (key) => page.getByRole('listitem').filter({ has: page.getByRole('link', { name: new RegExp(key) }) });
    await row('cpu-b').getByRole('button', { name: /Pause/ }).waitFor();
    assert.equal(
        await row('unsupported')
            .getByRole('button', { name: /Pause|Resume/ })
            .count(),
        0
    );
    assert.equal(await row('idle').getByRole('button').count(), 0);
    await row('cpu-b').getByRole('button', { name: /Pause/ }).click();
    await row('cpu-b')
        .getByRole('button', { name: /Resume/ })
        .waitFor();
    options.cpus = { ...Object.fromEntries(Object.entries(options.cpus).reverse()) };
    await poll(page);
    await row('cpu-b').getByRole('link').click();
    await page.getByRole('button', { name: 'Resume current work', exact: true }).click();
    await page.getByRole('button', { name: 'Pause current work', exact: true }).waitFor();
    const requests = options.requests.filter((request) => request.path.endsWith('/pause'));
    assert.deepEqual(
        requests.map(({ path, method, body }) => ({ path, method, body })),
        [
            { path: `/api/grids/${gridA}/cpus/cpu-b/pause`, method: 'POST', body: { paused: true } },
            { path: `/api/grids/${gridA}/cpus/cpu-b/pause`, method: 'POST', body: { paused: false } }
        ]
    );
    assert.ok(requests.every((request) => request.headers['x-ae2-request'] === 'true'));
    await clickTab(page, 'CPUs');
    await row('unsupported').getByRole('link').click();
    await page.getByRole('button', { name: /^(Pause|Resume) current work$/ }).waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('button', { name: /^(Pause|Resume) current work$/ }).count(), 0);
});

test('CPU cancellation requires confirmation from both list rows and detail', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true }, 'cpu-b': { ...cpu, isBusy: true } };
    page.removeAllListeners('dialog');
    let accept = false;
    const confirmations = [];
    page.on('dialog', async (dialog) => {
        confirmations.push(dialog.message());
        if (accept) await dialog.accept();
        else await dialog.dismiss();
    });
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-b/ }) });
    await row.getByRole('button', { name: /Cancel/ }).click();
    assert.equal(confirmations.length, 1);
    assert.match(confirmations[0], /cpu-b/);
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 0);
    accept = true;
    await row.getByRole('button', { name: /Cancel/ }).click();
    await row.getByRole('button', { name: /Cancel/ }).waitFor({ state: 'hidden' });
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).click();
    accept = false;
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    assert.match(confirmations.at(-1), /cpu-a/);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cancel')).length, 1);
    accept = true;
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).waitFor({ state: 'hidden' });
    assert.deepEqual(
        options.requests.filter((request) => request.path.endsWith('/cancel')).map((request) => request.path),
        [`/api/grids/${gridA}/cpus/cpu-b/cancel`, `/api/grids/${gridA}/cpus/cpu-a/cancel`]
    );
});

// Public seam: a pending or uncertain operation follows its grid/CPU across views. Neither
// a refreshed snapshot nor visiting another network authorizes a duplicate mutation.
test('pending and uncertain CPU pause stays locked across list, detail and grid navigation', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
    let release;
    const pending = new Promise((resolve) => {
        release = resolve;
    });
    let mutations = 0;
    await page.route('**/pause', (route) => {
        mutations++;
        release(route);
    });
    const row = (key) => page.getByRole('listitem').filter({ has: page.getByRole('link', { name: new RegExp(key) }) });
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    await row('cpu-a').getByRole('button', { name: /Pause/ }).click();
    const held = await pending;
    await row('cpu-a').getByRole('link').click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
    await page.evaluate((key) => {
        location.hash = `#/grids/${key}/cpus`;
    }, gridB);
    await row('cpu-a').getByRole('button', { name: /Pause/ }).waitFor();
    assert.equal(await row('cpu-a').getByRole('button', { name: /Pause/ }).isEnabled(), true);
    await held.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'TIMEOUT', data: null })
    });
    await settleResponse(page, held.request());
    assert.equal(
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .count(),
        0
    );
    await page.evaluate((key) => {
        location.hash = `#/grids/${key}/cpus`;
    }, gridA);
    await row('cpu-a')
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    await poll(page);
    assert.equal(await row('cpu-a').getByRole('button', { name: /Pause/ }).isDisabled(), true);
    assert.equal(
        await row('cpu-a')
            .getByRole('button', { name: /Cancel/ })
            .isDisabled(),
        true
    );
    assert.equal(await row('cpu-b').getByRole('button', { name: /Pause/ }).isEnabled(), true);
    await row('cpu-a').getByRole('link').click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isDisabled(), true);
    assert.equal(mutations, 1);
});

test('CPU pause rejection refreshes capability and hides stale controls', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true, supportsPause: true } };
    options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page.getByRole('button', { name: 'Pause current work', exact: true }).waitFor();
    options.pauseStatus = 'CPU_PAUSE_UNSUPPORTED';
    options.cpus['cpu-a'] = { ...options.cpus['cpu-a'], supportsPause: false };
    options.cpuDetails['cpu-a'] = cpuWork;
    await page.getByRole('button', { name: 'Pause current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /does not support pausing/i })
        .waitFor();
    await page.getByRole('button', { name: /Pause|Resume/ }).waitFor({ state: 'detached' });
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isEnabled(), true);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/pause')).length, 1);
});

test('concurrent CPU pause results remain non-actionable until fresh CPU data arrives', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = (key) => page.getByRole('listitem').filter({ has: page.getByRole('link', { name: new RegExp(key) }) });
    const operations = {};
    await page.route('**/pause', (route) => {
        operations[route.request().url().split('/').at(-2)] = route;
    });
    await row('cpu-a').getByRole('button', { name: /Pause/ }).click();
    await row('cpu-b').getByRole('button', { name: /Pause/ }).click();
    let captureFirst;
    let captureSecond;
    const firstRead = new Promise((resolve) => {
        captureFirst = resolve;
    });
    const secondRead = new Promise((resolve) => {
        captureSecond = resolve;
    });
    let reads = 0;
    await page.route('**/cpus', (route) => {
        if (++reads === 1) captureFirst(route);
        else captureSecond(route);
    });
    const successful = {
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: null })
    };
    await operations['cpu-a'].fulfill(successful);
    const stale = await firstRead;
    await operations['cpu-b'].fulfill(successful);
    const fresh = await secondRead;
    await stale.fulfill({ ...successful, body: JSON.stringify({ status: 'OK', data: options.cpus }) });
    await settleResponse(page, stale.request());
    assert.equal(await row('cpu-a').getByRole('button', { name: /Pause/ }).isDisabled(), true);
    assert.equal(await row('cpu-b').getByRole('button', { name: /Pause/ }).isDisabled(), true);
    assert.equal(
        await row('cpu-b')
            .getByRole('button', { name: /Cancel/ })
            .isDisabled(),
        true
    );
    const paused = Object.fromEntries(
        Object.entries(options.cpus).map(([key, value]) => [key, { ...value, isPaused: true }])
    );
    await fresh.fulfill({ ...successful, body: JSON.stringify({ status: 'OK', data: paused }) });
    await row('cpu-a')
        .getByRole('button', { name: /Resume/ })
        .waitFor();
    assert.equal(
        await row('cpu-b')
            .getByRole('button', { name: /Resume/ })
            .isEnabled(),
        true
    );
});

test('CPU list reports a removed action target without substituting another CPU', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-a/ }) });
    await row.getByRole('button', { name: /Pause/ }).waitFor();
    options.pauseStatus = 'CPU_NOT_FOUND';
    delete options.cpus['cpu-a'];
    await row.getByRole('button', { name: /Pause/ }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor();
    assert.equal(await row.count(), 0);
    assert.equal(await page.getByRole('link', { name: /cpu-b/ }).count(), 1);
    assert.deepEqual(
        options.requests.filter((request) => request.method === 'POST').map((request) => request.path),
        [`/api/grids/${gridA}/cpus/cpu-a/pause`]
    );
});

test('a removed CPU mutation target cannot strand another CPU detail load', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    options.cpuDetails['cpu-b'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    let captureMutation;
    const mutation = new Promise((resolve) => {
        captureMutation = resolve;
    });
    await page.route('**/pause', captureMutation);
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-a/ }) });
    await row.getByRole('button', { name: /Pause/ }).click();
    const action = await mutation;
    let captureDetail;
    const detail = new Promise((resolve) => {
        captureDetail = resolve;
    });
    let held = false;
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-b'),
        (route) => {
            if (held) return route.continue();
            held = true;
            captureDetail(route);
        }
    );
    await page.getByRole('link', { name: /cpu-b/ }).click();
    const oldRead = await detail;
    delete options.cpus['cpu-a'];
    await action.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'CPU_NOT_FOUND', data: null })
    });
    await oldRead.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: options.cpuDetails['cpu-b'] })
    });
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isEnabled(), true);
    assert.match(page.url(), /cpus\/cpu-b$/);
});

test('CPU monitoring opens stable current work and explicitly cancels it without unrelated requests', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '/ae2');
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, finalOutput: { ...iron, quantity: 12 } },
        'cpu-b': { ...cpu, isBusy: true, finalOutput: { ...iron, quantity: 12 } }
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'CPUs', exact: true }).click();
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    const resource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await resource.focus();
    await page.getByRole('tooltip').waitFor({ state: 'visible' });
    assert.match(await page.getByRole('tooltip').textContent(), /Active.*4/);
    assert.match(await page.getByRole('tooltip').textContent(), /Crafted total.*10/);
    options.cpus = { 'cpu-b': options.cpus['cpu-b'], 'cpu-a': options.cpus['cpu-a'] };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-b'));
    await poll(page);
    await (await refreshed).finished();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /^Idle$/i })
        .waitFor();
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    const cancel = options.requests.filter((request) => request.path.endsWith('/cancel'));
    assert.equal(cancel.length, 1);
    assert.equal(cancel[0].path, `/api/grids/${gridA}/cpus/cpu-b/cancel`);
    assert.equal(cancel[0].method, 'POST');
    assert.equal(cancel[0].headers['x-ae2-request'], 'true');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a')).length, 0);
});

// Public seam: a missing selected key cannot become another CPU, and access/read failures
// must remove current-work data and the ability to cancel from stale observations.
test('CPU removal and denied reads clear current work without selecting a replacement', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    delete options.cpus['cpu-b'];
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.detailError = 'NO_PERMISSIONS';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await page.getByRole('link', { name: /Assembler/ }).count(), 0);
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    options.detailError = null;
    await poll(page);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.cpuError = 'GRID_NOT_FOUND';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /network.*available/i })
        .waitFor();
    assert.equal(await page.getByRole('link', { name: /Assembler/ }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('known CPU cancellation rejections refresh stale work or clear unavailable private data', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.cancelStatus = 'CPU_NOT_BUSY';
    options.cpuDetails['cpu-a'] = { ...cpuWork, isBusy: false, items: null, finalOutput: null, hasTrackingInfo: false };
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /^Idle$/i })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    for (const status of ['CPU_NOT_FOUND', 'NO_PERMISSIONS', 'GRID_NOT_FOUND']) {
        options.cancelStatus = status;
        options.cpuDetails['cpu-a'] = cpuWork;
        await poll(page);
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor({ state: 'hidden' });
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    }
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cancel')).length, 4);
});

test('late CPU reads cannot leak across selection or grid changes and busy state does not need output metadata', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpuDetails['cpu-b'] = { ...cpuWork, finalOutput: null, items: [], hasTrackingInfo: false };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-a'),
        (route) => release(route)
    );
    await poll(page);
    const delayed = await captured;
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await page.getByText('Current output unavailable', { exact: true }).waitFor();
    await page
        .getByRole('status')
        .filter({ hasText: /^Busy$/i })
        .waitFor();
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: cpuWork })
    });
    await settleResponse(page, delayed.request());
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    assert.equal(await page.getByRole('tooltip').isVisible(), false);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isEnabled(), true);
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

// No expected-job token exists: a retry could cancel newer work on this same CPU. Unknown
// outcomes retain a local lock across reads/navigation and never issue another cancel request.
test('uncertain CPU cancellation never replays after refresh or route reentry', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    for (const status of ['TIMEOUT', 'INTERNAL_ERROR', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        await page.goto(`${base}?case=${status}#/grids/${gridA}/cpus/cpu-a`);
        options.fault = { operation: 'cancel', status };
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        const count = options.requests.filter((request) => request.path.endsWith('/cancel')).length;
        await poll(page);
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor();
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
        assert.equal(options.requests.filter((request) => request.path.endsWith('/cancel')).length, count);
    }
});

// Known native failures retain the computed resource details and safely display opaque text.
test('known submission rejection retains the plan and permits deliberate correction', async (t) => {
    const { page, options, base } = await fixture(t);
    options.submitStatus = 'FAIL';
    options.submitReason = '<img src=x onerror=alert(1)> Native ingredients disappeared';
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.getByText(options.submitReason, { exact: true }).waitFor();
    assert.equal(
        await page
            .getByRole('list', { name: 'Plan resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        1
    );
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, 1);
    options.submitStatus = 'CPU_NOT_FOUND';
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor();
    assert.equal(
        await page
            .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
            .getByRole('radio', { checked: true })
            .count(),
        0
    );
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    await page
        .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
        .getByRole('radio', { name: /cpu-b/ })
        .check();
    options.submitStatus = 'OK';
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, 3);
});

// Public seam: unusable plans and access failures must remove mutation authority, not merely
// add an error next to stale actionable private data.
test('unavailable and incomplete plans cannot submit, and denied refresh clears private plan data', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.planStatus = 'INVALID_ID';
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page
        .getByRole('status')
        .filter({ hasText: /plan.*unavailable/i })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Cancel', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.planStatus = 'OK';
    options.plan = { ...readyPlan, isSimulating: true };
    await page.reload();
    await page
        .getByRole('status')
        .filter({ hasText: /missing resources/i })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], missing: 8 }] };
    await page.reload();
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Missing: 8/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.plan = readyPlan;
    await page.reload();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    options.cpuError = 'NO_PERMISSIONS';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(
        await page
            .getByRole('list', { name: 'Plan resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.cpuError = null;
    await poll(page);
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor({ state: 'hidden' });
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

// The API has no idempotency receipt. Transport failures must never trigger mutation replay,
// including automatic polling and ordinary navigation back to the same runtime plan.
test('uncertain submission outcomes cannot be retried by polling or route reentry', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    let planId = 7;
    for (const status of ['error', 500, 'OK', 'TIMEOUT', 'INTERNAL_ERROR', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId++}`);
        options.fault = { operation: 'submit', status };
        await page.getByRole('button', { name: 'Start', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        const count = options.requests.filter((request) => request.path.endsWith('/submit')).length;
        await poll(page);
        await clickTab(page, 'Terminal');
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
        assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, count);
    }
});

test('uncertain creation and deletion remain explicit and are never replayed', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.fault = { operation: 'create', status: 'TIMEOUT' };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Calculate plan', exact: true }).isDisabled(), true);
    await poll(page);
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await page.goBack();
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: 'Calculate plan', exact: true }).isDisabled(), true);
    options.fault = { operation: 'delete', status: 'TIMEOUT' };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    await poll(page);
    assert.equal(await page.getByRole('button', { name: 'Cancel', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    assert.deepEqual(
        options.requests.filter((request) => request.method !== 'GET').map((request) => request.method),
        ['POST', 'DELETE']
    );
});

test('plan resources share custom formatted tooltips with exact values and dismiss without polling reopen', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.capabilities.craftingPlanSteps = true;
    options.plan = {
        ...readyPlan,
        plan: [
            {
                ...readyPlan.plan[0],
                displayName: '§aIron §lIngot',
                stored: 123456,
                requested: 234567,
                missing: 34567,
                damage: 4,
                componentCount: 2,
                steps: 17,
                usedPercent: 0.25
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resource = page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await resource.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.equal(await resource.getAttribute('title'), null);
    assert.match(await tooltip.innerText(), /minecraft:iron_ingot\s+Damage: 4\s+Components: 2/);
    for (const value of ['123,456', '234,567', '34,567', '17', '25%'])
        assert.ok((await tooltip.innerText()).includes(value));
    assert.equal((await textStyle(tooltip, 'Iron')).color, 'rgb(85, 255, 85)');
    assert.equal(
        await tooltip.getByText('Components: 2').evaluate((node) => getComputedStyle(node).color),
        'rgb(170, 170, 170)'
    );
    await page.keyboard.press('Escape');
    assert.equal(await tooltip.isVisible(), false);
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.mouse.move(0, 0);
    await resource.focus();
    await tooltip.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.equal(await resource.getAttribute('aria-describedby'), await tooltip.getAttribute('id'));
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('quartz');
    assert.equal(await resource.count(), 0);
    assert.equal(await tooltip.isVisible(), false);
});

test('plan tools search and CPU identifiers use current custom tooltips and clear removed or dismissed targets', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus = { 'cpu-a': cpu, 'cpu-b': { ...cpu, acceptsPlayerJobs: false } };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const sort = page.getByRole('button', { name: 'Sort by: Name', exact: true });
    await sort.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText(/Sort by.*Name/).waitFor();
    assert.equal(await sort.getAttribute('title'), null);
    await sort.click();
    await tooltip.getByText(/Sort by.*Quantity/).waitFor();
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    const order = page.getByRole('button', { name: 'Sort order: Ascending', exact: true });
    await page.mouse.move(0, 0);
    await order.focus();
    await tooltip.getByText(/Sort order.*Ascending/).waitFor();
    await page.keyboard.press('Enter');
    await tooltip.getByText(/Sort order.*Descending/).waitFor();
    const search = page.getByRole('searchbox', { name: 'Search plan resources' });
    await search.focus();
    await tooltip.getByText(/@/).first().waitFor();
    await search.fill('iron');
    assert.equal(await tooltip.isVisible(), false);
    const disabled = page.getByRole('radio', { name: /cpu-b/ });
    await page.mouse.move(0, 0);
    await disabled.locator('..').hover();
    await tooltip.getByText('cpu-b', { exact: true }).waitFor();
    assert.equal(await disabled.isDisabled(), true);
    assert.equal(await disabled.locator('..').getAttribute('title'), null);
    const enabled = page.getByRole('radio', { name: /cpu-a/ });
    await enabled.focus();
    await tooltip.getByText('cpu-a', { exact: true }).waitFor();
    await page.mouse.move(0, 0);
    await disabled.locator('..').hover();
    await tooltip.getByText('cpu-b', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.mouse.move(0, 0);
    await disabled.locator('..').hover();
    await tooltip.getByText('cpu-b', { exact: true }).waitFor();
    delete options.cpus['cpu-b'];
    await poll(page);
    await disabled.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false);
});

test('plan CPU tooltip shows live capacity work and state after polling', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.cpus = {
        'cpu-a': {
            ...cpu,
            name: '§aAssembly',
            isBusy: true,
            isPaused: true,
            availableStorage: 65536,
            usedStorage: 12345,
            coProcessors: 8,
            finalOutput: { ...iron, displayName: '§bIron', quantity: 640 }
        }
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByText('Plan ready', { exact: true }).waitFor();
    const choice = page.getByRole('radio', { name: /cpu-a/ });
    await choice.scrollIntoViewIfNeeded();
    await settleFrames(page);
    await choice.locator('..').hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Assembly', { exact: true }).waitFor();
    assert.equal((await textStyle(tooltip, 'Assembly')).color, 'rgb(85, 255, 85)');
    assert.match(await tooltip.innerText(), /Paused/);
    assert.match(await tooltip.innerText(), /64 KB/);
    assert.match(await tooltip.innerText(), /12\.06 KB/);
    assert.match(await tooltip.innerText(), /8 coprocessors/);
    assert.match(await tooltip.innerText(), /Iron × 640/);
    assert.equal((await textStyle(tooltip, 'Iron')).color, 'rgb(85, 255, 255)');
    assert.ok((await tooltip.innerText()).endsWith('cpu-a'));
    options.cpus['cpu-a'] = { ...cpu, name: 'Updated CPU', usedStorage: -1, coProcessors: 4 };
    await poll(page);
    await page.getByRole('radio', { name: /Updated CPU/ }).waitFor();
    await page.mouse.move(0, 0);
    await choice.scrollIntoViewIfNeeded();
    await settleFrames(page);
    await choice.locator('..').hover();
    await tooltip.getByText('Updated CPU', { exact: true }).waitFor();
    assert.match(await tooltip.innerText(), /Idle/);
    assert.match(await tooltip.innerText(), /8 KB/);
    assert.match(await tooltip.innerText(), /4 coprocessors/);
    assert.match(await tooltip.innerText(), /Used storage unavailable/);
    assert.doesNotMatch(await tooltip.innerText(), /Iron|Paused|12\.06 KB/);
});

test('plan tooltips clear on scroll resize navigation and access denial', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.plan = {
        ...readyPlan,
        plan: Array.from({ length: 80 }, (_, index) => ({ ...readyPlan.plan[0], displayName: `Ingredient ${index}` }))
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const region = page.getByRole('region', { name: 'Plan resources', exact: true });
    const resource = region.getByRole('button', { name: /^Ingredient 0 ·/ });
    const tooltip = page.getByRole('tooltip');
    await resource.hover();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    await region.evaluate((node) => {
        node.scrollTop = 100;
    });
    await page.waitForFunction(() => document.querySelector('#resource-tooltip').hidden);
    await region.evaluate((node) => {
        node.scrollTop = 0;
    });
    await page.mouse.move(0, 0);
    await resource.focus();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    await page.setViewportSize({ width: 1100, height: 800 });
    await page.waitForFunction(() => document.querySelector('#resource-tooltip').hidden);
    await resource.evaluate((node) => node.blur());
    await resource.focus();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    await clickTab(page, 'Terminal');
    await page.getByRole('searchbox', { name: 'Search resources', exact: true }).waitFor();
    assert.equal(await tooltip.isVisible(), false);
    await page.goBack();
    await resource.hover();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    options.cpuError = 'NO_PERMISSIONS';
    await poll(page);
    await resource.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false);
});

test('crafting popup and plan share optional atlas icons and honor native CPU admission', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], itemKey: 'iron', icon: options.itemsA[0].icon }] };
    options.cpus = { 'cpu-a': { ...cpu, acceptsPlayerJobs: false }, 'cpu-b': cpu };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    await page.getByRole('dialog').locator('.resource-icon-ready').waitFor();
    await page.getByRole('dialog').getByRole('textbox', { name: 'Craft quantity' }).fill('64');
    options.itemsA = [{ ...iron, icon: { page: 0, x: 64, y: 0 } }];
    await poll(page);
    await page.waitForFunction(() => {
        const icon = document.querySelector('dialog .resource-icon');
        return parseFloat(getComputedStyle(icon).backgroundPositionX) === -icon.getBoundingClientRect().width;
    });
    assert.equal(await page.getByRole('dialog').getByRole('textbox', { name: 'Craft quantity' }).inputValue(), '64');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const resources = page.getByRole('list', { name: 'Plan resources', exact: true });
    await resources.locator('.resource-icon-ready').waitFor();
    const cpus = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
    assert.equal(await cpus.getByRole('radio', { name: /cpu-a/ }).isDisabled(), true);
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).isChecked(), true);
    assert.ok(
        options.requests.some(
            (request) => request.path.endsWith('/crafting-plans/7') && request.query === '?icons=true'
        )
    );
});

test('plan CPU choices display live product icons only for busy processors using their own atlas metadata', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.cpuIcons = options.icons;
    options.icons = { ...options.icons, pages: [] };
    const output = { ...iron, quantity: 12, icon: { page: 0, x: 0, y: 0 } };
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, finalOutput: output },
        'cpu-b': { ...cpu, isBusy: true, isPaused: true, finalOutput: output },
        'cpu-idle': { ...cpu, finalOutput: output },
        'cpu-unknown': { ...cpu, isBusy: true, finalOutput: null }
    };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const choices = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
    const choice = (key) =>
        choices.locator('label').filter({ has: page.getByRole('radio', { name: new RegExp(key) }) });
    await choice('cpu-a').locator('.resource-icon-ready').waitFor();
    await choice('cpu-b').locator('.resource-icon-ready').waitFor();
    assert.equal(await choice('cpu-idle').locator('.resource-icon').isVisible(), false);
    assert.equal(await choice('cpu-unknown').locator('.resource-icon').isVisible(), false);
    const initialHeight = (await choice('cpu-a').boundingBox()).height;
    options.cpus['cpu-a'].finalOutput = { ...output, icon: { page: 0, x: 64, y: 0 } };
    options.cpus['cpu-b'].isBusy = false;
    await poll(page);
    await page.waitForFunction(() => {
        const icon = document.querySelector('input[value="cpu-a"]').parentElement.querySelector('.resource-icon');
        return parseFloat(getComputedStyle(icon).backgroundPositionX) === -icon.getBoundingClientRect().width;
    });
    assert.equal(await choice('cpu-b').locator('.resource-icon').isVisible(), false);
    assert.equal((await choice('cpu-a').boundingBox()).height, initialHeight);
    assert.equal(
        options.requests.some((request) => /\/cpus\/[^/]+$/.test(request.path)),
        false
    );
    await page.setViewportSize({ width: 390, height: 844 });
    await openMobilePane(page, 'CPUs');
    assert.ok(await choice('cpu-a').evaluate((node) => node.scrollWidth <= node.clientWidth));
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await openMobilePane(page, 'CPUs');
    await choices.getByRole('radio').first().waitFor();
    assert.equal(await choice('cpu-a').locator('.resource-icon').isVisible(), false);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).at(-1).query, '');
});

test('plan icon pack replacement supersedes a delayed completed-plan mapping', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], itemKey: 'iron', icon: { page: 0, x: 0, y: 0 } }] };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    let first = true;
    await page.route('**/crafting-plans/7?icons=true', async (route) => {
        if (!first) return route.continue();
        first = false;
        const response = await route.fetch();
        release(() => route.fulfill({ response }));
    });
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const finish = await captured;
    options.icons = { ...options.icons, packId: 'c'.repeat(64) };
    options.pack = { ...options.pack, packId: options.icons.packId };
    const discovery = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/icon-pack'));
    await poll(page);
    await (await discovery).finished();
    await finish();
    await page.getByRole('list', { name: 'Plan resources', exact: true }).locator('.resource-icon-ready').waitFor();
    assert.ok(options.requests.some((request) => request.path.includes(`/icon-packs/${'c'.repeat(64)}/`)));
});

for (const [width, height] of [
    [1280, 900],
    [390, 844],
    [390, 720]
]) {
    test(`plan grid matches CPU slots and forty CPU choices scroll independently at ${width}x${height}`, async (t) => {
        const { page, options, base } = await refreshingFixture(t, '', { viewport: { width, height } });
        options.cpuDetails['cpu-a'] = { ...cpuWork, hasTrackingInfo: false };
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const cpuResource = page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button')
            .first();
        await cpuResource.waitFor();
        const cpuSlot = await cpuResource.boundingBox();
        options.cpus = Object.fromEntries(
            Array.from({ length: 40 }, (_, index) => [
                `choice-${index}`,
                { ...cpu, name: `Processor ${index}`, acceptsPlayerJobs: index !== 38 }
            ])
        );
        options.itemsA = [{ ...iron, displayName: 'Iron Ingot assembly component '.repeat(8) }];
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).click();
        await page.getByRole('button', { name: 'Craft', exact: true }).click();
        await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
        const resource = page.getByRole('region', { name: 'Plan resources', exact: true }).getByRole('button').first();
        await resource.waitFor();
        const planSlot = await resource.boundingBox();
        await openMobilePane(page, 'CPUs');
        await page
            .locator('aside p')
            .filter({ hasText: /Iron Ingot assembly component/ })
            .focus();
        await page.getByRole('tooltip').waitFor();
        assert.ok((await page.getByRole('tooltip').innerText()).includes(options.itemsA[0].displayName));
        assert.ok(Math.abs(planSlot.width - cpuSlot.width) < 2, 'plan and three-row CPU slots share width');
        assert.ok(Math.abs(planSlot.height - cpuSlot.height) < 2, 'plan and three-row CPU slots share height');
        const choices = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
        const bounds = await choices.evaluate((node) => ({ height: node.clientHeight, content: node.scrollHeight }));
        assert.ok(bounds.content > bounds.height, 'CPU choices overflow their own bounded viewport');
        const last = choices.getByRole('radio', { name: /choice-39/ });
        const firstRow = await choices.getByRole('radio').first().boundingBox();
        assert.ok(
            bounds.height >= firstRow.height,
            `at least one complete choice fits (${bounds.height}/${firstRow.height})`
        );
        const heading = page.getByRole('heading', { name: 'Crafting CPU', exact: true });
        const before = await heading.boundingBox();
        await last.scrollIntoViewIfNeeded();
        await last.check();
        assert.equal(await last.isChecked(), true);
        assert.equal(await choices.getByRole('radio', { name: /choice-38/ }).isDisabled(), true);
        const after = await heading.boundingBox();
        assert.ok(Math.abs(after.y - before.y) < 1, 'list scrolling leaves the CPU heading stationary');
        assert.ok(await choices.evaluate((node) => node.scrollTop > 0));
        const visible = await choices.boundingBox();
        const selected = await last.boundingBox();
        assert.ok(selected.y >= visible.y && selected.y + selected.height <= visible.y + visible.height + 1);
        assert.ok(await page.getByRole('button', { name: 'Start', exact: true }).isEnabled());
        await last.focus();
        await page.keyboard.press('ArrowUp');
        assert.equal(await choices.getByRole('radio', { name: /choice-37/ }).isChecked(), true);
        const readingPosition = await choices.evaluate((node) => node.scrollTop);
        await poll(page);
        assert.equal(await choices.getByRole('radio', { name: /choice-37/ }).isChecked(), true);
        assert.equal(await choices.evaluate((node) => node.scrollTop), readingPosition);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        assert.equal(
            options.requests.filter((request) => request.method !== 'GET').length,
            1,
            'only explicit plan calculation mutates'
        );
    });
}

for (const width of [1280, 390]) {
    test(`plan frame, search, CPU attachment and footer remain usable at ${width}px`, async (t) => {
        const { page, options, base } = await fixture(
            t,
            '',
            { viewport: { width, height: 900 } },
            { mockClock: false }
        );
        options.plan = {
            ...readyPlan,
            plan: Array.from({ length: 90 }, (_, index) => ({
                ...readyPlan.plan[0],
                displayName: `Resource ${index}`,
                missing: index === 3 ? 8 : 0
            }))
        };
        await page.goto(`${base}#/grids/${gridA}/plans/7`);
        const region = page.getByRole('region', { name: 'Plan resources', exact: true });
        const resource = region.getByRole('button').first();
        await resource.waitFor();
        const card = await resource.boundingBox();
        assert.ok(card.width > card.height, 'plan resource cards remain rectangular');
        const search = await page.getByRole('searchbox', { name: 'Search plan resources' }).boundingBox();
        const heading = await page.getByRole('heading', { name: 'Crafting plan', exact: true }).boundingBox();
        const footer = await page.getByRole('button', { name: 'Cancel', exact: true }).boundingBox();
        const viewport = await region.boundingBox();
        assert.ok(Math.abs(heading.x - card.x) < 2, 'heading aligns to the resource grid');
        assert.ok(search.y + search.height <= card.y, 'search stays above resources');
        assert.ok(footer.y >= viewport.y + viewport.height, 'actions stay below the scroll viewport');
        assert.ok(footer.y + footer.height <= 900, 'actions stay within the viewport');
        await openMobilePane(page, 'CPUs');
        assert.ok(await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).isVisible());
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    });
}

test('crafting plan persists independent name total quantity and steps sorting within fixed groups', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingPlanSteps = true;
    options.plan = {
        ...readyPlan,
        plan: [
            ['Alpha', 60, 0, 0, 4],
            ['Zeta', 2, 10, 50, 1],
            ['Yotta', 30, 5, 0, 2],
            ['Gamma', 50, 0, 0, 8],
            ['Beta', 10, 40, 2, 7],
            ['Delta', 4, 20, 0, 9]
        ].map(([displayName, stored, requested, missing, steps]) => ({
            ...readyPlan.plan[0],
            displayName,
            stored,
            requested,
            missing,
            steps
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
    await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resources = page.getByRole('list', { name: 'Plan resources', exact: true });
    const names = async () =>
        await resources
            .getByRole('button')
            .evaluateAll((buttons) => buttons.map((button) => button.getAttribute('aria-label').split(' · ')[0]));
    const cases = [
        [
            'Name',
            ['Beta', 'Zeta', 'Delta', 'Yotta', 'Alpha', 'Gamma'],
            ['Zeta', 'Beta', 'Yotta', 'Delta', 'Gamma', 'Alpha']
        ],
        [
            'Quantity',
            ['Beta', 'Zeta', 'Delta', 'Yotta', 'Gamma', 'Alpha'],
            ['Zeta', 'Beta', 'Yotta', 'Delta', 'Alpha', 'Gamma']
        ],
        [
            'Crafting steps',
            ['Zeta', 'Beta', 'Yotta', 'Delta', 'Alpha', 'Gamma'],
            ['Beta', 'Zeta', 'Delta', 'Yotta', 'Gamma', 'Alpha']
        ]
    ];
    for (const [criterion, ascending, descending] of cases) {
        await resources.getByRole('button', { name: /^Alpha ·/ }).waitFor();
        assert.deepEqual(await names(), ascending, `${criterion} ascending within fixed groups`);
        await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
        assert.deepEqual(await names(), descending, `${criterion} descending within fixed groups`);
        await page.reload();
        await page.getByRole('button', { name: `Sort by: ${criterion}`, exact: true }).waitFor();
        await resources.getByRole('button', { name: /^Alpha ·/ }).waitFor();
        assert.deepEqual(await names(), descending, `${criterion} and direction survive reload`);
        await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).click();
        await page.getByRole('button', { name: `Sort by: ${criterion}`, exact: true }).click();
    }
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('ta');
    assert.deepEqual(await names(), ['Beta', 'Zeta', 'Delta', 'Yotta']);
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: 'Sort by: Registry ID', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).isVisible());
});

test('direct pending plan and reload restore the original order and options without replaying autostart', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.capabilities = { craftingLightMode: true, craftingIgnoreMissing: true };
    options.plan = {
        ...readyPlan,
        finalOutput: { ...quartz, itemKey: 'requested-quartz', quantity: 37 },
        options: { lightMode: true, ignoreMissing: true },
        autoStart: true,
        isSimulating: true
    };
    options.pendingReads = 1;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const output = page.getByText(/Certus Quartz Crystal × 37/);
    await output.waitFor();
    const more = page.getByRole('button', { name: 'Recalculate options', exact: true });
    assert.ok(await more.isDisabled());
    await poll(page);
    await more.click();
    const menu = page.getByRole('menu', { name: 'Recalculate options', exact: true });
    assert.equal(
        await menu
            .getByRole('menuitem', { name: 'Recalculate ignoring missing + autostart', exact: true })
            .evaluate((node) => node === node.parentElement.firstElementChild),
        true
    );
    await page.reload();
    await output.waitFor();
    await more.click();
    assert.equal(
        await menu
            .getByRole('menuitem', { name: 'Recalculate ignoring missing + autostart', exact: true })
            .evaluate((node) => node === node.parentElement.firstElementChild),
        true
    );
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, 0);
    options.nextPlanId = 8;
    await menu.getByRole('menuitem', { name: 'Recalculate ignoring missing', exact: true }).click();
    await page.waitForURL(/\/plans\/8$/);
    assert.deepEqual(
        options.requests.filter((entry) => entry.method !== 'GET').map((entry) => entry.method),
        ['DELETE', 'POST']
    );
    assert.deepEqual(options.requests.find((entry) => entry.path.endsWith('/crafting-plans')).body, {
        itemKey: 'requested-quartz',
        quantity: 37,
        lightMode: true,
        ignoreMissing: true
    });
});

test('delayed plan metadata cannot restore another grid order or automatic intent', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    let complete;
    const requested = new Promise((resolve) => {
        complete = resolve;
    });
    await page.route(`**/api/grids/${gridA}/crafting-plans/7`, (route) => complete(route));
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const pending = await requested;
    options.plan = {
        ...readyPlan,
        finalOutput: { ...quartz, quantity: 19 },
        autoStart: true
    };
    await page.goto(`${base}#/grids/${gridB}/plans/7`);
    await page.getByText(/Certus Quartz Crystal × 19/).waitFor();
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: readyPlan })
    });
    await poll(page);
    assert.ok(await page.getByText(/Certus Quartz Crystal × 19/).isVisible());
    assert.equal(await page.getByText(/Iron Ingot × 12/).count(), 0);
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, 0);
});
