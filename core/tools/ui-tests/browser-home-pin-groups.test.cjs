const test = require('node:test');
const assert = require('node:assert/strict');
const { fixture, gridA, gridB, iron, quartz, serverState } = require('./fixtures.cjs');

const config = {
    showStock: false,
    showFlow: false,
    showRecentFlow: false,
    flowRangeMillis: 86400000,
    showChart: false,
    stock: true,
    input: true,
    output: true,
    net: false,
    rangeMillis: 86400000
};
function pin(pinKey, gridKey, resource, order) {
    return {
        pinKey,
        available: true,
        gridKey,
        itemKey: resource.itemKey,
        gridName: gridKey,
        resource,
        terminalOrder: order,
        homeOrder: order,
        homeGroupKey: null,
        homeConfig: { ...config }
    };
}

test('Home groups persist cross-network placement, rename and deletion without unpinning cards', async (t) => {
    const { page, base, options } = await fixture(t);
    options.pins = [pin('iron-pin', gridA, iron, 0), pin('quartz-pin', gridB, quartz, 1)];
    await page.goto(base);
    await page.locator('.home-pin-card').first().waitFor();
    const add = page.locator('.home-pin-group-add');
    await add.getByRole('textbox').fill('Production');
    await add.getByRole('button', { name: 'Add group', exact: true }).click();
    const group = page.getByRole('region', { name: 'Production', exact: true });
    await group.waitFor();
    const ironCard = page.locator('.home-pin-card').filter({ hasText: 'Iron Ingot' });
    await ironCard.locator('summary').click();
    const groupChoice = ironCard.getByRole('combobox', { name: 'Group', exact: true });
    await groupChoice.selectOption({ label: 'Production' });
    await group.locator('.home-pin-card').waitFor();
    const originalCard = await group.locator('.home-pin-card').elementHandle();
    const quartzCard = page.locator('.home-pin-card').filter({ hasText: 'Certus Quartz Crystal' });
    await quartzCard.locator('summary').click();
    await quartzCard.getByRole('combobox', { name: 'Group', exact: true }).selectOption({ label: 'Production' });
    await serverState(() => options.pins.filter((value) => value.homeGroupKey).length, 2);
    await group.locator('.home-pin-name').filter({ hasText: 'Certus Quartz Crystal' }).waitFor();
    assert.deepEqual(await group.locator('.home-pin-name').allTextContents(), ['Iron Ingot', 'Certus Quartz Crystal']);
    assert.equal(await originalCard.evaluate((node) => node.isConnected), true, 'existing card survives regrouping');
    await group.getByRole('button', { name: 'Rename group', exact: true }).click();
    await group.locator('.home-pin-group-rename input').fill('Machines');
    await group.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('region', { name: 'Machines', exact: true }).waitFor();
    await page.reload();
    const restored = page.getByRole('region', { name: 'Machines', exact: true });
    await restored.locator('.home-pin-card').first().waitFor();
    assert.equal(await restored.locator('.home-pin-card').count(), 2);
    await restored.getByRole('button', { name: 'Delete group', exact: true }).click();
    await serverState(() => options.groups.length, 0);
    await page.waitForFunction(
        () =>
            document.querySelectorAll('.home-pin-card').length === 2 &&
            !document.querySelector('.home-pin-group[data-group-key]')
    );
    assert.deepEqual(
        options.pins.map((value) => [value.terminalOrder, value.homeOrder, value.homeGroupKey]),
        [
            [0, 0, null],
            [1, 1, null]
        ]
    );
    assert.deepEqual(
        options.pins.map((value) => value.homeConfig),
        [config, config]
    );
});

test('Home without named groups presents plain cards even after deleting the last group with Ungrouped collapsed', async (t) => {
    const { page, base, options } = await fixture(t);
    options.pins = [pin('iron-pin', gridA, iron, 0)];
    await page.goto(base);
    const card = page.locator('.home-pin-card');
    const plainCards = async () => {
        await card.waitFor();
        assert.equal(await page.getByRole('region', { name: 'Ungrouped', exact: true }).count(), 0);
        assert.equal(await page.getByRole('button', { name: 'Ungrouped', exact: true }).count(), 0);
        assert.equal(
            await card
                .locator('..')
                .locator('..')
                .evaluate((node) => getComputedStyle(node).borderTopWidth),
            '0px'
        );
    };
    await plainCards();
    const add = page.locator('.home-pin-group-add');
    await add.getByRole('textbox').fill('Production');
    await add.getByRole('button', { name: 'Add group', exact: true }).click();
    const ungrouped = page.getByRole('region', { name: 'Ungrouped', exact: true });
    await ungrouped.getByRole('button', { name: 'Ungrouped', exact: true }).click();
    assert.equal(await card.isVisible(), false);
    await page
        .getByRole('region', { name: 'Production', exact: true })
        .getByRole('button', { name: 'Delete group', exact: true })
        .click();
    await serverState(() => options.groups.length, 0);
    await plainCards();
    await page.reload();
    await plainCards();
});

