const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {
    gridA,
    gridB,
    iron,
    quartz,
    readyPlan,
    cpu,
    cpuWork,
    historyEntry,
    historyDetail,
    refreshingFixture,
    fixture,
    clickTab,
    settleFrames,
    settleResponse,
    poll,
    atlasFixture
} = require('./fixtures.cjs');

test('initial document has a dark canvas before scripts load and a custom theme can override it', async (t) => {
    const { page, options, base } = await fixture(t);
    options.themeCatalog = {
        defaultTheme: 'server:bright',
        themes: [
            {
                id: 'bright',
                name: 'Bright',
                version: '1',
                supportedModVersion: '1.2.x',
                views: ['home'],
                entry: 'theme.js',
                styles: ['style.css']
            }
        ]
    };
    options.themeFiles = {
        '/themes/bright/theme.js':
            'export default {id:"bright",mount(root){root.innerHTML="<h1>Custom theme ready</h1>";}};',
        '/themes/bright/style.css': 'html { background-color: rgb(230, 240, 250); color: black; }'
    };
    let release;
    const scripts = new Promise((resolve) => {
        release = resolve;
    });
    t.after(() => release());
    await page.route('**/assets/web/**/*.js', async (route) => {
        await scripts;
        await route.continue();
    });
    await page.goto(base, { waitUntil: 'commit' });
    await page.getByRole('status').waitFor();
    const initial = await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
    const channels = initial.match(/[\d.]+/g).map(Number);
    assert.ok(
        channels.slice(0, 3).every((channel) => channel < 90),
        initial
    );
    assert.ok(channels.length === 3 || channels[3] === 1, 'The initial canvas must be opaque');
    release();
    await page.getByRole('heading', { name: 'Custom theme ready' }).waitFor();
    assert.equal(
        await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor),
        'rgb(230, 240, 250)'
    );
});

test('a theme that never finishes loading still offers navigation to safe mode', async (t) => {
    const { page, options, base } = await fixture(t);
    options.themeCatalog = {
        defaultTheme: 'server:waiting',
        themes: [
            {
                id: 'waiting',
                name: 'Waiting layout',
                version: '1',
                supportedModVersion: '1.2.x',
                views: ['home'],
                entry: 'theme.js',
                styles: []
            }
        ]
    };
    options.themeFiles = {
        '/themes/waiting/theme.js': 'await new Promise(() => {}); export default {id:"waiting",mount(){}};'
    };
    const importing = page.waitForRequest((request) => request.url().endsWith('/themes/waiting/theme.js'));
    await page.goto(base);
    await importing;
    assert.equal(await page.getByRole('button', { name: 'Themes', exact: true }).count(), 0);
    await page.clock.fastForward(1500);
    await page.getByRole('link', { name: 'Open default UI in safe mode' }).click();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(new URL(page.url()).searchParams.get('safe'), '1');
    assert.equal(options.requests.filter((r) => r.path === '/themes/waiting/theme.js').length, 1);
});

