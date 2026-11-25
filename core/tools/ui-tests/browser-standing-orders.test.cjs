const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
    fixture,
    atlasFixture,
    refreshingFixture,
    gridA,
    iron,
    quartz,
    listedDefinition,
    poll,
    serverState
} = require('./fixtures.cjs');

const plastic = { ...iron, displayName: 'Plastic Sheet', itemKey: 'plastic', registryPath: 'plastic_sheet' };
const stockBelow = (resource, amount) => ({ type: 'stockBelow', resource, amount });
const definition = (overrides = {}) => ({
    name: 'Keep iron',
    enabled: true,
    cooldownSeconds: 0,
    trigger: stockBelow('iron', 1000),
    action: {
        resource: 'iron',
        amount: { type: 'fillTo', target: 10000, maxBatch: 2000 },
        options: { lightMode: false, ignoreMissing: false },
        cpu: { type: 'any' }
    },
    ...overrides
});
const runtime = (status, extra = {}) => ({
    status,
    reason: null,
    resource: null,
    path: null,
    submitError: null,
    missing: [],
    changedAt: 0,
    lastSubmittedAmount: 0,
    nextCheckAt: 0,
    craftingSince: 0,
    ...extra
});
const listed = (orderKey, overrides, status = null) => ({
    orderKey,
    definition: listedDefinition(definition(overrides)),
    invalid: null,
    status
});

async function open(t, orders, state = {}, route = '') {
    const context = await fixture(t);
    const { options } = context;
    options.itemsA = [iron, quartz, plastic];
    Object.assign(options.standingOrders, { orders, resources: { iron }, ...state });
    await context.page.goto(`${context.base}#/grids/${gridA}/standing-orders${route}`);
    return context;
}
const lastChange = (options) => options.requests.filter((request) => request.method !== 'GET').at(-1);

test('the list shows every order with its target, a readable status and why it has it', async (t) => {
    const { page } = await open(t, [
        listed('a', { name: 'Fresh order' }),
        listed(
            'b',
            { name: 'Busy order' },
            runtime('CRAFTING', { lastSubmittedAmount: 8000, craftingSince: 1700000000000 })
        ),
        listed(
            'c',
            { name: 'Short order' },
            runtime('BLOCKED_MISSING', {
                reason: 'MISSING_INGREDIENTS',
                missing: [{ resource: 'redstone', displayName: 'Redstone', amount: 12 }],
                nextCheckAt: 4102444800000
            })
        ),
        listed('d', { name: 'Off order', enabled: false }, runtime('DISABLED')),
        listed(
            'e',
            { name: 'Failed order' },
            runtime('SUBMIT_FAILED', { reason: 'SUBMIT_REJECTED', submitError: 'No suitable CPU found.' })
        )
    ]);
    const list = page.getByRole('list', { name: 'Standing orders list' });
    await list.waitFor();
    const rows = list.getByRole('listitem');
    assert.equal(await list.getByRole('heading').count(), 5);
    const row = (name) => rows.filter({ has: page.getByRole('heading', { name, exact: true }) });
    await row('Fresh order').getByText('Not checked yet').waitFor();
    await row('Fresh order').getByText('Iron Ingot').waitFor();
    await row('Busy order').getByText('Crafting', { exact: true }).waitFor();
    await row('Busy order').getByText('Last order: 8,000', { exact: false }).waitFor();
    await row('Short order').getByText('Missing ingredients').waitFor();
    await row('Short order').getByText('The network lacks ingredients', { exact: false }).waitFor();
    await row('Short order').getByText('Missing 12 Redstone').waitFor();
    await row('Failed order').getByText('The crafting system rejected the job.').waitFor();
    await row('Failed order').getByText('No suitable CPU found.').waitFor();
    await row('Short order').getByText('Next check:', { exact: false }).waitFor();
    await row('Off order').getByText('Disabled', { exact: true }).waitFor();
    assert.equal(await row('Off order').getByRole('checkbox').isChecked(), false);
    assert.equal(
        await row('Off order')
            .getByRole('button', { name: /^Run now/ })
            .count(),
        0
    );
    assert.equal(await row('Busy order').getByRole('checkbox').isChecked(), true);
});

test('a missing ingredient has its own atlas icon even without a catalogue key', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    await atlasFixture(page, options);
    const missing = { resource: null, displayName: 'Redstone', amount: 12, icon: { page: 0, x: 64, y: 0 } };
    options.standingOrders.resources = { iron: { ...iron, icon: { page: 0, x: 0, y: 0 } } };
    options.standingOrders.orders = [
        listed('a', {}, runtime('BLOCKED_MISSING', { reason: 'MISSING_INGREDIENTS', missing: [missing] }))
    ];
    await page.addInitScript(() =>
        localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ terminalDisplay: 'both' }))
    );
    await page.goto(`${base}#/grids/${gridA}/standing-orders`);
    const shortfall = page.getByRole('list', { name: 'Missing ingredients' }).getByRole('listitem');
    await shortfall.waitFor();
    assert.match(await shortfall.innerText(), /Missing 12 Redstone/);
    await shortfall.locator('[aria-hidden="true"]').first().waitFor({ timeout: 3000 });
    await page.waitForFunction(() => {
        const icon = document.querySelector('.standing-order-missing [aria-hidden="true"]');
        return (
            icon &&
            getComputedStyle(icon).backgroundImage !== 'none' &&
            parseFloat(getComputedStyle(icon).backgroundPositionX) < 0
        );
    });
});