test('Home group and card ordering are independent and unavailable cards remain manageable on phones', async (t) => {
    const { page, base, options } = await fixture(t, '', { viewport: { width: 390, height: 780 } });
    options.groups = [
        { groupKey: 'machines', name: 'Machines', ordinal: 0 },
        { groupKey: 'ores', name: 'Ores', ordinal: 1 }
    ];
    options.pins = [
        { ...pin('iron-pin', gridA, iron, 0), homeGroupKey: 'machines' },
        { ...pin('quartz-pin', gridB, quartz, 1), homeGroupKey: 'machines' },
        {
            ...pin('unavailable-pin', null, iron, 0),
            available: false,
            itemKey: null,
            resource: null,
            homeGroupKey: 'ores'
        }
    ];
    await page.goto(base);
    const machines = page.getByRole('region', { name: 'Machines', exact: true });
    const ores = page.getByRole('region', { name: 'Ores', exact: true });
    await machines.locator('.home-pin-card').first().waitFor();
    await ores.locator('.home-pin-group-actions').getByRole('button', { name: 'Move earlier', exact: true }).click();
    await serverState(() => options.groups.find((group) => group.groupKey === 'ores').ordinal, 0);
    await page
        .locator('.home-pin-group')
        .first()
        .filter({ has: page.getByRole('button', { name: 'Ores', exact: true }) })
        .waitFor();
    assert.deepEqual(
        await page
            .locator('.home-pin-group')
            .evaluateAll((groups) => groups.map((group) => group.getAttribute('aria-label'))),
        ['Ores', 'Machines', 'Ungrouped']
    );
    const quartzCard = machines.locator('.home-pin-card').filter({ hasText: 'Certus Quartz Crystal' });
    await quartzCard.getByRole('button', { name: 'Move earlier', exact: true }).click();
    await serverState(() => options.pins.find((value) => value.pinKey === 'quartz-pin').homeOrder, 0);
    await machines.locator('.home-pin-name').first().filter({ hasText: 'Certus Quartz Crystal' }).waitFor();
    assert.deepEqual(await machines.locator('.home-pin-name').allTextContents(), [
        'Certus Quartz Crystal',
        'Iron Ingot'
    ]);
    const unavailable = ores.locator('.home-pin-card');
    await unavailable.locator('summary').click();
    await unavailable.getByRole('combobox', { name: 'Group', exact: true }).selectOption({ label: 'Machines' });
    await serverState(() => options.pins.find((value) => value.pinKey === 'unavailable-pin').homeGroupKey, 'machines');
    const moved = machines.locator('.home-pin-card').filter({ hasText: 'Unavailable resource' });
    await moved.waitFor();
    assert.equal(await machines.locator('.home-pin-card').count(), 3);
    assert.equal(await ores.locator('.home-pin-card').count(), 0, 'empty named group remains visible');
    assert.equal(await ores.isVisible(), true);
    assert.equal(
        await moved.locator('.home-pin-settings-group:visible').count(),
        0,
        'resource configuration stays hidden'
    );
    await moved.getByRole('combobox', { name: 'Group', exact: true }).selectOption({ label: 'Ungrouped' });
    await serverState(() => options.pins.find((value) => value.pinKey === 'unavailable-pin').homeGroupKey, null);
    await page.getByRole('region', { name: 'Ungrouped', exact: true }).locator('.home-pin-card').waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
    assert.deepEqual(
        options.pins.slice(0, 2).map((value) => value.terminalOrder),
        [0, 1]
    );
});

