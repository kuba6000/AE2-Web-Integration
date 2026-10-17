const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const moduleUrl = pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/terminal.js'));

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