test('enabling, running and deleting send exactly the requested change and the list follows the answer', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Keep iron' }), listed('b', { name: 'Other' })]);
    const row = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: 'Keep iron' }) });
    await row.getByRole('checkbox').uncheck();
    await serverState(() => lastChange(options)?.method, 'PATCH');
    assert.deepEqual(lastChange(options).body, { enabled: false });
    assert.equal(lastChange(options).headers['x-ae2-request'], 'true');
    assert.match(lastChange(options).path, /\/standing-orders\/a$/);
    await row.getByRole('checkbox', { checked: false }).waitFor();
    await row.getByRole('checkbox').check();
    await serverState(() => lastChange(options).body?.enabled, true);

    await row.getByRole('button', { name: /^Run now/ }).click();
    await serverState(() => options.standingOrderRuns, 1);
    assert.match(lastChange(options).path, /\/standing-orders\/a\/run$/);

    await row.getByRole('button', { name: /^Delete/ }).click();
    await serverState(() => lastChange(options).method, 'DELETE');
    await page.getByRole('heading', { name: 'Keep iron' }).waitFor({ state: 'detached' });
    await page.getByRole('heading', { name: 'Other' }).waitFor();
});

test('a refused action explains itself and leaves the list as it was', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Keep iron' })]);
    options.standingOrderFailure = { status: 'STANDING_ORDER_NOT_RUNNABLE', httpStatus: 409 };
    await page.getByRole('button', { name: /^Run now/ }).click();
    await page.getByRole('status').getByText('This order cannot run now', { exact: false }).waitFor();
    await page.getByRole('heading', { name: 'Keep iron' }).waitFor();
});

test('without edit permission the list is read-only, and server or network switches are explained', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Keep iron' })], { canEdit: false });
    await page.getByText('You can view standing orders but not change them.').waitFor();
    for (const control of [
        page.getByRole('checkbox'),
        page.getByRole('button', { name: /Run now|Delete/ }),
        page.getByRole('link', { name: /Edit|New standing order/ })
    ])
        assert.equal(await control.count(), 0);
    options.standingOrders.serverEnabled = false;
    await page.reload();
    await page.getByText('Standing orders are turned off on this server', { exact: false }).waitFor();
    options.standingOrders.serverEnabled = true;
    options.standingOrders.gridEnabled = false;
    options.standingOrders.canEdit = true;
    await page.reload();
    await page.getByText('Standing orders are switched off for this network', { exact: false }).waitFor();
    await page.getByRole('link', { name: 'Network settings' }).waitFor();
    await page.getByRole('link', { name: 'New standing order' }).waitFor();
    const runNow = page.getByRole('button', { name: /Run now/ });
    assert.equal(await runNow.isEnabled(), false);
    options.standingOrders.gridEnabled = true;
    options.standingOrders.orders[0].status = runtime('GRID_UNAVAILABLE');
    await page.reload();
    await runNow.waitFor();
    assert.equal(await runNow.isEnabled(), true);
});

test('a refused save preserves a readable draft; a denied read clears its fields and the old list', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Private order' })]);
    await page.getByRole('link', { name: /^Edit/ }).click();
    const name = page.getByRole('textbox', { name: 'Name', exact: true });
    await name.fill('Private draft');
    options.standingOrderFailure = { status: 'NO_PERMISSIONS', httpStatus: 403 };
    options.standingOrders.canEdit = false;
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByText('You can view standing orders but not change them.').first().waitFor();
    assert.equal(await name.isDisabled(), true);
    assert.equal(await name.inputValue(), 'Private draft');

    await page.route('**/standing-orders{,?*}', (route) =>
        route.fulfill({ status: 403, json: { status: 'NO_PERMISSIONS', data: null } })
    );
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByText('You no longer have access to this network.').first().waitFor();
    assert.equal(await page.getByText('Private order', { exact: true }).count(), 0);
    assert.equal(
        await page.locator('input').evaluateAll((inputs) => inputs.some((i) => i.value === 'Private draft')),
        false
    );
});

test('the check cadence follows the network stock interval and points to network settings', async (t) => {
    const { page, options } = await open(t, []);
    options.settings[gridA].stockSampleIntervalSeconds = 120;
    await page.reload();
    await page.getByText('Orders are checked every 2 min', { exact: false }).waitFor();
    await page.getByText('This network has no standing orders yet.').waitFor();
    await page.getByRole('link', { name: 'Network settings' }).click();
    await page.getByRole('heading', { name: 'Network details' }).waitFor();
});