test('moving a Home card into a group preserves its chart and cached history', async (t) => {
    const { page, base, options } = await fixture(t);
    options.groups = [{ groupKey: 'production', name: 'Production', ordinal: 0 }];
    options.pins = [{ ...pin('iron-pin', gridA, iron, 0), homeConfig: { ...config, showChart: true } }];
    options.resourceHistory = {
        baseline: null,
        complete: true,
        truncated: false,
        points: [
            {
                kind: 'STOCK',
                observedAt: Date.now() - 10000,
                flowFrom: null,
                generation: 1,
                resource: { ...iron, quantity: '100' }
            }
        ]
    };
    await page.goto(base);
    const card = page.locator('.home-pin-card');
    await card.locator('canvas').waitFor();
    const canvas = await card.locator('canvas').elementHandle();
    const reads = () => options.requests.filter((request) => request.path.endsWith('/resource-history')).length;
    const before = reads();
    await card.locator('summary').click();
    await card.getByRole('combobox', { name: 'Group', exact: true }).selectOption({ label: 'Production' });
    await page.getByRole('region', { name: 'Production', exact: true }).locator('.home-pin-card').waitFor();
    assert.equal(await canvas.evaluate((node) => node.isConnected), true);
    assert.equal(reads(), before, 'grouping reuses recorded history');
});

test('a rejected group edit retains the draft and existing pin placement', async (t) => {
    const { page, base, options } = await fixture(t);
    options.groups = [{ groupKey: 'ores', name: 'Ores', ordinal: 0 }];
    options.pins = [{ ...pin('iron-pin', gridA, iron, 0), homeGroupKey: 'ores' }];
    options.groupMutationStatus = 'BAD_PARAM';
    await page.goto(base);
    await page.locator('.home-pin-card').waitFor();
    const add = page.locator('.home-pin-group-add');
    await add.getByRole('textbox').fill('Ores');
    const rejected = page.waitForResponse(
        (response) => new URL(response.url()).pathname.endsWith('/resource-pin-groups') && response.status() === 400
    );
    await add.getByRole('button', { name: 'Add group', exact: true }).click();
    await rejected;
    await page.waitForFunction(() => document.querySelector('.home-pin-group-add button')?.disabled === false);
    assert.equal(await add.getByRole('textbox').inputValue(), 'Ores');
    assert.equal(await page.getByRole('region', { name: 'Ores', exact: true }).locator('.home-pin-card').count(), 1);
    assert.equal(options.pins[0].homeGroupKey, 'ores');
});

test('Home group collapse survives reload and keyboard toggling preserves cards, charts and history', async (t) => {
    const { page, base, options } = await fixture(t);
    options.groups = [{ groupKey: 'production', name: 'Production', ordinal: 0 }];
    options.pins = [
        { ...pin('iron-pin', gridA, iron, 0), homeGroupKey: 'production', homeConfig: { ...config, showChart: true } }
    ];
    options.resourceHistory = {
        baseline: null,
        complete: true,
        truncated: false,
        points: [
            {
                kind: 'STOCK',
                observedAt: Date.now() - 10000,
                flowFrom: null,
                generation: 1,
                resource: { ...iron, quantity: '100' }
            }
        ]
    };
    await page.goto(base);
    const group = page.getByRole('region', { name: 'Production', exact: true });
    const card = group.locator('.home-pin-card');
    await card.locator('canvas').waitFor();
    const canvas = await card.locator('canvas').elementHandle();
    const reads = () => options.requests.filter((request) => request.path.endsWith('/resource-history')).length;
    const before = reads();
    const toggle = group.getByRole('button', { name: 'Production', exact: true });
    assert.equal(await toggle.count(), 1, 'a named group provides an accessible collapse button');
    await toggle.click();
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
    assert.equal(await card.isVisible(), false);
    assert.equal(await canvas.evaluate((node) => node.isConnected), true);
    await toggle.press('Space');
    assert.equal(await toggle.getAttribute('aria-expanded'), 'true');
    assert.equal(await card.isVisible(), true);
    assert.equal(await canvas.evaluate((node) => node.isConnected), true);
    assert.equal(reads(), before, 'presentation toggles reuse recorded history');
    await toggle.press('Enter');
    await page.reload();
    const restored = page.getByRole('region', { name: 'Production', exact: true });
    await restored.waitFor();
    assert.equal(
        await restored.getByRole('button', { name: 'Production', exact: true }).getAttribute('aria-expanded'),
        'false'
    );
    assert.equal(await restored.locator('.home-pin-card').isVisible(), false);
    await restored.getByRole('button', { name: 'Rename group', exact: true }).click();
    await restored.locator('.home-pin-group-rename input').fill('Machines');
    await restored.getByRole('button', { name: 'Save', exact: true }).click();
    const renamed = page.getByRole('region', { name: 'Machines', exact: true });
    await renamed.waitFor();
    assert.equal(
        await renamed.getByRole('button', { name: 'Machines', exact: true }).getAttribute('aria-expanded'),
        'false'
    );
});

