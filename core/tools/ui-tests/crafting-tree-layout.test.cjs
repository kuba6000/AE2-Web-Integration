const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/themes/default/crafting-tree-layout.js')
);
const row = (depth) => ({ depth, node: { kind: 'RESOURCE' } });
test('a linear dependency chain grows downwards in one column', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(0), row(1), row(2), row(3)]);
    assert.ok(layout.nodes.every((node) => node.x === layout.nodes[0].x));
    assert.ok(layout.nodes.slice(1).every((node, index) => node.y > layout.nodes[index].y));
});
test('parents align with their first child without overlapping siblings', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(0), row(1), row(2), row(2), row(1)]);
    const [root, first, a, b, second] = layout.nodes;
    assert.equal(first.x, a.x);
    assert.equal(root.x, first.x);
    assert.ok(a.x + a.width < b.x);
    assert.ok(b.x + b.width < second.x);
});
test('independent roots start at the top edge', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(40), row(41), row(1)]);
    assert.equal(layout.nodes[0].y, layout.nodes[2].y);
    assert.equal(layout.nodes[0].parent, null);
    assert.equal(layout.nodes[1].parent, 0);
});
test('request, task and contribution cards of a trace share the tree layout without overlapping', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const typed = (depth, kind) => ({ depth, node: { kind } });
    const layout = layoutCraftingTree([
        typed(0, 'REQUEST'),
        typed(1, 'CONTRIBUTION'),
        typed(1, 'TASK'),
        typed(2, 'REQUEST'),
        typed(2, 'REQUEST')
    ]);
    const [root, storage, task, first, second] = layout.nodes;
    assert.ok(layout.nodes.every((position) => position.width > 0));
    assert.equal(storage.parent, 0);
    assert.equal(task.parent, 0);
    assert.equal(first.parent, 2);
    assert.ok(storage.x + storage.width < first.x, 'the leaf does not sit on top of the task subtree');
    assert.ok(first.x + first.width < second.x);
    assert.equal(task.x + task.width / 2, first.x + first.width / 2);
    assert.ok(root.y < task.y && task.y < first.y);
});

test('compact geometry aligns parents with the first child and leaves earlier branches fixed on expansion', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const compact = (rows) => layoutCraftingTree(rows, 'icons');
    const before = compact([row(0), row(1), row(1)]);
    const after = compact([row(0), row(1), row(1), row(2), row(2)]);
    assert.equal(before.nodes[0].x, before.nodes[1].x);
    assert.equal(after.nodes[2].x, after.nodes[3].x);
    assert.equal(before.nodes[1].x, after.nodes[1].x);
    assert.equal(before.nodes[2].x, after.nodes[2].x);
    assert.ok(after.nodes.every((node) => node.width <= 72 && node.height <= 72));
    assert.ok(after.nodes[3].x + after.nodes[3].width < after.nodes[4].x);
});

test('text-mode recipe cards share resource height but remain narrower and leave connection space', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const rows = [row(0), { depth: 1, node: { kind: 'TASK' } }, row(2)];
    for (const display of ['both', 'names']) {
        const { nodes } = layoutCraftingTree(rows, display);
        assert.equal(nodes[1].height, nodes[0].height);
        assert.ok(nodes[1].width < nodes[0].width);
        assert.ok(nodes[0].height <= 80);
        assert.ok(nodes[1].y + nodes[1].height < nodes[2].y);
    }
});

test('viewport selection keeps crossing connections without mounting offscreen endpoint cards', async () => {
    const { treeViewport } = await import(moduleUrl);
    const nodes = [
        { x: 0, y: 0, width: 64, height: 64, parent: null },
        { x: 1000, y: 300, width: 64, height: 64, parent: 0 },
        { x: 4000, y: 300, width: 64, height: 64, parent: null }
    ];
    const visible = treeViewport(nodes, { x: 400, y: 260, width: 200, height: 50 });
    assert.deepEqual(visible.cards, []);
    assert.deepEqual(visible.connections, [1]);
    assert.deepEqual(treeViewport(nodes, { x: 1000, y: 300, width: 100, height: 100 }).cards, [1]);
    assert.deepEqual(treeViewport(nodes, { x: 400, y: 100, width: 200, height: 50 }).connections, []);
});
test('overview artwork grows relative to the tree without escaping its card', async () => {
    const { treeOverviewGeometry } = await import(moduleUrl);
    const node = { x: 20, y: 30, width: 240, height: 72 };
    const overview = treeOverviewGeometry(node, 0.3);
    assert.ok(overview.iconSize > 32);
    assert.ok(overview.fontSize > 14);
    assert.ok(overview.iconSize < node.height);
    assert.equal(overview.iconX + overview.iconSize / 2, node.x + node.width / 2);
    assert.equal(overview.iconY + overview.iconSize / 2, node.y + node.height / 2);
    assert.ok(treeOverviewGeometry(node, 0.2).fontSize > overview.fontSize);
    assert.equal(treeOverviewGeometry(node, 0.01), null);
});