test('keeping an item stocked from the terminal opens a prefilled editor that creates a fill-to order', async (t) => {
    const { page, options, base } = await open(t, []);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page
        .getByRole('button', { name: /Iron Ingot/ })
        .first()
        .click();
    await page.getByRole('link', { name: 'Keep stocked…' }).click();
    await page.getByRole('heading', { name: 'New standing order' }).waitFor();
    await page.getByText('Crafts the resource up to the target', { exact: false }).waitFor();
    assert.match(await page.getByRole('button', { name: /^Resource to craft/ }).innerText(), /Iron Ingot/);
    assert.match(await page.getByRole('button', { name: /^Resource: / }).innerText(), /Iron Ingot/);

    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Keep iron stocked');
    await page.getByLabel('Amount', { exact: true }).fill('1k');
    await page.getByLabel('Target stock').fill('10k');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Keep iron stocked' }).waitFor();
    assert.deepEqual(lastChange(options).body, {
        name: 'Keep iron stocked',
        enabled: true,
        cooldownSeconds: 0,
        trigger: { type: 'stockBelow', resource: 'iron', amount: 1000 },
        action: {
            resource: 'iron',
            amount: { type: 'fillTo', target: 10000, maxBatch: 10000 },
            options: { lightMode: false, ignoreMissing: false },
            cpu: { type: 'any' }
        }
    });
    await page.getByText('Standing order saved').waitFor();
});

test('the editor refuses an incomplete order before sending and shows what to fix', async (t) => {
    const { page, options } = await open(t, [], {}, '/new');
    const before = options.requests.length;
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByText('Enter a name of 1 to 128 characters.').waitFor();
    await page.getByText('Choose a resource.').first().waitFor();
    assert.equal(await page.getByRole('textbox', { name: 'Name', exact: true }).getAttribute('aria-invalid'), 'true');
    assert.equal(
        options.requests.slice(before).filter((request) => request.method !== 'GET').length,
        0,
        'nothing is sent while the draft is incomplete'
    );
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Fixed');
    assert.equal(await page.getByText('Enter a name of 1 to 128 characters.').count(), 0, 'errors clear on edit');
});

test('a server refusal is shown at the field its path names, in the user language', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Keep iron' })], {}, '/a');
    await page.getByRole('heading', { name: 'Edit standing order' }).waitFor();
    await page.getByLabel('Amount', { exact: true }).fill('5');
    options.standingOrderFailure = {
        status: 'INVALID_STANDING_ORDER',
        httpStatus: 400,
        data: { reason: 'AMOUNT_NOT_POSITIVE', path: 'trigger.amount' }
    };
    await page.getByRole('button', { name: 'Save' }).click();
    const field = page.getByLabel('Amount', { exact: true });
    await page.getByText('Enter an amount of at least 1.').waitFor();
    assert.equal(await field.getAttribute('aria-invalid'), 'true');
    assert.equal(await field.evaluate((input) => input === document.activeElement), true);
    assert.equal(
        await field.evaluate((input) => document.getElementById(input.getAttribute('aria-describedby')).textContent),
        'Enter an amount of at least 1.'
    );

    options.standingOrderFailure = {
        status: 'INVALID_STANDING_ORDER',
        httpStatus: 400,
        data: { reason: 'TREE_TOO_LARGE', path: '' }
    };
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('alert').getByText('There are too many conditions.').waitFor();
    options.standingOrderFailure = { status: 'STANDING_ORDER_LIMIT_REACHED', httpStatus: 409 };
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('alert').getByText('standing order limit', { exact: false }).waitFor();
    options.standingOrderFailure = { status: 'STANDING_ORDERS_DISABLED', httpStatus: 403 };
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('alert').getByText('turned off on this server', { exact: false }).waitFor();
    assert.equal(await page.getByLabel('Amount', { exact: true }).inputValue(), '5', 'the draft survives refusals');
});

test('a composed order sends one group with per-row negation, and options appear only when supported', async (t) => {
    const { page, options } = await open(t, [], {}, '/new');
    assert.equal(await page.getByLabel('Lite mode').count(), 0);
    assert.equal(await page.getByLabel('Ignore missing ingredients').count(), 0);

    await page.getByRole('button', { name: /^Resource to craft/ }).click();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('plastic');
    await page.getByRole('button', { name: 'Plastic Sheet' }).click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Plastic when iron is spare');
    await page.getByLabel('Amount', { exact: true }).fill('100');
    await page.getByLabel('Target stock').fill('500');
    await page.getByLabel('Largest single order').fill('200');

    await page.getByRole('button', { name: 'Add condition' }).click();
    const second = page.getByRole('group', { name: 'Condition 2' });
    await second.getByRole('button', { name: /^Resource/ }).click();
    await second.getByRole('searchbox', { name: 'Search resources' }).fill('iron');
    await second.getByRole('button', { name: 'Iron Ingot' }).click();
    await second.getByLabel('Amount').fill('50k');
    await page.getByRole('button', { name: 'Add condition' }).click();
    const third = page.getByRole('group', { name: 'Condition 3' });
    await third.getByLabel('Kind').selectOption('idleCpusAtLeast');
    await third.getByLabel('CPUs', { exact: true }).fill('2');
    await third.getByLabel('Invert (not)').check();
    await page.getByLabel('Craft when').selectOption('any');
    await page.getByLabel('Cooldown (seconds)').fill('30');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Plastic when iron is spare' }).waitFor();
    assert.deepEqual(lastChange(options).body.trigger, {
        type: 'any',
        children: [
            { type: 'stockBelow', resource: 'plastic', amount: 100 },
            { type: 'stockAtLeast', resource: 'iron', amount: 50000 },
            { type: 'not', child: { type: 'idleCpusAtLeast', count: 2 } }
        ]
    });
    assert.equal(lastChange(options).body.cooldownSeconds, 30);
    assert.deepEqual(lastChange(options).body.action.amount, { type: 'fillTo', target: 500, maxBatch: 200 });
});

