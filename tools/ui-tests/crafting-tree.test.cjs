const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/crafting-tree.js')
);
const node = (kind, id) => ({ kind, id, crafts: null, multiplier: null, complete: null });
const page = (parent, children = [], nextOffset = null) => ({
    data: {
        state: 'READY',
        root: { resourceId: 0, quantity: '9007199254740993', simulated: false },
        node: parent,
        children,
        nextOffset,
        partial: false,
        limits: [],
        unplacedCount: 0,
        resources: []
    },
    icons: null
});

test('tree loads only on open, reuses collapsed pages and stops cycles as references', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const calls = [];
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) => {
                calls.push(query);
                return query.kind === 'ROOT'
                    ? page(node('RESOURCE', 0), [
                          { node: node('RECIPE', 0), relation: 'DECLARED_OUTPUT', quantity: '4' }
                      ])
                    : page(node('RECIPE', 0), [
                          { node: node('RESOURCE', 0), relation: 'DECLARED_ALTERNATIVE', quantity: '1' }
                      ]);
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    assert.equal(calls.length, 0);
    await tree.show(true);
    assert.equal(tree.state.root.quantity, '9007199254740993');
    await tree.toggle('RECIPE:0');
    assert.equal(tree.state.rows.at(-1).reference, true);
    await tree.toggle('RECIPE:0');
    await tree.toggle('RECIPE:0');
    assert.equal(calls.length, 2);
    tree.dispose();
});

test('late tree pages cannot restore a departed or unauthorized plan', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let finish;
    let signal;
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, _q, s) => {
                signal = s;
                return new Promise((resolve) => {
                    finish = resolve;
                });
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    const pending = tree.show(true);
    tree.reset(null, null);
    assert.equal(signal.aborted, true);
    finish(page(node('RESOURCE', 0)));
    await pending;
    assert.equal(tree.state.rows.length, 0);
    assert.equal(tree.state.visible, false);
});

test('page continuation appends declared children without confusing partial capture with more pages', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const calls = [];
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) => {
                calls.push(query);
                const response = page(
                    node('RESOURCE', 0),
                    [{ node: node('RECIPE', query.offset), relation: 'DECLARED_OUTPUT', quantity: '9007199254740993' }],
                    query.offset === 0 ? 1 : null
                );
                response.data.partial = true;
                return response;
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.more('RESOURCE:0');
    assert.equal(calls[1].offset, 1);
    assert.equal(tree.state.pages['RESOURCE:0'].children.length, 2);
    assert.equal(tree.state.pages['RESOURCE:0'].nextOffset, null);
    assert.equal(tree.state.partial, true);
    assert.equal(tree.state.rows[1].quantity, '9007199254740993');
});

test('recycled plan identity discards old graph and requests a new root before merging', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let calls = 0;
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) => {
                calls++;
                if (calls === 2) {
                    assert.equal(query.captureId, 'old');
                    throw Object.assign(new Error(), { status: 'UPDATE_CHANGED' });
                }
                const response = page(
                    node('RESOURCE', 0),
                    calls === 1 ? [{ node: node('RECIPE', 7), relation: 'DECLARED_OUTPUT', quantity: '4' }] : []
                );
                response.data.captureId = calls === 1 ? 'old' : 'new';
                return response;
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('RECIPE:7');
    assert.equal(calls, 3);
    assert.equal(tree.state.captureId, 'new');
    assert.equal(tree.state.nodes['RECIPE:7'], undefined);
    assert.equal(tree.state.rows.length, 1);
});

test('page-local icon indexes are rebased while earlier resource icons stay correct', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let calls = 0;
    const tree = createCraftingTree(
        {
            planTree: async () => {
                const id = calls++;
                const response = page(node('RESOURCE', 0), [
                    { node: node('RECIPE', 0), relation: 'DECLARED_OUTPUT', quantity: '1' }
                ]);
                response.icons = {
                    packId: 'same',
                    width: 32,
                    height: 32,
                    pages: [{ digest: `atlas-${id}`, width: 32, height: 32 }]
                };
                response.data.resources = [{ id, displayName: `resource-${id}`, icon: { page: 0, x: 0, y: 0 } }];
                return response;
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('RECIPE:0');
    assert.equal(tree.state.icons.pages[tree.state.resources[0].icon.page].digest, 'atlas-0');
    assert.equal(tree.state.icons.pages[tree.state.resources[1].icon.page].digest, 'atlas-1');
});

test('large fanout is bounded in the visible projection without duplicating the entire graph', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) =>
                page(
                    node('RESOURCE', 0),
                    Array.from({ length: 50 }, (_, index) => ({
                        node: node('RECIPE', query.offset + index),
                        relation: 'DECLARED_OUTPUT',
                        quantity: '1'
                    })),
                    query.offset + 50
                )
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    for (let index = 0; index < 6; index++) await tree.more('RESOURCE:0');
    assert.ok(tree.state.rows.length <= 300);
    assert.equal(tree.state.bounded, true);
    await tree.toggle('RESOURCE:0');
    assert.equal(tree.state.rows.length, 1);
    assert.equal(tree.state.bounded, false);
});

