const test = require('node:test');
const assert = require('node:assert/strict');

test('a live replacement keeps nested recipe icons on their atlas pages and removes vanished providers', async () => {
    const { createPatterns } = await import('../../build/generated/frontend/assets/web/app/patterns.js');
    const icons = (digest) => ({ packId: 'pack', width: 64, height: 64, pages: [{ digest, width: 64, height: 64 }] });
    const stack = (name) => ({ displayName: name, itemKey: name, icon: { page: 0, x: 0, y: 0 }, quantity: 1 });
    const provider = (key, name) => ({
        key,
        name,
        icon: stack(name),
        slots: [{ kind: 'pattern', pattern: { kind: 'processing', inputs: [stack('ore')], outputs: [stack('ingot')] } }]
    });
    let events;
    let reads = 0;
    const state = createPatterns(
        {
            async patternProviders() {
                reads++;
                return {
                    data: [provider('a', 'original'), provider('b', 'other')],
                    icons: icons('first'),
                    view: { generation: 'g', revision: '1' },
                    subscription: 'token'
                };
            },
            patternEvents(grid, subscription, withIcons, handlers) {
                events = handlers;
                return () => {};
            }
        },
        () => {},
        () => true,
        () => true
    );
    await state.load('grid');
    await state.load('grid', true);
    assert.equal(reads, 1);
    events.delta({
        view: { generation: 'g', revision: '2' },
        icons: icons('second'),
        removed: ['b'],
        providers: [provider('a', 'new')]
    });
    assert.deepEqual(
        state.state.providers.map((p) => p.name),
        ['new']
    );
    const updated = state.state.providers[0];
    assert.deepEqual(
        [updated.icon, ...updated.slots[0].pattern.inputs, ...updated.slots[0].pattern.outputs].map((s) => s.icon.page),
        [1, 1, 1]
    );
    assert.deepEqual(
        state.state.icons.pages.map((p) => p.digest),
        ['first', 'second']
    );
    events.delta({ view: { generation: 'g', revision: '1' }, icons: null, removed: ['a'], providers: [] });
    assert.equal(state.state.providers[0].key, 'a');
    state.clear();
    events.delta({
        view: { generation: 'g', revision: '3' },
        icons: null,
        removed: [],
        providers: [provider('c', 'stale')]
    });
    assert.deepEqual(state.state.providers, []);
});

test('invalidating a live view hides old recipes while a newly authorized snapshot is pending', async () => {
    const { createPatterns } = await import('../../build/generated/frontend/assets/web/app/patterns.js');
    let events;
    let resolveRefresh;
    let reads = 0;
    const patterns = createPatterns(
        {
            patternProviders: () => {
                if (reads++)
                    return new Promise((resolve) => {
                        resolveRefresh = resolve;
                    });
                return Promise.resolve({
                    data: [{ key: 'private-machine' }],
                    icons: { packId: 'old' },
                    view: { generation: 'first', revision: '1' },
                    subscription: 'token'
                });
            },
            patternEvents: (_grid, _token, _icons, handlers) => {
                events = handlers;
                return () => {};
            }
        },
        () => {},
        () => true,
        () => true
    );
    await patterns.load('private-grid');
    events.reset(); // revoked, invalidate and reset are all fresh-snapshot boundaries
    assert.equal(patterns.state.status, 'loading');
    assert.deepEqual(patterns.state.providers, []);
    assert.equal(patterns.state.icons, null);
    patterns.clear();
    resolveRefresh({ data: [], icons: null });
});