test('crafting options and the CPU choice follow the server capabilities and the network CPUs', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities = { craftingLightMode: true, craftingIgnoreMissing: true, cpuSelectionMode: true };
    options.itemsA = [iron, quartz, plastic];
    options.cpus = {
        'cpu-a': { ...options.cpus['cpu-a'], name: 'Assembler' },
        'cpu-b': {
            ...options.cpus['cpu-b'],
            name: 'Automation only',
            acceptsPlayerJobs: true,
            selectionMode: 'AUTOMATION_ONLY'
        }
    };
    await page.goto(`${base}#/grids/${gridA}/standing-orders/new/iron`);
    await page.getByLabel('Lite mode').check();
    await page.getByLabel('Ignore missing ingredients').check();
    const cpu = page.getByLabel('Crafting CPU');
    await cpu.locator('option', { hasText: 'Assembler' }).waitFor({ state: 'attached' });
    assert.equal(
        await cpu.locator('option', { hasText: 'Automation only' }).count(),
        0,
        'only eligible CPUs are offered'
    );
    await cpu.selectOption('cpu-a');
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Options');
    await page.getByLabel('Amount', { exact: true }).fill('5');
    await page.getByLabel('Target stock').fill('10');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Options' }).waitFor();
    const { action } = lastChange(options).body;
    assert.deepEqual(action.options, { lightMode: true, ignoreMissing: true });
    assert.deepEqual(action.cpu, { type: 'specific', key: 'cpu-a' });
});

test('an order with nested conditions stays read-only for its rules but can still be renamed', async (t) => {
    const nested = {
        type: 'all',
        children: [stockBelow('iron', 5), { type: 'any', children: [stockBelow('iron', 1), stockBelow('plastic', 2)] }]
    };
    const { page, options } = await open(
        t,
        [listed('a', { name: 'Nested', trigger: nested })],
        { resources: { iron, plastic } },
        '/a'
    );
    await page.getByText('nested groups created through the API', { exact: false }).waitFor();
    await page.getByText('Any of:').waitFor();
    assert.equal(await page.getByRole('button', { name: 'Add condition' }).count(), 0);
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Renamed');
    await page.getByLabel('Enabled').uncheck();
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Renamed' }).waitFor();
    assert.deepEqual(lastChange(options).body, { name: 'Renamed', enabled: false });
    assert.deepEqual(
        options.standingOrders.orders[0].definition.trigger.children[1].children.map((child) => child.amount),
        [1, 2],
        'the rules were not touched'
    );
});

test('an unreadable stored order is listed with its reason and can be repaired with a complete definition', async (t) => {
    const { page, options } = await open(t, [
        { orderKey: 'broken', definition: null, invalid: { reason: 'UNSUPPORTED_VERSION', path: '' }, status: null }
    ]);
    await page.getByText('It was stored by a newer or unknown version.', { exact: false }).waitFor();
    assert.equal(await page.getByRole('checkbox').count(), 0, 'it cannot be switched');
    await page.getByRole('link', { name: /^Repair/ }).click();
    await page.getByRole('heading', { name: 'Repair standing order' }).waitFor();
    await page.getByRole('button', { name: /^Resource to craft/ }).click();
    await page.getByRole('button', { name: 'Iron Ingot' }).click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Repaired');
    assert.match(
        await page.getByRole('button', { name: /^Resource: / }).innerText(),
        /Iron Ingot/,
        'the watched resource follows the crafted one'
    );
    await page.getByLabel('Amount', { exact: true }).fill('10');
    await page.getByLabel('Target stock').fill('20');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Repaired' }).waitFor();
    assert.deepEqual(Object.keys(lastChange(options).body).sort(), [
        'action',
        'cooldownSeconds',
        'enabled',
        'name',
        'trigger'
    ]);
    assert.match(lastChange(options).path, /\/standing-orders\/broken$/);
});

test('an order that disappeared is reported instead of an empty editor', async (t) => {
    const { page } = await open(t, [listed('a', { name: 'Keep iron' })], {}, '/gone');
    await page.getByText('This standing order no longer exists.').waitFor();
    await page.getByRole('link', { name: 'Back to standing orders' }).click();
    await page.getByRole('heading', { name: 'Keep iron' }).waitFor();
});

