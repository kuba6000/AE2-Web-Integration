const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const timing = import(
    pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/cpu-timing.js'))
);
const header = Object.freeze({
    selectionMode: null,
    size: 8192,
    isBusy: true,
    supportsPause: false,
    isPaused: false,
    finalOutput: null,
    hasTrackingInfo: true,
    timeStarted: 0,
    timeElapsed: 10000
});
const resource = (name, extra = {}) =>
    Object.freeze({
        displayName: name,
        itemKey: name,
        icon: null,
        active: 0,
        pending: 0,
        stored: 0,
        timeSpentCrafting: 0,
        crafting: false,
        craftedTotal: 0,
        shareInCraftingTime: 0,
        shareInCraftingTimeCombined: 0,
        craftsPerSec: 0,
        ...extra
    });
const measured = (receivedAt, ...resources) => resources.map((item) => ({ resource: item, receivedAt }));
const byName = (detail) => Object.fromEntries(detail.items.map((item) => [item.displayName, item]));

test('running measurements continue from their own arrival time and finished ones stay fixed', async () => {
    const { deriveCpuDetail } = await timing;
    const rows = [
        ...measured(1000, resource('running', { crafting: true, timeSpentCrafting: 4000, craftedTotal: 12 })),
        ...measured(2000, resource('finished', { timeSpentCrafting: 2000, craftedTotal: 4 })),
        ...measured(2500, resource('late', { crafting: true, timeSpentCrafting: 1000 }))
    ];
    const detail = deriveCpuDetail(header, 1000, rows, 3000);
    const items = byName(detail);
    assert.equal(detail.timeElapsed, 12000);
    assert.equal(items.running.timeSpentCrafting, 6000);
    assert.equal(items.finished.timeSpentCrafting, 2000);
    assert.equal(items.late.timeSpentCrafting, 1500);
    assert.equal(items.running.craftsPerSec, 2);
    assert.equal(items.finished.craftsPerSec, 2);
    assert.equal(items.late.craftsPerSec, 0, 'A resource without crafted units has no rate');
});

test('time shares are relative to all measured time and to the elapsed time of the job, capped at one', async () => {
    const { deriveCpuDetail } = await timing;
    const rows = measured(
        0,
        resource('a', { timeSpentCrafting: 3000 }),
        resource('b', { timeSpentCrafting: 1000 }),
        resource('c', { timeSpentCrafting: 20000 })
    );
    const items = byName(deriveCpuDetail(header, 0, rows, 0));
    assert.equal(items.a.shareInCraftingTime, 3000 / 24000);
    assert.equal(items.b.shareInCraftingTime, 1000 / 24000);
    assert.equal(items.a.shareInCraftingTimeCombined, 0.3);
    assert.equal(items.c.shareInCraftingTimeCombined, 1);
});

test('without any measured time every resource owns the whole share and nothing divides by zero', async () => {
    const { deriveCpuDetail } = await timing;
    const rows = measured(0, resource('a'), resource('b'));
    const detail = deriveCpuDetail({ ...header, timeElapsed: 0 }, 0, rows, 0);
    for (const item of detail.items) {
        assert.equal(item.shareInCraftingTime, 1);
        assert.equal(item.shareInCraftingTimeCombined, 0);
        assert.equal(item.craftsPerSec, 0);
    }
});

test('data without tracking keeps the values it arrived with and an idle CPU has no resources', async () => {
    const { deriveCpuDetail } = await timing;
    const row = resource('a', { active: 1, shareInCraftingTime: 0.4, craftsPerSec: 2 });
    const untracked = deriveCpuDetail({ ...header, hasTrackingInfo: false, timeElapsed: 0 }, 0, measured(0, row), 9000);
    assert.equal(untracked.timeElapsed, 0);
    assert.equal(untracked.items[0], row);
    const idle = deriveCpuDetail({ ...header, isBusy: false, timeElapsed: 0 }, 0, null, 9000);
    assert.equal(idle.items, null);
    assert.equal(idle.timeElapsed, 0, 'An idle CPU has no running job to extend');
    assert.equal(deriveCpuDetail(header, 1000, null, 4000).timeElapsed, 13000);
});

test('resources keep the server order of active, pending and stored work with stable ties', async () => {
    const { deriveCpuDetail } = await timing;
    const rows = measured(
        0,
        resource('stored-small', { stored: 1 }),
        resource('pending-big', { pending: 9 }),
        resource('active-small', { active: 1, stored: 99 }),
        resource('stored-big', { stored: 5 }),
        resource('active-big', { active: 7 }),
        resource('pending-small', { pending: 2, stored: 50 }),
        resource('active-small-twin', { active: 1, pending: 99 })
    );
    const detail = deriveCpuDetail(header, 0, rows, 0);
    assert.deepEqual(
        detail.items.map((item) => item.displayName),
        [
            'active-big',
            'active-small',
            'active-small-twin',
            'pending-big',
            'pending-small',
            'stored-big',
            'stored-small'
        ]
    );
});

test('deriving never changes its inputs and a clock that moved backwards cannot shorten a measurement', async () => {
    const { deriveCpuDetail } = await timing;
    const row = resource('a', { crafting: true, timeSpentCrafting: 500 });
    const rows = Object.freeze(measured(5000, row));
    const detail = deriveCpuDetail(header, 5000, rows, 1000);
    assert.equal(row.timeSpentCrafting, 500);
    assert.equal(detail.items[0].timeSpentCrafting, 500);
    assert.equal(detail.timeElapsed, 10000);
    assert.notEqual(detail.items[0], row);
});