test('server theme rendering failure returns to built-in and version mismatch remains advisory', async (t) => {
    const { page, options, base } = await fixture(t);
    const manifest = {
        id: 'broken',
        name: 'Broken layout',
        version: '1',
        supportedModVersion: '1.2.x',
        views: ['home'],
        entry: 'theme.js',
        styles: []
    };
    options.themeCatalog = { defaultTheme: 'server:broken', themes: [manifest] };
    options.themeFiles = {
        '/themes/broken/theme.js': 'export default {id:"broken",mount(){throw new Error("Renderer unavailable");}};'
    };
    await page.goto(base);
    await page.getByRole('dialog').getByRole('alert').filter({ hasText: 'Renderer unavailable' }).waitFor();
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    options.themeFiles['/themes/broken/theme.js'] =
        'export default {id:"broken",mount(root,app,ctx){root.innerHTML="<h1>Different version layout</h1><button>Theme settings</button>";root.querySelector("button").onclick=()=>ctx.themes.open();}};';
    await page.reload();
    await page.getByRole('heading', { name: 'Different version layout' }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Themes', exact: true }).count(), 0);
    await page.getByRole('button', { name: 'Theme settings', exact: true }).click();
    await page.getByRole('status').filter({ hasText: '1.2.x' }).waitFor();
    assert.equal(await page.getByRole('heading', { name: 'Only install themes you trust' }).count(), 0);
});

for (const width of [390, 1280]) {
    test(`server default theme is trusted and browser can explicitly override or follow it at ${width}px`, async (t) => {
        const { page, options, base } = await fixture(t, '/panel', { viewport: { width, height: 844 } });
        const manifest = {
            id: 'sample',
            name: 'Sample layout',
            version: '1',
            supportedModVersion: '9.8.x',
            views: ['home'],
            entry: 'theme.js',
            styles: []
        };
        options.themeCatalog = { defaultTheme: 'server:sample', themes: [manifest] };
        options.themeFiles = {
            '/themes/sample/theme.js': `export default {id:'sample',mount(root,app,ctx){root.innerHTML='<h1>Server layout</h1><button>Theme settings</button>';root.querySelector('button').onclick=()=>ctx.themes.open();app.subscribe(state=>root.dataset.networks=String(state.grids.length));}};`
        };
        await page.goto(base);
        await page.getByRole('heading', { name: 'Server layout' }).waitFor();
        assert.equal(await page.getByRole('dialog').count(), 0);
        assert.equal(await page.getByRole('button', { name: 'Themes', exact: true }).count(), 0);
        await page.getByRole('button', { name: 'Theme settings', exact: true }).click();
        await page.getByRole('combobox', { name: 'Choose a theme', exact: true }).selectOption('builtin:default');
        await page.getByRole('button', { name: 'Apply', exact: true }).click();
        await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
        await page.reload();
        await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('button', { name: 'Choose a theme', exact: true }).click();
        await page.getByRole('combobox', { name: 'Choose a theme', exact: true }).selectOption('');
        await page.getByRole('button', { name: 'Apply', exact: true }).click();
        await page.getByRole('heading', { name: 'Server layout' }).waitFor();
    });
}

for (const width of [390, 1280]) {
    test(`default theme font choice persists and keeps resources usable at ${width}px`, async (t) => {
        const { page, base } = await fixture(t, '', { viewport: { width, height: 844 } });
        await page.goto(`${base}#/web-settings`);
        const font = page.getByRole('combobox', { name: 'Font', exact: true });
        await font.waitFor();
        assert.equal(await font.inputValue(), 'monocraft');
        assert.deepEqual(await font.locator('option').allTextContents(), ['Monocraft', 'Lato']);
        const fontResponse = page.waitForResponse((r) => r.url().endsWith('/fonts/lato/Lato-Regular.woff2'));
        await font.selectOption('lato');
        assert.equal((await fontResponse).status(), 200);
        await page.evaluate(() => document.fonts.ready);
        assert.ok(
            await page.evaluate(() =>
                Array.from(document.fonts).some((face) => face.family === 'Lato' && face.status === 'loaded')
            )
        );
        assert.match(
            await page
                .getByRole('heading', { name: 'Web settings', exact: true })
                .evaluate((n) => getComputedStyle(n).fontFamily),
            /Lato/
        );
        await page.reload();
        await font.waitFor();
        assert.equal(await font.inputValue(), 'lato');
        await page.goto(`${base}#/grids/${gridA}/items`);
        const resource = page.getByRole('button', { name: /Iron Ingot/ });
        await resource.waitFor();
        assert.match(await resource.evaluate((n) => getComputedStyle(n).fontFamily), /Lato/);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        await resource.click();
        await page.getByRole('heading', { name: 'Resource details', exact: true }).waitFor();
        await page.goto(`${base}#/web-settings`);
        await page.evaluate(() => {
            const key = 'ae2web:/:theme:default';
            const settings = JSON.parse(localStorage.getItem(key));
            localStorage.setItem(key, JSON.stringify({ ...settings, font: 'montserrat' }));
        });
        await page.reload();
        await font.waitFor();
        assert.equal(await font.inputValue(), 'lato');
        await font.selectOption('monocraft');
        await page.reload();
        await font.waitFor();
        assert.equal(await font.inputValue(), 'monocraft');
        assert.match(
            await page
                .getByRole('heading', { name: 'Web settings', exact: true })
                .evaluate((n) => getComputedStyle(n).fontFamily),
            /Monocraft/
        );
    });
}

test('server themes support relative modules and stylesheet assets, and safe mode skips their code', async (t) => {
    const { page, options, base } = await fixture(t, '/panel');
    const manifest = {
        id: 'portable',
        name: 'Server theme',
        version: '1',
        supportedModVersion: '9.8.x',
        views: ['home'],
        entry: 'js/theme.js',
        styles: ['styles/theme.css']
    };
    const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><rect width="2" height="2" fill="blue"/></svg>';
    options.themeCatalog = { defaultTheme: 'server:portable', themes: [manifest] };
    options.themeFiles = {
        '/themes/portable/js/theme.js': `import { title } from './title.js';
            export default {id:'portable',mount(root,app,ctx){
                root.innerHTML='<h1 class="portable-title"></h1><img alt="Package asset"><button>Load detail</button><button class="theme-settings">Theme settings</button>';
                root.querySelector('h1').textContent=title;
                root.querySelector('img').src=ctx.assets.url('images/pixel.svg');
                root.querySelector('.theme-settings').onclick=()=>ctx.themes.open();
                root.querySelector('button').onclick=async()=>{root.querySelector('h1').textContent=(await import('./detail.js')).title};
            }};`,
        '/themes/portable/js/title.js': 'export const title="Server layout";',
        '/themes/portable/js/detail.js': 'export const title="Detail loaded";',
        '/themes/portable/styles/theme.css':
            '@import "./colors.css"; .portable-title { background-image: url("../images/pixel.svg"); }',
        '/themes/portable/styles/colors.css':
            '.portable-title { color: rgb(15, 80, 125); } body { --installed-theme-marker: active; }',
        '/themes/portable/images/pixel.svg': svg
    };
    await page.goto(base);
    await page.getByRole('heading', { name: 'Server layout' }).waitFor();
    await page.waitForFunction(
        () => getComputedStyle(document.querySelector('.portable-title')).color === 'rgb(15, 80, 125)'
    );
    await page.waitForFunction(() => document.querySelector('img[alt="Package asset"]').naturalWidth === 2);
    assert.ok(options.requests.some((r) => r.path === '/themes/portable/styles/colors.css'));
    assert.equal(
        await page.locator('.portable-title').evaluate((node) => getComputedStyle(node).backgroundImage),
        `url("${base}themes/portable/images/pixel.svg")`
    );
    await page.getByRole('button', { name: 'Load detail' }).click();
    await page.getByRole('heading', { name: 'Detail loaded' }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Themes', exact: true }).count(), 0);
    await page.getByRole('button', { name: 'Theme settings', exact: true }).click();
    const dialog = page.getByRole('dialog');
    assert.equal(await dialog.locator('input[type=file]').count(), 0);
    await page.getByRole('combobox', { name: 'Choose a theme', exact: true }).selectOption('builtin:default');
    await page.getByRole('button', { name: 'Apply', exact: true }).click();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(
        await page
            .locator('body')
            .evaluate((node) => getComputedStyle(node).getPropertyValue('--installed-theme-marker').trim()),
        ''
    );
    const modules = options.requests.filter((r) => r.path.endsWith('/js/theme.js')).length;
    await page.goto(`${base}?safe=1#/web-settings`);
    await page.getByRole('button', { name: 'Choose a theme', exact: true }).waitFor();
    assert.equal(options.requests.filter((r) => r.path.endsWith('/js/theme.js')).length, modules);
});

// Public browser/HTTP seam: resource-to-provider relationships navigate the existing history,
// including later pages and names whose rendered text matches while their raw identity differs.
test('history resource provider links reveal the exact group across sorting and pagination without refetching', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const provider = historyDetail.interfaceShare[0];
    options.historyDetail = {
        ...historyDetail,
        items: [{ ...historyDetail.items[0], providers: ['§aShared machine', '§bShared machine', 'Unavailable'] }],
        interfaceShare: [
            {
                ...provider,
                name: '§aShared machine',
                timingsCombined: 10000,
                location: [{ dimensionId: 'test:green', x: 1, y: 2, z: 3 }]
            },
            ...Array.from({ length: 30 }, (_, index) => ({
                ...provider,
                name: `Machine ${index}`,
                timingsCombined: 5000
            })),
            {
                ...provider,
                name: '§bShared machine',
                timingsCombined: 1,
                location: [{ dimensionId: 'test:blue', x: 4, y: 5, z: 6 }]
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const search = page.getByRole('searchbox', { name: 'Search history', exact: true });
    const resource = page.getByRole('region', { name: 'Iron Ingot', exact: true });
    await search.fill('Iron');
    await resource.locator('summary').click();
    const names = resource.getByRole('list', { name: 'Crafted by', exact: true });
    await names.waitFor();
    assert.equal(await names.getByRole('button').count(), 2);
    assert.equal(await names.getByText('Unavailable', { exact: true }).count(), 1);
    const initialRequests = options.requests.filter((request) => request.path.endsWith('/crafting-history/1')).length;
    assert.equal(initialRequests, 1);
    await names.getByRole('button', { name: 'Shared machine', exact: true }).nth(1).click();
    await page.getByRole('list', { name: 'Pattern providers', exact: true }).waitFor();
    assert.equal(await search.inputValue(), '');
    const target = page.getByRole('region', { name: 'Shared machine', exact: true });
    await target.getByText(/test:blue.*4.*5.*6/).waitFor();
    assert.equal(await target.locator('summary').evaluate((node) => node === document.activeElement), true);
    const pager = page.getByRole('navigation', { name: 'History pages', exact: true });
    assert.equal(await pager.getByRole('button', { name: 'Previous page', exact: true }).isEnabled(), true);
    assert.equal(await pager.getByRole('button', { name: 'Next page', exact: true }).isDisabled(), true);
    const summaryBounds = await target.locator('summary').boundingBox();
    const viewportBounds = await page.getByRole('region', { name: 'History', exact: true }).boundingBox();
    const intervalHeading = await target.getByRole('heading', { name: 'Exact intervals', exact: true }).boundingBox();
    assert.ok(
        intervalHeading.y + intervalHeading.height <= viewportBounds.y + viewportBounds.height,
        'Lookup must reveal expanded details, not only the target summary'
    );
    assert.ok(
        summaryBounds.y >= viewportBounds.y - 1 &&
            summaryBounds.y + summaryBounds.height <= viewportBounds.y + viewportBounds.height + 1,
        JSON.stringify({ summaryBounds, viewportBounds })
    );
    await poll(page);
    assert.equal(await target.locator('details').getAttribute('open'), '');
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/crafting-history/1')).length,
        initialRequests
    );
    await page.getByRole('button', { name: 'Resources', exact: true }).click();
    await page.getByRole('combobox', { name: 'Sort by', exact: true }).selectOption('name');
    await resource.locator('summary').click();
    await names.getByRole('button', { name: 'Shared machine', exact: true }).first().click();
    const targets = page.getByRole('region', { name: 'Shared machine', exact: true });
    await targets
        .first()
        .getByText(/test:green.*1.*2.*3/)
        .waitFor();
    assert.equal(
        await targets
            .first()
            .locator('summary')
            .evaluate((node) => node === document.activeElement),
        true
    );
    assert.equal(await targets.last().locator('details').getAttribute('open'), null);
});

test('history mounts provider links only for expanded resources and keeps every large relation reachable', async (t) => {
    const { page, options, base } = await fixture(t);
    const names = Array.from({ length: 61 }, (_, index) => `Machine ${String(index).padStart(2, '0')}`);
    options.historyDetail = {
        ...historyDetail,
        items: [
            { ...historyDetail.items[0], providers: names },
            { ...historyDetail.items[0], displayName: 'Unmeasured resource', providers: [] }
        ],
        interfaceShare: names.map((name) => ({ ...historyDetail.interfaceShare[0], name }))
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const resource = page.getByRole('region', { name: 'Iron Ingot', exact: true });
    await resource.waitFor();
    assert.equal(await resource.getByRole('list', { name: 'Crafted by', exact: true }).count(), 0);
    await resource.locator('summary').click();
    const providers = resource.getByRole('list', { name: 'Crafted by', exact: true });
    await providers.waitFor();
    assert.equal(await providers.getByRole('button').count(), 25);
    const pages = resource.getByRole('navigation', { name: 'Provider pages', exact: true });
    await pages.getByRole('button', { name: 'Last page', exact: true }).click();
    assert.equal(await providers.getByRole('button').count(), 11);
    await providers.getByRole('button', { name: 'Machine 60', exact: true }).click();
    const group = page.getByRole('region', { name: 'Machine 60', exact: true });
    await group.getByRole('list', { name: 'Exact intervals', exact: true }).waitFor();
    assert.equal(await group.locator('summary').evaluate((node) => node === document.activeElement), true);
    await page.getByRole('button', { name: 'Resources', exact: true }).click();
    const empty = page.getByRole('region', { name: 'Unmeasured resource', exact: true });
    await empty.locator('summary').click();
    await empty.getByRole('list', { name: 'Exact intervals', exact: true }).waitFor();
    assert.equal(await empty.getByRole('list', { name: 'Crafted by', exact: true }).count(), 0);
    assert.equal(await empty.getByRole('button').count(), 0);
});

test('history prioritizes only the measured final resource identity and keeps search effective', async (t) => {
    const { page, options, base } = await fixture(t);
    const result = {
        ...historyDetail.items[0],
        itemKey: 'final',
        displayName: 'Zeta',
        timeSpentOn: 0,
        craftedTotal: 1,
        timings: []
    };
    options.historyDetail = {
        ...historyDetail,
        finalOutput: { ...iron, itemKey: 'final', displayName: 'Zeta' },
        items: [
            { ...result, itemKey: 'other-variant', craftedTotal: 99, timeSpentOn: 9000 },
            { ...result, itemKey: 'ingredient', displayName: 'Alpha', craftedTotal: 50, timeSpentOn: 5000 },
            result
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const resources = page.getByRole('list', { name: 'Resources', exact: true });
    for (const sort of ['time', 'quantity', 'name']) {
        await page.getByRole('combobox', { name: 'Sort by', exact: true }).selectOption(sort);
        const first = resources.getByRole('listitem').first();
        await first.getByRole('img', { name: 'Final product', exact: true }).waitFor();
        assert.match(await first.locator('summary').innerText(), /0 s/);
        assert.equal(await resources.getByRole('img', { name: 'Final product', exact: true }).count(), 1);
    }
    const marker = resources.getByRole('img', { name: 'Final product', exact: true });
    await marker.scrollIntoViewIfNeeded();
    await settleFrames(page);
    await marker.hover();
    await page.getByRole('tooltip').filter({ hasText: 'Final product' }).waitFor();
    await page.getByRole('searchbox', { name: 'Search history', exact: true }).fill('Alpha');
    assert.equal(await resources.getByRole('listitem').count(), 1);
    assert.equal(await resources.getByRole('img', { name: 'Final product', exact: true }).count(), 0);
    options.historyDetail.items = options.historyDetail.items.slice(0, 2);
    await page.reload();
    await resources.getByRole('heading', { name: 'Alpha', exact: true }).waitFor();
    assert.equal(await resources.getByRole('listitem').count(), 2);
    assert.equal(await resources.getByRole('img', { name: 'Final product', exact: true }).count(), 0);
});

// Public UI/HTTP seam: server capability overrides presentation without overwriting user intent.
test('resource crafting opens one accessible dialog from details, middle click and context menu', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const resource = page.getByRole('button', { name: /Iron Ingot/ });
    await resource.click();
    assert.equal(await page.getByRole('textbox').isVisible(), false);
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    const dialog = page.getByRole('dialog');
    const quantity = dialog.getByRole('textbox');
    await quantity.fill('37');
    await poll(page);
    assert.equal(await quantity.inputValue(), '37');
    assert.equal(await quantity.evaluate((input) => input === document.activeElement), true);
    await page.keyboard.press('Escape');
    assert.equal(await dialog.isVisible(), false);
    assert.equal(
        await page
            .getByRole('button', { name: 'Craft', exact: true })
            .evaluate((node) => node === document.activeElement),
        true
    );
    await resource.click({ button: 'middle' });
    await dialog.waitFor();
    await page.keyboard.press('Escape');
    await resource.click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Craft', exact: true }).click();
    await dialog.waitFor();
    await page.keyboard.press('Escape');
    await resource.focus();
    await page.keyboard.press('Shift+F10');
    await page.getByRole('menuitem', { name: 'Craft', exact: true }).waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('menu').isVisible(), false);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

test('autostart shows calculation before returning to the terminal after one native submission', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.pendingReads = 1;
    options.cpus = {};
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('textbox').fill('23');
    await dialog.getByRole('checkbox', { name: 'Start automatically', exact: true }).check();
    await dialog.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('heading', { name: 'Crafting plan', exact: true }).waitFor();
    assert.match(page.url(), /\/plans\/7$/);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/submit')).length, 0);
    await page.clock.fastForward(1200);
    await page.waitForURL(/\/items$/);
    assert.match(page.url(), /\/items$/);
    assert.equal(await dialog.isVisible(), false);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const notifications = page.getByRole('region', { name: 'Notifications', exact: true });
    await notifications.getByText('Crafting started', { exact: true }).waitFor();
    assert.match(await notifications.innerText(), /Iron Ingot/);
    await notifications.hover();
    await page.clock.fastForward(6000);
    assert.equal(await notifications.isVisible(), true);
    await page.mouse.move(0, 0);
    assert.deepEqual(
        options.requests.filter((r) => r.path.endsWith('/submit')).map((r) => r.body),
        [{}]
    );
    assert.deepEqual(
        options.requests.filter((r) => r.method === 'POST' && r.path.endsWith('/crafting-plans')).map((r) => r.body),
        [{ itemKey: 'iron', quantity: 23, autoStart: true }]
    );
    await page.clock.fastForward(6000);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/submit')).length, 1);
    assert.equal(await notifications.isVisible(), false);
});

for (const width of [390, 1280, 1920]) {
    test(`CPU action notifications survive navigation without shifting the view at ${width}px`, async (t) => {
        const { page, options, base } = await fixture(t);
        await page.setViewportSize({ width, height: 844 });
        options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
        options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const pause = page.getByRole('button', { name: /^Pause current work/ });
        await pause.waitFor();
        const grid = page.getByRole('region', { name: 'CPU resources', exact: true });
        const before = await grid.boundingBox();
        await pause.click();
        const notifications = page.getByRole('region', { name: 'Notifications', exact: true });
        await notifications.getByText('CPU paused', { exact: true }).waitFor();
        assert.deepEqual(await grid.boundingBox(), before);
        const box = await notifications.boundingBox();
        assert.ok(box.x >= 0 && box.x + box.width <= width);
        if (width >= 1280) {
            const header = await page.locator('.control-panel').boundingBox();
            assert.ok(box.x >= header.x + header.width + 8, 'Notifications belong beside the main window');
            assert.ok(Math.abs(box.y - header.y) <= 1, 'Notifications align with the top of the main window');
        }
        const close = notifications.getByRole('button', { name: 'Dismiss notification' });
        await close.focus();
        await page.mouse.move(0, 0);
        await page.clock.fastForward(10000);
        assert.equal(await notifications.isVisible(), true);
        if (process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, `notification-cpu-${width}.png`) });
        }
        if (width === 1920) {
            await page.setViewportSize({ width: 390, height: 844 });
            await page.waitForFunction(() => {
                const bounds = document.querySelector('[aria-label="Notifications"]').getBoundingClientRect();
                return bounds.x >= 0 && bounds.right <= innerWidth;
            });
            const narrow = await notifications.boundingBox();
            assert.ok(narrow.x >= 0 && narrow.x + narrow.width <= 390);
            await page.setViewportSize({ width, height: 844 });
            await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
            const restored = await notifications.boundingBox();
            const header = await page.locator('.control-panel').boundingBox();
            assert.ok(restored.x >= header.x + header.width + 8);
            assert.ok(Math.abs(restored.y - header.y) <= 1);
        }
        await page.evaluate(() => {
            location.hash = '#/web-settings';
        });
        await page.getByRole('heading', { name: 'Web settings', exact: true }).waitFor();
        assert.equal(await notifications.isVisible(), true);
        await close.click();
        assert.equal(await notifications.isVisible(), false);
    });
}

test('manual starts keep a bounded notification queue and polling never recreates expired confirmations', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    for (let attempt = 0; attempt < 4; attempt++) {
        if (attempt)
            await page.evaluate(
                ({ key, id }) => {
                    location.hash = `#/grids/${key}/plans/${id}`;
                },
                { key: gridA, id: 7 + attempt }
            );
        await page.getByRole('button', { name: 'Start', exact: true }).click();
        await page.waitForURL(/\/items$/);
    }
    const notifications = page.getByRole('region', { name: 'Notifications', exact: true });
    assert.equal(await notifications.getByRole('button', { name: 'Dismiss notification' }).count(), 3);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, 4);
    await page.mouse.move(0, 0);
    await page.clock.fastForward(5000);
    assert.equal(await notifications.isVisible(), false);
    const refreshed = page.waitForResponse((response) => response.url().endsWith('/api/grids'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await refreshed;
    assert.equal(await notifications.isVisible(), false);
});

test('autostart failures open the plan without repeating mutations or losing the native reason', async (t) => {
    for (const failure of ['missing', 'FAIL', 'TIMEOUT', 'NO_PERMISSIONS']) {
        const { page, options, base } = await refreshingFixture(t);
        if (failure === 'missing') options.plan = { ...readyPlan, isSimulating: true };
        else if (failure === 'TIMEOUT') options.fault = { operation: 'submit', status: failure };
        else {
            options.submitStatus = failure;
            options.submitReason = '<CPU became unavailable>';
            if (failure === 'NO_PERMISSIONS') options.submitDelay = 200;
        }
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
        await page.getByRole('checkbox', { name: 'Start automatically', exact: true }).check();
        await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
        await page.getByRole('heading', { name: 'Crafting plan', exact: true }).waitFor();
        assert.match(page.url(), /\/plans\/7$/);
        if (failure === 'FAIL') await page.getByText('<CPU became unavailable>', { exact: true }).waitFor();
        if (failure === 'TIMEOUT')
            await page
                .getByRole('status')
                .filter({ hasText: /outcome.*unknown/i })
                .waitFor();
        if (failure === 'TIMEOUT' || failure === 'FAIL') {
            const notifications = page.getByRole('region', { name: 'Notifications', exact: true });
            await notifications.waitFor();
            assert.equal(await notifications.getByText('Crafting started', { exact: true }).count(), 0);
            assert.equal(await notifications.getByRole('button', { name: 'Dismiss notification' }).count(), 1);
            if (process.env.UI_SCREENSHOT_DIR) {
                await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
                await page.screenshot({
                    path: path.join(process.env.UI_SCREENSHOT_DIR, `notification-${failure}.png`)
                });
            }
            await notifications.getByRole('button', { name: 'Dismiss notification' }).click();
            await poll(page);
            assert.equal(await notifications.isVisible(), false);
        }
        if (failure === 'NO_PERMISSIONS') {
            await page
                .getByRole('status')
                .filter({ hasText: /no longer have access/i })
                .waitFor();
            assert.equal(
                await page
                    .getByRole('list', { name: 'Plan resources', exact: true })
                    .getByRole('button', { name: /Iron Ingot/ })
                    .count(),
                0
            );
        }
        await poll(page);
        await page.goBack();
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goForward();
        await page.getByRole('heading', { name: 'Crafting plan', exact: true }).waitFor();
        assert.equal(options.requests.filter((r) => r.path.endsWith('/submit')).length, failure === 'missing' ? 0 : 1);
        assert.equal(
            options.requests.filter((r) => r.method === 'POST' && r.path.endsWith('/crafting-plans')).length,
            1
        );
    }
});

test('autostart navigation revokes pending intent and delayed plans cannot cross grids', async (t) => {
    const { page, options, base } = await fixture(t);
    let complete;
    const requested = new Promise((resolve) => {
        complete = resolve;
    });
    await page.route('**/crafting-plans/7', (route) => complete(route));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    await page.getByRole('checkbox', { name: 'Start automatically', exact: true }).check();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const pending = await requested;
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: readyPlan })
    });
    await page.clock.fastForward(6000);
    assert.match(page.url(), new RegExp(`${gridB}/items$`));
    assert.equal(await page.getByText('Iron Ingot', { exact: true }).filter({ visible: true }).count(), 0);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/submit')).length, 0);
    await page.unroute('**/crafting-plans/7');
    await page.goBack();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.clock.fastForward(6000);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/submit')).length, 0);
});