test('the network settings switch turns standing orders on or off for the network', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const box = page.getByLabel('Run standing orders on this network');
    await box.waitFor();
    assert.equal(await box.isChecked(), true);
    await box.uncheck();
    await page.getByRole('button', { name: 'Save settings' }).click();
    await serverState(() => lastChange(options)?.body?.standingOrdersEnabled, false);
    assert.deepEqual(lastChange(options).body, { standingOrdersEnabled: false });
    assert.equal(options.settings[gridA].standingOrdersEnabled, false);
    options.settings[gridA].canEditStandingOrders = false;
    await page.reload();
    await page.getByRole('button', { name: 'Save settings' }).waitFor();
    await page.waitForFunction(() => !document.querySelector('#network-name')?.disabled);
    assert.equal(await box.isEnabled(), false);
});

test('the Polish interface names the view, statuses and errors', async (t) => {
    const { page, options, base } = await fixture(t, '', { locale: 'pl-PL' });
    options.itemsA = [iron];
    Object.assign(options.standingOrders, {
        orders: [listed('a', { name: 'Żelazo' }, runtime('WAITING_FOR_CPU'))],
        resources: { iron }
    });
    await page.goto(`${base}#/grids/${gridA}/standing-orders`);
    await page.getByRole('heading', { name: 'Zlecenia stałe' }).waitFor();
    await page.getByText('Czeka na wolny CPU').waitFor();
    await page.getByRole('link', { name: 'Edytuj: Żelazo' }).click();
    await page.getByRole('button', { name: 'Zapisz' }).click();
    assert.equal(await page.getByRole('alert').count(), 0);
});

test('the phone layout keeps the list and the editor inside the screen width', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 375, height: 800 } });
    options.itemsA = [iron, plastic];
    Object.assign(options.standingOrders, {
        orders: [
            listed(
                'a',
                { name: 'A very long standing order name that has to wrap instead of overflowing the screen' },
                runtime('BLOCKED_MISSING', {
                    reason: 'MISSING_INGREDIENTS',
                    missing: [{ resource: null, displayName: 'A rather long ingredient name', amount: 123456789 }]
                })
            )
        ],
        resources: { iron }
    });
    const overflow = () =>
        page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    await page.goto(`${base}#/grids/${gridA}/standing-orders`);
    await page.getByRole('heading', { name: /A very long/ }).waitFor();
    assert.ok((await overflow()) <= 0, 'the list does not scroll sideways');
    await page.goto(`${base}#/grids/${gridA}/standing-orders/a`);
    await page.getByRole('heading', { name: 'Edit standing order' }).waitFor();
    await page.getByRole('button', { name: 'Add condition' }).click();
    await page.getByRole('button', { name: 'Add condition' }).click();
    assert.ok((await overflow()) <= 0, 'the editor does not scroll sideways');
});

test('only the editor reads the network resources, and it reads them live so the server can describe a pick', async (t) => {
    const { page, options, base } = await open(t, [listed('a', { name: 'Keep iron' })]);
    const itemReads = () => options.requests.filter((request) => request.path.endsWith('/items'));
    await page.getByRole('heading', { name: 'Keep iron' }).waitFor();
    assert.equal(itemReads().length, 0, 'the list does not read the inventory');
    await page.goto(`${base}#/grids/${gridA}/standing-orders/new`);
    await page.getByRole('heading', { name: 'New standing order' }).waitFor();
    await serverState(() => itemReads().length > 0, true);
    assert.equal(new URLSearchParams(itemReads()[0].query).get('live'), 'true');
    const reads = itemReads().length;
    await page.goto(`${base}#/grids/${gridA}/standing-orders`);
    await page.getByRole('heading', { name: 'Keep iron' }).waitFor();
    assert.equal(itemReads().length, reads, 'leaving the editor stops reading the inventory');
});

test('automatic updates refresh the order status without disturbing an open draft', async (t) => {
    const context = await refreshingFixture(t);
    const { page, options, base } = context;
    options.itemsA = [iron, quartz, plastic];
    Object.assign(options.standingOrders, { orders: [listed('a', { name: 'Keep iron' })], resources: { iron } });
    await page.goto(`${base}#/grids/${gridA}/standing-orders`);
    await page.getByText('Not checked yet').waitFor();
    options.standingOrders.orders[0].status = runtime('WAITING_FOR_CPU');
    await poll(page);
    await page.getByText('Waiting for a free CPU').waitFor();

    await page.getByRole('link', { name: /^Edit/ }).click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Draft name');
    const reads = () => options.requests.filter((request) => request.path.endsWith('/standing-orders')).length;
    const before = reads();
    await poll(page);
    await serverState(() => reads() > before, true);
    assert.equal(await page.getByRole('textbox', { name: 'Name', exact: true }).inputValue(), 'Draft name');
});

