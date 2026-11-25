const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, gridA, iron, quartz } = require('./fixtures.cjs');

function machine(key, name, visible = true) {
    return {
        key,
        name,
        visible,
        online: true,
        icon: null,
        priority: null,
        sortValue: 0,
        rowSize: 9,
        location: { dimension: '0', x: 10, y: 64, z: 12, side: 'north' },
        slots: [
            {
                kind: 'pattern',
                pattern: {
                    kind: 'processing',
                    inputs: [{ ...quartz, quantity: 2 }],
                    outputs: [{ ...iron, quantity: 1 }]
                }
            },
            { kind: 'empty', pattern: null }
        ]
    };
}

test('a direct pattern link opens the native terminal and searches by ingredient', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.patternProviders = true;
    options.patternTerminalName = 'interface_terminal';
    options.patternRows = [machine('A', 'Blast Furnace'), { ...machine('B', 'Printer'), slots: [] }];
    await page.goto(base + '#/grids/' + gridA + '/patterns');
    await page.getByRole('heading', { name: 'Interface Terminal', exact: true }).waitFor({ timeout: 4000 });
    const furnace = page.getByRole('button', { name: /Blast Furnace/ });
    const printer = page.getByRole('button', { name: /Printer/ });
    await furnace.waitFor();
    await printer.waitFor();
    await page.getByRole('searchbox').fill('certus');
    await printer.waitFor({ state: 'hidden' });
    assert.ok(await furnace.isVisible());
});

test('only supported grids expose pattern inventory through a direct link', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base + '#/grids/' + gridA + '/patterns');
    await page.getByText('This view does not exist.').waitFor();
    assert.ok(!options.requests.some((r) => r.path.endsWith('/pattern-providers')));
});

test('physical slots show pattern details; hidden providers and free slots can be filtered', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.patternProviders = true;
    options.patternTerminalName = 'pattern_access_terminal';
    const broken = { kind: 'pattern', pattern: { kind: 'invalid', outputs: [], inputs: [] } };
    options.patternRows = [
        machine('A', 'Assembler'),
        { ...machine('Z', 'Spare Press', false), online: false, slots: [broken] }
    ];
    await page.goto(base + '#/grids/' + gridA + '/patterns');
    await page.getByRole('heading', { name: 'Pattern Access Terminal' }).waitFor();
    const filter = page.getByRole('combobox', { name: 'Show providers' });
    await page.getByRole('button', { name: /Iron Ingot/ }).focus();
    const tip = page.getByRole('tooltip');
    assert.match(await tip.innerText(), /2 × Certus Quartz Crystal/);
    assert.match(await tip.innerText(), /10, 64, 12/);
    const spare = page.getByRole('button', { name: 'Spare Press' });
    assert.equal(await spare.count(), 0);
    await filter.selectOption('all');
    await spare.waitFor();
    assert.ok(await page.getByText('Offline', { exact: true }).isVisible());
    await page.getByRole('button', { name: /Invalid pattern/ }).focus();
    assert.match(await tip.innerText(), /Invalid pattern/);
    await filter.selectOption('notFull');
    await spare.waitFor({ state: 'hidden' });
    assert.ok(await page.getByRole('button', { name: 'Assembler' }).isVisible());
});

test('patterns fall back to the built-in view and restore the selected theme on leaving', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.patternProviders = true;
    options.patternTerminalName = 'interface_terminal';
    options.themeCatalog = {
        defaultTheme: 'server:legacy',
        themes: [
            {
                id: 'legacy',
                name: 'Legacy',
                version: '1',
                supportedModVersion: '1.2.x',
                views: ['home'],
                entry: 'theme.js',
                styles: ['style.css']
            }
        ]
    };
    options.themeFiles = {
        '/themes/legacy/theme.js':
            'export default {id:"legacy",mount(root,app){app.subscribe(s=>{if(s.route.view==="patterns")throw Error("Unsupported view");root.textContent="Legacy home";});}};',
        '/themes/legacy/style.css': 'body { --legacy-proof: mounted; }'
    };
    await page.goto(base);
    const oldTheme = page.getByText('Legacy home');
    await oldTheme.waitFor();
    await page.evaluate((key) => {
        location.hash = '/grids/' + key + '/patterns';
    }, gridA);
    await page.getByRole('heading', { name: 'Interface Terminal' }).waitFor();
    assert.equal(
        await page.evaluate(() => getComputedStyle(document.body).getPropertyValue('--legacy-proof').trim()),
        ''
    );
    await page.goBack();
    await oldTheme.waitFor();
    await page.waitForFunction(
        () => getComputedStyle(document.body).getPropertyValue('--legacy-proof').trim() === 'mounted'
    );
});