test('collapsed Home groups show recorded stock and net for the configured period, with unknown resources kept unknown', async (t) => {
    const { page, base, options } = await fixture(t);
    options.groups = [{ groupKey: 'production', name: 'Production', ordinal: 0 }];
    options.pins = [
        {
            ...pin('iron-pin', gridA, iron, 0),
            homeGroupKey: 'production',
            homeConfig: { ...config, flowRangeMillis: 900000 }
        },
        {
            ...pin('unknown-pin', null, iron, 1),
            homeGroupKey: 'production',
            available: false,
            itemKey: null,
            resource: null
        }
    ];
    const now = Date.now();
    const flow = {
        kind: 'FLOW',
        flowFrom: now - 15000,
        generation: 1,
        resource: null,
        inputOperations: '1',
        outputOperations: '1'
    };
    options.resourceHistory = {
        baseline: { kind: 'STOCK', observedAt: now - 20000, resource: { ...iron, quantity: '100' } },
        complete: true,
        truncated: false,
        points: [
            { ...flow, observedAt: now - 3600000, input: '1000', output: '0' },
            { ...flow, observedAt: now - 10000, input: '12', output: '4' },
            { kind: 'STOCK', observedAt: now - 5000, resource: { ...iron, quantity: '123' } },
            { kind: 'STOCK', observedAt: now + 100000, resource: { ...iron, quantity: '999' } }
        ]
    };
    await page.goto(base);
    const group = page.getByRole('region', { name: 'Production', exact: true });
    const toggle = group.getByRole('button', { name: 'Production', exact: true });
    await toggle.click();
    assert.equal(await group.locator('.home-pin-compact-row').count(), 2);
    const row = group.locator('.home-pin-compact-row').filter({ hasText: 'Iron Ingot' });
    await page.waitForFunction(() => document.querySelector('.home-pin-compact-stock')?.textContent === '123');
    assert.equal(await row.locator('.home-pin-compact-net').textContent(), '+8');
    const unknown = group.locator('.home-pin-compact-row').filter({ hasText: 'Unavailable resource' });
    assert.equal(await unknown.locator('.home-pin-compact-stock').textContent(), '—');
    assert.equal(await unknown.locator('.home-pin-compact-net').textContent(), '—');
    assert.equal(await unknown.getByRole('link').count(), 0);
    await row.focus();
    await page.getByRole('tooltip').filter({ hasText: '15 min' }).waitFor();
    const reads = options.requests.filter((request) => request.path.endsWith('/resource-history')).length;
    assert.equal(
        reads,
        1,
        'one shared range supplies compact metrics even when optional full-card sections are hidden'
    );
    await toggle.click();
    assert.equal(await group.locator('.home-pin-card').first().isVisible(), true);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, reads);
    options.resourceHistory = {
        baseline: null,
        complete: true,
        truncated: false,
        points: [
            { kind: 'STOCK', observedAt: now - 5000, resource: { ...iron, quantity: '0' } },
            { kind: 'FLOW_OBSERVATION', observedAt: now - 5000, flowFrom: now - 10000 }
        ]
    };
    await page.locator('#refresh').click();
    await toggle.click();
    await page.waitForFunction(() => document.querySelector('.home-pin-compact-stock')?.textContent === '0');
    assert.equal(await row.locator('.home-pin-compact-net').textContent(), '0');
    options.resourceHistory.points.push({ ...flow, observedAt: now - 5000, input: '2', output: '9' });
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('.home-pin-compact-net')?.textContent === '−7');
});