test('a saved change is followed by one more read that shows the status the engine gave the order', async (t) => {
    const { page, options } = await open(t, [], {}, '/new/iron');
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Soon');
    await page.getByLabel('Amount', { exact: true }).fill('5');
    await page.getByLabel('Target stock').fill('10');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Soon' }).waitFor();
    await page.getByText('Not checked yet').waitFor();
    const reads = () =>
        options.requests.filter((request) => request.method === 'GET' && request.path.endsWith('/standing-orders'))
            .length;
    const before = reads();
    options.standingOrders.orders[0].status = runtime('IDLE');
    await page.clock.fastForward(2000);
    await serverState(() => reads() > before, true);
    await page.getByText('Nothing to do').waitFor();
});

test('Enter in the resource search confirms nothing and never saves the editor', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Keep iron' })], {}, '/a');
    await page.getByRole('heading', { name: 'Edit standing order' }).waitFor();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Pending rename');
    await page.getByRole('button', { name: /^Resource to craft/ }).click();
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    await search.fill('plastic');
    await search.press('Enter');
    await page.getByRole('button', { name: 'Plastic Sheet' }).waitFor();
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0, 'nothing was saved');
    await page.getByRole('heading', { name: 'Edit standing order' }).waitFor();
    assert.equal(await page.getByRole('textbox', { name: 'Name', exact: true }).inputValue(), 'Pending rename');
});

test('a resource can be picked when the pointer press leaves focus (as in Safari), and a press outside closes the list', async (t) => {
    const { page } = await open(t, [], {}, '/new');
    await page.getByRole('button', { name: /^Resource to craft/ }).click();
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    // A browser that does not focus buttons reports a focus loss without a new focus target.
    await search.evaluate((input) => input.dispatchEvent(new FocusEvent('focusout', { bubbles: true })));
    await search.waitFor();
    await page.getByRole('button', { name: 'Plastic Sheet' }).click();
    assert.match(await page.getByRole('button', { name: /^Resource to craft/ }).innerText(), /Plastic Sheet/);
    await page.getByRole('button', { name: /^Resource to craft/ }).click();
    await search.waitFor();
    await page.getByRole('heading', { name: 'New standing order' }).click();
    await search.waitFor({ state: 'hidden' });
});

test('an order deleted elsewhere is dropped from the list after a refused action', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Gone soon' }), listed('b', { name: 'Stays' })]);
    await page.getByRole('heading', { name: 'Gone soon' }).waitFor();
    options.standingOrders.orders = options.standingOrders.orders.filter((entry) => entry.orderKey !== 'a');
    await page
        .getByRole('listitem')
        .filter({ has: page.getByRole('heading', { name: 'Gone soon' }) })
        .getByRole('button', { name: /^Run now/ })
        .click();
    await page.getByText('no longer exists', { exact: false }).waitFor();
    await page.getByRole('heading', { name: 'Gone soon' }).waitFor({ state: 'detached' });
    await page.getByRole('heading', { name: 'Stays' }).waitFor();
});

test('an editor whose order was deleted elsewhere turns into a missing one on the first refused save', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Keep iron' })], {}, '/a');
    await page.getByRole('textbox', { name: 'Name', exact: true }).waitFor();
    options.standingOrders.orders = [];
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Renamed');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByText('This standing order no longer exists.').first().waitFor();
    await page.getByRole('button', { name: 'Save' }).waitFor({ state: 'hidden' });
    assert.equal(
        options.requests.filter((request) => request.method === 'PATCH').length,
        1,
        'the refusal is not repeated'
    );
});

test('deleting an unreadable order names it as unreadable in the confirmation', async (t) => {
    const { page } = await open(t, [
        { orderKey: 'broken', definition: null, invalid: { reason: 'UNSUPPORTED_VERSION', path: '' }, status: null }
    ]);
    const messages = [];
    page.on('dialog', (dialog) => messages.push(dialog.message()));
    await page.getByRole('button', { name: /^Delete/ }).click();
    await serverState(() => messages.length, 1);
    assert.match(messages[0], /Unreadable standing order/);
    assert.doesNotMatch(messages[0], /Repair/);
});

test('the editor holds one live resource stream while it is open and releases it on every way out', async (t) => {
    const { page, options, base } = await open(t, [listed('a', { name: 'Keep iron' })]);
    options.liveView = { generation: '1', revision: '1' };
    const streams = () => options.itemStreams?.length ?? 0;
    await page.goto(`${base}#/grids/${gridA}/standing-orders/a`);
    await serverState(streams, 1);
    await page.getByRole('link', { name: 'Back to standing orders' }).click();
    await serverState(streams, 0);
    await page.goto(`${base}#/grids/${gridA}/standing-orders/new`);
    await serverState(streams, 1);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await serverState(streams, 0);
});

