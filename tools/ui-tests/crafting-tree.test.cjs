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

test('page-local animation indexes are merged without crossing sequences or pages', async () => {
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
                    width: 64,
                    height: 64,
                    pages: [{ digest: `atlas-${id}`, width: 128, height: 64 }],
                    animations: [
                        {
                            page: 0,
                            frames: [
                                { x: id, y: 0, durationMillis: 50 },
                                { x: 64, y: 0, durationMillis: 50 }
                            ]
                        }
                    ]
                };
                response.data.resources = [
                    { id, displayName: `resource-${id}`, icon: { page: 0, x: 0, y: 0, animation: 0 } },
                    { id: id + 10, displayName: `static-${id}`, icon: { page: 0, x: 0, y: 0 } }
                ];
                return response;
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('RECIPE:0');
    const { icons, resources } = tree.state;
    assert.equal(icons.animations.length, 2);
    for (const id of [0, 1]) {
        const animation = icons.animations[resources[id].icon.animation];
        assert.equal(animation.frames[0].x, id);
        assert.equal(icons.pages[animation.page].digest, `atlas-${id}`);
    }
    assert.equal(resources[10].icon.animation, undefined);
});

test('animation indexes stay valid after missing-only mode restores the full view tables', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const frames = new Map();
    let calls = 0;
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) => {
                const id = calls++;
                // The full view's first page and the missing-only pages differ; later full pages repeat the latter.
                const x = query.missingOnly || id > 0 ? 64 : 0;
                frames.set(id, x);
                const response = page(
                    query.kind === 'ROOT' ? node('RESOURCE', 0) : node('RECIPE', 1),
                    query.kind === 'ROOT'
                        ? [{ node: node('RECIPE', 1), relation: 'DECLARED_OUTPUT', quantity: '1' }]
                        : []
                );
                response.data.captureId = 'same';
                response.data.missingOnly = query.missingOnly;
                response.data.missingComplete = true;
                response.icons = {
                    packId: 'same',
                    width: 64,
                    height: 64,
                    pages: [{ digest: 'atlas', width: 128, height: 64 }],
                    animations: [
                        {
                            page: 0,
                            frames: [
                                { x, y: 0, durationMillis: 50 },
                                { x: 128 - x, y: 0, durationMillis: 50 }
                            ]
                        }
                    ]
                };
                response.data.resources = [
                    { id, displayName: `resource-${id}`, icon: { page: 0, x: 0, y: 0, animation: 0 } }
                ];
                return response;
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.setMissingOnly(true);
    await tree.setMissingOnly(false);
    await tree.toggle('RESOURCE:0');
    const { icons, resources } = tree.state;
    assert.ok(Object.keys(resources).length >= 2);
    for (const [id, resource] of Object.entries(resources))
        assert.equal(icons.animations[resource.icon.animation].frames[0].x, frames.get(Number(id)));
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

const keyOf = (value) => `${value.kind}:${value.id}`;
let nodeExtras = {};
const nodeOf = (key) => {
    const [kind, id] = key.split(':');
    return { ...node(kind, Number(id)), ...nodeExtras[key] };
};
const relationBelow = (parent) =>
    parent.startsWith('RECIPE:')
        ? 'INGREDIENT'
        : parent.startsWith('INGREDIENT:')
          ? 'DECLARED_ALTERNATIVE'
          : 'DECLARED_OUTPUT';
const edge = (key, parent = '') => ({ node: nodeOf(key), relation: relationBelow(parent), quantity: '1' });
const parseLoaded = (text = '') => {
    const keys = new Set();
    for (const group of text.split('|').filter(Boolean)) {
        const [kind, ranges] = group.split(':');
        for (const range of ranges.split(',')) {
            const [from, to = from] = range.split('-').map(Number);
            for (let id = from; id <= to; id++) keys.add(`${kind}:${id}`);
        }
    }
    return keys;
};

/**
 * An in-memory graph that answers like the tree endpoint: single pages, and batches that read the requested
 * nodes and then breadth-first the nodes they reference within a child-row budget, never repeating a node.
 */
function treeServer(adjacency, { header = {}, root = 'RESOURCE:0' } = {}) {
    const childrenOf = (key) => (typeof adjacency === 'function' ? adjacency(key) : (adjacency[key] ?? []));
    const requests = [];
    const delivered = new Set();
    const repeated = [];
    const envelope = (query, body) => ({
        data: {
            state: 'READY',
            captureId: 'capture',
            root: { resourceId: 0, quantity: '9007199254740993', simulated: false },
            partial: false,
            limits: [],
            unplacedCount: 0,
            unlinkedMissingCount: 0,
            missingOnly: !!query.missingOnly,
            missingComplete: false,
            missingResourceCount: 0,
            resources: [],
            ...header,
            ...body
        },
        icons: null
    });
    const server = {
        requests,
        repeated,
        envelope,
        rowsDelivered: 0,
        batches: () => requests.filter((request) => request.batch),
        api: {
            planTree: async (_g, _p, query) => {
                requests.push({ batch: false, query });
                const key = query.kind === 'ROOT' ? root : `${query.kind}:${query.id}`;
                const all = childrenOf(key);
                const end = Math.min(all.length, query.offset + query.limit);
                const children = all.slice(query.offset, end).map((child) => edge(child, key));
                server.rowsDelivered += children.length;
                return envelope(query, { node: nodeOf(key), children, nextOffset: end < all.length ? end : null });
            },
            planTreeBatch: async (_g, _p, query) => {
                requests.push({ batch: true, query, held: new Set(delivered) });
                const loaded = parseLoaded(query.loaded);
                const queue = query.nodes.split(',').map((token) => {
                    const [kind, id, offset = '0'] = token.split(':');
                    return { key: `${kind}:${id}`, offset: Number(offset) };
                });
                for (const start of queue) if (!start.offset && delivered.has(start.key)) repeated.push(start.key);
                const seen = new Set(queue.map((start) => start.key));
                const pages = [];
                let budget = query.rows;
                while (budget > 0 && queue.length) {
                    const start = queue.shift();
                    const all = childrenOf(start.key);
                    const end = Math.min(all.length, start.offset + budget);
                    const children = all.slice(start.offset, end).map((child) => edge(child, start.key));
                    pages.push({
                        node: nodeOf(start.key),
                        offset: start.offset,
                        children,
                        nextOffset: end < all.length ? end : null
                    });
                    if (end >= all.length) delivered.add(start.key);
                    server.rowsDelivered += children.length;
                    budget -= Math.max(1, children.length);
                    for (const next of children.map((child) => keyOf(child.node)))
                        if (!loaded.has(next) && !seen.has(next)) {
                            seen.add(next);
                            queue.push({ key: next, offset: 0 });
                        }
                }
                return envelope(query, { pages });
            }
        }
    };
    return server;
}

test('missing mode asks the server for paths, reveals them with bounds and restores full-view expansion', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const server = treeServer(
        { 'RESOURCE:0': ['RECIPE:1'] },
        { header: { captureId: 'same', missingComplete: true, missingResourceCount: 1 } }
    );
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('RESOURCE:0');
    await tree.setMissingOnly(true);
    assert.equal(tree.state.missingOnly, true);
    assert.ok(
        server.requests.some(
            (request) =>
                request.query.missingOnly && (request.query.id === 1 || request.query.nodes?.includes('RECIPE:1'))
        )
    );
    await tree.setMissingOnly(false);
    assert.equal(tree.state.expanded['RESOURCE:0'], false);
    assert.equal(tree.state.missingOnly, false);
});