test('crafting lite mode follows authenticated server capability and only sends an explicit supported choice', async (t) => {
    for (const supported of [false, true]) {
        const { page, options, base } = await fixture(t);
        options.capabilities = { craftingLightMode: supported };
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
        const lightMode = page.getByRole('checkbox', { name: 'Lite mode', exact: true });
        if (supported) await lightMode.check();
        else assert.equal(await lightMode.isVisible(), false);
        await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
        await page.getByRole('heading', { name: 'Crafting plan', exact: true }).waitFor();
        assert.deepEqual(options.requests.find((r) => r.method === 'POST' && r.path.endsWith('/crafting-plans')).body, {
            itemKey: 'iron',
            quantity: 1,
            ...(supported ? { lightMode: true } : {})
        });
    }
});

test('a new calculation rejection after successful autostart preserves its dialog draft', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    await page.getByRole('checkbox', { name: 'Start automatically', exact: true }).check();
    const submission = page.waitForResponse((response) => response.url().endsWith('/submit'));
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await (await submission).finished();
    await page.waitForURL(/\/items$/);
    options.fault = { operation: 'create', status: 'BAD_PARAM' };
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    await page.getByRole('textbox').fill('31');
    const rejection = page.waitForResponse(
        (response) => response.request().method() === 'POST' && response.url().endsWith('/crafting-plans')
    );
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await (await rejection).finished();
    assert.equal(await page.getByRole('dialog').isVisible(), true);
    assert.equal(await page.getByRole('textbox').inputValue(), '31');
    assert.equal(await page.getByRole('button', { name: 'Calculate plan', exact: true }).isEnabled(), true);
});