test('a failed resource read leaves no stream, says so in the picker and can be retried', async (t) => {
    const { page, options, base } = await open(t, [], {}, '/new');
    options.status = 403;
    await page.goto(`${base}#/grids/${gridA}/standing-orders`);
    options.liveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/grids/${gridA}/standing-orders/new`);
    await page.getByRole('button', { name: /^Resource to craft/ }).click();
    await page.getByText('You no longer have access to this network.', { exact: false }).first().waitFor();
    assert.equal(options.itemStreams?.length ?? 0, 0);
    options.status = 200;
    await page.getByRole('button', { name: 'Try again' }).click();
    await page.getByRole('button', { name: 'Plastic Sheet' }).waitFor();
    await serverState(() => options.itemStreams?.length ?? 0, 1);
});

test('with the server switch off orders can be disabled, edited and deleted, but not created, enabled or run', async (t) => {
    const { page, options } = await open(
        t,
        [listed('a', { name: 'Keep iron' }), listed('b', { name: 'Resting', enabled: false })],
        { serverEnabled: false }
    );
    const row = (name) => page.getByRole('listitem').filter({ has: page.getByRole('heading', { name }) });
    await row('Keep iron').waitFor();
    assert.equal(await page.getByRole('link', { name: 'New standing order' }).count(), 0);
    assert.equal(await page.getByRole('button', { name: /^Run now/ }).count(), 0);
    assert.equal(await row('Resting').getByRole('checkbox').isDisabled(), true, 'an order cannot be switched on');
    await row('Resting').getByRole('link', { name: /^Edit/ }).waitFor();
    await row('Resting')
        .getByRole('button', { name: /^Delete/ })
        .waitFor();
    await row('Keep iron').getByRole('checkbox').uncheck();
    await serverState(() => lastChange(options)?.body?.enabled, false);

    await page.goto(page.url().replace(/standing-orders.*$/, 'standing-orders/new'));
    await page.getByText('Standing orders are turned off on this server', { exact: false }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Save' }).isDisabled(), true);
});

test('a locked order shows its crafting options in the read-only summary', async (t) => {
    const nested = {
        type: 'all',
        children: [stockBelow('iron', 5), { type: 'any', children: [stockBelow('iron', 1)] }]
    };
    const { page } = await open(
        t,
        [
            listed('a', {
                name: 'Nested',
                trigger: nested,
                action: { ...definition().action, options: { lightMode: true, ignoreMissing: true } }
            })
        ],
        {},
        '/a'
    );
    await page.getByText('Crafting options: Lite mode, Ignore missing ingredients').waitFor();
});

test('leaving an editor with unsaved changes asks first, while an unchanged editor or a save leaves at once', async (t) => {
    const { page, options } = await open(t, [listed('a', { name: 'Keep iron' })], {}, '/a');
    const questions = [];
    let accept = false;
    page.removeAllListeners('dialog');
    page.on('dialog', async (dialog) => {
        questions.push(dialog.message());
        if (accept) await dialog.accept();
        else await dialog.dismiss();
    });
    const back = page.getByRole('link', { name: 'Back to standing orders' });
    await page.getByRole('textbox', { name: 'Name', exact: true }).waitFor();
    await back.click();
    await page.getByRole('heading', { name: 'Keep iron' }).waitFor();
    assert.equal(questions.length, 0, 'nothing to lose, nothing asked');

    await page.getByRole('link', { name: /^Edit/ }).click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Draft name');
    await back.click();
    await serverState(() => questions.length, 1);
    assert.equal(await page.getByRole('textbox', { name: 'Name', exact: true }).inputValue(), 'Draft name');
    accept = true;
    await back.click();
    await serverState(() => questions.length, 2);
    await page.getByRole('heading', { name: 'Keep iron' }).waitFor();

    await page.getByRole('link', { name: /^Edit/ }).click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Saved name');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Saved name' }).waitFor();
    assert.equal(questions.length, 2, 'saving does not ask');
    assert.deepEqual(lastChange(options).body, { name: 'Saved name' });
});

test('an open resource list leaves no document listener behind when the page is torn down', async (t) => {
    const context = await fixture(t);
    const { page, options, base } = context;
    await page.addInitScript(() => {
        const live = new Set();
        window.pointerListeners = live;
        const add = EventTarget.prototype.addEventListener;
        const remove = EventTarget.prototype.removeEventListener;
        EventTarget.prototype.addEventListener = function (type, listener, ...rest) {
            if (this === document && type === 'pointerdown') live.add(listener);
            return add.call(this, type, listener, ...rest);
        };
        EventTarget.prototype.removeEventListener = function (type, listener, ...rest) {
            if (this === document && type === 'pointerdown') live.delete(listener);
            return remove.call(this, type, listener, ...rest);
        };
    });
    options.itemsA = [iron, quartz, plastic];
    await page.goto(`${base}#/grids/${gridA}/standing-orders/new`);
    await page.getByRole('button', { name: /^Resource to craft/ }).click();
    await page.getByRole('searchbox', { name: 'Search resources' }).waitFor();
    const open = await page.evaluate(() => window.pointerListeners.size);
    assert.ok(open >= 1, 'the open list listens for presses outside');
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: false })));
    assert.equal(await page.evaluate(() => window.pointerListeners.size), open - 1);
});

