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

const traceNode = (kind, id, extra = {}) =>
    node(kind, id, { inputs: null, resourceId: null, contribution: null, ...extra });
const traceState = {
    resources: {
        0: { displayName: 'Machine', missingAmount: null, icon: null },
        2: { displayName: '§aGear', missingAmount: null, icon: { page: 0, x: 0, y: 0 } },
        3: { displayName: 'Ingot', missingAmount: null, icon: null }
    }
};

test('a trace request states its item and exact amount, with the item icon', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const card = craftingTreeContent(
        row(traceNode('REQUEST', 1, { resourceId: 2 }), { quantity: '9007199254740993', relation: 'CHILD_REQUEST' }),
        traceState,
        locale,
        'en'
    );
    assert.equal(card.title, '§aGear');
    assert.ok(card.details.some((line) => line.endsWith('9,007,199,254,740,993')));
    assert.deepEqual(card.icon, { page: 0, x: 0, y: 0 });
    assert.equal(card.missing, false);
});

test('each contribution leaf names how the request was met, with its own amount, and only a shortage is missing', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const cards = Object.fromEntries(
        ['STORAGE', 'EMITTED', 'MISSING', 'REUSED', 'UNKNOWN'].map((contribution) => [
            contribution,
            craftingTreeContent(
                row(traceNode('CONTRIBUTION', 4, { resourceId: 3, contribution }), {
                    quantity: '29',
                    relation: 'CONTRIBUTION'
                }),
                traceState,
                locale,
                'en'
            )
        ])
    );
    // Each kind has its own wording and every leaf states its amount and what it is for.
    assert.equal(new Set(Object.values(cards).map((card) => card.title.replace(/\s*\d+$/, ''))).size, 5);
    for (const card of Object.values(cards)) {
        assert.ok(card.title.endsWith('29'));
        assert.ok(card.details.includes('Ingot'));
    }
    assert.deepEqual(
        Object.entries(cards)
            .filter(([, card]) => card.missing)
            .map(([kind]) => kind),
        ['MISSING']
    );
});

test('a task card states the crafted amount, crafts, outputs with byproducts and declared inputs', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const task = traceNode('TASK', 1, {
        crafts: '3',
        complete: true,
        outputs: [
            { resourceId: 2, quantity: '2', primary: true },
            { resourceId: 0, quantity: '1', primary: false }
        ],
        inputs: [{ resourceId: 3, quantity: '1' }]
    });
    const card = craftingTreeContent(row(task, { quantity: '4', relation: 'CRAFT' }), traceState, locale, 'en');
    const line = (key) => card.details.find((entry) => entry.startsWith(key));
    assert.ok(line('treeCraftAmount').endsWith('4'));
    assert.ok(line('treeCrafts').endsWith('3'));
    const outputs = line('treeOutputs');
    assert.ok(outputs.includes('Gear') && outputs.includes('Machine') && outputs.includes('treeByproduct'));
    assert.ok(!outputs.includes('§'));
    assert.ok(line('treeInputs').includes('Ingot'));
    assert.ok(!card.details.some((entry) => entry.startsWith('treePartialNode')));
    const shared = craftingTreeContent(
        row(task, { quantity: '2', relation: 'CRAFT', reference: true }),
        traceState,
        locale,
        'en'
    );
    assert.ok(shared.details.some((entry) => entry.startsWith('treeReference')));
    assert.ok(shared.details.some((entry) => entry.startsWith('treeCraftAmount') && entry.endsWith('2')));
    const incomplete = craftingTreeContent(
        row({ ...task, complete: false }, { quantity: '4', relation: 'CRAFT' }),
        traceState,
        locale,
        'en'
    );
    assert.ok(incomplete.details.some((entry) => entry.startsWith('treePartialNode')));
});
