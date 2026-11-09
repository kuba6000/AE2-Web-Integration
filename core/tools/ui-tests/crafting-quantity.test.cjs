const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const quantity = import(
    pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/crafting-quantity.js'))
);

test('quantity expressions evaluate exactly with suffixes, decimal commas, parentheses and unary signs', async () => {
    const { parseCraftingQuantity } = await quantity;
    assert.equal(parseCraftingQuantity('10.5m + (100k / 2) - 64'), 10549936);
    assert.equal(parseCraftingQuantity('-(2-3) * (10,5m + 100k) / 2'), 5300000);
    assert.equal(parseCraftingQuantity(' 64 '), 64);
    assert.equal(parseCraftingQuantity('1b'), 1000000000);
    assert.equal(parseCraftingQuantity('2T'), 2000000000000);
    assert.equal(parseCraftingQuantity('1/3 * 3'), 1);
    assert.equal(parseCraftingQuantity('9007199254740991'), Number.MAX_SAFE_INTEGER);
});

test('quantity expressions accept only positive safe integers', async () => {
    const { parseCraftingQuantity } = await quantity;
    for (const invalid of [
        '',
        '   ',
        '0',
        '-1',
        '1.5',
        '1/3',
        '1/0',
        '1.0000000000000001',
        '9007199254740992',
        '2**3',
        'abc',
        '(1',
        '1 +',
        '1 2',
        '1'.repeat(257)
    ])
        assert.equal(parseCraftingQuantity(invalid), null, JSON.stringify(invalid));
});
