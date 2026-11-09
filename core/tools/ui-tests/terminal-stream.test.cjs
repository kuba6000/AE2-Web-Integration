const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const moduleUrl = pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/terminal.js'));

test('recent-only events keep the inventory snapshot reusable by themes while publishing fresh tracking', async (t) => {
    const originalDocument = global.document;
    global.document = new EventTarget();
    const { createTerminal } = await import(moduleUrl);
    let events;
    const item = { itemKey: 'iron', displayName: 'Iron', quantity: 12, craftable: false };
    const anonymous = { itemKey: null, displayName: 'Unknown', quantity: 5, craftable: false };
    const terminal = createTerminal(
        {
            resourcePins: async () => ({ data: [], icons: null }),
            grids: async () => [{ key: 'grid', name: 'Grid' }],
            iconPack: async () => ({ available: false, packId: null }),
            items: async () => ({
                data: [item, anonymous],
                icons: null,
                view: { generation: '1', revision: '1' },
                subscription: 'session'
            }),
            itemEvents: (_key, _subscription, _icons, handlers) => {
                events = handlers;
                return () => {};
            }
        },
        {
            values: { autoRefresh: true },
            set(name, value) {
                this.values[name] = value;
            }
        },
        { enabled() {}, dispose() {} },
        {}
    );
    t.after(() => {
        terminal.dispose();
        global.document = originalDocument;
    });
    terminal.route({ view: 'items', gridKey: 'grid' });
    await terminal.refresh();
    const inventory = terminal.state.items;
    const snapshotRevision = terminal.state.itemSnapshotRevision;
    let observed;
    terminal.subscribe((state) => {
        observed = { items: state.items, recent: state.recent };
    });
    events.delta({
        view: { generation: '1', revision: '2' },
        definitions: [],
        updates: [],
        removed: [],
        unkeyed: null,
        recent: { resources: { iron: { input: '7', output: '3', stockChange: null } } }
    });
    assert.equal(observed.recent.resources.iron.input, '7');
    assert.equal(observed.items, inventory, 'Tracking-only events do not invalidate a theme inventory cache');
    assert.equal(observed.items[1], anonymous);
    assert.equal(terminal.state.itemSnapshotRevision, snapshotRevision);
    events.delta({
        view: { generation: '1', revision: '3' },
        definitions: [],
        removed: [],
        unkeyed: null,
        updates: [{ itemKey: 'iron', quantity: 14, craftable: false }]
    });
    assert.notEqual(observed.items, inventory);
    assert.equal(observed.items[0].quantity, 14);
    assert.equal(
        observed.items[1],
        anonymous,
        'An explicit null unkeyed payload retains the original anonymous objects'
    );
    assert.equal(inventory[0].quantity, 12, 'Previously delivered inventory snapshots retain their quantities');
    assert.equal(
        terminal.state.itemSnapshotRevision,
        snapshotRevision,
        'Live stock changes retain full-snapshot provenance'
    );
    events.delta({
        view: { generation: '1', revision: '4' },
        definitions: [],
        removed: [],
        updates: [{ itemKey: 'iron', quantity: 15, craftable: false }]
    });
    assert.equal(observed.items[1], anonymous, 'An omitted unkeyed payload also retains anonymous objects');
    events.delta({ view: { generation: '1', revision: '5' }, definitions: [], removed: [], updates: [], unkeyed: [] });
    assert.equal(observed.items.length, 1, 'An explicit empty payload removes all anonymous resources');
    await terminal.refresh();
    assert.equal(terminal.state.itemSnapshotRevision, snapshotRevision + 1);
});

