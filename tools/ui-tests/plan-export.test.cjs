const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const exporter = import(
    pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/plan-export-layout.js'))
);

test('plan image preserves explicit rows or columns, with contiguous rectangular slots and a title', async () => {
    const { planExportLayout, defaultPlanExportSettings } = await exporter;
    const settings = { ...defaultPlanExportSettings, scale: 1, header: true };
    const geometry = { width: 160, height: 100, headerHeight: 32 };
    assert.deepEqual(planExportLayout(7, settings, { axis: 'columns', value: 3 }, geometry), {
        columns: 3,
        rows: 3,
        width: 480,
        height: 332,
        tileWidth: 160,
        tileHeight: 100,
        scale: 1,
        headerHeight: 32
    });
    const rows = planExportLayout(7, settings, { axis: 'rows', value: 2 }, geometry);
    assert.equal(rows.rows, 2);
    assert.equal(rows.columns, 4);
    assert.equal(planExportLayout(0, settings, { axis: 'square' }, geometry), null);
});

test('square layout, ratios and step-down preserve a single complete grid', async () => {
    const { planExportLayout, defaultPlanExportSettings, smallerPlanExportLayout, planExportFits, planExportRatio } =
        await exporter;
    const settings = { ...defaultPlanExportSettings, header: false, scale: 1 };
    const geometry = { width: 160, height: 100, headerHeight: 32 };
    const square = planExportLayout(16, settings, { axis: 'square' }, geometry);
    assert.equal(square.columns, 3);
    assert.equal(square.rows, 6);
    assert.equal(planExportRatio(1920, 1080), '16:9');
    assert.equal(planExportLayout(1, settings, { axis: 'square' }, geometry).width, 160);
    let huge = planExportLayout(10000, settings, { axis: 'columns', value: 100 }, geometry);
    assert.equal(planExportFits(huge), false);
    while (huge && !planExportFits(huge)) huge = smallerPlanExportLayout(huge, geometry);
    assert.ok(huge);
    assert.equal(huge.columns, 100);
    assert.equal(huge.rows, 100);
    assert.ok(huge.scale < 1);
    assert.equal(planExportLayout(3, settings, { axis: 'rows', value: 0 }, geometry), null);
});

test('bad saved settings do not discard valid preferences and count is not persisted', async () => {
    const { readPlanExportSettings } = await exporter;
    const saved = {
        'planExport.scale': 77,
        'planExport.name': false,
        'planExport.numbers': 'full',
        'planExport.ratio': '0:4'
    };
    const options = readPlanExportSettings((key) => saved[key] ?? null);
    assert.equal(options.name, false);
    assert.equal(options.numbers, 'full');
    assert.ok([0.5, 1, 1.5, 2, 3, 4].includes(options.scale));
    assert.equal(options.ratio, 'square');
    assert.equal('columns' in options, false);
    assert.equal('rows' in options, false);
});