test('denied crafting creation closes the private resource dialog and clears the selected resource', async (t) => {
    const { page, options, base } = await fixture(t);
    options.fault = { operation: 'create', status: 'NO_PERMISSIONS' };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    const response = page.waitForResponse((response) => response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await (await response).finished();
    assert.equal(await page.getByRole('dialog').isVisible(), false);
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Craft', exact: true }).count(), 0);
});

test('craft dialog and contextual menu fit a narrow viewport and close through outside interaction', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 844 }, hasTouch: true });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).tap();
    await page.getByRole('button', { name: 'Craft', exact: true }).tap();
    const dialog = page.getByRole('dialog');
    const bounds = await dialog.boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
    assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 844);
    await dialog.getByRole('button', { name: 'Close', exact: true }).tap();
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    await item.focus();
    await page.keyboard.press('ContextMenu');
    const menu = page.getByRole('menu');
    const menuBounds = await menu.boundingBox();
    assert.ok(menuBounds.x >= 0 && menuBounds.x + menuBounds.width <= 390);
    assert.ok(menuBounds.y >= 0 && menuBounds.y + menuBounds.height <= 844);
    await page.getByRole('heading', { name: /AE2/ }).tap();
    assert.equal(await menu.isVisible(), false);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

test('unavailable icons override the saved mode and recover without losing it', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    await page.goto(`${base}#/web-settings`);
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await mode.selectOption('icons');
    options.pack = { available: false, packId: null, width: 0, height: 0 };
    await page.reload();
    await page.getByText(/server.*not.*icon pack/i).waitFor();
    assert.equal(await mode.inputValue(), 'names');
    assert.equal(
        await mode.getByRole('option', { name: 'Icons only', exact: true }).evaluate((option) => option.disabled),
        true
    );
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    await item.waitFor();
    assert.equal(
        new URLSearchParams(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query).has('icons'),
        false
    );
    assert.equal(await item.getByText(iron.displayName, { exact: true }).isVisible(), true);
    options.pack = { available: true, packId: 'a'.repeat(64), width: 64, height: 64 };
    await poll(page);
    await page.waitForFunction(() => {
        const item = document.querySelector('#items button');
        const rect = item.getBoundingClientRect();
        return Math.abs(rect.width - rect.height) < 1;
    });
    await clickTab(page, 'Web settings');
    assert.equal(await mode.inputValue(), 'icons');
    await page.reload();
    await mode.waitFor();
    await page.waitForFunction(() => document.querySelector('#terminal-display')?.value === 'icons');
});

test('pending icon discovery leaves inventory usable and failures preserve confirmed availability', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    let release;
    const held = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/icon-pack', async (route) => {
        await held;
        await route.continue();
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    await item.waitFor();
    assert.equal(await item.getByText(iron.displayName, { exact: true }).isVisible(), true);
    assert.equal(
        new URLSearchParams(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query).has('icons'),
        false
    );
    release();
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    options.packError = true;
    await poll(page);
    await clickTab(page, 'Web settings');
    await page.getByText(/could not check.*icon/i).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'both');
});

