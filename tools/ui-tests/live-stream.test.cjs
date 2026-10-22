const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/live-stream.js')
);

/** A keyed list whose rows differ from the terminal's: other numbers and a header section. */
async function setup(t, { enabled = true } = {}) {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const { createLiveStream } = await import(moduleUrl);
    const owner = {
        rows: [],
        icons: null,
        header: null,
        statuses: [],
        resyncs: 0,
        failures: [],
        changes: 0,
        applied: [],
        valid: true
    };
    const stream = createLiveStream({
        enabled: () => enabled,
        rows: () => owner.rows,
        icons: () => owner.icons,
        section(delta, remap) {
            if (delta.header) owner.header = { ...delta.header, product: remap(delta.header.product) };
        },
        status: (next) => owner.statuses.push(next),
        apply(change) {
            owner.applied.push(change);
            if (change.rows) owner.rows = change.rows.all;
            if (change.icons) owner.icons = change.icons;
        },
        failed: (confirmed) => owner.failures.push(confirmed),
        changed: () => owner.changes++,
        resync: () => owner.resyncs++
    });
    const connections = [];
    const start = (snapshot = { view: { generation: '1', revision: '1' }, subscription: 'token' }) =>
        stream.start(
            snapshot,
            (subscription, handlers) => {
                const connection = { subscription, handlers, closed: false };
                connections.push(connection);
                return () => {
                    connection.closed = true;
                };
            },
            () => owner.valid
        );
    return { stream, owner, connections, start };
}
const row = (itemKey, extra = {}) => ({ itemKey, icon: null, active: 0, stored: 0, ...extra });
const delta = (revision, content = {}) => ({
    view: { generation: '1', revision: String(revision) },
    definitions: [],
    updates: [],
    removed: [],
    icons: null,
    ...content
});

test('start follows a snapshot only when it carries a subscription and live updates are wanted', async (t) => {
    const { stream, owner, connections, start } = await setup(t);
    start({});
    assert.equal(connections.length, 0);
    assert.equal(stream.active, false);
    owner.valid = false;
    start();
    assert.equal(connections.length, 0, 'An obsolete owner scope opens no connection');
    owner.valid = true;
    start();
    assert.equal(connections.length, 1);
    assert.equal(connections[0].subscription, 'token');
    assert.equal(stream.active, true);
    assert.deepEqual(owner.statuses.at(-1), { status: 'connecting', lastEventAt: null });
    connections[0].handlers.ready();
    assert.equal(owner.statuses.at(-1).status, 'live');
    assert.equal(owner.changes, 1);
    stream.stop();
    assert.equal(owner.statuses.at(-1), null);
    assert.equal(connections[0].closed, true);
    assert.equal(stream.active, false);
    connections[0].handlers.ready();
    connections[0].handlers.delta(delta(2));
    assert.equal(owner.changes, 1, 'Events of a stopped connection are ignored');
});

test('disabled live updates open no connection', async (t) => {
    const { connections, start } = await setup(t, { enabled: false });
    start();
    assert.equal(connections.length, 0);
});

test('deltas apply definitions, absolute updates and removals to a keyed list and feed extra sections', async (t) => {
    const { owner, connections, start } = await setup(t);
    const anonymous = row(null);
    owner.rows = [row('a', { active: 1 }), row('b'), anonymous];
    start();
    const { handlers } = connections[0];
    handlers.delta(
        delta(2, {
            header: { busy: true, product: { icon: null } },
            definitions: [row('c')],
            updates: [{ itemKey: 'a', active: 5, stored: 9 }],
            removed: ['b']
        })
    );
    assert.deepEqual(
        owner.rows.map((entry) => [entry.itemKey, entry.active, entry.stored]),
        [
            ['a', 5, 9],
            ['c', 0, 0],
            [null, 0, 0]
        ]
    );
    assert.equal(owner.rows[2], anonymous, 'An omitted unkeyed list keeps the anonymous rows');
    assert.deepEqual(owner.header, { busy: true, product: { icon: null } });
    assert.equal([...owner.applied.at(-1).rows.keyed.keys()].join(), 'a,c');
    handlers.delta(delta(3, { unkeyed: [] }));
    assert.deepEqual(
        owner.rows.map((entry) => entry.itemKey),
        ['a', 'c']
    );
    const rows = owner.rows;
    handlers.delta(delta(4, { header: { busy: false, product: { icon: null } } }));
    assert.equal(owner.rows, rows, 'A section-only delta leaves the rows untouched');
    assert.equal(owner.applied.at(-1).rows, null);
    assert.deepEqual(owner.header, { busy: false, product: { icon: null } });
});

