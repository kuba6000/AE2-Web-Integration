const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const model = import(
    pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/standing-order-model.js'))
);

const leaf = (type, extra) => ({
    type,
    resource: null,
    amount: null,
    count: null,
    children: null,
    child: null,
    ...extra
});
const below = (resource, amount) => leaf('stockBelow', { resource, amount });
const atLeast = (resource, amount) => leaf('stockAtLeast', { resource, amount });
const idle = (count) => leaf('idleCpusAtLeast', { count });
const not = (child) => leaf('not', { child });
const group = (type, children) => leaf(type, { children });
const action = (overrides = {}) => ({
    resource: 'plastic',
    amount: { type: 'fillTo', batch: null, target: 10000, maxBatch: 2000 },
    options: { lightMode: false, ignoreMissing: false },
    cpu: { type: 'any', key: null },
    ...overrides
});
const definition = (trigger, overrides = {}) => ({
    name: 'Keep plastic',
    enabled: true,
    cooldownSeconds: 30,
    trigger,
    action: action(),
    ...overrides
});

test('a stored order made of one stock condition or a flat group becomes an editable draft', async () => {
    const { draftFromDefinition } = await model;
    const single = draftFromDefinition(definition(below('plastic', 1000)));
    assert.equal(single.rules.locked, null);
    assert.deepEqual(single.rules.conditions, [
        { kind: 'stockBelow', resource: 'plastic', value: '1000', negate: false }
    ]);
    assert.equal(single.name, 'Keep plastic');
    assert.equal(single.cooldownSeconds, '30');

    const composed = draftFromDefinition(
        definition(group('any', [below('plastic', 10000), not(atLeast('ethylene', 50000)), idle(2)]))
    );
    assert.equal(composed.rules.locked, null);
    assert.equal(composed.rules.match, 'any');
    assert.deepEqual(
        composed.rules.conditions.map(({ kind, negate }) => [kind, negate]),
        [
            ['stockBelow', false],
            ['stockAtLeast', true],
            ['idleCpusAtLeast', false]
        ]
    );
});

test('a negated single condition stays one editable row', async () => {
    const { draftFromDefinition } = await model;
    const draft = draftFromDefinition(definition(not(idle(1))));
    assert.equal(draft.rules.locked, null);
    assert.deepEqual(draft.rules.conditions, [{ kind: 'idleCpusAtLeast', resource: null, value: '1', negate: true }]);
});

test('nested groups and double negations are locked instead of being flattened', async () => {
    const { draftFromDefinition } = await model;
    for (const trigger of [
        group('all', [below('a', 1), group('any', [below('b', 1), below('c', 1)])]),
        not(group('all', [below('a', 1), below('b', 1)])),
        not(not(below('a', 1)))
    ])
        assert.equal(draftFromDefinition(definition(trigger)).rules.locked, 'nested');
});

test('amounts beyond the safe integer range lock the order because they cannot be sent back exactly', async () => {
    const { draftFromDefinition } = await model;
    assert.equal(draftFromDefinition(definition(below('a', 2 ** 60))).rules.locked, 'large');
    assert.equal(
        draftFromDefinition(
            definition(below('a', 5), {
                action: action({ amount: { type: 'fixed', batch: 2 ** 60, target: null, maxBatch: null } })
            })
        ).rules.locked,
        'large'
    );
});

test('action settings round trip through the draft for fixed and fill-to amounts and a specific CPU', async () => {
    const { draftFromDefinition, buildDefinition } = await model;
    const fixed = definition(below('plastic', 5), {
        action: action({
            amount: { type: 'fixed', batch: 64, target: null, maxBatch: null },
            options: { lightMode: true, ignoreMissing: false },
            cpu: { type: 'specific', key: 'cpu-1' }
        })
    });
    const rebuilt = buildDefinition(draftFromDefinition(fixed));
    assert.equal(rebuilt.ok, true);
    assert.deepEqual(rebuilt.value, {
        name: 'Keep plastic',
        enabled: true,
        cooldownSeconds: 30,
        trigger: { type: 'stockBelow', resource: 'plastic', amount: 5 },
        action: {
            resource: 'plastic',
            amount: { type: 'fixed', batch: 64 },
            options: { lightMode: true, ignoreMissing: false },
            cpu: { type: 'specific', key: 'cpu-1' }
        }
    });
    const fill = buildDefinition(draftFromDefinition(definition(below('plastic', 5))));
    assert.deepEqual(fill.value.action.amount, { type: 'fillTo', target: 10000, maxBatch: 2000 });
    assert.deepEqual(fill.value.action.cpu, { type: 'any' });
});

