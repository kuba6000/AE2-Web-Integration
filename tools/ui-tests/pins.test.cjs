const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const moduleUrl = pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/terminal.js'));
const config = {
    showStock: true,
    showFlow: true,
    showChart: true,
    stock: true,
    input: true,
    output: true,
    net: false,
    rangeMillis: 86400000
};
const pin = (id, gridKey = 'a') => ({
    pinKey: id,
    available: true,
    gridKey,
    itemKey: id,
    gridName: gridKey,
    resource: { itemKey: id, displayName: id, icon: null },
    terminalOrder: null,
    homeOrder: 0,
    homeConfig: { ...config }
});
async function app(t, overrides = {}) {
    const before = global.document;
    global.document = new EventTarget();
    const { createTerminal } = await import(moduleUrl);
    const terminal = createTerminal(
        {
            grids: async () => [{ key: 'a' }, { key: 'b' }, { key: 'unrelated' }],
            iconPack: async () => ({ available: false, packId: null }),
            resourcePins: async () => ({ data: [], icons: null }),
            ...overrides
        },
        {
            values: { autoRefresh: false },
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
test('Home reads only pinned history across networks with bounded concurrency and exact counters', async (t) => {
    let active = 0,
        max = 0;
    const reads = [];
    const terminal = await app(t, {
        resourcePins: async () => ({ data: [pin('iron'), pin('gold', 'b'), pin('water', 'b')], icons: null }),
        items: async () => {
            throw new Error('Home must not read live inventory');
        },
        cpus: async () => {
            throw new Error('Home must not read CPUs');
        },
        resourceHistory: async (grid, item) => {
            reads.push([grid, item]);
            max = Math.max(max, ++active);
            await new Promise((resolve) => setTimeout(resolve, 10));
            active--;
            return {
                icons: null,
                data: {
                    points: [
                        {
                            kind: 'FLOW',
                            input: '9007199254740993',
                            output: '1',
                            flowFrom: 1,
                            observedAt: 2,
                            resource: null
                        }
                    ],
                    baseline: null,
                    enabled: false,
                    complete: false,
                    truncated: false
                }
            };
        }
    });
    await terminal.refresh();
    assert.deepEqual(reads.sort(), [
        ['a', 'iron'],
        ['b', 'gold'],
        ['b', 'water']
    ]);
    assert.equal(max, 2);
    assert.equal(terminal.state.pins.histories.iron.points[0].input, '9007199254740993');
    assert.equal(terminal.state.pins.histories.iron.enabled, false);
    assert.equal(terminal.state.pins.histories.iron.complete, false);
});

test('navigation cancels Home reads and revoked pins discard cached metadata and history', async (t) => {
    let available = true,
        waiting = false,
        release,
        aborted;
    const terminal = await app(t, {
        resourcePins: async () => ({
            data: [
                available
                    ? pin('iron')
                    : { ...pin('iron'), available: false, gridKey: null, itemKey: null, gridName: null, resource: null }
            ],
            icons: null
        }),
        resourceHistory: async (_g, _i, signal) => {
            if (waiting)
                await new Promise((resolve) => {
                    release = resolve;
                    signal.addEventListener('abort', () => {
                        aborted = true;
                    });
                });
            return {
                icons: null,
                data: { points: [], baseline: null, enabled: true, complete: true, truncated: false }
            };
        }
    });
    await terminal.refresh();
    assert.equal(terminal.state.pins.items[0].resource.displayName, 'iron');
    available = false;
    await terminal.refresh();
    assert.equal(terminal.state.pins.items[0].resource, null);
    assert.deepEqual(terminal.state.pins.histories, {});
    available = true;
    waiting = true;
    const refresh = terminal.refresh();
    while (!release) await new Promise((resolve) => setImmediate(resolve));
    terminal.route({ view: 'web-settings', gridKey: null });
    assert.equal(aborted, true);
    release();
    await refresh;
    assert.deepEqual(terminal.state.pins.items, []);
    assert.deepEqual(terminal.state.pins.histories, {});
});

test('pin mutations are single flight and apply independent placement and settings from server replies', async (t) => {
    let resolveSave,
        calls = 0;
    const both = { ...pin('iron'), terminalOrder: 2 };
    const terminal = await app(t, {
        resourcePins: async () => ({ data: [both], icons: null }),
        resourceHistory: async () => ({
            icons: null,
            data: { points: [], baseline: null, enabled: false, complete: true, truncated: false }
        }),
        updateResourcePin: async (key, update) => {
            calls++;
            assert.equal(key, 'iron');
            assert.deepEqual(update, { terminal: false });
            return new Promise((resolve) => {
                resolveSave = resolve;
            });
        }
    });
    await terminal.refresh();
    const save = terminal.pins.update('iron', { terminal: false });
    assert.equal(terminal.state.pins.pending, true);
    await terminal.pins.update('iron', { home: false });
    assert.equal(calls, 1);
    resolveSave({ data: [{ ...both, terminalOrder: null }], icons: null });
    await save;
    assert.equal(terminal.state.pins.items[0].homeOrder, 0);
    assert.deepEqual(terminal.state.pins.items[0].homeConfig, config);
    assert.equal(terminal.state.pins.items[0].terminalOrder, null);
    assert.equal(terminal.state.pins.pending, false);
});

test('Home pin display and order mutations reuse matching history and hidden cards request no history', async (t) => {
    let data = [pin('iron')],
        reads = 0;
    const terminal = await app(t, {
        resourcePins: async () => ({ data, icons: null }),
        resourceHistory: async () => {
            reads++;
            return {
                icons: null,
                data: { points: [], baseline: null, enabled: true, complete: true, truncated: false }
            };
        },
        updateResourcePin: async (_key, update) => {
            if (update.homeConfig) data = [{ ...data[0], homeConfig: update.homeConfig }];
            return { data, icons: null };
        }
    });
    await terminal.refresh();
    assert.equal(reads, 1);
    await terminal.pins.update('iron', { move: { placement: 'home', direction: 'later' } });
    assert.equal(reads, 1);
    await terminal.pins.update('iron', { homeConfig: { ...config, showChart: false } });
    assert.equal(reads, 1);
    await terminal.pins.update('iron', { homeConfig: { ...config, rangeMillis: 3600000 } });
    assert.equal(reads, 2);
    await terminal.pins.update('iron', {
        homeConfig: { ...config, showStock: false, showFlow: false, showChart: false }
    });
    await terminal.refresh();
    assert.equal(reads, 2);
});

test('a revoked grid discards peer history failures without resurrecting metadata', async (t) => {
    let failA, failB;
    const terminal = await app(t, {
        resourcePins: async () => ({ data: [pin('iron'), pin('gold')], icons: null }),
        resourceHistory: (_g, item) =>
            new Promise((_resolve, reject) => {
                if (item === 'iron') failA = reject;
                else failB = reject;
            })
    });
    const refresh = terminal.refresh();
    while (!failB) await new Promise((resolve) => setImmediate(resolve));
    failA(Object.assign(new Error('revoked'), { status: 'NO_PERMISSIONS' }));
    await new Promise((resolve) => setImmediate(resolve));
    failB(Object.assign(new Error('offline'), { status: 'NETWORK_ERROR' }));
    await refresh;
    assert.equal(terminal.state.pins.status, 'ready');
    assert.equal(terminal.state.pins.items.length, 2);
    assert.ok(
        terminal.state.pins.items.every((pin) => !pin.available && pin.resource === null && pin.gridKey === null)
    );
    assert.deepEqual(terminal.state.pins.histories, {});
});

test('uncertain pin mutations reconcile authoritative settings before another edit is admitted', async (t) => {
    let data = [pin('iron')],
        release,
        mutations = 0,
        reads = 0;
    const terminal = await app(t, {
        resourcePins: async () => {
            if (++reads > 1)
                await new Promise((resolve) => {
                    release = resolve;
                });
            return { data, icons: null };
        },
        resourceHistory: async () => ({
            icons: null,
            data: { points: [], baseline: null, enabled: false, complete: true, truncated: false }
        }),
        updateResourcePin: async () => {
            mutations++;
            data = [{ ...pin('iron'), homeConfig: { ...config, rangeMillis: 3600000 } }];
            throw Object.assign(new Error('response lost'), { status: 'NETWORK_ERROR' });
        }
    });
    await terminal.refresh();
    const save = terminal.pins.update('iron', { homeConfig: { ...config, rangeMillis: 3600000 } });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(typeof release, 'function', 'An uncertain result requests current account pins');
    await terminal.pins.update('iron', { home: false });
    assert.equal(mutations, 1);
    release();
    await save;
    assert.equal(terminal.state.pins.items[0].homeConfig.rangeMillis, 3600000);
    assert.equal(terminal.state.pins.status, 'ready');
});

test('author preview supports independent resource pin placements through the public HTTP contract', async (t) => {
    const { startServer } = await import(pathToFileURL(path.resolve(__dirname, '../theme-preview/server.mjs')));
    const server = startServer({ port: 0 });
    if (!server.listening) await new Promise((resolve) => server.once('listening', resolve));
    t.after(() => new Promise((resolve) => server.close(resolve)));
    const base = `http://127.0.0.1:${server.address().port}/`;
    const read = async (path, method = 'GET', body) =>
        (
            await fetch(base + path, {
                method,
                headers: { 'Content-Type': 'application/json', 'X-AE2-Request': 'true' },
                body: body ? JSON.stringify(body) : undefined
            })
        ).json();
    let reply = await read('api/grids/AAAAAAAAAAAAAAAAAAAAAA/resource-pins', 'POST', {
        itemKey: 'example-0',
        placement: 'terminal'
    });
    const key = reply.data[0].pinKey;
    assert.equal(reply.data[0].homeOrder, null);
    reply = await read('api/resource-pins/' + key, 'PATCH', { home: true });
    assert.equal(reply.data[0].terminalOrder, 0);
    assert.equal(reply.data[0].homeOrder, 0);
    reply = await read('api/resource-pins/' + key, 'PATCH', { home: false });
    assert.equal(reply.data[0].terminalOrder, 0);
    assert.equal(reply.data[0].homeOrder, null);
    reply = await read('api/resource-pins/' + key, 'DELETE');
    assert.deepEqual(reply.data, []);
});

test('late icon discovery reloads pin icons without clearing accepted Home measurements', async (t) => {
    let discover;
    let reads = 0;
    const terminal = await app(t, {
        iconPack: () =>
            new Promise((resolve) => {
                discover = resolve;
            }),
        resourcePins: async () => ({ data: [pin('iron')], icons: { packId: String(++reads) } }),
        resourceHistory: async () => ({
            icons: null,
            data: {
                points: [{ kind: 'STOCK', observedAt: 1, quantity: '42' }],
                baseline: null,
                enabled: true,
                complete: true,
                truncated: false
            }
        })
    });
    await terminal.refresh();
    discover({ available: true, packId: 'new' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.equal(reads, 2);
    assert.equal(terminal.state.pins.histories.iron.points[0].quantity, '42');
});