test('late confirmation of absent icons does not reload already usable items or CPU detail', async (t) => {
    const { page, options, base } = await fixture(t);
    options.packDelay = 750;
    for (const view of ['items', 'cpus/cpu-a']) {
        const discovery = page.waitForRequest((request) => request.url().endsWith('/api/icon-pack'));
        await page.goto('about:blank');
        await page.goto(`${base}#/grids/${gridA}/${view}`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const reads = () => options.requests.filter((request) => request.path.endsWith(`/${view}`));
        assert.equal(reads().length, 1);
        await settleResponse(page, await discovery);
        assert.equal(reads().length, 1);
    }
});

test('initial icon discovery error stays distinct from absence and recovers', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.packError = true;
    await page.goto(`${base}#/web-settings`);
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await page.getByText(/could not check.*icon/i).waitFor();
    assert.equal(await page.getByText(/server.*not.*icon pack/i).isVisible(), false);
    assert.equal(await mode.inputValue(), 'names');
    options.packError = false;
    await poll(page);
    await page.waitForFunction(() => document.querySelector('#terminal-display')?.value === 'both');
    options.pack = { available: false, packId: null, width: 0, height: 0 };
    await poll(page);
    await page.getByText(/server.*not.*icon pack/i).waitFor();
    options.packError = true;
    await poll(page);
    await page.getByText(/could not check.*icon/i).waitFor();
    assert.equal(await page.getByText(/server.*not.*icon pack/i).isVisible(), false);
    assert.equal(await mode.inputValue(), 'names');
});

test('superseded slow discovery cannot overwrite recovered availability after a grid failure', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.pack = { available: false, packId: null, width: 0, height: 0 };
    options.packDelay = 2000;
    options.gridError = 'NO_PERMISSIONS';
    const firstDiscovery = page.waitForRequest((r) => r.url().endsWith('/api/icon-pack'));
    await page.goto(`${base}#/web-settings`);
    const oldRequest = await firstDiscovery;
    await page.waitForFunction(() => document.querySelector('#network-message')?.textContent.length > 0);
    options.gridError = null;
    options.packDelay = 0;
    options.pack = { available: true, packId: 'a'.repeat(64), width: 64, height: 64 };
    await poll(page);
    await page.waitForFunction(() => document.querySelector('#terminal-display')?.value === 'both');
    await settleResponse(page, oldRequest);
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'both');
    assert.equal(await page.getByText(/server.*not.*icon pack/i).isVisible(), false);
});

for (const saved of [
    { resourceIcons: true, terminalDisplay: 'compact', expected: 'both' },
    { resourceIcons: false, terminalDisplay: 'icons', expected: 'icons' }
]) {
    test(`terminal display ignores obsolete icon preferences and preserves current ${saved.terminalDisplay ?? 'missing'} choice`, async (t) => {
        const { page, options, base } = await fixture(t);
        await atlasFixture(page, options);
        await page.addInitScript(({ resourceIcons, terminalDisplay }) => {
            const key = 'ae2web:/:theme:default';
            if (!localStorage.getItem(key))
                localStorage.setItem(key, JSON.stringify({ resourceIcons, terminalDisplay }));
        }, saved);
        await page.goto(`${base}#/web-settings`);
        const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
        await mode.waitFor();
        await page.waitForFunction(
            (expected) => document.querySelector('#terminal-display')?.value === expected,
            saved.expected
        );
        await mode.selectOption('names');
        await page.reload();
        await mode.waitFor();
        assert.equal(await mode.inputValue(), 'names');
    });
}

test('saved names choice receives a dismissible icon notice outside the terminal', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.addInitScript(() => {
        const key = 'ae2web:/:theme:default';
        if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({ terminalDisplay: 'names' }));
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const enable = page.getByRole('button', { name: 'Enable icons', exact: true });
    await enable.waitFor();
    assert.equal(
        new URLSearchParams(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query).has('icons'),
        false
    );
    await page.evaluate(() => document.fonts.ready);
    await settleFrames(page);
    const panel = await page.getByRole('navigation').boundingBox();
    const notice = await enable.boundingBox();
    const terminal = await page.getByRole('searchbox').boundingBox();
    assert.ok(notice.y > panel.y + panel.height && notice.y + notice.height < terminal.y);
    await page.getByRole('button', { name: 'Dismiss icon suggestion', exact: true }).click();
    await page.reload();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.isVisible(), false);
    await clickTab(page, 'Web settings');
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'names');
});

// Public browser seam: compact presentation persists without changing resource identity or CPU presentation.
test('enabling suggested icons persists and a later names choice does not nag', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.addInitScript(() => {
        const key = 'ae2web:/:theme:default';
        if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({ terminalDisplay: 'names' }));
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const enable = page.getByRole('button', { name: 'Enable icons', exact: true });
    await enable.click();
    await page.reload();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.isVisible(), false);
    await clickTab(page, 'Web settings');
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    assert.equal(await mode.inputValue(), 'both');
    await mode.selectOption('names');
    await clickTab(page, 'Terminal');
    await page.reload();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.isVisible(), false);
});

test('missing resource sprites share a question mark in detailed inventory and CPU', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    await item.waitFor();
    const fallback = () =>
        item.locator('.resource-icon').evaluate((icon) => getComputedStyle(icon, '::before').content);
    assert.equal(await fallback(), '"?"');
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await clickTab(page, 'Terminal');
    assert.equal(await item.locator('.resource-icon').isVisible(), false);
    await clickTab(page, 'CPUs');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuItem = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await cpuItem.waitFor();
    assert.equal(await cpuItem.locator('.resource-icon').isVisible(), false);
    const detailQuery = options.requests.filter((r) => r.path.endsWith('/cpus/cpu-a')).at(-1).query;
    assert.equal(new URLSearchParams(detailQuery).has('icons'), false);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await cpuItem.waitFor();
    assert.equal(
        await cpuItem.locator('.resource-icon').evaluate((icon) => getComputedStyle(icon, '::before').content),
        '"?"'
    );
    const iconQuery = options.requests.filter((r) => r.path.endsWith('/cpus/cpu-a')).at(-1).query;
    assert.equal(new URLSearchParams(iconQuery).get('icons'), 'true');
});

