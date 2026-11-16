const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const moduleUrl = pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/terminal.js'));
const order = (orderKey, enabled = true) => ({
    orderKey,
    invalid: null,
    status: null,
    definition: {
        name: orderKey,
        enabled,
        cooldownSeconds: 0,
        trigger: { type: 'stockBelow', resource: 'iron', amount: 5, count: null, children: null, child: null },
        action: {
            resource: 'iron',
            amount: { type: 'fillTo', batch: null, target: 10, maxBatch: 10 },
            options: { lightMode: false, ignoreMissing: false },
            cpu: { type: 'any', key: null }
        }
    }
});
const listing = (orders) => ({
    data: { serverEnabled: true, gridEnabled: true, canEdit: true, maxOrders: 10, orders, resources: {} },
    icons: null
});

async function app(t, overrides = {}, preferences = { autoRefresh: false }) {
    const before = global.document;
    global.document = new EventTarget();
    const { createTerminal } = await import(moduleUrl);
    const terminal = createTerminal(
        {
            grids: async () => [{ key: 'a' }],
            iconPack: async () => ({ available: false, packId: null }),
            resourcePins: async () => ({ data: [], icons: null }),
            settings: async () => ({ stockSampleIntervalSeconds: 300 }),
            cpus: async () => ({ data: {}, icons: null }),
            items: async () => ({ data: [], icons: null }),
            standingOrders: async () => listing([order('o1')]),
            ...overrides
        },
        {
            values: preferences,
            set(name, value) {
                this.values[name] = value;
            }
        },
        { enabled() {}, dispose() {} },
        {}
    );
    t.after(() => {
        terminal.dispose();
        global.document = before;
    });
    return terminal;
}
const listRoute = { view: 'standing-orders', gridKey: 'a', editor: null };
const editorRoute = { view: 'standing-orders', gridKey: 'a', editor: { order: null, itemKey: null } };

test('manual refresh updates the editor CPU choices without resetting its draft', async (t) => {
    let cpus = { old: { name: 'Old CPU', acceptsPlayerJobs: true } };
    const terminal = await app(t, { cpus: async () => ({ data: cpus, icons: null }) });
    terminal.route(editorRoute);
    await terminal.refresh();
    terminal.standingOrders.edit({ type: 'name', value: 'Unsaved order' });
    assert.deepEqual(
        terminal.state.standingOrders.cpus.map((cpu) => cpu.key),
        ['old']
    );
    cpus = { replacement: { name: 'Replacement CPU', acceptsPlayerJobs: true } };
    await terminal.refresh();
    assert.deepEqual(
        terminal.state.standingOrders.cpus.map((cpu) => cpu.key),
        ['replacement']
    );
    assert.equal(terminal.state.standingOrders.editor.draft.name, 'Unsaved order');
});

test('a change in flight still delivers its answer when the icon setting changes meanwhile', async (t) => {
    let release;
    const terminal = await app(t, {
        updateStandingOrder: () =>
            new Promise((resolve) => {
                release = () => resolve(listing([order('o1', false)]));
            })
    });
    terminal.route(listRoute);
    await terminal.refresh();
    const orders = terminal.state.standingOrders;
    assert.equal(orders.status, 'ready');
    const change = terminal.standingOrders.setEnabled('o1', false);
    assert.equal(orders.pending, true);
    terminal.displayIcons(true);
    release();
    await change;
    assert.equal(orders.pending, false, 'the controls are usable again');
    assert.equal(orders.orders[0].definition.enabled, false, 'the answer replaced the list');
});

test('a refusal that says the list is outdated reads the list again', async (t) => {
    let reads = 0;
    const terminal = await app(t, {
        standingOrders: async () => {
            reads++;
            return listing(reads === 1 ? [order('o1')] : []);
        },
        runStandingOrder: async () => {
            throw Object.assign(new Error('gone'), { status: 'STANDING_ORDER_NOT_FOUND' });
        }
    });
    terminal.route(listRoute);
    await terminal.refresh();
    await terminal.standingOrders.run('o1');
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(reads, 2);
    assert.deepEqual(terminal.state.standingOrders.orders, []);
});