test('stale revisions are skipped, newer generations and unknown updates request a fresh snapshot', async (t) => {
    const { stream, owner, connections, start } = await setup(t);
    owner.rows = [row('a')];
    start({ view: { generation: '1', revision: '9' }, subscription: 'token' });
    const { handlers } = connections[0];
    const changes = owner.changes;
    handlers.delta(delta(9, { updates: [{ itemKey: 'a', active: 1, stored: 1 }] }));
    assert.equal(owner.rows[0].active, 0, 'A revision that is not newer is not applied');
    assert.equal(owner.changes, changes + 1);
    handlers.delta(delta(10, { updates: [{ itemKey: 'a', active: 1, stored: 1 }] }));
    assert.equal(owner.rows[0].active, 1);
    handlers.delta(delta('900000000000000000000'));
    assert.equal(owner.resyncs, 0, 'Revisions compare numerically beyond the safe integer range');
    handlers.delta({ ...delta(901), view: { generation: '2', revision: '1' } });
    assert.equal(owner.resyncs, 1);
    assert.equal(stream.active, false);
    assert.equal(connections[0].closed, true);
    start();
    connections[1].handlers.delta(delta(2, { updates: [{ itemKey: 'missing', active: 1, stored: 1 }] }));
    assert.equal(owner.resyncs, 2);
    assert.equal(connections[1].closed, true);
});

test('icon pages merge by digest, remap rows and a different pack requests a fresh snapshot', async (t) => {
    const { owner, connections, start } = await setup(t);
    const page = (digest) => ({ digest, width: 16, height: 16 });
    owner.icons = { packId: 'pack', pages: [page('old')] };
    owner.rows = [row('a', { icon: { page: 0, x: 0, y: 0 } })];
    start();
    const { handlers } = connections[0];
    handlers.delta(
        delta(2, {
            icons: { packId: 'pack', pages: [page('new'), page('old')] },
            definitions: [row('b', { icon: { page: 0, x: 1, y: 2 } }), row('c', { icon: { page: 1, x: 3, y: 4 } })]
        })
    );
    assert.deepEqual(
        owner.icons.pages.map((entry) => entry.digest),
        ['old', 'new']
    );
    assert.deepEqual(
        owner.rows.map((entry) => entry.icon?.page ?? null),
        [0, 1, 0]
    );
    handlers.delta(delta(3, { icons: { packId: 'other', pages: [] } }));
    assert.equal(owner.resyncs, 1);
});

test('a section receives the same icon page remapping as the rows of its delta', async (t) => {
    const { owner, connections, start } = await setup(t);
    const page = (digest) => ({ digest, width: 16, height: 16 });
    owner.icons = { packId: 'pack', pages: [page('old')] };
    start();
    const { handlers } = connections[0];
    handlers.delta(
        delta(2, {
            icons: { packId: 'pack', pages: [page('new'), page('old')] },
            header: { busy: true, product: { icon: { page: 0, x: 5, y: 6 } } },
            definitions: [row('a', { icon: { page: 0, x: 1, y: 2 } })]
        })
    );
    assert.deepEqual(
        owner.icons.pages.map((entry) => entry.digest),
        ['old', 'new']
    );
    assert.deepEqual(owner.header.product.icon, { page: 1, x: 5, y: 6 });
    assert.equal(owner.rows[0].icon.page, owner.header.product.icon.page, 'Both use the page merged for the delta');
    handlers.delta(delta(3, { header: { busy: false, product: { icon: { page: 0, x: 7, y: 8 } } } }));
    assert.equal(owner.header.product.icon, null, 'Without icon metadata in the delta no icon is kept');
});

test('reset resynchronizes, a failed connection reports confirmation and retries after the backoff', async (t) => {
    const { stream, owner, connections, start } = await setup(t);
    start();
    connections[0].handlers.reset();
    assert.equal(owner.resyncs, 1);
    start();
    connections[1].handlers.error();
    assert.deepEqual(owner.failures, [false]);
    assert.equal(stream.reconnecting, true);
    assert.deepEqual(owner.statuses.at(-1), { status: 'connecting', lastEventAt: null });
    t.mock.timers.tick(1999);
    assert.equal(owner.resyncs, 1);
    t.mock.timers.tick(1);
    assert.equal(owner.resyncs, 2);
    assert.equal(stream.reconnecting, false);
    start();
    connections[2].handlers.ready();
    connections[2].handlers.error();
    assert.deepEqual(owner.failures, [false, true]);
    stream.stop();
    assert.equal(stream.reconnecting, false);
    t.mock.timers.tick(2000);
    assert.equal(owner.resyncs, 2, 'Stopping cancels the pending reconnect');
    start();
    connections[3].handlers.error();
    owner.valid = false;
    t.mock.timers.tick(2000);
    assert.equal(owner.resyncs, 2, 'An obsolete owner scope discards the reconnect');
});