test('automatic missing-path reveal loads a long chain in one batch and opens a bounded number of levels', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const kinds = ['RESOURCE', 'RECIPE', 'INGREDIENT'];
    const server = treeServer(
        (key) => {
            const id = Number(key.split(':')[1]);
            return id < 90 ? [`${kinds[(id + 1) % 3]}:${id + 1}`] : [];
        },
        { header: { captureId: 'bounded', missingComplete: true, missingResourceCount: 1 } }
    );
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.setMissingOnly(true);
    assert.equal(server.batches().length, 1);
    assert.ok(server.batches()[0].query.missingOnly);
    assert.ok(server.batches()[0].query.rows <= 300);
    assert.ok(tree.state.rows.some((row) => !tree.state.expanded[row.key]));
    assert.ok(tree.state.rows.length < 30);
    assert.ok(Object.keys(tree.state.pages).length > 60);
});

test('expand all reads the graph in a few batches, never asking again for a node it holds', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const server = treeServer(
        (key) =>
            key === 'RESOURCE:0'
                ? Array.from({ length: 305 }, (_, index) => `RECIPE:${index}`)
                : key.startsWith('RECIPE:')
                  ? ['RESOURCE:0']
                  : [],
        { header: { unplacedCount: 1, unlinkedMissingCount: 1 } }
    );
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.expandAll();
    assert.equal(tree.state.expanding, false);
    assert.equal(tree.state.expandedComplete, true);
    assert.equal(tree.state.rows.length, 300);
    assert.ok(tree.state.totalRows > 600);
    assert.equal(tree.state.expandedNodes, 308);
    // The fixture used to need one request per node: 314 requests for the same graph.
    assert.equal(server.requests.filter((request) => !request.batch).length, 1);
    assert.ok(server.batches().length <= 4, `batches: ${server.batches().length}`);
    assert.ok(server.batches().every((request) => request.query.rows <= 500));
    const tokens = server.batches().flatMap((request) => request.query.nodes.split(','));
    assert.ok(tokens.includes('RESOURCE:0:50'), 'the root page continues from its next offset');
    assert.ok(tokens.includes('UNPLACED:0') && tokens.includes('UNLINKED_MISSING:0'));
    assert.deepEqual(server.repeated, []);
    for (const request of server.batches().slice(1)) {
        const hinted = parseLoaded(request.query.loaded);
        for (const key of request.held)
            if (key !== 'UNPLACED:0' && key !== 'UNLINKED_MISSING:0') assert.ok(hinted.has(key));
    }
    await tree.toggle('RECIPE:0');
    assert.equal(tree.state.expandedComplete, false);
    tree.collapseAll();
    assert.equal(tree.state.rows.length, 3);
    assert.equal(tree.state.windowStart, 0);
    const loaded = server.requests.length;
    await tree.expandAll();
    assert.equal(server.requests.length, loaded);
    assert.equal(tree.state.expandedComplete, true);
    tree.dispose();
});

