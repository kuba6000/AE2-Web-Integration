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
    assert.deepEqual(await page.locator('.home-pin-group-heading h3').allTextContents(), [
        'Ores',
        'Machines',
        'Ungrouped'
    ]);
    const quartzCard = machines.locator('.home-pin-card').filter({ hasText: 'Certus Quartz Crystal' });
    await quartzCard.getByRole('button', { name: 'Move earlier', exact: true }).click();
    await serverState(() => options.pins.find((value) => value.pinKey === 'quartz-pin').homeOrder, 0);
    assert.deepEqual(await machines.locator('.home-pin-name').allTextContents(), [
        'Certus Quartz Crystal',
        'Iron Ingot'
    ]);
    const unavailable = ores.locator('.home-pin-card');
    await unavailable.locator('summary').click();
    await unavailable.getByRole('combobox', { name: 'Group', exact: true }).selectOption({ label: 'Machines' });
    await serverState(() => options.pins.find((value) => value.pinKey === 'unavailable-pin').homeGroupKey, 'machines');
    assert.equal(await machines.locator('.home-pin-card').count(), 3);
    assert.equal(await ores.locator('.home-pin-card').count(), 0, 'empty named group remains visible');
    assert.equal(await ores.isVisible(), true);
    const moved = machines.locator('.home-pin-card').filter({ hasText: 'Unavailable resource' });
    assert.equal(
        await moved.locator('.home-pin-settings-group:visible').count(),
        0,
        'resource configuration stays hidden'
    );
    await moved.getByRole('combobox', { name: 'Group', exact: true }).selectOption({ label: 'Ungrouped' });
    await serverState(() => options.pins.find((value) => value.pinKey === 'unavailable-pin').homeGroupKey, null);
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