test('compact terminal mode persists and preserves accessible selection and crafting', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }, quartz];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    await item.waitFor();
    const original = await item.boundingBox();
    assert.ok(original.width > original.height);
    await clickTab(page, 'Web settings');
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await mode.waitFor();
    assert.equal(await mode.inputValue(), 'both');
    await mode.selectOption('icons');
    await page.reload();
    await mode.waitFor();
    await page.waitForFunction(
        (select) => !select.querySelector('option[value="icons"]').disabled,
        await mode.elementHandle()
    );
    assert.equal(await mode.inputValue(), 'icons');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await item.waitFor();
    const compact = await item.boundingBox();
    assert.ok(Math.abs(compact.width - compact.height) < 1, 'compact item slots are square');
    assert.ok(compact.width < original.width);
    const nameBox = await item.getByText(iron.displayName, { exact: true }).boundingBox();
    assert.ok(!nameBox || nameBox.width <= 1, 'the item name is not permanently painted in the slot');
    await item.hover();
    await page.getByRole('tooltip').getByText(iron.displayName, { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await item.focus();
    await page.getByRole('tooltip').getByText(iron.displayName, { exact: true }).waitFor();
    await page.keyboard.press('Enter');
    assert.equal(await item.getAttribute('aria-pressed'), 'true');
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('textbox', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    const planItem = page
        .getByRole('region', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await planItem.waitFor();
    assert.equal(await planItem.getByText(iron.displayName, { exact: true }).isVisible(), false);
    assert.deepEqual(
        options.requests.find((request) => request.method === 'POST' && request.path.endsWith('/crafting-plans')).body,
        { itemKey: 'iron', quantity: 12 }
    );
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuItem = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await cpuItem.waitFor();
    const cpuBox = await cpuItem.boundingBox();
    assert.ok(cpuBox.width > cpuBox.height, 'CPU resources retain their rectangular layout');
    assert.equal(await cpuItem.getByText(iron.displayName, { exact: true }).isVisible(), false);
    await cpuItem.focus();
    await page.getByRole('tooltip').getByText(iron.displayName, { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await clickTab(page, 'Web settings');
    await mode.selectOption('both');
    await page.reload();
    await mode.waitFor();
    await page.waitForFunction(
        (select) => !select.querySelector('option[value="icons"]').disabled,
        await mode.elementHandle()
    );
    assert.equal(await mode.inputValue(), 'both');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await cpuItem.waitFor();
    assert.equal(await cpuItem.getByText(iron.displayName, { exact: true }).isVisible(), true);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await item.waitFor();
    assert.equal(await item.getByText(iron.displayName, { exact: true }).isVisible(), true);
});

// Public rendered geometry seam: responsive square slots contain sprites that stay still on hover.
for (const deviceScaleFactor of [1, 1.5]) {
    test(`compact sprite layout stays within the viewport and stable on hover at DPR ${deviceScaleFactor}`, async (t) => {
        const { page, options, base } = await fixture(t, '', { deviceScaleFactor });
        await atlasFixture(page, options);
        options.itemsA = Array.from({ length: 80 }, (_, index) => ({
            ...iron,
            displayName: `Resource ${index}`,
            itemKey: `resource-${index}`,
            icon: { page: 0, x: 0, y: 0 }
        }));
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
        await page.goto(`${base}#/grids/${gridA}/items`);
        const items = page.locator('#items').getByRole('button');
        await items.first().waitFor();
        await page.evaluate(() => document.fonts.ready);
        for (const width of [997, 1031, 1003, 391]) {
            await page.setViewportSize({ width, height: 844 });
            await items.first().scrollIntoViewIfNeeded();
            await settleFrames(page);
            const bounds = await items.first().evaluate((button) => {
                const slot = button.getBoundingClientRect();
                const list = button.closest('ul').getBoundingClientRect();
                const viewport = document.querySelector('#item-scroll');
                const clip = viewport.getBoundingClientRect();
                return {
                    firstTop: slot.top,
                    gridRight: list.right,
                    viewportTop: clip.top + viewport.clientTop,
                    viewportRight: clip.left + viewport.clientLeft + viewport.clientWidth
                };
            });
            assert.ok(bounds.firstTop >= bounds.viewportTop - 0.03, 'the first slot border is not clipped');
            assert.ok(bounds.gridRight <= bounds.viewportRight + 0.03, 'the last column stays inside the viewport');
            const read = () =>
                items.evaluateAll((buttons) =>
                    buttons.slice(0, 8).map((button) => {
                        const slot = button.getBoundingClientRect();
                        const icon = button.querySelector('.resource-icon').getBoundingClientRect();
                        return {
                            x: icon.x,
                            y: icon.y,
                            width: icon.width,
                            height: icon.height,
                            slotWidth: slot.width,
                            slotHeight: slot.height
                        };
                    })
                );
            const before = await read();
            await items.first().hover();
            assert.deepEqual(await read(), before, 'hover does not move or scale sprites');
            for (const icon of before) {
                assert.equal(icon.width, width <= 600 ? 32 : 40);
                assert.equal(icon.height, width <= 600 ? 32 : 40);
                assert.ok(Math.abs(icon.slotWidth - icon.slotHeight) < 0.02, 'slots remain square');
            }
            await items.nth(35).scrollIntoViewIfNeeded();
            const scrolledIcon = items.nth(35).locator('.resource-icon');
            const beforeHover = await scrolledIcon.boundingBox();
            await items.nth(35).hover();
            assert.deepEqual(await scrolledIcon.boundingBox(), beforeHover, 'scrolled hover does not move sprites');
        }
    });
}

test('compact terminal keeps quantities and craftability readable with missing icons', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [
        { ...iron, quantity: 9999, icon: { page: 0, x: 0, y: 0 } },
        { ...quartz, quantity: 1e18, craftable: true }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
    await clickTab(page, 'Terminal');
    const missing = page.getByRole('button', { name: /Certus Quartz Crystal/ });
    await missing.waitFor();
    await page.evaluate(() => document.fonts.ready);
    for (const width of [390, 1024]) {
        await page.setViewportSize({ width, height: 844 });
        for (const [name, amount] of [
            [iron.displayName, '9,999'],
            [quartz.displayName, '1E']
        ]) {
            const item = page.getByRole('button', { name: new RegExp(name) });
            const itemBox = await item.boundingBox();
            const quantityBox = await item.getByText(amount, { exact: true }).boundingBox();
            const markerBox = await item.getByRole('img').boundingBox();
            assert.ok(Math.abs(itemBox.width - itemBox.height) < 1, 'resized slots remain square');
            assert.ok(
                quantityBox.x + quantityBox.width < itemBox.x + itemBox.width,
                'quantity stays inside the right edge'
            );
            assert.ok(markerBox.y + markerBox.height < quantityBox.y, 'craftability never overlaps the quantity');
            assert.ok(quantityBox.y > itemBox.y + itemBox.height / 2, 'quantity overlays the bottom of the slot');
            assert.ok(
                markerBox.y >= itemBox.y && markerBox.y < itemBox.y + itemBox.height / 2,
                'craftability overlays the top of the slot'
            );
            assert.ok(
                markerBox.x > itemBox.x + itemBox.width / 2 &&
                    markerBox.x + markerBox.width < itemBox.x + itemBox.width,
                'craftability stays inside the right edge'
            );
        }
    }
    await missing.focus();
    await page.getByRole('tooltip').getByText(quartz.displayName, { exact: true }).waitFor();
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).focus();
    await page.getByRole('tooltip').getByText(iron.displayName, { exact: true }).waitFor();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: 'Craft', exact: true }).waitFor();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('quartz');
    await missing.waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
});

// Public browser seam: atlas delivery, rendered sprites and the persistent display preference.
// Shared pages must not be fetched per item or again for quantity-only polling updates.
test('CPU overview and history products share atlas pages and respect the display preference without hiding names', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await atlasFixture(page, options);
    const icon = { page: 0, x: 0, y: 0 };
    options.cpus = {
        busy: { ...cpu, name: 'Busy processor', isBusy: true, finalOutput: { ...iron, icon } },
        idle: { ...cpu, name: 'Idle processor', finalOutput: { ...iron, icon } },
        unknown: { ...cpu, name: 'Unknown output', isBusy: true, finalOutput: null }
    };
    options.history = [
        { ...historyEntry, finalOutput: { ...historyEntry.finalOutput, icon } },
        { ...historyEntry, id: 2, finalOutput: { ...historyEntry.finalOutput, icon: null } }
    ];
    const atlasReads = () => options.requests.filter((request) => request.path.startsWith('/api/icon-packs/'));
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const busy = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /Busy processor/ }) });
    await busy.waitFor();
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.cpu-card .resource-icon')].some(
            (icon) => getComputedStyle(icon).backgroundImage !== 'none'
        )
    );
    assert.equal(await page.locator('.cpu-card .resource-icon:visible').count(), 1);
    assert.ok((await busy.innerText()).includes('Iron Ingot'));
    assert.equal(options.requests.filter((r) => r.path.endsWith('/cpus')).at(-1).query, '?icons=true');
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'product-icons-cpu.png') });
    }
    await clickTab(page, 'History');
    const first = page.getByRole('link', { name: /Iron Ingot.*#1$/ });
    const missing = page.getByRole('link', { name: /Iron Ingot.*#2$/ });
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.history-list .resource-icon')].some(
            (icon) => getComputedStyle(icon).backgroundImage !== 'none'
        )
    );
    assert.equal(
        await missing.locator('.resource-icon').evaluate((node) => getComputedStyle(node, '::before').content),
        '"?"'
    );
    assert.equal(
        new URLSearchParams(options.requests.filter((r) => r.path.endsWith('/crafting-history')).at(-1).query).get(
            'icons'
        ),
        'true'
    );
    assert.equal(atlasReads().length, 1, 'overview and history reuse the same atlas page');
    await page.setViewportSize({ width: 390, height: 844 });
    const bounds = await first.evaluate((node) => ({ width: node.clientWidth, content: node.scrollWidth }));
    assert.ok(bounds.content <= bounds.width + 1, 'history product icon and text fit a mobile row');
    if (process.env.UI_SCREENSHOT_DIR) {
        await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'product-icons-history-mobile.png') });
    }
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
    await page.goto(`${base}#/grids/${gridA}/history`);
    await first.waitFor();
    assert.ok((await first.innerText()).includes('Iron Ingot'), 'history remains readable in terminal icons-only mode');
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    const before = atlasReads().length;
    for (const route of ['history', 'cpus']) {
        await page.goto(`${base}#/grids/${gridA}/${route}`);
        await page.getByRole('link', { name: route === 'history' ? /Iron Ingot.*#1$/ : /Busy processor/ }).waitFor();
        assert.equal(
            await page.locator('.history-list .resource-icon:visible, .cpu-card .resource-icon:visible').count(),
            0
        );
        assert.equal(
            new URLSearchParams(
                options.requests
                    .filter((r) => r.path.endsWith(route === 'history' ? '/crafting-history' : '/cpus'))
                    .at(-1).query
            ).has('icons'),
            false
        );
    }
    assert.equal(atlasReads().length, before);
    assert.equal(
        options.requests.some((r) => /\/crafting-history\/\d+$|\/cpus\/(busy|idle|unknown)$/.test(r.path)),
        false,
        'list icons do not fetch per-row details'
    );
});

