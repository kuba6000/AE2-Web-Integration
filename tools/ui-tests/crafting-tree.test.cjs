const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/crafting-tree.js')
);

const node = (id, kind, extra = {}) => ({
    id,
    kind,
    resourceId: null,
    recipeId: null,
    crafts: null,
    multiplier: null,
    complete: null,
    candidates: null,
    contribution: null,
    children: [],
    ...extra
});
const edge = (nodeId, relation, quantity, missing = false) => ({
    nodeId,
    relation,
    quantity,
    primary: null,
    missing
});
function document() {
    return {
        state: 'READY',
        mode: 'TRACE',
        captureId: 'capture',
        root: { resourceId: 0, quantity: '4', simulated: false },
        roots: [0],
        resources: [
            { id: 0, displayName: 'Machine', icon: null, missingAmount: '0' },
            { id: 1, displayName: 'Gear', icon: null, missingAmount: '2' }
        ],
        recipes: [{ id: 0, inputs: [], outputs: [{ resourceId: 0, quantity: '1', primary: true }] }],
        nodes: [
            node(0, 'REQUEST', {
                resourceId: 0,
                children: [edge(1, 'CONTRIBUTION', '1'), edge(2, 'CRAFT', '3', true)]
            }),
            node(1, 'CONTRIBUTION', { resourceId: 0, contribution: 'STORAGE' }),
            node(2, 'TASK', {
                recipeId: 0,
                crafts: '3',
                complete: true,
                children: [edge(3, 'CHILD_REQUEST', '6', true), edge(4, 'CHILD_REQUEST', '2', true)]
            }),
            node(3, 'REQUEST', { resourceId: 1, children: [edge(5, 'CONTRIBUTION', '2', true)] }),
            node(4, 'REQUEST', { resourceId: 1, children: [edge(2, 'CRAFT', '2', true)] }),
            node(5, 'CONTRIBUTION', { resourceId: 1, contribution: 'MISSING' })
        ],
        partial: false,
        limits: [],
        missingComplete: true,
        missingResourceCount: 1
    };
}

test('one capture supports local expansion and missing-path filtering, including shared references', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const calls = [];
    const tree = createCraftingTree(
        {
            planTree: async (_grid, _plan, query) => {
                calls.push(query);
                return { data: document(), icons: null };
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    assert.equal(calls.length, 0);
    await tree.show(true);
    await tree.expandAll();
    assert.equal(calls.length, 1);
    assert.equal(tree.state.rows.filter((row) => row.node.id === 2).length, 2);
    assert.equal(tree.state.rows.findLast((row) => row.node.id === 2).reference, true);
    const fullExpansion = { ...tree.state.expanded };
    await tree.setMissingOnly(true);
    assert.equal(calls.length, 1);
    assert.ok(tree.state.rows.some((row) => row.node.id === 5));
    assert.ok(tree.state.rows.every((row) => row.node.id !== 1));
    await tree.setMissingOnly(false);
    assert.deepEqual(tree.state.expanded, fullExpansion);
    assert.equal(calls.length, 1);
    tree.dispose();
});

test('reopening revalidates the capture, retaining expansion and response-local icon references', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const calls = [];
    const icons = (first, second, page) => ({
        packId: 'pack',
        width: 32,
        height: 32,
        pages: [
            { digest: first, width: 32, height: 32 },
            { digest: second, width: 32, height: 32 }
        ],
        animations: [{ page, frames: [{ x: 0, y: 0, durationMillis: 100 }] }]
    });
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) => {
                calls.push(query);
                const data = document();
                data.resources[1].icon = { page: calls.length === 1 ? 0 : 1, x: 0, y: 0, animation: 0 };
                return { data, icons: calls.length === 1 ? icons('gear', 'machine', 0) : icons('machine', 'gear', 1) };
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    const initialIcon = tree.state.resources[1].icon;
    assert.equal(tree.state.icons.pages[initialIcon.page].digest, 'gear');
    assert.equal(tree.state.icons.animations[initialIcon.animation].page, initialIcon.page);
    await tree.expandAll();
    const expanded = { ...tree.state.expanded };
    await tree.show(false);
    await tree.show(true);
    assert.equal(calls[1].captureId, 'capture');
    assert.deepEqual(tree.state.expanded, expanded);
    assert.equal(tree.state.expandedComplete, true);
    const icon = tree.state.resources[1].icon;
    assert.equal(tree.state.icons.pages[icon.page].digest, 'gear');
    assert.equal(tree.state.icons.animations[icon.animation].page, 1);
    tree.dispose();
});

test('changed capture restarts without old data, and authorization failures clear the capture', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const calls = [];
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, query) => {
                calls.push(query);
                if (calls.length === 2) throw Object.assign(new Error(), { status: 'UPDATE_CHANGED' });
                if (calls.length === 4) throw Object.assign(new Error(), { status: 'NO_PERMISSIONS' });
                const data = document();
                if (calls.length === 3) {
                    data.captureId = 'new';
                    data.nodes = [node(8, 'REQUEST', { resourceId: 0 })];
                    data.roots = [8];
                }
                return { data, icons: null };
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.expandAll();
    await tree.show(false);
    await tree.show(true);
    assert.equal(calls[1].captureId, 'capture');
    assert.equal(calls[2].captureId, undefined);
    assert.equal(tree.state.captureId, 'new');
    assert.deepEqual(
        tree.state.rows.map((row) => row.node.id),
        [8]
    );
    assert.equal(tree.state.nodes['TASK:2'], undefined);
    await tree.show(false);
    await tree.show(true);
    assert.equal(tree.state.status, 'ERROR');
    assert.equal(tree.state.error, 'NO_PERMISSIONS');
    assert.equal(tree.state.captureId, null);
    assert.deepEqual(tree.state.rows, []);
    tree.dispose();
});