test('collapse all and stop fence pending expansion without late reopening', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const server = treeServer({ 'RESOURCE:0': ['RECIPE:1'] });
    let finish, signal;
    const tree = createCraftingTree(
        {
            planTree: server.api.planTree,
            planTreeBatch: async (_g, _p, query, s) => {
                signal = s;
                return new Promise((resolve) => {
                    finish = () =>
                        resolve(
                            server.envelope(query, {
                                pages: [
                                    {
                                        node: node('RECIPE', 1),
                                        offset: 0,
                                        children: [edge('RESOURCE:2')],
                                        nextOffset: null
                                    }
                                ]
                            })
                        );
                });
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    for (const collapse of [false, true]) {
        const expanding = tree.expandAll();
        while (!finish) await new Promise((resolve) => setTimeout(resolve, 1));
        if (collapse) tree.collapseAll();
        else tree.stopExpansion();
        assert.equal(signal.aborted, true);
        assert.equal(tree.state.expanding, false);
        finish();
        await expanding;
        assert.equal(tree.state.pages['RECIPE:1'], undefined);
        assert.equal(tree.state.expandedComplete, false);
        if (collapse) assert.equal(tree.state.rows.length, 1);
        finish = null;
    }
    tree.dispose();
});

test('a batch for a replaced plan capture discards the old tree and restarts from the root', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const server = treeServer({ 'RESOURCE:0': ['RECIPE:1'] });
    let replaced = false;
    const tree = createCraftingTree(
        {
            planTree: async (g, p, query, s) => {
                const response = await server.api.planTree(g, p, query, s);
                if (replaced) response.data.captureId = 'new';
                return response;
            },
            planTreeBatch: async (_g, _p, query) => {
                assert.equal(query.captureId, 'capture');
                replaced = true;
                throw Object.assign(new Error(), { status: 'UPDATE_CHANGED' });
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.expandAll();
    assert.equal(tree.state.captureId, 'new');
    assert.equal(tree.state.expanding, false);
    assert.equal(tree.state.error, null);
    assert.equal(server.requests.filter((request) => request.query.kind === 'ROOT').length, 2);
    assert.equal(tree.state.pages['RECIPE:1'], undefined);
    tree.dispose();
});

test('expand all stops at cache capacity and reports incomplete expansion', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    // Every resource declares forty producers, so the graph is far larger than the browser keeps.
    const server = treeServer(
        (key) =>
            key === 'UNPLACED:0'
                ? ['RESOURCE:999999999']
                : key.startsWith('RESOURCE:')
                  ? Array.from({ length: 40 }, (_, index) => `RESOURCE:${Number(key.split(':')[1]) * 40 + index + 1}`)
                  : [],
        { header: { unplacedCount: 1 } }
    );
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('UNPLACED:0');
    await tree.toggle('UNPLACED:0');
    await tree.expandAll();
    assert.equal(tree.state.cacheBounded, true);
    assert.equal(tree.state.expanding, false);
    assert.equal(tree.state.expandedComplete, false);
    assert.ok(server.rowsDelivered <= 3000, `rows: ${server.rowsDelivered}`);
    assert.ok(server.batches().length >= 5 && server.batches().length <= 8, `batches: ${server.batches().length}`);
    assert.ok(server.batches().every((request) => request.query.rows <= 500));
    assert.equal(tree.state.expanded['UNPLACED:0'], true);
    assert.ok(tree.state.totalRows > 300);
    const retainedRows = tree.state.totalRows;
    const requests = server.requests.length;
    tree.collapseAll();
    await tree.expandAll();
    assert.equal(server.requests.length, requests);
    assert.equal(tree.state.totalRows, retainedRows);
    assert.equal(tree.state.expandedComplete, false);
    tree.dispose();
});

test('a group with one declared candidate shows that candidate directly, without a group card or a flash of one', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    nodeExtras = { 'INGREDIENT:0': { candidates: 1, multiplier: '4' }, 'INGREDIENT:1': { candidates: 2 } };
    const server = treeServer({
        'RESOURCE:0': ['RECIPE:0'],
        'RECIPE:0': ['INGREDIENT:0', 'INGREDIENT:1'],
        'INGREDIENT:0': ['RESOURCE:1'],
        'INGREDIENT:1': ['RESOURCE:2', 'RESOURCE:3']
    });
    const published = [];
    const tree = createCraftingTree(server.api, () => published.push(tree.state.rows.map((row) => row.key)));
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('RECIPE:0');
    const layout = tree.state.rows.map((row) => [row.key, row.depth]);
    assert.deepEqual(layout, [
        ['RESOURCE:0', 0],
        ['RECIPE:0', 1],
        ['RESOURCE:1', 2],
        ['INGREDIENT:1', 2]
    ]);
    const direct = tree.state.rows[2];
    assert.equal(direct.relation, 'DECLARED_ALTERNATIVE');
    assert.equal(direct.quantity, '1');
    assert.equal(direct.group.multiplier, '4');
    assert.ok(published.every((keys) => !keys.includes('INGREDIENT:0')));
    assert.deepEqual(
        server.batches().map((request) => [request.query.nodes, request.query.rows]),
        [['INGREDIENT:0', 1]],
        'Only the single-candidate group is read ahead, with exactly its own row budget'
    );
    await tree.toggle('INGREDIENT:1');
    nodeExtras = {};
    assert.deepEqual(
        tree.state.rows.map((row) => row.key),
        ['RESOURCE:0', 'RECIPE:0', 'RESOURCE:1', 'INGREDIENT:1', 'RESOURCE:2', 'RESOURCE:3']
    );
});

test('a collapsed single candidate keeps its own expansion key and later occurrences stay references', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    nodeExtras = { 'INGREDIENT:0': { candidates: 1 }, 'INGREDIENT:1': { candidates: 1 } };
    const server = treeServer({
        'RESOURCE:0': ['RECIPE:0'],
        'RECIPE:0': ['INGREDIENT:0', 'INGREDIENT:1'],
        'INGREDIENT:0': ['RESOURCE:1'],
        'INGREDIENT:1': ['RESOURCE:1'],
        'RESOURCE:1': ['RECIPE:1']
    });
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle('RECIPE:0');
    assert.deepEqual(
        tree.state.rows.map((row) => [row.key, row.reference]),
        [
            ['RESOURCE:0', false],
            ['RECIPE:0', false],
            ['RESOURCE:1', false],
            ['RESOURCE:1', true]
        ]
    );
    await tree.toggle('RESOURCE:1');
    assert.equal(tree.state.expanded['RESOURCE:1'], true);
    assert.deepEqual(
        tree.state.rows.map((row) => row.key),
        ['RESOURCE:0', 'RECIPE:0', 'RESOURCE:1', 'RECIPE:1', 'RESOURCE:1']
    );
    nodeExtras = {};
});

test('expand all collapses single-candidate groups too', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    nodeExtras = { 'INGREDIENT:0': { candidates: 1 } };
    const server = treeServer({
        'RESOURCE:0': ['RECIPE:0'],
        'RECIPE:0': ['INGREDIENT:0'],
        'INGREDIENT:0': ['RESOURCE:1']
    });
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.expandAll();
    assert.deepEqual(
        tree.state.rows.map((row) => row.key),
        ['RESOURCE:0', 'RECIPE:0', 'RESOURCE:1']
    );
    nodeExtras = {};
});

