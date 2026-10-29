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
test('parents center horizontally over child subtrees without overlapping siblings', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(0), row(1), row(2), row(2), row(1)]);
    const [root, first, a, b, second] = layout.nodes;
    assert.equal(first.x, (a.x + b.x) / 2);
    assert.equal(root.x, (first.x + second.x) / 2);
    assert.ok(a.x + a.width < b.x);
    assert.ok(b.x + b.width < second.x);
});
test('a window starting deep in the graph lays out from its own top edge', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(40), row(41), row(1)]);
    assert.equal(layout.nodes[0].y, layout.nodes[2].y);
    assert.equal(layout.nodes[0].parent, null);
    assert.equal(layout.nodes[1].parent, 0);
});
