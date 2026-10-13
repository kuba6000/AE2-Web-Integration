const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/resource-history.js')
);

test('selected resource history preserves exact quantities and coverage', async () => {
    const { createResourceHistory } = await import(moduleUrl);
    const points = [
        {
            observedAt: 1000,
            generation: 'generation',
            kind: 'FLOW',
            resource: null,
            input: '9223372036854775807',
            output: '0',
            complete: false
        }
    ];
    const history = createResourceHistory(
        {
            resourceHistory: async () => ({ points, complete: false, truncated: true, enabled: true })
        },
        () => {}
    );
    await history.select('grid', 'item');
    assert.equal(history.state.status, 'ready');
    assert.equal(history.state.points[0].input, '9223372036854775807');
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
                    : { points: [{ input: '42' }], complete: true, truncated: false, enabled: true }
        },
        () => {}
    );
    const oldRead = history.select('grid', 'old');
    await history.select('grid', 'new');
    finishOld({ points: [{ input: '999' }], complete: true, truncated: false });
    await oldRead;
    assert.equal(history.state.points[0].input, '42');
    history.dispose();
    assert.equal(history.state.status, 'idle');
    assert.deepEqual(history.state.points, []);
});