// Public UI/HTTP seam: history details use optional server mappings, never inferred icons.
test('history details display product and paged resource icons only when enabled', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    const icon = { page: 0, x: 0, y: 0 };
    options.historyDetail = {
        ...historyDetail,
        finalOutput: { ...historyDetail.finalOutput, icon },
        items: Array.from({ length: 27 }, (_, index) => ({
            ...historyDetail.items[0],
            displayName: `Material ${index}`,
            icon: index === 0 ? null : icon
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const product = page.getByRole('heading', { name: 'Iron Ingot', exact: true }).locator('..');
    const first = page.getByRole('region', { name: 'Material 0', exact: true });
    const ready = page.getByRole('region', { name: 'Material 1', exact: true });
    await first.waitFor();
    await page.waitForFunction(
        (node) =>
            !!node.querySelector('.resource-icon') &&
            getComputedStyle(node.querySelector('.resource-icon')).backgroundImage !== 'none',
        await product.elementHandle()
    );
    await ready.scrollIntoViewIfNeeded();
    await page.waitForFunction(
        (node) => getComputedStyle(node.querySelector('.resource-icon')).backgroundImage !== 'none',
        await ready.elementHandle()
    );
    assert.equal(
        await first.locator('.resource-icon').evaluate((node) => getComputedStyle(node, '::before').content),
        '"?"'
    );
    assert.equal(options.requests.filter((r) => r.path.endsWith('/crafting-history/1')).at(-1).query, '?icons=true');
    assert.equal(options.requests.filter((r) => r.path.startsWith('/api/icon-packs/')).length, 1);
    const pages = page.getByRole('navigation', { name: 'History pages', exact: true });
    await pages.getByRole('button', { name: 'Next page', exact: true }).click();
    await page.getByRole('region', { name: 'Material 25', exact: true }).waitFor();
    await poll(page);
    assert.equal(
        await page.getByRole('region', { name: 'Material 25', exact: true }).isVisible(),
        true,
        'polling preserves the selected page'
    );
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    assert.equal(await page.getByRole('region', { name: 'Smelter', exact: true }).locator('.resource-icon').count(), 0);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    const reads = options.requests.filter((r) => r.path.startsWith('/api/icon-packs/')).length;
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await first.waitFor();
    assert.equal(await page.locator('.history-detail .resource-icon:visible').count(), 0);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/crafting-history/1')).at(-1).query, '');
    assert.equal(options.requests.filter((r) => r.path.startsWith('/api/icon-packs/')).length, reads);
});

test('history detail icons refresh after late pack discovery and pack replacement without polling the snapshot', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.packDelay = 150;
    options.historyDetail = {
        ...historyDetail,
        finalOutput: { ...historyDetail.finalOutput, icon: { page: 0, x: 0, y: 0 } },
        items: [{ ...historyDetail.items[0], icon: { page: 0, x: 64, y: 0 } }]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const row = page.getByRole('region', { name: 'Iron Ingot', exact: true });
    await row.waitFor();
    // The row is re-rendered when late icon discovery refetches the detail, so look it up on every check.
    const iconPainted = () =>
        page.waitForFunction(() => {
            const icon = document.querySelector('section[aria-label="Iron Ingot"] .resource-icon');
            return !!icon && getComputedStyle(icon).backgroundImage !== 'none';
        });
    await iconPainted();
    const detailReads = () => options.requests.filter((r) => r.path.endsWith('/crafting-history/1'));
    assert.equal(detailReads()[0].query, '', 'the snapshot may arrive before icon availability');
    assert.equal(detailReads().at(-1).query, '?icons=true', 'late discovery fetches optional mappings');
    await row.locator('summary').click();
    const before = detailReads().length;
    await poll(page);
    assert.equal(detailReads().length, before, 'ordinary polling does not reread completed history');
    assert.equal(await row.getByRole('list', { name: 'Exact intervals', exact: true }).isVisible(), true);
    options.packDelay = 0;
    options.icons = { ...options.icons, packId: 'c'.repeat(64) };
    options.pack = { ...options.pack, packId: options.icons.packId };
    const refreshed = page.waitForResponse((response) => response.url().includes('/crafting-history/1?icons=true'));
    await poll(page);
    await (await refreshed).finished();
    await iconPainted();
    assert.ok(
        options.requests.some((r) => r.path.includes(`/icon-packs/${'c'.repeat(64)}/pages/`)),
        'replacement uses its new atlas URL'
    );
});

for (const view of ['cpus', 'history']) {
    test(`${view} product mappings recover from pack replacement and clear on unavailable or denied reads`, async (t) => {
        const { page, options, base } = await refreshingFixture(t);
        await atlasFixture(page, options);
        const icon = { page: 0, x: 0, y: 0 };
        options.cpus = { busy: { ...cpu, name: 'Busy processor', isBusy: true, finalOutput: { ...iron, icon } } };
        options.history = [{ ...historyEntry, finalOutput: { ...historyEntry.finalOutput, icon } }];
        const endpoint = view === 'cpus' ? '/cpus' : '/crafting-history';
        const iconSelector = view === 'cpus' ? '.cpu-card .resource-icon' : '.history-list .resource-icon';
        options.pageStatus = 404;
        page.on('response', (response) => {
            if (new URL(response.url()).pathname.includes('/api/icon-packs/') && response.status() === 404) {
                options.pack = { available: true, packId: 'c'.repeat(64), width: 64, height: 64 };
                options.icons = { ...options.icons, packId: 'c'.repeat(64) };
                options.pageStatus = 200;
            }
        });
        await page.goto(`${base}#/grids/${gridA}/${view}`);
        await page.waitForFunction((selector) => document.querySelector(selector)?.style.backgroundImage, iconSelector);
        const pages = options.requests.filter((request) => request.path.startsWith('/api/icon-packs/'));
        assert.equal(pages.length, 2);
        assert.ok(pages.at(-1).path.includes('c'.repeat(64)));
        options.pack = { available: false, packId: null, width: 0, height: 0 };
        const absent = page.waitForResponse(
            (response) =>
                new URL(response.url()).pathname.endsWith(endpoint) &&
                !new URL(response.url()).searchParams.has('icons')
        );
        await poll(page);
        await settleResponse(page, (await absent).request());
        assert.equal(await page.locator(`${iconSelector}:visible`).count(), 0);
        options.pack = { available: true, packId: 'c'.repeat(64), width: 64, height: 64 };
        await poll(page);
        await page.waitForFunction((selector) => document.querySelector(selector)?.style.backgroundImage, iconSelector);
        if (view === 'cpus') options.cpuError = 'NO_PERMISSIONS';
        else options.historyError = 'NO_PERMISSIONS';
        await poll(page);
        await page
            .getByRole('status')
            .filter({ hasText: /no longer have access/i })
            .waitFor();
        assert.equal(await page.locator(iconSelector).count(), 0);
    });

    test(`${view} late icon-enabled response cannot restore images after choosing names`, async (t) => {
        const { page, options, base } = await refreshingFixture(t);
        await atlasFixture(page, options);
        const icon = { page: 0, x: 0, y: 0 };
        options.cpus = { busy: { ...cpu, name: 'Busy processor', isBusy: true, finalOutput: { ...iron, icon } } };
        options.history = [{ ...historyEntry, finalOutput: { ...historyEntry.finalOutput, icon } }];
        const endpoint = view === 'cpus' ? '/cpus' : '/crafting-history';
        let release;
        const captured = new Promise((resolve) => {
            release = resolve;
        });
        await page.route(
            (url) => url.pathname.endsWith(endpoint) && url.searchParams.get('icons') === 'true',
            (route) => release(route)
        );
        await page.goto(`${base}#/grids/${gridA}/${view}`);
        const delayed = await captured;
        await clickTab(page, 'Web settings');
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
        await page.goto(`${base}#/grids/${gridA}/${view}`);
        await page.getByRole('link', { name: view === 'cpus' ? /Busy processor/ : /Iron Ingot.*#1$/ }).waitFor();
        await delayed.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
                status: 'OK',
                data: view === 'cpus' ? options.cpus : options.history,
                icons: options.icons
            })
        });
        await settleResponse(page, delayed.request());
        assert.equal(
            await page.locator('.cpu-card .resource-icon:visible, .history-list .resource-icon:visible').count(),
            0
        );
        assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 0);
        assert.equal(
            new URLSearchParams(options.requests.filter((request) => request.path.endsWith(endpoint)).at(-1).query).has(
                'icons'
            ),
            false
        );
    });
}