test('a modified click on a link leaves a dirty editor alone without asking', async (t) => {
    const { page } = await open(t, [listed('a', { name: 'Keep iron' })], {}, '/a');
    const questions = [];
    page.removeAllListeners('dialog');
    page.on('dialog', async (dialog) => {
        questions.push(dialog.message());
        await dialog.dismiss();
    });
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Draft name');
    const popup = page.context().waitForEvent('page');
    await page.getByRole('link', { name: 'Back to standing orders' }).click({ modifiers: ['Shift'] });
    await (await popup).close();
    assert.equal(questions.length, 0);
    assert.equal(await page.getByRole('textbox', { name: 'Name', exact: true }).inputValue(), 'Draft name');
});

test('an editor whose list read fails shows the error without a loading message', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [iron];
    options.standingOrderReadFailure = true;
    await page.goto(`${base}#/grids/${gridA}/standing-orders/a`);
    await page.getByText('The server is busy.').first().waitFor();
    assert.equal(await page.getByText('Loading', { exact: false }).filter({ visible: true }).count(), 0);
});

test('the built-in condition identifies the output and cannot be edited', async (t) => {
    const { page } = await open(t, [listed('a', { name: 'Keep iron' })], {}, '/a');
    await page.getByLabel('Name', { exact: true }).waitFor();
    const guard = page.getByRole('group', { name: 'Condition 0 Always active', exact: true });
    assert.equal(await guard.count(), 1);
    assert.match(await guard.innerText(), /Iron Ingot/);
    assert.equal(await guard.locator('select,input,button').count(), 0);
});

test('a stock comparison and a fill target can use the stock of another resource', async (t) => {
    const { page, options } = await open(t, [], {}, '/new/iron');
    await page.getByLabel('Name', { exact: true }).fill('Match quartz reserves');
    const condition = page.getByRole('group', { name: 'Condition 1', exact: true });
    const craft = page.getByRole('group', { name: 'Then craft', exact: true });
    await condition.getByLabel('Kind').selectOption('stockRatioBelow');
    await craft.getByLabel('How much').selectOption('fillToRatio');
    for (const group of [condition, craft]) {
        await group.getByRole('button', { name: /^Reference resource/ }).click();
        await group.getByRole('button', { name: 'Certus Quartz Crystal', exact: true }).click();
    }
    assert.equal(await condition.getByLabel('Multiplier', { exact: true }).count(), 1);
    await condition.getByLabel('Multiplier').fill('1,5');
    await craft.getByLabel('Multiplier').fill('1/3');
    await craft.getByLabel('Largest single order').fill('64');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('heading', { name: 'Match quartz reserves' }).waitFor();
    assert.deepEqual(lastChange(options).body.trigger, {
        type: 'stockRatioBelow',
        resource: 'iron',
        reference: 'quartz',
        numerator: 3,
        denominator: 2
    });
    assert.deepEqual(lastChange(options).body.action.amount, {
        type: 'fillToRatio',
        reference: 'quartz',
        numerator: 1,
        denominator: 3,
        maxBatch: 64
    });
});

test('the fixed condition and pickers paint a saved item even when it is absent from live stock', async (t) => {
    const { waitIconColor } = require('./icon-pixels.cjs');
    const { page, base, options } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [quartz];
    options.standingOrders.orders = [listed('a', {})];
    options.standingOrders.resources = { iron: { ...iron, icon: { page: 1, x: 64, y: 0 } } };
    await page.route('**/standing-orders?*', async (route) => {
        const response = await route.fetch();
        const json = await response.json();
        if (json.icons) json.icons.pages = [{ ...json.icons.pages[0], digest: 'c'.repeat(64) }, ...json.icons.pages];
        await route.fulfill({ response, json });
    });
    await page.goto(base + '#/web-settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    await page.goto(base + '#/grids/' + gridA + '/standing-orders/a');
    const guard = page.getByRole('group', { name: /Condition 0/ });
    await guard.waitFor();
    assert.equal(await guard.locator('.resource-icon').count(), 1);
    await waitIconColor(page, guard.locator('.resource-icon'), 'green');
    const button = page.getByRole('button', { name: /Iron Ingot/ }).last();
    await button.scrollIntoViewIfNeeded();
    await waitIconColor(page, button.locator('.resource-icon'), 'green');

    options.itemsA = [{ ...quartz, icon: { page: 0, x: 0, y: 0 } }];
    const ratio = { reference: 'quartz', numerator: 1, denominator: 2 };
    options.standingOrders.orders = [
        listed('b', {
            trigger: {
                type: 'not',
                child: { type: 'all', children: [{ type: 'stockRatioBelow', resource: 'iron', ...ratio }] }
            },
            action: { ...definition().action, amount: { type: 'fillToRatio', ...ratio, maxBatch: 10 } }
        })
    ];
    await page.goto(base + '#/grids/' + gridA + '/standing-orders/b');
    await page.reload();
    const summary = page.locator('.standing-order-summary');
    for (const line of [
        summary
            .locator('li')
            .filter({ hasText: /^Iron Ingot:/ })
            .last(),
        summary.getByText(/^Craft Iron Ingot up to/)
    ]) {
        await line.scrollIntoViewIfNeeded();
        await waitIconColor(page, line.locator('.resource-icon').first(), 'green');
        await waitIconColor(page, line.locator('.resource-icon').last(), 'red');
    }
});
