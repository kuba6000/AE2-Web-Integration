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
