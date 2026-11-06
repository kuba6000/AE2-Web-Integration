const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const app = (name) =>
    pathToFileURL(path.resolve(__dirname, `../../build/generated/frontend/assets/web/app/${name}.js`));

test('plan tree URLs survive parsing and view navigation', async (t) => {
    const original = global.location;
    global.location = { hash: '#/grids/a%20b/plans/12/tree' };
    t.after(() => {
        global.location = original;
    });
    const { readRoute, navigateToPlan } = await import(app('router'));
    assert.deepEqual(readRoute(), { view: 'plan', gridKey: 'a b', planId: '12', tree: true });
    navigateToPlan('a b', 12, false);
    assert.equal(global.location.hash, '/grids/a%20b/plans/12');
    assert.equal(readRoute().view, 'plan');
    assert.equal(!!readRoute().tree, false);
    navigateToPlan('a b', 12, true);
    assert.equal(global.location.hash, '/grids/a%20b/plans/12/tree');
});

test('same-plan history navigation preserves state and revalidates the retained capture', async (t) => {
    const original = global.location;
    global.location = { hash: '' };
    const { createCrafting } = await import(app('crafting'));
    let treeReads = 0;
    let expired = false;
    const crafting = createCrafting(
        {
            plan: async () => ({
                icons: null,
                data: {
                    isDone: false,
                    finalOutput: { itemKey: 'iron', displayName: 'Iron', quantity: 1 },
                    options: { lightMode: false, ignoreMissing: false },
                    autoStart: false
                }
            }),
            planTree: async () => {
                treeReads++;
                if (expired) throw { status: 'INVALID_ID' };
                return {
                    icons: null,
                    data: {
                        state: 'READY',
                        mode: 'TRACE',
                        captureId: 'capture',
                        root: null,
                        roots: [],
                        nodes: [],
                        resources: [],
                        recipes: [],
                        limits: [],
                        partial: false,
                        missingComplete: true,
                        missingResourceCount: 0
                    }
                };
            }
        },
        () => {},
        () => false,
        { values: { planSort: 'name' } },
        () => {}
    );
    t.after(() => {
        crafting.dispose();
        global.location = original;
    });
    const route = { view: 'plan', gridKey: 'grid', planId: '12', tree: true };
    crafting.route(route);
    await new Promise(setImmediate);
    assert.equal(crafting.state.tree.visible, true);
    assert.equal(crafting.state.tree.status, 'READY');
    crafting.search('retain this');
    crafting.route({ ...route, tree: false });
    assert.equal(crafting.state.tree.visible, false);
    crafting.route(route);
    await new Promise(setImmediate);
    assert.equal(crafting.state.tree.visible, true);
    assert.equal(crafting.state.search, 'retain this');
    assert.equal(treeReads, 2);
    crafting.block('NETWORK_ERROR');
    await crafting.refresh();
    await new Promise(setImmediate);
    assert.equal(crafting.state.tree.status, 'READY');
    assert.equal(crafting.state.tree.visible, true);
    assert.equal(treeReads, 3);
    expired = true;
    crafting.route({ ...route, tree: false });
    crafting.route(route);
    await new Promise(setImmediate);
    assert.equal(treeReads, 4);
    assert.equal(crafting.state.tree.status, 'ERROR');
    assert.equal(crafting.state.tree.error, 'INVALID_ID');
    assert.equal(crafting.state.tree.captureId, null);
    assert.deepEqual(crafting.state.tree.rows, []);
    await crafting.showTree(false);
    assert.equal(global.location.hash, '/grids/grid/plans/12');
});

test('switching plan views preserves an in-flight plan read', async (t) => {
    const { createCrafting } = await import(app('crafting'));
    let finish;
    let signal;
    let reads = 0;
    const crafting = createCrafting(
        {
            plan: (_grid, _id, pendingSignal) => {
                reads++;
                signal = pendingSignal;
                return new Promise((resolve) => {
                    finish = resolve;
                });
            },
            planTree: async () => ({
                icons: null,
                data: {
                    state: 'UNAVAILABLE',
                    mode: null,
                    captureId: null,
                    root: null,
                    roots: [],
                    nodes: [],
                    resources: [],
                    recipes: [],
                    limits: [],
                    partial: false,
                    missingComplete: false,
                    missingResourceCount: 0
                }
            })
        },
        () => {},
        () => false,
        { values: { planSort: 'name' } },
        () => {}
    );
    t.after(() => crafting.dispose());
    crafting.route({ view: 'plan', gridKey: 'grid', planId: '12' });
    const pending = crafting.refresh();
    crafting.route({ view: 'plan', gridKey: 'grid', planId: '12', tree: true });
    finish({
        icons: null,
        data: {
            isDone: false,
            finalOutput: { itemKey: 'iron', displayName: 'Iron', quantity: 1 },
            options: { lightMode: false, ignoreMissing: false },
            autoStart: false
        }
    });
    await pending;
    assert.equal(signal.aborted, false);
    assert.equal(reads, 1);
    assert.equal(crafting.state.status, 'calculating');
    assert.equal(crafting.state.plan.finalOutput.itemKey, 'iron');
});