test('terminal live status rejects closed stream callbacks and disabling automatic refresh keeps manual reads non-streaming', async (t) => {
    const originalDocument = global.document;
    global.document = new EventTarget();
    const { createTerminal } = await import(moduleUrl);
    const connections = [],
        reads = [],
        observed = [];
    let pendingGrids;
    let gridReads = 0;
    const preferences = {
        values: { autoRefresh: true, selectedGridKey: null },
        set(name, value) {
            this.values[name] = value;
        }
    };
    const terminal = createTerminal(
        {
            resourcePins: async () => ({ data: [], icons: null }),
            grids: async () => {
                gridReads++;
                return pendingGrids ?? [{ key: 'grid', name: 'Grid' }];
            },
            iconPack: async () => ({ available: false, packId: null }),
            items: async (_key, _signal, _icons, live) => {
                reads.push(live);
                return {
                    data: [],
                    icons: null,
                    ...(live ? { view: { generation: '1', revision: '1' }, subscription: 'session' } : {})
                };
            },
            itemEvents: (_key, _subscription, _icons, handlers) => {
                const connection = { handlers, closed: false };
                connections.push(connection);
                return () => {
                    connection.closed = true;
                };
            }
        },
        preferences,
        { enabled() {}, dispose() {} },
        {}
    );
    t.after(() => {
        terminal.dispose();
        global.document = originalDocument;
    });
    terminal.subscribe((state) => observed.push(state.stream && { ...state.stream }));
    terminal.route({ view: 'items', gridKey: 'grid' });
    await terminal.refresh();
    assert.deepEqual(terminal.state.stream, { status: 'connecting', lastEventAt: null });
    const old = connections.at(-1);
    old.handlers.ready();
    assert.equal(terminal.state.stream.status, 'live');
    assert.ok(Number.isFinite(terminal.state.stream.lastEventAt));
    terminal.preference('autoRefresh', false);
    assert.equal(old.closed, true);
    assert.equal(terminal.state.stream, null);
    assert.equal(observed.at(-1), null, 'Subscribers must immediately see that the connection stopped');
    const count = reads.length;
    old.handlers.ready();
    old.handlers.error();
    old.handlers.reset();
    old.handlers.delta({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [] });
    assert.equal(terminal.state.stream, null);
    assert.equal(reads.length, count);
    await terminal.refresh({ reloadDetail: true });
    assert.equal(reads.at(-1), false);
    assert.equal(connections.length, 1);
    terminal.preference('autoRefresh', true);
    await new Promise((resolve) => setImmediate(resolve));
    const current = connections.at(-1);
    assert.notEqual(current, old);
    old.handlers.error();
    assert.equal(terminal.state.stream.status, 'connecting');
    current.handlers.ready();
    assert.equal(terminal.state.stream.status, 'live');
    let resolveGrids;
    pendingGrids = new Promise((resolve) => {
        resolveGrids = resolve;
    });
    const beforeResync = reads.length;
    const resync = terminal.refresh({ reloadDetail: true });
    const repeatedResync = terminal.refresh({ reloadDetail: true });
    assert.equal(current.closed, true, 'Manual resync stops the old stream before waiting for HTTP');
    assert.deepEqual(observed.at(-1), { status: 'connecting', lastEventAt: null });
    resolveGrids([{ key: 'grid', name: 'Grid' }]);
    await Promise.all([resync, repeatedResync]);
    assert.equal(reads.length, beforeResync + 1, 'Repeated manual refresh shares one snapshot');
    pendingGrids = undefined;
    connections.at(-1).handlers.ready();
    pendingGrids = new Promise((resolve) => {
        resolveGrids = resolve;
    });
    const beforePoll = reads.length;
    const poll = terminal.refresh({ automatic: true });
    const queuedResync = terminal.refresh({ reloadDetail: true });
    const repeatedQueuedResync = terminal.refresh({ reloadDetail: true });
    assert.equal(terminal.state.stream.status, 'live', 'An in-flight background poll does not interrupt live updates');
    resolveGrids([{ key: 'grid', name: 'Grid' }]);
    await Promise.all([poll, queuedResync, repeatedQueuedResync]);
    pendingGrids = undefined;
    assert.equal(reads.length, beforePoll + 1, 'Queued manual resync takes one new snapshot');
    assert.equal(terminal.state.stream.status, 'connecting');
    connections.at(-1).handlers.ready();
    connections.at(-1).handlers.error();
    assert.deepEqual(observed.at(-1), { status: 'connecting', lastEventAt: null });
    pendingGrids = new Promise((resolve) => {
        resolveGrids = resolve;
    });
    const beforeNavigation = gridReads;
    const lastPoll = terminal.refresh({ automatic: true });
    const obsoleteResync = terminal.refresh({ reloadDetail: true });
    terminal.route({ view: 'web-settings', gridKey: null });
    resolveGrids([{ key: 'grid', name: 'Grid' }]);
    await Promise.all([lastPoll, obsoleteResync]);
    assert.equal(gridReads, beforeNavigation + 1, 'Queued terminal resync is discarded after navigation');
    assert.equal(terminal.state.stream, null);
    current.handlers.ready();
    assert.equal(terminal.state.stream, null);
    terminal.dispose();
    const emitted = observed.length;
    current.handlers.error();
    assert.equal(observed.length, emitted);
    assert.equal(terminal.state.stream, null);
});

