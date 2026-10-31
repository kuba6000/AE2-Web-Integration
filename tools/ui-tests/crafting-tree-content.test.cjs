const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/themes/default/crafting-tree-content.js')
);
// Keys come back unchanged: the tests check which facts a card states, not their wording.
const locale = { common: (key) => key };
const node = (kind, id, extra = {}) => ({
    kind,
    id,
    crafts: null,
    multiplier: null,
    complete: null,
    candidates: null,
    outputs: null,
    ...extra
});
const state = {
    resources: {
        0: { displayName: 'Plate', missingAmount: '0' },
        1: { displayName: '§aSlag', missingAmount: '0' }
    }
};
const row = (nodeValue, extra = {}) => ({
    key: `${nodeValue.kind}:${nodeValue.id}`,
    node: nodeValue,
    depth: 1,
    reference: false,
    quantity: null,
    relation: null,
    ...extra
});

test('a recipe card lists each declared output with its exact amount and marks byproducts', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const recipe = node('RECIPE', 0, {
        crafts: '2',
        outputs: [
            { resourceId: 0, quantity: '9007199254740993', primary: true },
            { resourceId: 1, quantity: '2', primary: false }
        ]
    });
    const { details } = craftingTreeContent(row(recipe), state, locale, 'en');
    const outputs = details.find((line) => line.startsWith('treeOutputs'));
    assert.ok(outputs, 'outputs are stated');
    const [primary, byproduct] = outputs.slice(outputs.indexOf(':') + 1).split(', ');
    assert.ok(primary.includes('Plate') && primary.includes('9,007,199,254,740,993'));
    assert.ok(!primary.includes('treeByproduct'));
    assert.ok(byproduct.includes('Slag') && byproduct.includes('2') && byproduct.includes('treeByproduct'));
    assert.ok(!outputs.includes('§'), 'formatting codes never reach the card text');
    assert.ok(details.some((line) => line.startsWith('treeCrafts')));
});

test('cards without declared outputs state none', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    for (const value of [node('RESOURCE', 0), node('RECIPE', 1, { outputs: [] })])
        assert.ok(
            !craftingTreeContent(row(value), state, locale, 'en').details.some((line) => line.startsWith('treeOutputs'))
        );
});

test('a candidate that stands in for its single-candidate group keeps the group multiplier and its own per-template amount', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const direct = row(node('RESOURCE', 0), {
        relation: 'DECLARED_ALTERNATIVE',
        quantity: '3',
        group: node('INGREDIENT', 4, { multiplier: '8' })
    });
    const { details, title } = craftingTreeContent(direct, state, locale, 'en');
    assert.equal(title, 'Plate');
    assert.ok(details.some((line) => line.startsWith('treePerTemplate') && line.endsWith('3')));
    assert.ok(details.some((line) => line.startsWith('treeMultiplier') && line.endsWith('8')));
});