test('reset aborts an in-flight response and fences a late result', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let finish, signal;
    const tree = createCraftingTree(
        {
            planTree: async (_g, _p, _q, current) => {
                signal = current;
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
    finish({ data: document(), icons: null });
    await pending;
    assert.equal(tree.state.visible, false);
    assert.equal(tree.state.captureId, null);
    assert.deepEqual(tree.state.rows, []);
    tree.dispose();
});

test('large captures expand locally and expose every expanded row', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const data = document();
    data.nodes = [
        node(0, 'REQUEST', {
            resourceId: 0,
            children: Array.from({ length: 3400 }, (_, i) => edge(i + 1, 'CHILD_REQUEST', '1', true))
        }),
        ...Array.from({ length: 3400 }, (_, i) => node(i + 1, 'REQUEST', { resourceId: 1 }))
    ];
    let requests = 0;
    const tree = createCraftingTree(
        {
            planTree: async () => {
                requests++;
                return { data, icons: null };
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    const pending = tree.expandAll();
    tree.stopExpansion();
    await pending;
    assert.equal(tree.state.expandedComplete, false);
    const collapsing = tree.expandAll();
    tree.collapseAll();
    await collapsing;
    assert.deepEqual(tree.state.expanded, {});
    assert.equal(tree.state.rows.length, 1);
    await tree.expandAll();
    assert.equal(tree.state.expandedComplete, true);
    assert.equal(tree.state.expandedNodes, 3401);
    assert.equal(tree.state.rows.length, 3401);
    assert.equal(tree.state.rows.at(-1).node.id, 3400);
    assert.equal(requests, 1);
    tree.dispose();
});

test('missing roots, including synthetic groups, require relevant edges or a positive missing amount', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const data = document();
    data.roots = [0, 6, 7];
    data.nodes[0].children = [];
    data.nodes.push(node(6, 'UNLINKED_MISSING', { children: [edge(5, 'MISSING_RESOURCE', '2', true)] }));
    data.nodes.push(node(7, 'UNPLACED'));
    const tree = createCraftingTree({ planTree: async () => ({ data, icons: null }) }, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.setMissingOnly(true);
    assert.deepEqual(
        tree.state.rows.map((row) => row.node.id),
        [6, 5]
    );
    await tree.setMissingOnly(false);
    data.resources[0].missingAmount = '1';
    await tree.show(false);
    await tree.show(true);
    await tree.setMissingOnly(true);
    assert.deepEqual(
        tree.state.rows.map((row) => row.node.id),
        [0, 6, 5]
    );
    tree.dispose();
});

test('too-large and partial captures remain explicit without polling', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const data = document();
    data.state = 'TOO_LARGE';
    data.captureId = null;
    data.mode = null;
    data.root = null;
    data.roots = [];
    data.resources = [];
    data.recipes = [];
    data.nodes = [];
    let calls = 0;
    const tree = createCraftingTree(
        {
            planTree: async () => {
                calls++;
                return { data, icons: null };
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    assert.equal(tree.state.status, 'TOO_LARGE');
    await new Promise((resolve) => setTimeout(resolve, 1100));
    assert.equal(calls, 1);
    data.state = 'READY';
    data.partial = true;
    await tree.show(false);
    await tree.show(true);
    assert.equal(tree.state.partial, true);
    tree.dispose();
});

test('selecting missing paths during preparation reveals them when the capture arrives', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let finish;
    const tree = createCraftingTree(
        {
            planTree: async () =>
                new Promise((resolve) => {
                    finish = resolve;
                })
        },
        () => {}
    );
    tree.reset('grid', 1);
    const loading = tree.show(true);
    await tree.setMissingOnly(true);
    finish({ data: document(), icons: null });
    await loading;
    assert.ok(tree.state.rows.some((row) => row.node.id === 5));
    assert.ok(tree.state.rows.every((row) => row.node.id !== 1));
    await tree.setMissingOnly(false);
    assert.ok(tree.state.rows.some((row) => row.node.id === 1));
    tree.dispose();
});

test('a preparing response with the eventual capture ID initializes and reveals its first ready document', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    let calls = 0;
    const tree = createCraftingTree(
        {
            planTree: async () => {
                calls++;
                const data = document();
                if (calls === 1) {
                    data.state = 'PREPARING';
                    data.mode = null;
                    data.root = null;
                    data.roots = [];
                    data.resources = [];
                    data.recipes = [];
                    data.nodes = [];
                }
                return { data, icons: null };
            }
        },
        () => {}
    );
    tree.reset('grid', 1);
    await tree.show(true);
    assert.equal(tree.state.captureId, 'capture');
    await tree.setMissingOnly(true);
    await tree.retry();
    assert.equal(tree.state.status, 'READY');
    assert.ok(tree.state.rows.some((row) => row.node.id === 5));
    await tree.setMissingOnly(false);
    assert.ok(tree.state.rows.some((row) => row.node.id === 1));
    await new Promise((resolve) => setTimeout(resolve, 1100));
    assert.equal(calls, 2);
    tree.dispose();
});

test('reset then reopening the same retained capture initializes its root again', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const tree = createCraftingTree({ planTree: async () => ({ data: document(), icons: null }) }, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    tree.reset(null, null);
    tree.reset('grid', 1);
    await tree.show(true);
    assert.ok(tree.state.rows.some((row) => row.node.id === 1));
    tree.dispose();
});

test('switching filters retains each view’s own expansion choices', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const tree = createCraftingTree({ planTree: async () => ({ data: document(), icons: null }) }, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.expandAll();
    const full = { ...tree.state.expanded };
    await tree.setMissingOnly(true);
    await tree.toggle(tree.state.rows.find((row) => row.node.id === 2).key);
    const missing = { ...tree.state.expanded };
    await tree.setMissingOnly(false);
    assert.deepEqual(tree.state.expanded, full);
    await tree.setMissingOnly(true);
    assert.deepEqual(tree.state.expanded, missing);
    assert.ok(tree.state.rows.every((row) => row.node.id !== 5));
    tree.dispose();
});

test('a single-candidate ingredient displays its resource directly and repeated resources remain references', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const data = document();
    data.mode = 'AGGREGATE';
    data.roots = [0];
    data.nodes = [
        node(0, 'RESOURCE', { resourceId: 0, children: [edge(1, 'DECLARED_OUTPUT', '1')] }),
        node(1, 'RECIPE', { recipeId: 0, children: [edge(2, 'INGREDIENT', null), edge(3, 'INGREDIENT', null)] }),
        node(2, 'INGREDIENT', { candidates: 1, multiplier: '4', children: [edge(4, 'DECLARED_ALTERNATIVE', '3')] }),
        node(3, 'INGREDIENT', { candidates: 1, multiplier: '1', children: [edge(4, 'DECLARED_ALTERNATIVE', '1')] }),
        node(4, 'RESOURCE', { resourceId: 1 })
    ];
    const published = [];
    const tree = createCraftingTree({ planTree: async () => ({ data, icons: null }) }, () => {
        published.push(tree.state.rows.map((row) => row.node.kind));
    });
    tree.reset('grid', 1);
    await tree.show(true);
    await tree.toggle(tree.state.rows.find((row) => row.node.id === 1).key);
    assert.deepEqual(
        tree.state.rows.map((row) => row.node.id),
        [0, 1, 4, 4]
    );
    assert.deepEqual(
        tree.state.rows.slice(2).map((row) => row.reference),
        [false, true]
    );
    assert.equal(tree.state.rows[2].group.multiplier, '4');
    assert.equal(tree.state.rows[2].quantity, '3');
    assert.ok(published.every((kinds) => !kinds.includes('INGREDIENT')));
    tree.dispose();
});

test('shared nodes link to their original occurrence across a large expanded plane', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const data = document();
    data.nodes = [
        node(0, 'REQUEST', {
            children: [
                ...Array.from({ length: 350 }, (_, i) => edge(i + 1, 'CHILD_REQUEST', '1')),
                edge(10, 'CHILD_REQUEST', '2')
            ]
        }),
        ...Array.from({ length: 350 }, (_, i) => node(i + 1, 'REQUEST', { resourceId: 1 }))
    ];
    const tree = createCraftingTree({ planTree: async () => ({ data, icons: null }) }, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    const matches = tree.state.rows.filter((row) => row.node.id === 10);
    assert.equal(matches.length, 2);
    assert.equal(matches[1].referenceTarget, matches[0].key);
    assert.equal(matches[1].cycle, false);
    tree.dispose();
});

test('separate requests for the same resource expand while shared nodes and cycles are identified', async () => {
    const { createCraftingTree } = await import(moduleUrl);
    const data = document();
    data.nodes = [
        node(0, 'REQUEST', { children: [edge(1, 'CHILD_REQUEST', '1'), edge(3, 'CHILD_REQUEST', '2')] }),
        node(1, 'REQUEST', { resourceId: 1, children: [edge(2, 'CRAFT', '1')] }),
        node(2, 'TASK', { children: [edge(1, 'CHILD_REQUEST', '1')] }),
        node(3, 'REQUEST', { resourceId: 1, children: [edge(2, 'CRAFT', '2')] })
    ];
    const tree = createCraftingTree({ planTree: async () => ({ data, icons: null }) }, () => {});
    tree.reset('grid', 1);
    await tree.show(true);
    const siblings = tree.state.rows.filter((row) => row.node.resourceId === 1);
    assert.equal(siblings.length, 2);
    assert.ok(siblings.every((row) => !row.reference));
    assert.notEqual(siblings[0].key, siblings[1].key);
    await tree.toggle(siblings[0].key);
    const task = tree.state.rows.find((row) => row.node.id === 2);
    await tree.toggle(task.key);
    const cycle = tree.state.rows.find((row) => row.node.id === 1 && row.depth === 3);
    assert.equal(cycle.cycle, true);
    await tree.toggle(siblings[1].key);
    const shared = tree.state.rows.findLast((row) => row.node.id === 2);
    assert.equal(shared.reference, true);
    assert.equal(shared.cycle, false);
    assert.equal(shared.referenceTarget, task.key);
    tree.dispose();
});
