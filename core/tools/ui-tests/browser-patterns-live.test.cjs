const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, refreshingFixture, gridA, iron } = require('./fixtures.cjs');

const empty = { kind: 'empty', pattern: null };
const pattern = { kind: 'pattern', pattern: { kind: 'processing', inputs: [], outputs: [{ ...iron, quantity: 1 }] } };
function provider(index, name, slots) {
    return {
        key: String(index),
        name,
        visible: true,
        online: true,
        icon: null,
        priority: null,
        sortValue: index,
        rowSize: 9,
        location: { dimension: '0', x: index, y: 60, z: 0, side: null },
        slots
    };
}

test('live pattern replacements and removals preserve a collapsed group and search', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '', {}, { mockClock: false });
    options.capabilities.patternProviders = true;
    options.patternTerminalName = 'interface_terminal';
    const press = provider(1, 'Factory press', [pattern]);
    const mill = provider(2, 'Factory mill', [empty]);
    const spare = provider(3, 'Factory spare', [pattern]);
    options.patternRows = [press, mill, spare];
    const attached = page.waitForResponse((r) => r.url().includes('/pattern-providers/events?'), { timeout: 5000 });
    await page.goto(base + '#/grids/' + gridA + '/patterns');
    await attached;
    const search = page.getByRole('searchbox');
    await search.fill('Factory');
    const group = page.getByRole('button', { name: /Factory press$/ });
    await group.click();
    assert.equal(await group.getAttribute('aria-expanded'), 'false');
    const updated = { ...mill, name: 'Factory mill MK II' };
    options.patternStream.write(
        'event: delta\ndata: ' +
            JSON.stringify({
                view: { generation: '1.0', revision: '2' },
                providers: [updated],
                removed: [spare.key],
                icons: null
            }) +
            '\n\n'
    );
    await page.getByRole('button', { name: /Factory mill MK II$/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: /Factory spare$/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: /Factory mill$/ }).count(), 0);
    assert.equal(await group.getAttribute('aria-expanded'), 'false');
    assert.equal(await search.inputValue(), 'Factory');
});

test('five thousand 36-slot providers remain searchable on a narrow screen', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 375, height: 800 } },
        { mockClock: false }
    );
    options.capabilities.patternProviders = true;
    options.patternTerminalName = 'interface_terminal';
    const slots = Array.from({ length: 36 }, (_, i) => (i % 12 === 0 ? pattern : empty));
    options.patternRows = Array.from({ length: 5000 }, (_, i) =>
        provider(i, 'Workshop ' + String(i).padStart(4, '0'), slots)
    );
    const start = performance.now();
    await page.goto(base + '#/grids/' + gridA + '/patterns');
    await page.getByRole('button', { name: /Workshop 0000$/ }).waitFor({ timeout: 15000 });
    const ready = performance.now();
    await page.getByRole('searchbox').fill('4999');
    await page.getByRole('button', { name: /Workshop 4999$/ }).waitFor({ timeout: 6000 });
    assert.equal(await page.getByRole('button', { name: /Workshop 0000$/ }).count(), 0);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    t.diagnostic(
        '375px viewport: load ' +
            Math.round(ready - start) +
            ' ms; search ' +
            Math.round(performance.now() - ready) +
            ' ms.'
    );
});

test('an unchanged pattern gains and loses its duplicate marker when another device changes', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '', {}, { mockClock: false });
    options.capabilities.patternProviders = true;
    options.patternTerminalName = 'interface_terminal';
    const first = provider(1, 'Press Alpha', [pattern, empty]);
    const second = provider(22, 'Rolling Mill', [empty, empty]);
    options.patternRows = [first, second];
    const attached = page.waitForResponse((r) => r.url().includes('/pattern-providers/events?'));
    await page.goto(base + '#/grids/' + gridA + '/patterns');
    await attached;
    const filters = page.getByRole('combobox', { name: 'Show providers' });
    await filters.selectOption('duplicates', { timeout: 3000 });
    const press = page.getByRole('button', { name: /Press Alpha$/ });
    const mill = page.getByRole('button', { name: /Rolling Mill$/ });
    assert.equal(await press.count(), 0);
    const update = (revision, providers, removed) =>
        options.patternStream.write(
            'event: delta\ndata: ' +
                JSON.stringify({ view: { generation: '1.0', revision }, providers, removed, icons: null }) +
                '\n\n'
        );
    update('2', [{ ...second, slots: [empty, pattern] }], []);
    await press.waitFor();
    await mill.waitFor();
    const slot = page.getByRole('button', { name: /Slot 1.*Iron Ingot/i });
    await slot.getByText('×2', { exact: true }).waitFor();
    await slot.focus();
    const tip = await page.getByRole('tooltip').innerText();
    assert.match(tip, /Rolling Mill/);
    assert.match(tip, /22, 60, 0/);
    assert.match(tip, /Slot 2/i);
    update('3', [], [second.key]);
    await press.waitFor({ state: 'hidden' });
    await mill.waitFor({ state: 'hidden' });
    await filters.selectOption('all');
    await press.waitFor();
    assert.equal(await slot.getByText('×2', { exact: true }).count(), 0);
});
