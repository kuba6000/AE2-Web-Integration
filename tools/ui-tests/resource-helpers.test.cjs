const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const helpers = import(
    pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/resource-helpers.js'))
);
const resource = (displayName, registryNamespace, registryPath, extra = {}) =>
    Object.freeze({
        displayName,
        registryNamespace,
        registryPath,
        resourceType: 'ITEM',
        itemKey: registryPath,
        quantity: 1,
        craftable: false,
        ...extra
    });

test('resource selection matches formatted names and registry terms without changing shared data', async () => {
    const { selectResources, registryId } = await helpers;
    const source = Object.freeze([
        resource('§cIron Dust', 'gregtech', 'dust_iron', { quantity: 0, craftable: true, marker: 'retain' }),
        resource('Iron Ingot', 'minecraft', 'iron_ingot', { quantity: 20 }),
        resource('Liquid Iron', 'gregtech', 'iron', { resourceType: 'FLUID', quantity: 100 }),
        resource('Unknown resource', null, null, { resourceType: 'OTHER' })
    ]);
    const options = {
        search: 'iron @GREG',
        language: 'en',
        showItems: true,
        showFluids: false,
        filter: 'craftable',
        sort: 'quantity',
        sortOrder: 'descending'
    };
    const selected = selectResources(source, options);
    assert.deepEqual(selected, [source[0]]);
    assert.equal(selected[0], source[0]);
    assert.equal(selected[0].marker, 'retain');
    assert.equal(source.length, 4);
    assert.equal(registryId(source[0]), 'gregtech:dust_iron');
    assert.equal(registryId(source[3]), '');
});

test('plan selection keeps missing and requested groups first in either sorting direction', async () => {
    const { selectPlanResources } = await helpers;
    const rows = Object.freeze([
        resource('Storage', 'test', 'storage', { stored: 100, requested: 0, missing: 0, steps: 0 }),
        resource('Requested small', 'test', 'small', { stored: 2, requested: 3, missing: 0, steps: 100 }),
        resource('Missing', 'test', 'missing', { stored: 0, requested: 0, missing: 1, steps: 1 }),
        resource('Requested large', 'test', 'large', { stored: 8, requested: 2, missing: 0, steps: 1 })
    ]);
    const options = { search: '', language: 'en', sort: 'quantity', sortOrder: 'descending' };
    assert.deepEqual(selectPlanResources(rows, options), [rows[2], rows[3], rows[1], rows[0]]);
    assert.deepEqual(selectPlanResources(rows, { ...options, sortOrder: 'ascending' }), [
        rows[2],
        rows[1],
        rows[3],
        rows[0]
    ]);
    assert.deepEqual(selectPlanResources(rows, { ...options, sort: 'steps' }), [rows[2], rows[1], rows[3], rows[0]]);
    assert.deepEqual(selectPlanResources(rows, { ...options, search: 'small @TEST' }), [rows[1]]);
});

test('themes can independently filter or sort while retaining unknown resource types and stable ties', async () => {
    const { filterResources, sortResources } = await helpers;
    const rows = Object.freeze([
        resource('Same', 'test', 'shared', { quantity: 5, itemKey: 'variant-b' }),
        resource('§aSame', 'test', 'shared', { quantity: 5, itemKey: 'variant-a' }),
        resource('Other', null, null, { quantity: 0, resourceType: 'OTHER', craftable: true }),
        resource('Water', 'test', 'water', { resourceType: 'FLUID', quantity: 8 })
    ]);
    const filters = { search: '', language: 'en', filter: 'all', showItems: false, showFluids: false };
    assert.deepEqual(filterResources(rows, filters), [rows[2]]);
    assert.deepEqual(filterResources(rows, { ...filters, filter: 'stored' }), []);
    assert.deepEqual(filterResources(rows, { ...filters, showItems: true, filter: 'craftable' }), [rows[2]]);
    assert.deepEqual(sortResources(rows, { language: 'en', sort: 'quantity', sortOrder: 'descending' }), [
        rows[3],
        rows[1],
        rows[0],
        rows[2]
    ]);
    assert.equal(rows[0].itemKey, 'variant-b');
    assert.equal(rows[1].itemKey, 'variant-a');
});