test('terminal reconnect retains its snapshot and reports only failed recovery until a confirmed stream returns', async (t) => {
    const originalDocument = global.document;
    global.document = new EventTarget();
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const { createTerminal } = await import(moduleUrl);
    const connections = [],
        errors = [];
    const item = { itemKey: 'iron', displayName: 'Iron', quantity: 12, craftable: false };
    const icons = { packId: 'pack', pages: [] };
    const recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: 300000,
        flowObservedAt: 300000,
        stockObservedAt: 300000,
        flowSince: 180000,
        stockSince: 0,
        flowComplete: true,
        stockComplete: true,
        resources: { iron: { input: '12', output: '0', stockChange: '12' } }
    };
    let failure,
        gridFailure,
        reads = 0;
    const terminal = createTerminal(
        {
            resourcePins: async () => ({ data: [], icons: null }),
            grids: async () => {
                if (gridFailure) throw { status: gridFailure };
                return [{ key: 'grid', name: 'Grid' }];
            },
            iconPack: async () => ({ available: false, packId: null }),
            resourceHistory: async () => ({ data: null }),
            items: async () => {
                reads++;
                if (failure) throw { status: failure };
                return {
                    data: [item],
                    icons,
                    recent,
                    view: { generation: '1', revision: '1' },
                    subscription: 'session'
                };
            },
            itemEvents: (_key, _subscription, _icons, handlers) => {
                connections.push(handlers);
                return () => {};
            }
        },
        {
            values: { autoRefresh: true },
            set(name, value) {
                this.values[name] = value;
            }
        },
        { enabled() {}, dispose() {} },
        {}
    );
    t.after(() => {
        terminal.dispose();
        global.document = originalDocument;
    });
    terminal.subscribe((state) => errors.push(state.itemError));
    terminal.route({ view: 'items', gridKey: 'grid' });
    await terminal.refresh();
    connections.at(-1).ready();
    terminal.select(item);
    const selected = terminal.state.selected;
    connections.at(-1).error();
    assert.equal(
        terminal.state.itemError,
        null,
        'A previously confirmed stream may disconnect without a visible error'
    );
    assert.equal(terminal.state.stream.status, 'connecting');
    const beforeRetry = reads;
    await terminal.refresh({ automatic: true });
    assert.equal(reads, beforeRetry, 'Discovery does not bypass the reconnect backoff');
    gridFailure = 'NETWORK_ERROR';
    await terminal.refresh({ automatic: true });
    assert.equal(
        terminal.state.itemError,
        null,
        'A background discovery failure cannot preempt the first reconnect attempt'
    );
    gridFailure = undefined;
    failure = 'NETWORK_ERROR';
    t.mock.timers.tick(2000);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(terminal.state.itemError, 'NETWORK_ERROR');
    assert.equal(terminal.state.itemStatus, 'ready');
    assert.deepEqual(terminal.state.items, [item]);
    assert.equal(terminal.state.itemIcons, icons);
    assert.equal(terminal.state.recent, recent);
    assert.equal(terminal.state.selected, selected);
    gridFailure = 'NETWORK_ERROR';
    await terminal.refresh({ automatic: true });
    assert.deepEqual(
        terminal.state.items,
        [item],
        'Offline discovery during recovery does not discard the cached view'
    );
    assert.equal(terminal.state.selected, selected);
    gridFailure = undefined;
    failure = undefined;
    errors.length = 0;
    await terminal.refresh({ automatic: true });
    assert.ok(
        errors.every((error) => error === 'NETWORK_ERROR'),
        'HTTP success alone does not claim SSE recovery'
    );
    connections.at(-1).error();
    assert.equal(
        terminal.state.itemError,
        'NETWORK_ERROR',
        'A replacement connection that never becomes ready is a failure'
    );
    t.mock.timers.tick(2000);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(terminal.state.itemError, 'NETWORK_ERROR');
    connections.at(-1).ready();
    assert.equal(terminal.state.itemError, null);
    assert.equal(terminal.state.stream.status, 'live');
    await terminal.refresh();
    connections.at(-1).error();
    assert.equal(
        terminal.state.itemError,
        'NETWORK_ERROR',
        'An initial/manual attach failure is not a confirmed disconnect'
    );
    failure = 'NO_PERMISSIONS';
    t.mock.timers.tick(2000);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(terminal.state.itemStatus, 'error');
    assert.equal(terminal.state.selected, null);
    assert.equal(terminal.state.items.length, 0);
    assert.equal(terminal.state.recent, null);
    assert.equal(terminal.state.stream, null);
    failure = undefined;
    await terminal.refresh();
    connections.at(-1).ready();
    connections.at(-1).error();
    gridFailure = 'UNAUTHORIZED';
    await terminal.refresh({ automatic: true });
    assert.equal(terminal.state.gridError, 'UNAUTHORIZED');
    assert.equal(terminal.state.items.length, 0, 'Access loss still clears private data during backoff');
    assert.equal(terminal.state.stream, null);
    const readsBeforeRevokedRetry = reads;
    t.mock.timers.tick(2000);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(reads, readsBeforeRevokedRetry, 'Revocation cancels the pending reconnect');
});

