const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/themes/default/crafting-tree-layout.js')
);
const row = (depth) => ({ depth, node: { kind: 'RESOURCE' } });
test('a linear dependency chain shares one vertical row', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(0), row(1), row(2), row(3)]);
    assert.ok(layout.nodes.every((node) => node.y === layout.nodes[0].y));
    assert.ok(layout.nodes.slice(1).every((node, index) => node.x > layout.nodes[index].x));
});
test('parents center between visible child subtrees and sibling cards do not overlap', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(0), row(1), row(2), row(2), row(1)]);
    const [root, first, a, b, second] = layout.nodes;
    assert.equal(first.y, (a.y + b.y) / 2);
    assert.equal(root.y, (first.y + second.y) / 2);
    assert.ok(a.y + a.height < b.y);
    assert.ok(b.y + b.height < second.y);
});
test('a window starting deep in the graph lays out from its own left edge', async () => {
    const { layoutCraftingTree } = await import(moduleUrl);
    const layout = layoutCraftingTree([row(40), row(41), row(1)]);
    assert.equal(layout.nodes[0].x, layout.nodes[2].x);
    assert.equal(layout.nodes[0].parent, null);
    assert.equal(layout.nodes[1].parent, 0);
});
