const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/resource-history.js')
);

test('resource history owns its atlas metadata and rejects late icon-enabled responses after disabling icons', async () => {
    const { createResourceHistory } = await import(moduleUrl);
    const icons = { packId: 'pack-a', width: 32, height: 32, pages: [{ digest: 'atlas-a', width: 32, height: 32 }] };
    const data = {
        points: [{ kind: 'STOCK', resource: { itemKey: 'item', quantity: '42', icon: { page: 0, x: 0, y: 0 } } }],
        baseline: null,
        enabled: true,
        complete: true,
        truncated: false
    };
    let enabled = true,
        finish;
    let delay = false;
    const history = createResourceHistory(
        {
            resourceHistory: async (_grid, _item, _signal, _from, includeIcons) => {
                if (delay && includeIcons)
                    return new Promise((resolve) => {
                        finish = resolve;
                    });
                return { data, icons: includeIcons ? icons : null };
            }
        },
        () => {},
        () => enabled
    );
    await history.select('grid', 'item');
    assert.equal(history.state.points[0].resource.quantity, '42');
    assert.deepEqual(history.state.icons, icons);
    delay = true;
    const old = history.refresh();
    enabled = false;
    history.invalidateIcons();
    await history.refresh();
    finish({ data, icons });
    await old;
    assert.equal(history.state.icons, null);
    assert.equal(history.state.status, 'ready');
    history.dispose();
});

test('range selection cancels obsolete reads and requests only the chosen history window', async () => {
    const { createResourceHistory } = await import(moduleUrl);
    const requests = [];
    const history = createResourceHistory(
        {
            resourceHistory: async (_grid, _item, signal, from) => {
                requests.push({ signal, from });
                return {
                    data: { points: [], baseline: null, complete: true, truncated: false, enabled: true },
                    icons: null
                };
            }
        },
        () => {},
        () => false
    );
    await history.select('grid', 'item');
    const before = Date.now();
    await history.range(3600000);
    assert.equal(history.state.rangeMillis, 3600000);
    assert.ok(requests.at(-1).from >= before - 3600000);
    assert.ok(requests.at(-1).from <= Date.now() - 3600000);
    assert.equal(requests[0].signal.aborted, true);
    history.dispose();
});

test('selected resource history preserves exact quantities and coverage', async () => {
    const { createResourceHistory } = await import(moduleUrl);
    const points = [
        {
            observedAt: 1000,
            generation: 'generation',
            kind: 'FLOW',
            resource: null,
            input: '9223372036854775807',
            output: '0'
        },
        { observedAt: 1000, generation: 'generation', kind: 'GAP', resource: null, input: '0', output: '0' }
    ];
    const history = createResourceHistory(
        {
            resourceHistory: async () => ({
                data: { points, complete: false, truncated: true, enabled: true },
                icons: null
            })
        },
        () => {},
        () => false
    );
    await history.select('grid', 'item');
    assert.equal(history.state.status, 'ready');
    assert.equal(history.state.points[0].input, '9223372036854775807');
    assert.equal(history.state.points[1].kind, 'GAP');
    assert.equal(history.state.complete, false);
    assert.equal(history.state.truncated, true);
    assert.equal(history.state.enabled, true);
});

test('changing selection rejects late history from the previous resource', async () => {
    const { createResourceHistory } = await import(moduleUrl);
    let finishOld;
    const history = createResourceHistory(
        {
            resourceHistory: async (_grid, item) =>
                item === 'old'
                    ? new Promise((resolve) => {
                          finishOld = resolve;
                      })
                    : {
                          data: { points: [{ input: '42' }], complete: true, truncated: false, enabled: true },
                          icons: null
                      }
        },
        () => {},
        () => false
    );
    const oldRead = history.select('grid', 'old');
    await history.select('grid', 'new');
    finishOld({ data: { points: [{ input: '999' }], complete: true, truncated: false }, icons: null });
    await oldRead;
    assert.equal(history.state.points[0].input, '42');
    history.dispose();
    assert.equal(history.state.status, 'idle');
    assert.deepEqual(history.state.points, []);
});

test('a selection range is applied before its first read without replacing the preferred full history range', async () => {
    const { createResourceHistory } = await import(moduleUrl);
    const requests = [];
    const history = createResourceHistory(
        {
            resourceHistory: async (_grid, _item, _signal, from) => {
                requests.push(from);
                return {
                    data: { points: [], baseline: null, enabled: true, complete: true, truncated: false },
                    icons: null
                };
            }
        },
        () => {},
        () => false
    );
    await history.range(86400000);
    const before = Date.now();
    await history.select('grid', 'item', 3600000);
    assert.equal(requests.length, 1);
    assert.ok(requests[0] >= before - 3600000 && requests[0] <= Date.now() - 3600000);
    assert.equal(history.state.rangeMillis, 3600000);
    await history.select('grid', 'item');
    assert.equal(history.state.rangeMillis, 86400000);
    await history.select('grid', 'item', 3600000);
    assert.equal(history.state.rangeMillis, 3600000);
    await history.select('grid', 'item');
    assert.equal(history.state.rangeMillis, 86400000);
    assert.equal(requests.length, 4);
    history.dispose();
});
