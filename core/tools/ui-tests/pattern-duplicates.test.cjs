const test = require('node:test');
const assert = require('node:assert/strict');

const pattern = (...keys) => ({
    kind: 'pattern',
    pattern: { kind: 'processing', inputs: [], outputs: keys.map((itemKey, i) => ({ itemKey, quantity: i + 1 })) }
});

test('output conflicts identify distinct physical slots across the entire network', async () => {
    const { indexPatternOutputs } = await import('../../build/generated/frontend/assets/web/app/pattern-duplicates.js');
    const first = {
        key: 'a',
        visible: true,
        online: true,
        slots: [
            pattern('duplicate', 'same-slot', 'same-slot', null),
            { kind: 'empty', pattern: null },
            pattern('duplicate', 'second-output'),
            pattern(null),
            { kind: 'pattern', pattern: { kind: 'invalid', inputs: [], outputs: [{ itemKey: 'same-slot' }] } }
        ]
    };
    const second = {
        key: 'b',
        visible: false,
        online: false,
        slots: [pattern('second-output', 'duplicate', 'duplicate')]
    };
    const result = indexPatternOutputs([first, second]);
    assert.deepEqual([...result.keys()].sort(), ['duplicate', 'second-output']);
    const duplicate = result.get('duplicate');
    assert.equal(duplicate.count, 3);
    assert.deepEqual(
        duplicate.locations.map(({ provider, slot }) => [provider.key, slot]),
        [
            ['a', 0],
            ['a', 2],
            ['b', 0]
        ]
    );
    assert.equal(result.get('second-output').count, 2);
});

test('many matching slots report the true count without retaining every example', async () => {
    const { indexPatternOutputs } = await import('../../build/generated/frontend/assets/web/app/pattern-duplicates.js');
    const repeated = { key: 'shared', slots: Array(5000).fill(pattern('ingot')) };
    const hit = indexPatternOutputs([repeated]).get('ingot');
    assert.equal(hit.count, 5000);
    assert.ok(hit.locations.length > 0 && hit.locations.length < hit.count);
});