test('collapsed Home groups explain disabled tracking while retaining recorded net and unknown unmeasured values', async (t) => {
    const { page, base, options } = await fixture(t);
    options.settings[gridA].isStorageTracked = false;
    options.settings[gridA].isFlowTracked = false;
    options.groups = [{ groupKey: 'production', name: 'Production', ordinal: 0 }];
    options.pins = [{ ...pin('iron-pin', gridA, iron, 0), homeGroupKey: 'production' }];
    await page.goto(base);
    const group = page.getByRole('region', { name: 'Production', exact: true });
    await group.getByRole('button', { name: 'Production', exact: true }).click();
    const row = group.locator('.home-pin-compact-row');
    await row.focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /tracking is off/i })
        .waitFor();
    assert.equal(await row.locator('.home-pin-compact-stock').textContent(), '—');
    assert.equal(await row.locator('.home-pin-compact-net').textContent(), '—');
    const now = Date.now();
    options.resourceHistory.points = [
        { kind: 'STOCK', observedAt: now - 5000, resource: { ...iron, quantity: '24' } },
        {
            kind: 'FLOW',
            observedAt: now - 5000,
            flowFrom: now - 10000,
            resource: null,
            input: '12',
            output: '5'
        }
    ];
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('.home-pin-compact-net')?.textContent === '+7');
    assert.equal(await row.locator('.home-pin-compact-stock').textContent(), '24');
    await row.focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /tracking is off/i })
        .waitFor();
    assert.equal(
        options.requests.filter((request) => /\/(?:items|cpus)(?:\/|$)/.test(request.path)).length,
        0,
        'Home summaries never fall back to native inventory or CPU reads'
    );
});

for (const width of [1440, 390]) {
    test(`Home group forms align their controls and section frames fit at ${width}px`, async (t) => {
        const { page, base, options } = await fixture(t, '', { viewport: { width, height: 900 } });
        options.groups = [{ groupKey: 'production', name: 'Production', ordinal: 0 }];
        options.pins = [{ ...pin('iron-pin', gridA, iron, 0), homeGroupKey: 'production' }];
        await page.goto(base);
        const group = page.getByRole('region', { name: 'Production', exact: true });
        await group.waitFor();
        const aligned = async (form) => {
            const input = await form.getByRole('textbox').boundingBox();
            const button = await form.getByRole('button', { name: /Add group|Save/, exact: true }).boundingBox();
            assert.ok(Math.abs(input.y - button.y) <= 1, 'input and submit button share their top edge');
            assert.ok(Math.abs(input.height - button.height) <= 1, 'controls have equal height');
        };
        await aligned(page.locator('.home-pin-group-add'));
        await group.getByRole('button', { name: 'Rename group', exact: true }).click();
        await aligned(group.locator('.home-pin-group-rename'));
        const frame = await group.evaluate((node) => ({
            border: parseFloat(getComputedStyle(node).borderTopWidth),
            right: node.getBoundingClientRect().right,
            overflow: document.documentElement.scrollWidth > innerWidth
        }));
        assert.equal(frame.border, 1, 'named sections have a thin visible frame');
        assert.ok(frame.right <= width);
        assert.equal(frame.overflow, false);
        await group.getByRole('button', { name: 'Production', exact: true }).click();
        const name = await group.locator('.home-pin-compact-name').boundingBox();
        assert.ok(name.width >= 80, 'item names keep usable space when no icon is displayed');
    });
}

test('expanding a refreshed Home group repaints its chart from the already accepted history', async (t) => {
    const { page, base, options } = await fixture(t);
    const now = Math.floor(Date.now() / 60000) * 60000;
    await page.clock.setFixedTime(now);
    options.groups = [{ groupKey: 'production', name: 'Production', ordinal: 0 }];
    options.pins = [
        {
            ...pin('iron-pin', gridA, iron, 0),
            homeGroupKey: 'production',
            homeConfig: { ...config, showChart: true, rangeMillis: 900000, flowRangeMillis: 900000 }
        }
    ];
    const stock = (age, quantity) => ({
        kind: 'STOCK',
        observedAt: now - age,
        resource: { ...iron, quantity: String(quantity) }
    });
    options.resourceHistory = {
        baseline: null,
        complete: true,
        truncated: false,
        points: [stock(900000, 10), stock(450000, 100), stock(10000, 10)]
    };
    await page.goto(base);
    const group = page.getByRole('region', { name: 'Production', exact: true });
    const canvas = group.locator('.home-pin-chart canvas');
    await canvas.waitFor();
    await page.evaluate(() => document.fonts.ready);
    const before = await canvas.evaluate((node) => node.toDataURL());
    const toggle = group.getByRole('button', { name: 'Production', exact: true });
    await toggle.click();
    options.resourceHistory.points = [stock(900000, 10), stock(450000, 20), stock(10000, 200)];
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('.home-pin-compact-stock')?.textContent === '200');
    const reads = options.requests.filter((request) => request.path.endsWith('/resource-history')).length;
    await toggle.click();
    await canvas.waitFor();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.ok(
        (await canvas.evaluate((node) => node.toDataURL())) !== before,
        'the visible chart reflects the changed recorded data'
    );
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, reads);
});