/**
 * An in-memory TRACE plan, worked by hand: the machine request is half stored, half crafted by task 0, whose
 * gear request is crafted by task 1; task 1 also serves the later gear request R5, so it is shared.
 */
const traceNode = (kind, id, extra = {}) => ({
    kind,
    id,
    crafts: null,
    multiplier: null,
    complete: null,
    candidates: null,
    outputs: null,
    inputs: null,
    resourceId: null,
    contribution: null,
    ...extra
});
const traceResources = ['Machine', 'Plate', 'Gear', 'Ingot'].map((displayName, id) => ({
    id,
    displayName,
    icon: null,
    missingAmount: null
}));
const requestLink = (id, resourceId, quantity) => ({
    relation: 'CHILD_REQUEST',
    node: traceNode('REQUEST', id, { resourceId }),
    quantity
});
const leafLink = (id, contribution, quantity, resourceId) => ({
    relation: 'CONTRIBUTION',
    node: traceNode('CONTRIBUTION', id, { resourceId, contribution }),
    quantity
});
const craftLink = (task, quantity) => ({
    relation: 'CRAFT',
    node: traceNode('TASK', task, { crafts: '3', complete: true }),
    quantity
});
const traceAdjacency = {
    'REQUEST:0': [leafLink(0, 'STORAGE', '1', 0), craftLink(0, '3')],
    'TASK:0': [requestLink(1, 2, '6'), requestLink(2, 1, '6')],
    'REQUEST:1': [leafLink(2, 'STORAGE', '2', 2), craftLink(1, '4')],
    'REQUEST:2': [leafLink(4, 'EMITTED', '2', 1)],
    'TASK:1': [requestLink(4, 3, '3'), requestLink(5, 2, '3')],
    'REQUEST:4': [leafLink(8, 'MISSING', '2', 3), leafLink(9, 'UNKNOWN', '1', 3)],
    'REQUEST:5': [leafLink(10, 'REUSED', '1', 2), craftLink(1, '2')]
};
const traceMissing = {
    'REQUEST:0': [craftLink(0, '3')],
    'TASK:0': [requestLink(1, 2, '6')],
    'REQUEST:1': [craftLink(1, '4')],
    'TASK:1': [requestLink(4, 3, '3'), requestLink(5, 2, '3')],
    'REQUEST:4': [leafLink(8, 'MISSING', '2', 3)],
    'REQUEST:5': [craftLink(1, '2')]
};
function traceServer() {
    const requests = [];
    const adjacencyOf = (query, key) => (query.missingOnly ? traceMissing : traceAdjacency)[key] ?? [];
    const nodeFor = (key) => {
        const [kind, id] = key.split(':');
        return kind === 'REQUEST'
            ? traceNode('REQUEST', Number(id), { resourceId: Number(id) === 0 ? 0 : 2 })
            : traceNode(kind, Number(id), { crafts: '3', complete: true });
    };
    const envelope = (query, body) => ({
        data: {
            state: 'READY',
            mode: 'TRACE',
            captureId: 'trace',
            root: { resourceId: 0, quantity: '4', simulated: false },
            partial: false,
            limits: [],
            unplacedCount: 0,
            unlinkedMissingCount: 0,
            missingOnly: !!query.missingOnly,
            missingComplete: true,
            missingResourceCount: 1,
            resources: traceResources,
            ...body
        },
        icons: null
    });
    return {
        requests,
        api: {
            planTree: async (_g, _p, query) => {
                requests.push({ batch: false, query });
                const key = query.kind === 'ROOT' ? 'REQUEST:0' : `${query.kind}:${query.id}`;
                const all = adjacencyOf(query, key);
                const end = Math.min(all.length, query.offset + query.limit);
                return envelope(query, {
                    node: nodeFor(key),
                    children: all.slice(query.offset, end),
                    nextOffset: end < all.length ? end : null
                });
            },
            planTreeBatch: async (_g, _p, query) => {
                requests.push({ batch: true, query });
                const queue = query.nodes.split(',').map((token) => token.split(':').slice(0, 2).join(':'));
                const seen = new Set(queue);
                const pages = [];
                let budget = query.rows;
                while (budget > 0 && queue.length) {
                    const key = queue.shift();
                    const children = adjacencyOf(query, key);
                    pages.push({ node: nodeFor(key), offset: 0, children, nextOffset: null });
                    budget -= Math.max(1, children.length);
                    for (const next of children.map((child) => `${child.node.kind}:${child.node.id}`))
                        if (!next.startsWith('CONTRIBUTION:') && !seen.has(next)) {
                            seen.add(next);
                            queue.push(next);
                        }
                }
                return envelope(query, { pages });
            }
        }
    };
}
const requestedKeys = (server) =>
    server.requests.flatMap((request) =>
        request.batch
            ? request.query.nodes.split(',').map((token) => token.split(':').slice(0, 2).join(':'))
            : [`${request.query.kind}:${request.query.id}`]
    );