test('the keep-stocked template watches the resource it crafts and fills it up to a target', async () => {
    const { keepStockedDraft, buildDefinition } = await model;
    const draft = keepStockedDraft('plastic');
    draft.name = 'Keep plastic';
    draft.rules.conditions[0].value = '1k';
    draft.rules.amount.target = '10k';
    const built = buildDefinition(draft);
    assert.equal(built.ok, true);
    assert.deepEqual(built.value.trigger, { type: 'stockBelow', resource: 'plastic', amount: 1000 });
    assert.equal(built.value.action.resource, 'plastic');
    assert.equal(built.value.action.amount.type, 'fillTo');
    assert.equal(built.value.action.amount.target, 10000);
    assert.equal(built.value.enabled, true);
});

test('an empty largest batch means the target itself; a set one is kept', async () => {
    const { keepStockedDraft, buildDefinition } = await model;
    const draft = keepStockedDraft('plastic');
    draft.name = 'x';
    draft.rules.conditions[0].value = '5';
    draft.rules.amount.target = '100';
    assert.equal(buildDefinition(draft).value.action.amount.maxBatch, 100);
    draft.rules.amount.maxBatch = '25';
    assert.equal(buildDefinition(draft).value.action.amount.maxBatch, 25);
});

test('several rows build one group of the chosen kind and negation wraps only its own row', async () => {
    const { keepStockedDraft, buildDefinition } = await model;
    const draft = keepStockedDraft('plastic');
    draft.name = 'x';
    draft.rules.amount.target = '100';
    draft.rules.conditions[0].value = '5';
    draft.rules.conditions.push(
        { kind: 'stockAtLeast', resource: 'ethylene', value: '50k', negate: false },
        { kind: 'idleCpusAtLeast', resource: null, value: '2', negate: true }
    );
    draft.rules.match = 'any';
    assert.deepEqual(buildDefinition(draft).value.trigger, {
        type: 'any',
        children: [
            { type: 'stockBelow', resource: 'plastic', amount: 5 },
            { type: 'stockAtLeast', resource: 'ethylene', amount: 50000 },
            { type: 'not', child: { type: 'idleCpusAtLeast', count: 2 } }
        ]
    });
});

test('an incomplete draft reports every offending field instead of building a body', async () => {
    const { keepStockedDraft, buildDefinition } = await model;
    const draft = keepStockedDraft(null);
    draft.name = '  ';
    draft.cooldownSeconds = '700000';
    draft.rules.conditions[0].value = 'lots';
    draft.rules.conditions.push({ kind: 'idleCpusAtLeast', resource: null, value: '0', negate: false });
    draft.rules.amount.target = '';
    const built = buildDefinition(draft);
    assert.equal(built.ok, false);
    assert.deepEqual(Object.keys(built.errors).sort(), [
        'action.amount.target',
        'action.resource',
        'condition.0.resource',
        'condition.0.value',
        'condition.1.value',
        'cooldownSeconds',
        'name'
    ]);
});

test('names are trimmed and limited like the server does before anything is sent', async () => {
    const { keepStockedDraft, buildDefinition } = await model;
    const draft = keepStockedDraft('plastic');
    draft.rules.conditions[0].value = '5';
    draft.rules.amount.target = '100';
    draft.name = '  Keep plastic  ';
    assert.equal(buildDefinition(draft).value.name, 'Keep plastic');
    draft.name = 'x'.repeat(129);
    assert.equal(buildDefinition(draft).ok, false);
    draft.name = 'bad\nname';
    assert.deepEqual(Object.keys(buildDefinition(draft).errors), ['name']);
});

test('only the parts that changed are sent when an existing order is edited', async () => {
    const { draftFromDefinition, applyChange, buildChanges } = await model;
    const baseline = draftFromDefinition(definition(below('plastic', 1000)));
    assert.deepEqual(buildChanges(baseline, structuredClone(baseline)), { ok: true, value: {} });

    let draft = applyChange(baseline, { type: 'enabled', value: false });
    assert.deepEqual(buildChanges(baseline, draft).value, { enabled: false });

    draft = applyChange(baseline, { type: 'name', value: ' Renamed ' });
    assert.deepEqual(buildChanges(baseline, draft).value, { name: 'Renamed' });

    draft = applyChange(baseline, { type: 'conditionValue', index: 0, value: '2k' });
    assert.deepEqual(buildChanges(baseline, draft).value, {
        trigger: { type: 'stockBelow', resource: 'plastic', amount: 2000 }
    });

    draft = applyChange(baseline, { type: 'cpu', key: 'cpu-9' });
    assert.deepEqual(buildChanges(baseline, draft).value.action.cpu, { type: 'specific', key: 'cpu-9' });
    assert.equal('trigger' in buildChanges(baseline, draft).value, false);
});

test('a locked order can still be renamed, switched and given a cooldown without touching its rules', async () => {
    const { draftFromDefinition, applyChange, buildChanges } = await model;
    const nested = group('all', [below('a', 1), group('any', [below('b', 1), below('c', 1)])]);
    const baseline = draftFromDefinition(definition(nested));
    let draft = applyChange(baseline, { type: 'name', value: 'New name' });
    draft = applyChange(draft, { type: 'enabled', value: false });
    draft = applyChange(draft, { type: 'cooldownSeconds', value: '60' });
    draft = applyChange(draft, { type: 'addCondition' });
    draft = applyChange(draft, { type: 'amount', field: 'target', value: '1' });
    assert.deepEqual(buildChanges(baseline, draft).value, { name: 'New name', enabled: false, cooldownSeconds: 60 });
});