test('resource atlas is shared across visible items and CPU rows and can be disabled persistently', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '/ae2');
    await atlasFixture(page, options);
    options.itemsA = [
        { ...iron, icon: { page: 0, x: 0, y: 0 } },
        { ...quartz, icon: { page: 0, x: 64, y: 0 } }
    ];
    options.cpuDetails = {
        'cpu-a': {
            ...cpuWork,
            finalOutput: { ...quartz, icon: { page: 0, x: 64, y: 0 } },
            items: [{ ...cpuWork.items[0], itemKey: 'iron', icon: { page: 0, x: 0, y: 0 } }]
        }
    };
    options.cpus['cpu-a'] = { ...cpu, isBusy: true, finalOutput: { ...quartz, icon: { page: 0, x: 64, y: 0 } } };
    const pages = () => options.requests.filter((request) => request.path.startsWith('/api/icon-packs/'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('#items .resource-icon')].filter(
                (icon) => getComputedStyle(icon).backgroundImage !== 'none'
            ).length === 2
    );
    assert.equal(
        await page
            .locator('#items .resource-icon')
            .first()
            .evaluate((icon) => {
                const box = icon.getBoundingClientRect();
                const row = icon.closest('button').getBoundingClientRect();
                return box.left >= row.left && box.right <= row.right && box.top >= row.top && box.bottom <= row.bottom;
            }),
        true
    );
    assert.equal(pages().length, 1);
    assert.deepEqual(
        await page
            .locator('#items .resource-icon')
            .evaluateAll((icons) => icons.map((icon) => getComputedStyle(icon).backgroundPosition)),
        ['-32px 0px', '0px 0px']
    );
    assert.equal(
        new URLSearchParams(options.requests.filter((request) => request.path.endsWith('/items')).at(-1).query).get(
            'icons'
        ),
        'true'
    );
    options.itemsA = options.itemsA.map((item) => ({ ...item, quantity: item.quantity + 1 }));
    await poll(page);
    await page.getByRole('button', { name: /Certus Quartz/ }).waitFor();
    assert.equal(pages().length, 1);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.cpu-item .resource-icon')].some(
            (icon) => getComputedStyle(icon).backgroundImage !== 'none'
        )
    );
    assert.equal(pages().length, 1);
    const product = page.getByRole('region', { name: 'Current job', exact: true }).locator('.resource-icon');
    await product.scrollIntoViewIfNeeded();
    await page.waitForFunction(
        () => document.querySelector('.cpu-detail-output .resource-icon')?.style.backgroundImage
    );
    const productCrop = await product.evaluate((icon) => ({
        x: parseFloat(getComputedStyle(icon).backgroundPositionX),
        width: icon.getBoundingClientRect().width
    }));
    assert.equal(productCrop.x, -productCrop.width, 'The product uses the second atlas cell at its rendered size');
    const selectorIcon = page
        .getByRole('region', { name: 'CPU selector', exact: true })
        .locator('.resource-icon:visible');
    assert.equal(await selectorIcon.count(), 1);
    await page.waitForFunction(
        () => document.querySelector('.cpu-selector-link .resource-icon')?.style.backgroundImage
    );
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).at(-1).query, '?icons=true');
    assert.equal(pages().length, 1, 'job and resource icons share the existing atlas fetch');
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.reload();
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'names');
    const before = pages().length;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(pages().length, before);
    assert.equal(
        new URLSearchParams(options.requests.filter((request) => request.path.endsWith('/items')).at(-1).query).has(
            'icons'
        ),
        false
    );
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'Current job', exact: true })
        .getByText('Certus Quartz Crystal', { exact: false })
        .waitFor();
    assert.equal(await product.isVisible(), false);
    assert.equal(await selectorIcon.count(), 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).at(-1).query, '');
    assert.equal(pages().length, before);
});

// Public seam: global settings are navigable without a grid, preserve the selected grid's
test('atlas pages are limited to nearby rows and malformed mappings never become image URLs', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.setViewportSize({ width: 1000, height: 650 });
    options.icons.pages = Array.from({ length: 90 }, (_, i) => ({
        digest: i.toString(16).padStart(64, '0'),
        width: 128,
        height: 64
    }));
    options.itemsA = options.icons.pages.map((_, i) => ({
        ...iron,
        displayName: `Resource ${String(i).padStart(2, '0')}`,
        itemKey: `item-${i}`,
        icon: { page: i, x: 0, y: 0 }
    }));
    const pages = () => options.requests.filter((request) => request.path.startsWith('/api/icon-packs/'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    assert.ok(pages().length > 0 && pages().length < 50);
    assert.equal(
        await page
            .locator('#items .resource-icon')
            .last()
            .evaluate((icon) => getComputedStyle(icon).backgroundImage),
        'none'
    );
    await page.locator('#item-scroll').evaluate((scroll) => {
        scroll.scrollTop = scroll.scrollHeight;
    });
    await page.waitForFunction(
        () => [...document.querySelectorAll('#items .resource-icon')].at(-1)?.style.backgroundImage
    );
    assert.equal(
        await page
            .locator('#items .resource-icon')
            .first()
            .evaluate((icon) => getComputedStyle(icon).backgroundImage),
        'none'
    );
    const before = pages().length;
    options.icons = { ...options.icons, packId: '../evil.example' };
    await page.reload();
    await page.getByRole('button', { name: /Resource 00/ }).waitFor();
    await settleFrames(page);
    assert.equal(pages().length, before);
    assert.equal(
        await page
            .locator('#items .resource-icon')
            .first()
            .evaluate((icon) => getComputedStyle(icon).backgroundImage),
        'none'
    );
});

test('a stale atlas discovers the replacement pack and reloads resource mappings', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.pageStatus = 404;
    await page.route('**/api/icon-packs/**', async (route) => {
        const response = await route.fetch();
        if (response.status() === 404) {
            options.pack = { available: true, packId: 'c'.repeat(64), width: 64, height: 64 };
            options.icons = { ...options.icons, packId: 'c'.repeat(64) };
            options.pageStatus = 200;
        }
        await route.fulfill({ response });
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage);
    const requests = options.requests.filter((request) => request.path.startsWith('/api/icon-packs/'));
    assert.equal(requests.length, 2);
    assert.ok(requests[1].path.includes('c'.repeat(64)));
    assert.equal(options.requests.filter((request) => request.path === '/api/icon-pack').length, 2);
});

test('failed atlas pages keep resources usable without repeated requests during polling', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.pageStatus = 503;
    const failed = page.waitForResponse((response) => new URL(response.url()).pathname.startsWith('/api/icon-packs/'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await (await failed).finished();
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    assert.equal(await page.getByRole('button', { name: 'Craft', exact: true }).isEnabled(), true);
    for (let i = 0; i < 2; i++) {
        options.itemsA = options.itemsA.map((item) => ({ ...item, quantity: item.quantity + 1 }));
        const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/items'));
        await poll(page);
        await (await refreshed).finished();
    }
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 1);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundImage),
        'none'
    );
});

test('late atlas responses cannot repaint another grid after navigation', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.pageDelay = 300;
    const started = page.waitForRequest((request) => new URL(request.url()).pathname.startsWith('/api/icon-packs/'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await started;
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await settleResponse(page, await started);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundImage),
        'none'
    );
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 1);
});

test('visible atlas requests have bounded concurrency', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.setViewportSize({ width: 1200, height: 1000 });
    options.pageDelay = 150;
    options.icons.pages = Array.from({ length: 30 }, (_, i) => ({
        digest: i.toString(16).padStart(64, '0'),
        width: 128,
        height: 64
    }));
    options.itemsA = options.icons.pages.map((_, i) => ({
        ...iron,
        displayName: `Resource ${i}`,
        itemKey: `item-${i}`,
        icon: { page: i, x: 0, y: 0 }
    }));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('#items .resource-icon')].filter((icon) => icon.style.backgroundImage)
                .length > 4
    );
    assert.ok(options.maxActivePages > 1 && options.maxActivePages <= 4);
});