async function cpuTerminal(t, tracked = true) {
    t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: 10000 });
    const originalDocument = global.document;
    global.document = new EventTarget();
    global.document.hidden = false;
    const { createTerminal } = await import(moduleUrl);
    const row = (itemKey, extra = {}) => ({
        itemKey,
        displayName: itemKey ?? 'Anonymous',
        icon: null,
        active: 1,
        pending: 0,
        stored: 0,
        timeSpentCrafting: 1000,
        crafting: true,
        craftedTotal: 2,
        shareInCraftingTime: 0.5,
        shareInCraftingTimeCombined: 0.1,
        craftsPerSec: 2,
        ...extra
    });
    const detail = {
        isBusy: true,
        hasTrackingInfo: tracked,
        timeElapsed: tracked ? 10000 : 0,
        timeStarted: 0,
        finalOutput: null,
        items: [row('iron'), row(null)]
    };
    let events;
    let snapshotReads = 0;
    const terminal = createTerminal(
        {
            grids: async () => [{ key: 'grid', name: 'Grid' }],
            resourcePins: async () => ({ data: [], icons: null }),
            iconPack: async () => ({ available: false, packId: null }),
            cpus: async () => ({ data: { cpu: detail }, icons: null }),
            cpu: async () => {
                snapshotReads++;
                return { data: detail, icons: null, view: { generation: '1', revision: '1' }, subscription: 'cpu' };
            },
            cpuEvents: (_grid, _cpu, _token, _icons, handlers) => {
                events = handlers;
                return () => {};
            }
        },
        {
            values: { autoRefresh: true },
            set(name, value) {
                this.values[name] = value;
            }
        },
        { enabled() {}, dispose() {} },
        {}
    );
    t.after(() => {
        terminal.dispose();
        global.document = originalDocument;
    });
    terminal.route({ view: 'cpus', gridKey: 'grid', cpuKey: 'cpu' });
    await terminal.refresh();
    const notifications = [];
    terminal.subscribe((state, hint) => notifications.push({ hint, elapsed: state.cpus.detail?.timeElapsed }));
    return { terminal, detail, row, notifications, events: () => events, snapshotReads: () => snapshotReads };
}