test('changing the crafted resource moves a stock condition that watched it and leaves other rows alone', async () => {
    const { keepStockedDraft, applyChange } = await model;
    let draft = keepStockedDraft('plastic');
    draft = applyChange(draft, { type: 'addCondition' });
    draft = applyChange(draft, { type: 'conditionResource', index: 1, resource: { itemKey: 'ethylene' } });
    draft = applyChange(draft, { type: 'target', resource: { itemKey: 'rubber' } });
    assert.equal(draft.rules.resource, 'rubber');
    assert.deepEqual(
        draft.rules.conditions.map((row) => row.resource),
        ['rubber', 'ethylene']
    );
    draft = applyChange(keepStockedDraft(null), { type: 'target', resource: { itemKey: 'rubber' } });
    assert.equal(draft.rules.conditions[0].resource, 'rubber');
});

test('rows can be added up to the server tree limit and removed down to one', async () => {
    const { keepStockedDraft, applyChange, maxConditions } = await model;
    let draft = keepStockedDraft('plastic');
    for (let count = 0; count < maxConditions + 5; count++) draft = applyChange(draft, { type: 'addCondition' });
    assert.equal(draft.rules.conditions.length, maxConditions);
    // The largest flat tree must stay within the server's node limit: one group plus a negated row each.
    assert.ok(1 + maxConditions * 2 <= 32);
    for (let count = 0; count < maxConditions + 5; count++)
        draft = applyChange(draft, { type: 'removeCondition', index: 0 });
    assert.equal(draft.rules.conditions.length, 1);
});

test('switching between stock and CPU conditions clears the value that no longer fits', async () => {
    const { keepStockedDraft, applyChange } = await model;
    let draft = keepStockedDraft('plastic');
    draft = applyChange(draft, { type: 'conditionValue', index: 0, value: '5k' });
    draft = applyChange(draft, { type: 'conditionKind', index: 0, kind: 'stockAtLeast' });
    assert.equal(draft.rules.conditions[0].value, '5k');
    draft = applyChange(draft, { type: 'conditionKind', index: 0, kind: 'idleCpusAtLeast' });
    assert.equal(draft.rules.conditions[0].value, '');
});

test('a rejection path points at the editor field that produced it', async () => {
    const { keepStockedDraft, applyChange, fieldForPath } = await model;
    let single = keepStockedDraft('plastic');
    assert.equal(fieldForPath('trigger.amount', single), 'condition.0.value');
    assert.equal(fieldForPath('trigger.resource', single), 'condition.0.resource');
    single = applyChange(single, { type: 'conditionNegate', index: 0, value: true });
    assert.equal(fieldForPath('trigger.child.amount', single), 'condition.0.value');

    let draft = applyChange(keepStockedDraft('plastic'), { type: 'addCondition' });
    draft = applyChange(draft, { type: 'conditionKind', index: 1, kind: 'idleCpusAtLeast' });
    draft = applyChange(draft, { type: 'conditionNegate', index: 1, value: true });
    assert.equal(fieldForPath('trigger.children[0].amount', draft), 'condition.0.value');
    assert.equal(fieldForPath('trigger.children[1].child.count', draft), 'condition.1.value');
    assert.equal(fieldForPath('trigger.children[7].amount', draft), 'trigger');
    assert.equal(fieldForPath('trigger.children', draft), 'trigger');
    assert.equal(fieldForPath('trigger.children[1]', draft), 'trigger');

    assert.equal(fieldForPath('name', draft), 'name');
    assert.equal(fieldForPath('cooldownSeconds', draft), 'cooldownSeconds');
    assert.equal(fieldForPath('action.resource', draft), 'action.resource');
    assert.equal(fieldForPath('action.amount.maxBatch', draft), 'action.amount.maxBatch');
    assert.equal(fieldForPath('action.cpu.key', draft), 'action.cpu.key');
    assert.equal(fieldForPath('action.options.lightMode', draft), 'action.options.lightMode');
    assert.equal(fieldForPath('', draft), null);
    assert.equal(fieldForPath('somewhere.else', draft), null);
});

test('known refusal reasons keep their own message and an unknown one falls back to the generic refusal', async () => {
    const { reasonMessageKey } = await model;
    assert.notEqual(reasonMessageKey('NAME_LENGTH'), reasonMessageKey('COOLDOWN_OUT_OF_RANGE'));
    assert.notEqual(reasonMessageKey('NAME_LENGTH'), reasonMessageKey('SOMETHING_NEW'));
    assert.equal(reasonMessageKey('SOMETHING_NEW'), reasonMessageKey('ALSO_NEW'));
});