test('a trace is shown as such, expands to every occurrence once and renders a shared task as a reference', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const server = traceServer();
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    assert.equal(tree.state.mode, 'TRACE');
    assert.equal(tree.state.rows[0].key, 'REQUEST:0');
    assert.equal(tree.state.rows[0].quantity, '4');
    await tree.expandAll();
    assert.equal(tree.state.expandedComplete, true);
    assert.deepEqual(
        tree.state.rows.map((row) => row.key),
        [
            'REQUEST:0',
            'CONTRIBUTION:0',
            'TASK:0',
            'REQUEST:1',
            'CONTRIBUTION:2',
            'TASK:1',
            'REQUEST:4',
            'CONTRIBUTION:8',
            'CONTRIBUTION:9',
            'REQUEST:5',
            'CONTRIBUTION:10',
            'TASK:1',
            'REQUEST:2',
            'CONTRIBUTION:4'
        ]
    );
    const shared = tree.state.rows.filter((row) => row.key === 'TASK:1');
    assert.deepEqual(
        shared.map((row) => [row.reference, row.quantity]),
        [
            [false, '4'],
            [true, '2']
        ]
    );
    // Leaves are answers, not nodes to open: no page is ever requested for one, and they cannot be toggled.
    assert.ok(requestedKeys(server).every((key) => !key.startsWith('CONTRIBUTION:')));
    const before = server.requests.length;
    await tree.toggle('CONTRIBUTION:8');
    assert.equal(server.requests.length, before);
    assert.equal(tree.state.expanded['CONTRIBUTION:8'], undefined);
    tree.dispose();
});

test('missing-only on a trace reveals the paths to missing leaves and never tries to open a leaf', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const server = traceServer();
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    const opened = server.requests.length;
    await tree.setMissingOnly(true);
    assert.equal(tree.state.mode, 'TRACE');
    assert.ok(server.requests.slice(opened).every((request) => request.query.missingOnly));
    assert.ok(tree.state.rows.some((row) => row.key === 'CONTRIBUTION:8'));
    assert.ok(tree.state.rows.every((row) => row.key !== 'CONTRIBUTION:0' && row.key !== 'REQUEST:3'));
    assert.ok(requestedKeys(server).every((key) => !key.startsWith('CONTRIBUTION:')));
    assert.equal(tree.state.canRevealMissing, false);
    tree.dispose();
});

test('the mode of the latest response is kept until the tree is reset', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const server = traceServer();
    const tree = createCraftingTree(server.api, () => {});
    tree.reset('grid', 1);
    assert.equal(tree.state.mode, null);
    await tree.show(true);
    assert.equal(tree.state.mode, 'TRACE');
    tree.reset('grid', 2);
    assert.equal(tree.state.mode, null);
    tree.dispose();
});