test('an editor whose order disappears becomes a missing one instead of repeating its refusal', async (t) => {
    let present = true;
    const terminal = await app(t, {
        standingOrders: async () => listing(present ? [order('o1')] : []),
        updateStandingOrder: async () => {
            throw Object.assign(new Error('gone'), { status: 'STANDING_ORDER_NOT_FOUND' });
        }
    });
    terminal.route({ view: 'standing-orders', gridKey: 'a', editor: { order: 'o1', itemKey: null } });
    await terminal.refresh();
    assert.equal(terminal.state.standingOrders.editor.status, 'ready');
    present = false;
    terminal.standingOrders.edit({ type: 'name', value: 'Renamed' });
    await terminal.standingOrders.save();
    await new Promise((resolve) => setTimeout(resolve, 20));
    const editor = terminal.state.standingOrders.editor;
    assert.equal(editor.status, 'missing');
    assert.equal(editor.saving, false);
});

function liveItems() {
    const open = [];
    return {
        open,
        active: () => open.filter((stream) => !stream.closed).length,
        api: {
            items: async (_grid, _signal, _icons, live) => ({
                data: [],
                icons: null,
                ...(live ? { view: { generation: '1', revision: '1' }, subscription: `s${open.length}` } : {})
            }),
            itemEvents() {
                const stream = { closed: false };
                open.push(stream);
                return () => {
                    stream.closed = true;
                };
            }
        }
    };
}

test('the editor keeps its live resource stream when automatic updates are turned off and releases it on closing', async (t) => {
    const items = liveItems();
    const terminal = await app(t, items.api, { autoRefresh: true });
    terminal.route(editorRoute);
    await terminal.refresh();
    assert.equal(items.active(), 1);
    terminal.preference('autoRefresh', false);
    assert.equal(items.active(), 1, 'the editor still needs the stream');
    terminal.route(listRoute);
    assert.equal(items.active(), 0, 'closing the editor releases it');
});

test('the terminal still stops its live stream when automatic updates are turned off', async (t) => {
    const items = liveItems();
    const terminal = await app(t, items.api, { autoRefresh: true });
    terminal.route({ view: 'items', gridKey: 'a' });
    await terminal.refresh();
    assert.equal(items.active(), 1);
    terminal.preference('autoRefresh', false);
    assert.equal(items.active(), 0);
});

function fillNewOrder(terminal) {
    const edit = terminal.standingOrders.edit;
    edit({ type: 'name', value: 'Keep iron' });
    edit({ type: 'conditionValue', index: 0, value: '5' });
    edit({ type: 'amount', field: 'target', value: '10' });
}
const newIronRoute = { view: 'standing-orders', gridKey: 'a', editor: { order: null, itemKey: 'iron' } };

test('an editor for an order missing from the cached list waits for a fresh read before calling it missing', async (t) => {
    let orders = [order('o1')];
    const terminal = await app(t, { standingOrders: async () => listing(orders) });
    terminal.route(listRoute);
    await terminal.refresh();
    orders = [order('o1'), order('o2')];
    terminal.route({ view: 'standing-orders', gridKey: 'a', editor: { order: 'o2', itemKey: null } });
    assert.equal(terminal.state.standingOrders.editor.status, 'loading', 'not missing before the server answered');
    await terminal.refresh();
    assert.equal(terminal.state.standingOrders.editor.status, 'ready');

    terminal.route({ view: 'standing-orders', gridKey: 'a', editor: { order: 'gone', itemKey: null } });
    await terminal.refresh();
    assert.equal(terminal.state.standingOrders.editor.status, 'missing');
});

test('an unconfirmed create reads the list and warns when a new order appeared, so a retry is a conscious one', async (t) => {
    let orders = [order('o1')];
    let creates = 0;
    const terminal = await app(t, {
        standingOrders: async () => listing(orders),
        createStandingOrder: async () => {
            creates++;
            orders = [order('o1'), order('o2')];
            throw Object.assign(new Error('timeout'), { status: 'TIMEOUT' });
        }
    });
    terminal.route(newIronRoute);
    await terminal.refresh();
    fillNewOrder(terminal);
    await terminal.standingOrders.save();
    const state = terminal.state.standingOrders;
    assert.equal(creates, 1);
    assert.deepEqual(
        state.orders.map((entry) => entry.orderKey),
        ['o1', 'o2'],
        'the list was read again'
    );
    assert.equal(state.editor.formError, 'standingOrderCreateUnconfirmed');
    assert.equal(state.editor.saving, false);
});