test('revoked access clears cached private graph and ignores later expansion', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let first = true;
    const tree = createCraftingTree(
        {
            planTree: async () => {
                if (!first) throw Object.assign(new Error(), { status: 'NO_PERMISSIONS' });
                first = false;
                return page(node('RESOURCE', 0), [
                    { node: node('RECIPE', 0), relation: 'DECLARED_OUTPUT', quantity: '4' }
                ]);
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('RECIPE:0');
    assert.equal(tree.state.status, 'ERROR');
    assert.equal(tree.state.error, 'NO_PERMISSIONS');
    assert.equal(tree.state.rows.length, 0);
    assert.deepEqual(tree.state.resources, {});
    assert.deepEqual(tree.state.pages, {});
});

test('wide branches remain navigable beyond the visible window and references can focus earlier nodes', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) =>
                page(
                    node('RESOURCE', 0),
                    Array.from({ length: 50 }, (_, index) => ({
                        node: node('RECIPE', query.offset + index),
                        relation: 'DECLARED_OUTPUT',
                        quantity: '1'
                    })),
                    query.offset + 50
                )
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    for (let index = 0; index < 6; index++) await tree.more('RESOURCE:0');
    tree.moveWindow(1);
    assert.ok(tree.state.rows.some((row) => row.key === 'RECIPE:349'));
    assert.ok(tree.state.rows.length <= 300);
    tree.focus('RESOURCE:0');
    assert.equal(tree.state.rows[0].key, 'RESOURCE:0');
});

test('reopening a cached tree revalidates access before continuing cached navigation', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let allowed = true;
    const tree = createCraftingTree(
        {
            planTree: async () => {
                if (!allowed) throw Object.assign(new Error(), { status: 'INVALID_ID' });
                const response = page(node('RESOURCE', 0));
                response.data.captureId = 'original';
                return response;
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.show(false);
    allowed = false;
    await tree.show(true);
    assert.equal(tree.state.error, 'INVALID_ID');
    assert.equal(tree.state.rows.length, 0);
});

test('browser cache admission stops requests at its limit and explicit retry clears it', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let calls = 0;
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) => {
                calls++;
                return page(
                    node('RESOURCE', 0),
                    Array.from({ length: query.limit }, (_, index) => ({
                        node: node('RECIPE', query.offset + index),
                        relation: 'DECLARED_OUTPUT',
                        quantity: '1'
                    })),
                    query.offset + query.limit
                );
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    for (let index = 0; index < 80; index++) await tree.more('RESOURCE:0');
    assert.equal(tree.state.cacheBounded, true);
    const before = calls;
    await tree.more('RESOURCE:0');
    assert.equal(calls, before);
    await tree.retry();
    assert.equal(calls, before + 1);
    assert.equal(tree.state.cacheBounded, false);
    assert.ok(tree.state.rows.length < 100);
});

test('focusing an already visible shared node preserves the window and its ancestors', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const tree = createCraftingTree(
        {
            planTree: async () =>
                page(node('RESOURCE', 0), [{ node: node('RECIPE', 0), relation: 'DECLARED_OUTPUT', quantity: '4' }])
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    tree.focus('RECIPE:0');
    assert.equal(tree.state.windowStart, 0);
    assert.equal(tree.state.rows[0].key, 'RESOURCE:0');
});

test('expired first request publishes an error revision newer than its loading revision', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const seen = [];
    const tree = createCraftingTree(
        {
            planTree: async () => {
                throw Object.assign(new Error(), { status: 'INVALID_ID' });
            }
        },
        () => seen.push({ revision: tree.state.revision, status: tree.state.status })
    );
    tree.reset('grid', 1);
    await tree.show(true);
    assert.equal(seen.at(-1).status, 'ERROR');
    assert.ok(seen.at(-1).revision > seen[0].revision);
});
