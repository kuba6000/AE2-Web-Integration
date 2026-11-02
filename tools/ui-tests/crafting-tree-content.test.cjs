const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/themes/default/crafting-tree-content.js')
);
const locale = { common: (key) => key };
const node = (kind, id, extra = {}) => ({
    kind,
    id,
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
const row = (value, extra = {}) => ({
    key: `${value.kind}:${value.id}`,
    node: value,
    depth: 1,
    reference: false,
    quantity: null,
    relation: null,
    ...extra
});
const state = {
    resources: {
        0: { displayName: 'Machine', missingAmount: '0', icon: null },
        1: { displayName: '§aGear', missingAmount: '2', icon: { page: 0, x: 0, y: 0 } },
        2: { displayName: 'Ingot', missingAmount: '0', icon: null }
    },
    recipes: {
        4: {
            id: 4,
            inputs: [{ multiplier: '1', candidates: [{ resourceId: 2, quantity: '1' }] }],
            outputs: [
                { resourceId: 1, quantity: '9007199254740993', primary: true },
                { resourceId: 0, quantity: '1', primary: false }
            ]
        }
    }
};

test('task and recipe cards use shared recipe declarations and preserve occurrence amounts', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const task = node('TASK', 8, { recipeId: 4, crafts: '3', complete: false });
    const card = craftingTreeContent(row(task, { quantity: '4', relation: 'CRAFT' }), state, locale, 'en');
    const line = (key) => card.details.find((entry) => entry.startsWith(key));
    assert.ok(line('treeOutputs').includes('9,007,199,254,740,993'));
    assert.ok(line('treeOutputs').includes('Machine') && line('treeOutputs').includes('treeByproduct'));
    assert.ok(line('treeInputs').includes('Ingot'));
    assert.ok(line('treeCraftAmount').endsWith('4'));
    assert.ok(line('treeCrafts').endsWith('3'));
    assert.ok(line('treePartialNode'));
    const shared = craftingTreeContent(
        row({ ...task, id: 9 }, { quantity: '2', relation: 'CRAFT' }),
        state,
        locale,
        'en'
    );
    assert.ok(shared.details.some((entry) => entry.startsWith('treeCraftAmount') && entry.endsWith('2')));
    assert.ok(shared.details.some((entry) => entry.startsWith('treeOutputs') && entry.includes('Gear')));
});

test('resource and contribution cards use the actual resource identity, including unknown sources', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const material = craftingTreeContent(row(node('RESOURCE', 9, { resourceId: 1 })), state, locale, 'en');
    assert.equal(material.title, '§aGear');
    assert.equal(material.missing, true);
    const source = craftingTreeContent(
        row(node('CONTRIBUTION', 10, { resourceId: 2, contribution: 'STORAGE' }), {
            quantity: '29',
            relation: 'CONTRIBUTION'
        }),
        state,
        locale,
        'en'
    );
    assert.ok(source.details.includes('Ingot'));
    const unknown = craftingTreeContent(
        row(node('CONTRIBUTION', 11, { contribution: 'STORAGE' }), { quantity: '29', relation: 'CONTRIBUTION' }),
        state,
        locale,
        'en'
    );
    assert.deepEqual(unknown.details, []);
    assert.equal(unknown.icon, null);
});

test('a single-candidate group retains its multiplier and per-template amount', async () => {
    const { craftingTreeContent } = await import(moduleUrl);
    const card = craftingTreeContent(
        row(node('RESOURCE', 9, { resourceId: 1 }), {
            relation: 'DECLARED_ALTERNATIVE',
            quantity: '3',
            group: node('INGREDIENT', 4, { multiplier: '8' })
        }),
        state,
        locale,
        'en'
    );
    assert.ok(card.details.some((line) => line.startsWith('treePerTemplate') && line.endsWith('3')));
    assert.ok(card.details.some((line) => line.startsWith('treeMultiplier') && line.endsWith('8')));
});