test('an unconfirmed create that left no new order keeps the generic unconfirmed message', async (t) => {
    let reads = 0;
    const terminal = await app(t, {
        standingOrders: async () => {
            reads++;
            return listing([order('o1')]);
        },
        createStandingOrder: async () => {
            throw Object.assign(new Error('lost'), { status: 'NETWORK_ERROR' });
        }
    });
    terminal.route(newIronRoute);
    await terminal.refresh();
    fillNewOrder(terminal);
    await terminal.standingOrders.save();
    assert.equal(reads, 2);
    assert.equal(terminal.state.standingOrders.editor.formError, 'actionUnconfirmed');
});

test('leaving the editor while a create is in flight still reads the status once the change is answered', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    let release;
    let reads = 0;
    const terminal = await app(t, {
        standingOrders: async () => {
            reads++;
            return listing([order('o1')]);
        },
        createStandingOrder: () =>
            new Promise((resolve) => {
                release = () => resolve(listing([order('o1'), order('o2')]));
            })
    });
    terminal.route(newIronRoute);
    await terminal.refresh();
    fillNewOrder(terminal);
    const saving = terminal.standingOrders.save();
    terminal.route(listRoute);
    await terminal.refresh();
    const before = reads;
    release();
    await saving;
    assert.equal(reads, before, 'nothing is read before the engine had its tick');
    t.mock.timers.tick(2000);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(reads, before + 1, 'one settle read after the change was answered');
});

test('with the server switch off a new order is not sent, while an existing one can still be saved', async (t) => {
    const sent = [];
    const before = global.location;
    global.location = { hash: '' };
    t.after(() => {
        global.location = before;
    });
    const off = (orders) => ({ ...listing(orders), data: { ...listing(orders).data, serverEnabled: false } });
    const terminal = await app(t, {
        standingOrders: async () => off([order('o1')]),
        createStandingOrder: async () => {
            sent.push('create');
            return off([order('o1')]);
        },
        updateStandingOrder: async (_grid, _order, body) => {
            sent.push(body);
            return off([order('o1', false)]);
        }
    });
    terminal.route(newIronRoute);
    await terminal.refresh();
    fillNewOrder(terminal);
    await terminal.standingOrders.save();
    assert.deepEqual(sent, []);

    terminal.route({ view: 'standing-orders', gridKey: 'a', editor: { order: 'o1', itemKey: null } });
    await terminal.refresh();
    terminal.standingOrders.edit({ type: 'enabled', value: false });
    await terminal.standingOrders.save();
    assert.deepEqual(sent, [{ enabled: false }]);
});

test('the editor reports unsaved changes only while the draft differs from what it opened with', async (t) => {
    const terminal = await app(t);
    terminal.route({ view: 'standing-orders', gridKey: 'a', editor: { order: 'o1', itemKey: null } });
    await terminal.refresh();
    const editor = () => terminal.state.standingOrders.editor;
    assert.equal(editor().dirty, false);
    terminal.standingOrders.edit({ type: 'name', value: 'Renamed' });
    assert.equal(editor().dirty, true);
    terminal.standingOrders.edit({ type: 'name', value: 'o1' });
    assert.equal(editor().dirty, false);
});

test('repairing an unreadable order with the server switch off sends a switched-off order', async (t) => {
    const sent = [];
    const before = global.location;
    global.location = { hash: '' };
    t.after(() => {
        global.location = before;
    });
    const broken = { orderKey: 'b', definition: null, invalid: { reason: 'MALFORMED_JSON', path: '' }, status: null };
    const off = () => ({ ...listing([broken]), data: { ...listing([broken]).data, serverEnabled: false } });
    const terminal = await app(t, {
        standingOrders: async () => off(),
        updateStandingOrder: async (_grid, _order, body) => {
            sent.push(body);
            return off();
        }
    });
    terminal.route({ view: 'standing-orders', gridKey: 'a', editor: { order: 'b', itemKey: null } });
    await terminal.refresh();
    const edit = terminal.standingOrders.edit;
    edit({ type: 'target', resource: { itemKey: 'iron' } });
    fillNewOrder(terminal);
    await terminal.standingOrders.save();
    assert.equal(sent.length, 1);
    assert.equal(sent[0].enabled, false);
});