test('CPU clock notifications carry complete state and a timing hint, without advancing snapshot provenance', async (t) => {
    const { terminal, notifications, events, snapshotReads } = await cpuTerminal(t);
    const revision = terminal.state.cpus.detailSnapshotRevision;
    assert.equal(revision, 1);
    assert.equal(notifications.at(-1).hint, undefined, 'Initial subscription is a complete ordinary update');
    const reads = snapshotReads();
    t.mock.timers.tick(1000);
    assert.deepEqual(notifications.at(-1), { hint: 'cpu-timing', elapsed: 11000 });
    assert.equal(snapshotReads(), reads, 'The timing clock does not fetch detail');
    assert.equal(terminal.state.cpus.detailSnapshotRevision, revision);
    events().delta({
        view: { generation: '1', revision: '2' },
        definitions: [],
        removed: [],
        icons: null,
        updates: [
            {
                itemKey: 'iron',
                active: 3,
                pending: 0,
                stored: 0,
                timeSpentCrafting: 2000,
                crafting: true,
                craftedTotal: 4
            }
        ]
    });
    assert.equal(notifications.at(-1).hint, undefined, 'A live data change is an ordinary update');
    assert.equal(terminal.state.cpus.detail.items[0].active, 3);
    assert.equal(terminal.state.cpus.detailSnapshotRevision, revision);
    await terminal.cpus.refresh(true);
    assert.equal(terminal.state.cpus.detailSnapshotRevision, revision, 'Summary polling does not force grid ordering');
    await terminal.cpus.refresh();
    assert.equal(terminal.state.cpus.detailSnapshotRevision, revision + 1, 'Accepted full detail refresh does');
});

test('CPU clock is quiet while hidden, after leaving the detail, and after disposal', async (t) => {
    const { terminal, notifications } = await cpuTerminal(t);
    global.document.hidden = true;
    const before = notifications.length;
    t.mock.timers.tick(1000);
    assert.equal(notifications.length, before);
    global.document.hidden = false;
    t.mock.timers.tick(1000);
    assert.equal(notifications.at(-1).elapsed, 12000, 'Visible timing catches up from the original measurements');
    terminal.route({ view: 'home', gridKey: null });
    await Promise.resolve();
    const departed = notifications.length;
    t.mock.timers.tick(1000);
    assert.equal(notifications.length, departed);
    terminal.dispose();
    t.mock.timers.tick(1000);
    assert.equal(notifications.length, departed);
});

test('busy CPU detail without tracking does not publish pointless clock updates', async (t) => {
    const { terminal, notifications } = await cpuTerminal(t, false);
    const before = notifications.length;
    t.mock.timers.tick(2000);
    assert.equal(notifications.length, before);
    assert.equal(terminal.state.cpus.detail.timeElapsed, 0);
});

test('CPU anonymous replacement provenance distinguishes new rows from unchanged clock and keyed updates', async (t) => {
    const { terminal, events, row } = await cpuTerminal(t);
    const initial = terminal.state.cpus.unkeyedRevision;
    assert.equal(initial, 1);
    const delta = (revision, extra) =>
        events().delta({
            view: { generation: '1', revision: String(revision) },
            definitions: [],
            updates: [],
            removed: [],
            icons: null,
            ...extra
        });
    delta(2, { unkeyed: null });
    t.mock.timers.tick(1000);
    assert.equal(terminal.state.cpus.unkeyedRevision, initial);
    delta(3, { unkeyed: [row(null, { active: 5 })] });
    assert.equal(terminal.state.cpus.unkeyedRevision, initial + 1, 'Same description cannot imply anonymous identity');
    delta(4, {});
    assert.equal(terminal.state.cpus.unkeyedRevision, initial + 1);
    delta(5, { unkeyed: [] });
    assert.equal(terminal.state.cpus.unkeyedRevision, initial + 2);
    assert.equal(terminal.state.cpus.detail.items.length, 1);
});
