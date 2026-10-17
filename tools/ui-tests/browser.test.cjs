const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright');

const resources = path.resolve(__dirname, '../../src/main/resources');
const compiledResources = path.resolve(__dirname, '../../build/generated/frontend');
const gridA = 'AAAAAAAAAAAAAAAAAAAAAA';
const gridB = 'BBBBBBBBBBBBBBBBBBBBBA';

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
        await seedAutomaticRefresh(page, base, false);
        await page.goto(`${base}#/web-settings`);
        const font = page.getByRole('combobox', { name: 'Font', exact: true });
        await font.waitFor({ timeout: 1500 });
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
        () => getComputedStyle(document.querySelector('.portable-title')).color === 'rgb(15, 80, 125)',
        {},
        { timeout: 3000 }
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
const iron = {
    displayName: 'Iron Ingot',
    registryNamespace: 'minecraft',
    registryPath: 'iron_ingot',
    componentCount: 0,
    damage: 0,
    quantity: 128640,
    craftable: true,
    resourceType: 'ITEM',
    itemKey: 'iron',
    icon: null
};
const quartz = {
    displayName: 'Certus Quartz Crystal',
    registryNamespace: 'ae2',
    registryPath: 'certus_quartz_crystal',
    componentCount: 0,
    damage: 0,
    quantity: 42,
    craftable: false,
    resourceType: 'ITEM',
    itemKey: 'quartz',
    icon: null
};
const readyPlan = {
    finalOutput: { ...iron, quantity: 12 },
    options: { lightMode: false, ignoreMissing: false },
    autoStart: false,
    isDone: true,
    isSimulating: false,
    bytesTotal: 2048,
    plan: [
        {
            registryNamespace: 'minecraft',
            registryPath: 'iron_ingot',
            componentCount: 0,
            damage: 0,
            displayName: 'Iron Ingot',
            itemKey: null,
            icon: null,
            stored: 4,
            requested: 12,
            missing: 0,
            steps: 3,
            usedPercent: 0
        }
    ]
};
const cpu = {
    acceptsPlayerJobs: true,
    selectionMode: null,
    name: 'Assembler',
    isBusy: false,
    supportsPause: false,
    isPaused: false,
    availableStorage: 8192,
    usedStorage: 0,
    coProcessors: 1
};
const cpuWork = {
    selectionMode: null,
    size: 8192,
    isBusy: true,
    supportsPause: false,
    isPaused: false,
    finalOutput: { ...iron, quantity: 12 },
    hasTrackingInfo: true,
    timeStarted: 1700000000000,
    timeElapsed: 10000,
    items: [
        {
            registryNamespace: 'minecraft',
            registryPath: 'iron_ingot',
            componentCount: 0,
            damage: 0,
            displayName: 'Iron Ingot',
            itemKey: null,
            icon: null,
            active: 4,
            pending: 6,
            stored: 2,
            timeSpentCrafting: 5000,
            craftedTotal: 10,
            shareInCraftingTime: 0.4,
            shareInCraftingTimeCombined: 0.5,
            craftsPerSec: 2
        }
    ]
};
const historyEntry = {
    id: 1,
    finalOutput: { ...iron, quantity: 12 },
    timeStarted: 1700000000000,
    timeDone: 1700000010000,
    wasCancelled: true
};
const historyDetail = {
    ...historyEntry,
    items: [
        {
            displayName: 'Iron Ingot',
            itemKey: null,
            icon: null,
            registryNamespace: 'minecraft',
            registryPath: 'iron_ingot',
            componentCount: 0,
            damage: 0,
            timeSpentOn: 5000,
            craftedTotal: 10,
            craftsPerSec: 2,
            shareInCraftingTime: 0.4,
            shareInCraftingTimeCombined: 0.5,
            timings: [{ started: 1700000001000, ended: 1700000006000 }]
        }
    ],
    interfaceShare: [
        {
            name: 'Smelter',
            timingsCombined: 5000,
            location: [{ dimensionId: 'minecraft:overworld', x: 120, y: 64, z: -32 }],
            timings: [{ started: 1700000001000, ended: 1700000006000 }]
        }
    ]
};

// Public browser/HTTP seam: resource-to-provider relationships navigate the existing history,
// including later pages and names whose rendered text matches while their raw identity differs.
test('history resource provider links reveal the exact group across sorting and pagination without refetching', async (t) => {
    const { page, options, base } = await fixture(t);
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
    await names.waitFor({ timeout: 2000 });
    assert.equal(await names.getByRole('button').count(), 2);
    assert.equal(await names.getByText('Unavailable', { exact: true }).count(), 1);
    const initialRequests = options.requests.filter((request) => request.path.endsWith('/crafting-history/1')).length;
    assert.equal(initialRequests, 1);
    await names.getByRole('button', { name: 'Shared machine', exact: true }).nth(1).click();
    await page.getByRole('list', { name: 'Pattern providers', exact: true }).waitFor();
    assert.equal(await search.inputValue(), '');
    const target = page.getByRole('region', { name: 'Shared machine', exact: true });
    await target.getByText(/test:blue.*4.*5.*6/).waitFor({ timeout: 2000 });
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
        .waitFor({ timeout: 2000 });
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
        await first.getByRole('img', { name: 'Final product', exact: true }).waitFor({ timeout: 2000 });
        assert.match(await first.locator('summary').innerText(), /0 s/);
        assert.equal(await resources.getByRole('img', { name: 'Final product', exact: true }).count(), 1);
    }
    const marker = resources.getByRole('img', { name: 'Final product', exact: true });
    await marker.scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await marker.hover();
    await page.getByRole('tooltip').filter({ hasText: 'Final product' }).waitFor({ timeout: 2000 });
    await page.getByRole('searchbox', { name: 'Search history', exact: true }).fill('Alpha');
    assert.equal(await resources.getByRole('listitem').count(), 1);
    assert.equal(await resources.getByRole('img', { name: 'Final product', exact: true }).count(), 0);
    options.historyDetail.items = options.historyDetail.items.slice(0, 2);
    await page.reload();
    await resources.getByRole('heading', { name: 'Alpha', exact: true }).waitFor();
    assert.equal(await resources.getByRole('listitem').count(), 2);
    assert.equal(await resources.getByRole('img', { name: 'Final product', exact: true }).count(), 0);
});

async function fixture(t, mount = '', contextOptions = {}, { mockClock = true } = {}) {
    const options = {
        delayA: 0,
        status: 200,
        requests: [],
        empty: false,
        reverseGrids: false,
        loggedOut: false,
        username: 'ExamplePlayer',
        isAdmin: false,
        availableUpdate: {
            version: '10.0.0',
            tag: '1.7.10-10.0.0',
            releaseUrl: 'https://github.com/kuba6000/AE2-Web-Integration/releases/tag/1.7.10-10.0.0',
            skipped: false
        },
        skipUpdateStatus: 'OK',
        modVersion: '9.8.7-browser-fixture',
        publicMode: true,
        capabilities: { craftingLightMode: false },
        bootstrapStatus: 200,
        bootstrapDelay: 0,
        itemsA: [iron, quartz],
        plan: readyPlan,
        pendingReads: 0,
        cpus: { 'cpu-a': cpu, 'cpu-b': cpu },
        planStatus: 'OK',
        submitStatus: 'OK',
        cpuDetails: { 'cpu-a': cpuWork, 'cpu-b': cpuWork },
        cancelStatus: 'OK',
        pauseStatus: 'OK',
        history: [historyEntry],
        historyDetail,
        resourceHistory: { points: [], baseline: null, complete: true, truncated: false },
        settings: {
            [gridA]: {
                isTracked: false,
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300,
                name: ''
            },
            [gridB]: {
                isTracked: false,
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300,
                name: ''
            }
        },
        accessSources: {}
    };
    const planRequests = new Map();
    const server = http.createServer(async (request, response) => {
        const url = new URL(request.url, 'http://localhost');
        if (!url.pathname.startsWith(mount + '/')) {
            response.writeHead(404).end();
            return;
        }
        const resource = url.pathname.slice(mount.length);
        const body = [];
        for await (const chunk of request) body.push(chunk);
        options.requests.push({
            path: resource,
            query: url.search,
            method: request.method,
            headers: request.headers,
            body: body.length ? JSON.parse(Buffer.concat(body).toString()) : null
        });
        if (resource === '/api/themes') {
            response.writeHead(200, { 'Content-Type': 'application/json' }).end(
                JSON.stringify({
                    status: 'OK',
                    data: options.themeCatalog || { defaultTheme: 'builtin:default', themes: [] }
                })
            );
            return;
        }
        if (resource.startsWith('/themes/')) {
            const file = options.themeFiles?.[resource];
            response
                .writeHead(file ? 200 : 404, {
                    'Content-Type':
                        { '.css': 'text/css', '.svg': 'image/svg+xml' }[path.extname(resource)] ?? 'text/javascript'
                })
                .end(file || '');
            return;
        }
        // Test-owned theme modules exercise the same bootstrap and HTTP services as production.
        if (options.themeEntry && resource === '/__theme-test.js') {
            response.writeHead(200, { 'Content-Type': 'text/javascript' }).end(options.themeEntry);
            return;
        }
        if (options.themeEntry && resource === '/') {
            response
                .writeHead(200, { 'Content-Type': 'text/html' })
                .end(
                    '<!doctype html><title>Theme fixture</title><main id="app"></main><script type="module" src="./__theme-test.js"></script>'
                );
            return;
        }
        if (resource.startsWith('/api/')) {
            if (resource.startsWith('/api/icon-packs/')) {
                options.activePages = (options.activePages || 0) + 1;
                options.maxActivePages = Math.max(options.maxActivePages || 0, options.activePages);
                response.once('close', () => options.activePages--);
                response.writeHead(options.pageStatus || 200, { 'Content-Type': 'image/png' });
                if (options.pageDelay) setTimeout(() => response.end(options.atlas), options.pageDelay);
                else response.end(options.atlas);
                return;
            }
            response.setHeader('Content-Type', 'application/json');
            const mutation =
                request.method === 'PATCH'
                    ? 'settings'
                    : request.method === 'DELETE'
                      ? 'delete'
                      : resource.endsWith('/pause')
                        ? 'pause'
                        : resource.endsWith('/cancel')
                          ? 'cancel'
                          : resource.endsWith('/submit')
                            ? 'submit'
                            : request.method === 'POST' && resource.endsWith('/crafting-plans')
                              ? 'create'
                              : null;
            if (mutation && options.fault?.operation === mutation) {
                if (options.fault.status === 'NETWORK_ERROR') {
                    response.destroy();
                    return;
                }
                if (options.fault.status === 'INVALID_RESPONSE') {
                    response.end('<html>Proxy error</html>');
                    return;
                }
                response.statusCode = options.fault.status === 'INTERNAL_ERROR' ? 500 : 503;
                response.end(JSON.stringify({ status: options.fault.status, data: null }));
                return;
            }
            if (resource === '/api/context') {
                if (options.bootstrapDelay) await new Promise((resolve) => setTimeout(resolve, options.bootstrapDelay));
                response.statusCode = options.bootstrapStatus;
                response.end(
                    JSON.stringify({
                        status: options.bootstrapStatus === 200 ? 'OK' : 'INTERNAL_ERROR',
                        data:
                            options.bootstrapStatus === 200
                                ? {
                                      publicMode: options.publicMode,
                                      capabilities: options.loggedOut ? {} : options.capabilities,
                                      modVersion: options.modVersion,
                                      isOutdated: options.isOutdated || false,
                                      availableUpdate:
                                          !options.loggedOut && options.isAdmin && options.isOutdated
                                              ? options.availableUpdate
                                              : null,
                                      user: options.loggedOut
                                          ? null
                                          : { username: options.username, isAdmin: options.isAdmin }
                                  }
                                : null
                    })
                );
            } else if (resource === '/api/updates/skip') {
                const input = body.length ? JSON.parse(Buffer.concat(body).toString()) : {};
                let status = options.skipUpdateStatus;
                if (!options.isAdmin) status = 'FORBIDDEN';
                else if (!options.availableUpdate || input.tag !== options.availableUpdate.tag)
                    status = 'UPDATE_CHANGED';
                if (status === 'OK') options.availableUpdate.skipped = true;
                response.statusCode =
                    status === 'OK' ? 200 : status === 'FORBIDDEN' ? 403 : status === 'UPDATE_CHANGED' ? 409 : 500;
                response.end(JSON.stringify({ status, data: null }));
            } else if (resource === '/api/icon-pack') {
                if (options.packError) {
                    response.writeHead(503).end(JSON.stringify({ status: 'INTERNAL_ERROR', data: null }));
                    return;
                }
                const pack = options.pack || { available: false, packId: null, width: 0, height: 0 };
                if (options.packDelay) await new Promise((resolve) => setTimeout(resolve, options.packDelay));
                response.end(JSON.stringify({ status: 'OK', data: pack }));
            } else if (resource === '/api/auth/logout') {
                if (options.logoutError) {
                    response.writeHead(503).end(JSON.stringify({ status: options.logoutError, data: null }));
                    return;
                }
                options.loggedOut = true;
                response.end(JSON.stringify({ status: 'OK', data: null }));
            } else if (resource === '/api/grids') {
                if (options.gridDelay) await new Promise((resolve) => setTimeout(resolve, options.gridDelay));
                if (options.gridError === 'NETWORK_ERROR') {
                    response.destroy();
                    return;
                }
                if (options.gridError) {
                    response.statusCode = 403;
                    response.end(JSON.stringify({ status: options.gridError, data: null }));
                    return;
                }
                const grids = [
                    { key: gridA, owner: 'Alpha', cpuCount: 2, isOwned: false, accessSources: options.accessSources },
                    { key: gridB, owner: 'Beta', cpuCount: 1, accessSources: {} }
                ];
                response.end(
                    JSON.stringify({
                        status: 'OK',
                        data: (options.empty
                            ? []
                            : options.grids || (options.reverseGrids ? grids.reverse() : grids)
                        ).map((grid) => ({
                            ...grid,
                            name: options.settings[grid.key]?.name ?? grid.name ?? '',
                            isTrackingEnabled: options.settings[grid.key]?.isTracked ?? false
                        }))
                    })
                );
            } else if (resource.endsWith('/crafting-plans')) {
                const input = options.requests.at(-1).body;
                const item = options.itemsA.find((item) => item.itemKey === input.itemKey) || iron;
                planRequests.set(`${resource}/${options.nextPlanId ?? 7}`, {
                    finalOutput: { ...item, itemKey: input.itemKey, quantity: input.quantity },
                    options: { lightMode: input.lightMode === true, ignoreMissing: input.ignoreMissing === true },
                    autoStart: input.autoStart === true
                });
                response.statusCode = 202;
                response.end(JSON.stringify({ status: 'OK', data: { jobId: options.nextPlanId ?? 7 } }));
            } else if (resource.endsWith('/submit')) {
                if (options.submitDelay) await new Promise((resolve) => setTimeout(resolve, options.submitDelay));
                response.statusCode = options.submitStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.submitStatus, data: options.submitReason || null }));
            } else if (/\/crafting-plans\/\d+$/.test(resource)) {
                response.statusCode = options.planStatus === 'OK' ? 200 : 404;
                response.end(
                    JSON.stringify({
                        status: options.planStatus,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null,
                        data:
                            request.method === 'DELETE'
                                ? null
                                : options.pendingReads-- > 0
                                  ? {
                                        ...options.plan,
                                        ...planRequests.get(resource),
                                        isDone: false,
                                        isSimulating: false,
                                        bytesTotal: 0,
                                        plan: null
                                    }
                                  : { ...options.plan, ...planRequests.get(resource) }
                    })
                );
            } else if (resource.endsWith('/cpus')) {
                const gridKey = resource.split('/')[3];
                const cpuError = options.cpuErrors?.[gridKey] || options.cpuError;
                const result = JSON.stringify({
                    status: cpuError || 'OK',
                    data: cpuError ? null : options.cpusByGrid?.[gridKey] || options.cpus,
                    icons: url.searchParams.get('icons') === 'true' ? options.icons : null
                });
                if (options.cpuDelay) await new Promise((resolve) => setTimeout(resolve, options.cpuDelay));
                response.statusCode = cpuError ? 403 : 200;
                response.end(result);
            } else if (resource.endsWith('/crafting-history')) {
                response.end(
                    JSON.stringify({
                        status: options.historyError || 'OK',
                        data: options.history,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null
                    })
                );
            } else if (/\/crafting-history\/\d+$/.test(resource)) {
                response.end(
                    JSON.stringify({
                        status: options.historyError || 'OK',
                        data: options.historyDetail,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null
                    })
                );
            } else if (resource.endsWith('/resource-history')) {
                const key = resource.includes(gridA) ? gridA : gridB;
                response.end(
                    JSON.stringify({
                        status: options.resourceHistoryError || 'OK',
                        data: {
                            ...options.resourceHistory,
                            enabled: !!options.settings[key].isStorageTracked || !!options.settings[key].isFlowTracked
                        },
                        icons: url.searchParams.get('icons') === 'true' ? (options.historyIcons ?? options.icons) : null
                    })
                );
            } else if (resource.endsWith('/settings')) {
                const key = resource.includes(gridA) ? gridA : gridB;
                if (request.method === 'PATCH' && !options.settingsError)
                    options.settings[key] = options.settingsReply || {
                        ...options.settings[key],
                        ...options.requests.at(-1).body
                    };
                if (typeof options.settings[key].name === 'string')
                    options.settings[key].name = options.settings[key].name.trim();
                response.end(JSON.stringify({ status: options.settingsError || 'OK', data: options.settings[key] }));
            } else if (resource.endsWith('/pause')) {
                if (options.pauseStatus === 'OK') {
                    const key = decodeURIComponent(resource.split('/').at(-2));
                    const isPaused = options.requests.at(-1).body.paused;
                    options.cpus[key] = { ...options.cpus[key], isPaused };
                    options.cpuDetails[key] = { ...options.cpuDetails[key], isPaused };
                }
                response.statusCode = options.pauseStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.pauseStatus, data: null }));
            } else if (resource.endsWith('/cancel')) {
                if (options.cancelStatus === 'OK') {
                    const key = decodeURIComponent(resource.split('/').at(-2));
                    options.cpus[key] = { ...options.cpus[key], isBusy: false, finalOutput: null, usedStorage: 0 };
                    options.cpuDetails[key] = {
                        size: 8192,
                        isBusy: false,
                        finalOutput: null,
                        items: null,
                        hasTrackingInfo: false,
                        timeStarted: 0,
                        timeElapsed: 0
                    };
                }
                response.statusCode = options.cancelStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.cancelStatus, data: null }));
            } else if (/\/cpus\/[^/]+$/.test(resource)) {
                const key = decodeURIComponent(resource.split('/').at(-1));
                const status = options.detailError || (options.cpuDetails[key] ? 'OK' : 'CPU_NOT_FOUND');
                response.statusCode = status === 'OK' ? 200 : 404;
                response.end(
                    JSON.stringify({
                        status,
                        data: status === 'OK' ? options.cpuDetails[key] : null,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null
                    })
                );
            } else if (resource.endsWith('/items/events')) {
                response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
                response.flushHeaders();
                if (!options.delayStreamReady) response.write('event: ready\ndata: {}\n\n');
                options.itemStreams ??= [];
                options.itemStreams.push(response);
                response.on('close', () => {
                    options.itemStreams = options.itemStreams.filter((stream) => stream !== response);
                });
            } else if (resource.endsWith('/items')) {
                const first = resource.includes(gridA);
                const send = () => {
                    response.statusCode = options.status;
                    response.end(
                        JSON.stringify(
                            options.status === 200
                                ? {
                                      status: 'OK',
                                      recent: options.recent,
                                      ...(options.liveView
                                          ? {
                                                view: options.liveView,
                                                subscription: `subscription-${options.liveView.revision}`
                                            }
                                          : {}),
                                      icons: url.searchParams.get('icons') === 'true' ? options.icons : null,
                                      data: first
                                          ? options.itemsA
                                          : [
                                                {
                                                    ...iron,
                                                    displayName: 'Gold Ingot',
                                                    registryNamespace: 'minecraft',
                                                    registryPath: 'gold_ingot',
                                                    quantity: 5
                                                }
                                            ]
                                  }
                                : { status: 'NO_PERMISSIONS', data: null }
                        )
                    );
                };
                if (first && options.delayA) setTimeout(send, options.delayA);
                else send();
            } else {
                response.statusCode = 404;
                response.end(JSON.stringify({ status: 'NOT_FOUND', data: null }));
            }
            return;
        }
        const login =
            resource === '/' &&
            (options.loggedOut ||
                url.searchParams.has('INVALID_PASSWORD') ||
                url.searchParams.has('confirmregistration'));
        const relative = resource === '/' ? (login ? 'assets/login.html' : 'assets/web/index.html') : resource.slice(1);
        const resourceRoot =
            relative.endsWith('.js') || relative.endsWith('/uplot.css') || relative.endsWith('/uplot-LICENSE.txt')
                ? compiledResources
                : resources;
        const file = path.resolve(resourceRoot, relative);
        if (!file.startsWith(resourceRoot + path.sep)) {
            response.writeHead(404).end();
            return;
        }
        try {
            response.setHeader(
                'Content-Type',
                file.endsWith('.js') || file.endsWith('.mjs')
                    ? 'text/javascript'
                    : file.endsWith('.svg')
                      ? 'image/svg+xml'
                      : file.endsWith('.css')
                        ? 'text/css'
                        : file.endsWith('.woff2')
                          ? 'font/woff2'
                          : 'text/html'
            );
            const content = await fs.readFile(file);
            response.end(content);
        } catch {
            response.writeHead(404).end('Missing asset');
        }
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const closeServer = () => new Promise((resolve) => server.close(resolve));
    const browser = await chromium
        .launch({
            headless: true,
            ...(process.env.BROWSER_CHANNEL ? { channel: process.env.BROWSER_CHANNEL } : {})
        })
        .catch(async (error) => {
            await closeServer();
            throw error;
        });
    t.after(async () => {
        await browser.close();
        await closeServer();
    });
    const page = await browser.newPage({ locale: 'en-US', ...contextOptions });
    page.on('dialog', (dialog) => dialog.accept());
    if (mockClock) await page.clock.install();
    const errors = [];
    page.on('pageerror', (error) => errors.push(String(error)));
    t.after(() => assert.deepEqual(errors, []));
    return { page, options, base: `http://127.0.0.1:${server.address().port}${mount}/` };
}

async function clickTab(page, name) {
    const link = page.getByRole('link', { name, exact: true });
    if (page.viewportSize().width <= 600 && !(await link.isVisible()))
        await page.getByRole('button', { name: /^(Navigation|Nawigacja)$/ }).click();
    await link.click();
}

async function openMobilePane(page, name) {
    if (page.viewportSize().width <= 600) await page.getByRole('button', { name, exact: true }).click();
}

async function closeMobilePane(page) {
    const close = page.getByRole('button', { name: 'Close panel', exact: true });
    if (await close.isVisible()) await close.click();
}

async function settleResponse(page, request) {
    const response = await request.response();
    if (response) await response.finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

// Advance the browser's real polling timers; API traffic and UI updates still run normally.
async function poll(page) {
    const requested = page.waitForRequest((request) => request.url().endsWith('/api/grids'));
    await page.clock.fastForward(6000);
    await requested;
}

async function seedAutomaticRefresh(page, base, autoRefresh) {
    await page.addInitScript(
        ({ key, autoRefresh }) => {
            if (localStorage.getItem(key) === null) localStorage.setItem(key, JSON.stringify({ autoRefresh }));
        },
        { key: `ae2web:${new URL(base).pathname}:ui`, autoRefresh }
    );
}

async function atlasFixture(page, options) {
    options.atlas = await page.evaluate(() => {
        const canvas = document.createElement('canvas');
        canvas.width = 128;
        canvas.height = 64;
        const context = canvas.getContext('2d');
        context.fillStyle = '#ff0000';
        context.fillRect(0, 0, 64, 64);
        context.fillStyle = '#00ff00';
        context.fillRect(64, 0, 64, 64);
        return canvas.toDataURL().split(',')[1];
    });
    options.atlas = Buffer.from(options.atlas, 'base64');
    options.icons = {
        packId: 'a'.repeat(64),
        width: 64,
        height: 64,
        pages: [{ digest: 'b'.repeat(64), width: 128, height: 64 }]
    };
    options.pack = { available: true, packId: options.icons.packId, width: 64, height: 64 };
}

// Public UI/HTTP seam: server capability overrides presentation without overwriting user intent.
test('resource crafting opens one accessible dialog from details, middle click and context menu', async (t) => {
    const { page, options, base } = await fixture(t);
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
    await seedAutomaticRefresh(page, base, false);
    options.pendingReads = 1;
    options.cpus = {};
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('textbox').fill('23');
    await dialog.getByRole('checkbox', { name: 'Start automatically', exact: true }).check();
    await dialog.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('heading', { name: 'Crafting plan', exact: true }).waitFor({ timeout: 5000 });
    assert.match(page.url(), /\/plans\/7$/);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/submit')).length, 0);
    await page.clock.fastForward(1200);
    await page.waitForURL(/\/items$/);
    assert.match(page.url(), /\/items$/);
    assert.equal(await dialog.isVisible(), false);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const notifications = page.getByRole('region', { name: 'Notifications', exact: true });
    await notifications.getByText('Crafting started', { exact: true }).waitFor({ timeout: 3000 });
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
        await seedAutomaticRefresh(page, base, false);
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
    await seedAutomaticRefresh(page, base, false);
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
        const { page, options, base } = await fixture(t);
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

test('crafting light mode follows authenticated server capability and only sends an explicit supported choice', async (t) => {
    for (const supported of [false, true]) {
        const { page, options, base } = await fixture(t);
        options.capabilities = { craftingLightMode: supported };
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
        const lightMode = page.getByRole('checkbox', { name: 'Light mode', exact: true });
        if (supported) await lightMode.check({ timeout: 3000 });
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
    await seedAutomaticRefresh(page, base, false);
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
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    await atlasFixture(page, options);
    await page.goto(`${base}#/web-settings`);
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await mode.selectOption('icons');
    options.pack = { available: false, packId: null, width: 0, height: 0 };
    await page.reload();
    await page.getByText(/server.*not.*icon pack/i).waitFor({ timeout: 3000 });
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
    await page.waitForFunction(
        () => {
            const item = document.querySelector('#items button');
            const rect = item.getBoundingClientRect();
            return Math.abs(rect.width - rect.height) < 1;
        },
        null,
        { timeout: 3000 }
    );
    await clickTab(page, 'Web settings');
    assert.equal(await mode.inputValue(), 'icons');
    await page.reload();
    await mode.waitFor();
    await page.waitForFunction(() => document.querySelector('#terminal-display').value === 'icons');
});

test('pending icon discovery leaves inventory usable and failures preserve confirmed availability', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.packDelay = 2000;
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    await item.waitFor({ timeout: 1000 });
    assert.equal(await item.getByText(iron.displayName, { exact: true }).isVisible(), true);
    assert.equal(
        new URLSearchParams(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query).has('icons'),
        false
    );
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage, null, {
        timeout: 4000
    });
    options.packDelay = 0;
    options.packError = true;
    await poll(page);
    await clickTab(page, 'Web settings');
    await page.getByText(/could not check.*icon/i).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'both');
});

for (const view of ['items', 'cpus/cpu-a']) {
    test(`late confirmation of absent icons does not reload already usable ${view}`, async (t) => {
        const { page, options, base } = await fixture(t);
        options.packDelay = 750;
        const discovery = page.waitForRequest((request) => request.url().endsWith('/api/icon-pack'));
        await page.goto(`${base}#/grids/${gridA}/${view}`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const reads = () => options.requests.filter((request) => request.path.endsWith(`/${view}`));
        assert.equal(reads().length, 1);
        await settleResponse(page, await discovery);
        assert.equal(reads().length, 1);
    });
}

test('initial icon discovery error stays distinct from absence and recovers', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    await atlasFixture(page, options);
    options.packError = true;
    await page.goto(`${base}#/web-settings`);
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await page.getByText(/could not check.*icon/i).waitFor();
    assert.equal(await page.getByText(/server.*not.*icon pack/i).isVisible(), false);
    assert.equal(await mode.inputValue(), 'names');
    options.packError = false;
    await poll(page);
    await page.waitForFunction(() => document.querySelector('#terminal-display').value === 'both', null, {
        timeout: 3000
    });
    options.pack = { available: false, packId: null, width: 0, height: 0 };
    await poll(page);
    await page.getByText(/server.*not.*icon pack/i).waitFor();
    options.packError = true;
    await poll(page);
    await page.getByText(/could not check.*icon/i).waitFor({ timeout: 3000 });
    assert.equal(await page.getByText(/server.*not.*icon pack/i).isVisible(), false);
    assert.equal(await mode.inputValue(), 'names');
});

test('superseded slow discovery cannot overwrite recovered availability after a grid failure', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    await atlasFixture(page, options);
    options.pack = { available: false, packId: null, width: 0, height: 0 };
    options.packDelay = 2000;
    options.gridError = 'NO_PERMISSIONS';
    const firstDiscovery = page.waitForRequest((r) => r.url().endsWith('/api/icon-pack'));
    await page.goto(`${base}#/web-settings`);
    const oldRequest = await firstDiscovery;
    await page.waitForFunction(() => document.querySelector('#network-message').textContent.length > 0);
    options.gridError = null;
    options.packDelay = 0;
    options.pack = { available: true, packId: 'a'.repeat(64), width: 64, height: 64 };
    await poll(page);
    await page.waitForFunction(() => document.querySelector('#terminal-display').value === 'both', null, {
        timeout: 3000
    });
    await settleResponse(page, oldRequest);
    await new Promise((resolve) => setTimeout(resolve, 2100));
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'both');
    assert.equal(await page.getByText(/server.*not.*icon pack/i).isVisible(), false);
});

for (const saved of [
    { resourceIcons: true, terminalDisplay: 'compact', expected: 'both' },
    { resourceIcons: false, expected: 'both' },
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
            (expected) => document.querySelector('#terminal-display').value === expected,
            saved.expected,
            { timeout: 3000 }
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
    await enable.waitFor({ timeout: 3000 });
    assert.equal(
        new URLSearchParams(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query).has('icons'),
        false
    );
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
    await enable.click({ timeout: 3000 });
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
    assert.equal(options.requests.filter((r) => r.path.endsWith('/cpus/cpu-a')).at(-1).query, '');
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await cpuItem.waitFor();
    assert.equal(
        await cpuItem.locator('.resource-icon').evaluate((icon) => getComputedStyle(icon, '::before').content),
        '"?"'
    );
    assert.equal(options.requests.filter((r) => r.path.endsWith('/cpus/cpu-a')).at(-1).query, '?icons=true');
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
    await mode.waitFor({ timeout: 3000 });
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
for (const deviceScaleFactor of [1, 1.25, 1.5, 1.75, 2]) {
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
            await page.evaluate(
                () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
            );
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
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('.cpu-card .resource-icon')].some(
                (icon) => getComputedStyle(icon).backgroundImage !== 'none'
            ),
        null,
        { timeout: 3000 }
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
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('.history-list .resource-icon')].some(
                (icon) => getComputedStyle(icon).backgroundImage !== 'none'
            ),
        null,
        { timeout: 3000 }
    );
    assert.equal(
        await missing.locator('.resource-icon').evaluate((node) => getComputedStyle(node, '::before').content),
        '"?"'
    );
    assert.equal(options.requests.filter((r) => r.path.endsWith('/crafting-history')).at(-1).query, '?icons=true');
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
            options.requests.filter((r) => r.path.endsWith(route === 'history' ? '/crafting-history' : '/cpus')).at(-1)
                .query,
            ''
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
    const { page, options, base } = await fixture(t);
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
        await product.elementHandle(),
        { timeout: 3000 }
    );
    await ready.scrollIntoViewIfNeeded();
    await page.waitForFunction(
        (node) => getComputedStyle(node.querySelector('.resource-icon')).backgroundImage !== 'none',
        await ready.elementHandle(),
        { timeout: 3000 }
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
    const { page, options, base } = await fixture(t);
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
    await row.scrollIntoViewIfNeeded();
    await page.waitForFunction(
        (node) =>
            !!node.querySelector('.resource-icon') &&
            getComputedStyle(node.querySelector('.resource-icon')).backgroundImage !== 'none',
        await row.elementHandle(),
        { timeout: 3000 }
    );
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
    await row.scrollIntoViewIfNeeded();
    await page.waitForFunction(
        (node) => getComputedStyle(node.querySelector('.resource-icon')).backgroundImage !== 'none',
        await row.elementHandle()
    );
    assert.ok(
        options.requests.some((r) => r.path.includes(`/icon-packs/${'c'.repeat(64)}/pages/`)),
        'replacement uses its new atlas URL'
    );
});

for (const view of ['cpus', 'history']) {
    test(`${view} product mappings recover from pack replacement and clear on unavailable or denied reads`, async (t) => {
        const { page, options, base } = await fixture(t);
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
        await page.waitForFunction(
            (selector) => document.querySelector(selector)?.style.backgroundImage,
            iconSelector,
            { timeout: 3000 }
        );
        const pages = options.requests.filter((request) => request.path.startsWith('/api/icon-packs/'));
        assert.equal(pages.length, 2);
        assert.ok(pages.at(-1).path.includes('c'.repeat(64)));
        options.pack = { available: false, packId: null, width: 0, height: 0 };
        const absent = page.waitForResponse(
            (response) => new URL(response.url()).pathname.endsWith(endpoint) && !new URL(response.url()).search
        );
        await poll(page);
        await settleResponse(page, (await absent).request());
        assert.equal(await page.locator(`${iconSelector}:visible`).count(), 0);
        options.pack = { available: true, packId: 'c'.repeat(64), width: 64, height: 64 };
        await poll(page);
        await page.waitForFunction(
            (selector) => document.querySelector(selector)?.style.backgroundImage,
            iconSelector,
            { timeout: 3000 }
        );
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
        const { page, options, base } = await fixture(t);
        await atlasFixture(page, options);
        const icon = { page: 0, x: 0, y: 0 };
        options.cpus = { busy: { ...cpu, name: 'Busy processor', isBusy: true, finalOutput: { ...iron, icon } } };
        options.history = [{ ...historyEntry, finalOutput: { ...historyEntry.finalOutput, icon } }];
        const endpoint = view === 'cpus' ? '/cpus' : '/crafting-history';
        let release;
        const captured = new Promise((resolve) => {
            release = resolve;
        });
        await page.route(`**${endpoint}?icons=true`, (route) => release(route));
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
        assert.equal(options.requests.filter((request) => request.path.endsWith(endpoint)).at(-1).query, '');
    });
}

test('resource atlas is shared across visible items and CPU rows and can be disabled persistently', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await seedAutomaticRefresh(page, base, true);
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
            ).length === 2,
        null,
        { timeout: 3000 }
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
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('.cpu-item .resource-icon')].some(
                (icon) => getComputedStyle(icon).backgroundImage !== 'none'
            ),
        null,
        { timeout: 3000 }
    );
    assert.equal(pages().length, 1);
    const product = page.getByRole('region', { name: 'Current job', exact: true }).locator('.resource-icon');
    await product.scrollIntoViewIfNeeded();
    await page.waitForFunction(
        () => document.querySelector('.cpu-detail-output .resource-icon')?.style.backgroundImage,
        null,
        {
            timeout: 3000
        }
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
    await seedAutomaticRefresh(page, base, false);
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
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage, null, {
        timeout: 3000
    });
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
        () => [...document.querySelectorAll('#items .resource-icon')].at(-1)?.style.backgroundImage,
        null,
        { timeout: 3000 }
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
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
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
    await seedAutomaticRefresh(page, base, false);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.pageStatus = 404;
    page.on('response', (response) => {
        if (new URL(response.url()).pathname.includes('/api/icon-packs/') && response.status() === 404) {
            options.pack = { available: true, packId: 'c'.repeat(64), width: 64, height: 64 };
            options.icons = { ...options.icons, packId: 'c'.repeat(64) };
            options.pageStatus = 200;
        }
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage, null, {
        timeout: 3000
    });
    const requests = options.requests.filter((request) => request.path.startsWith('/api/icon-packs/'));
    assert.equal(requests.length, 2);
    assert.ok(requests[1].path.includes('c'.repeat(64)));
    assert.equal(options.requests.filter((request) => request.path === '/api/icon-pack').length, 2);
});

test('failed atlas pages keep resources usable without repeated requests during polling', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
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
    await seedAutomaticRefresh(page, base, false);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.pageDelay = 300;
    const started = page.waitForRequest((request) => new URL(request.url()).pathname.startsWith('/api/icon-packs/'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await started;
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await page.waitForTimeout(400);
    assert.equal(
        await page.locator('#items .resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundImage),
        'none'
    );
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/icon-packs/')).length, 1);
});

test('visible atlas requests have bounded concurrency', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
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
                .length > 4,
        null,
        { timeout: 3000 }
    );
    assert.ok(options.maxActivePages > 1 && options.maxActivePages <= 4);
});

// Public seam: global settings are navigable without a grid, preserve the selected grid's
// links, and persist browser preferences without pretending to mutate server settings.
test('global settings tabs preserve grid navigation and browser preferences', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('link', { name: 'Web settings', exact: true }).click({ timeout: 3000 });
    assert.match(page.url(), /#\/web-settings$/);
    const header = page.locator('#app header');
    await header.getByText(options.username, { exact: true }).waitFor();
    await header.getByRole('button', { name: 'Log out', exact: true }).waitFor();
    assert.equal(await header.getByRole('combobox').count(), 0);
    await page.getByRole('button', { name: 'Choose a theme' }).click();
    const theme = page.getByRole('combobox', { name: 'Choose a theme', exact: true });
    assert.equal(await theme.isEnabled(), true);
    assert.equal(await theme.inputValue(), '');
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    const appearance = page.getByRole('combobox', { name: 'Appearance', exact: true });
    await appearance.selectOption('dark');
    await clickTab(page, 'Server settings');
    assert.match(page.url(), /#\/server-settings$/);
    await page.getByRole('heading', { name: 'Server settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Appearance', exact: true }).isVisible(), false);
    assert.equal(
        await page.getByRole('link', { name: 'Network', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/settings`
    );
    await clickTab(page, 'Network');
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).waitFor();
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.reload();
    const language = page.getByRole('combobox', { name: 'Język', exact: true });
    await language.waitFor();
    assert.equal(await language.inputValue(), 'pl');
    assert.equal(await page.getByRole('combobox', { name: 'Wygląd', exact: true }).inputValue(), 'dark');
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

test('global settings direct routes work without an available grid', async (t) => {
    const { page, options, base } = await fixture(t);
    options.empty = true;
    for (const [route, heading] of [
        ['web-settings', 'Web settings'],
        ['server-settings', 'Server settings']
    ]) {
        await page.goto(`${base}#/${route}`);
        await page.getByRole('heading', { name: heading, exact: true }).waitFor({ timeout: 3000 });
        assert.match(page.url(), new RegExp(`#/${route}$`));
    }
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/grids/')).length, 0);
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

// Public seam: About identifies the running mod and the resources actually included in
// this theme; it is a read-only global destination and does not discard grid navigation.
test('About shows runtime metadata and resource links while retaining the selected grid', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('link', { name: 'About', exact: true }).click({ timeout: 3000 });
    assert.match(page.url(), /#\/about$/);
    const about = page.getByRole('heading', { name: 'About', exact: true }).locator('..');
    await about.getByText(options.modVersion).waitFor();
    await about.getByText('AE2 Web Integration', { exact: true }).waitFor();
    await about.getByText('kuba6000').first().waitFor();
    await about.getByText('LGPL-3.0-or-later').first().waitFor();
    await about.getByText('Default', { exact: true }).waitFor();
    await about.getByText('Monocraft', { exact: true }).waitFor();
    for (const family of ['Monocraft', 'Lato']) {
        await about
            .getByRole('row')
            .filter({ has: page.getByRole('link', { name: family, exact: true }) })
            .getByText(/SIL Open Font License\s*1\.1/)
            .waitFor();
    }
    await about
        .getByRole('row')
        .filter({ hasText: 'HackerNoon Pixel Icon Library' })
        .getByText('MIT', { exact: true })
        .waitFor({ timeout: 3000 });
    for (const href of [
        'https://github.com/kuba6000/AE2-Web-Integration',
        'https://github.com/kuba6000/AE2-Web-Integration/issues',
        'https://github.com/IdreesInc/Monocraft',
        'https://github.com/google/fonts/tree/main/ofl/lato',
        'https://github.com/hackernoon/pixel-icon-library'
    ])
        assert.ok(await about.locator(`a[href="${href}"]`).count(), `About must link to ${href}`);
    await clickTab(page, 'Network');
    assert.match(page.url(), new RegExp(`#/grids/${gridA}/settings$`));
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).waitFor();
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

test('About opens without a grid and remains usable in a translated narrow viewport', async (t) => {
    const { page, options, base } = await fixture(t);
    options.empty = true;
    await page.setViewportSize({ width: 375, height: 740 });
    await page.goto(`${base}#/about`);
    await page.getByRole('heading', { name: 'About', exact: true }).waitFor({ timeout: 3000 });
    await page.getByText(options.modVersion).waitFor();
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/grids/')).length, 0);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    const aboutLink = page.locator('a[href="#/about"]');
    assert.notEqual(await aboutLink.innerText(), 'About');
    await page.getByRole('button', { name: 'Nawigacja', exact: true }).click();
    await aboutLink.click();
    await page.getByText(options.modVersion).waitFor();
    assert.equal(await page.locator('html').getAttribute('lang'), 'pl');
    assert.equal(await page.getByRole('heading', { name: 'Included resources', exact: true }).count(), 0);
    assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
        true,
        'About metadata and navigation must fit a narrow viewport without page-level horizontal scrolling'
    );
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method !== 'GET').length,
        0
    );
});

test('Home retains the selected network and owns switching while Web settings owns automatic refresh', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await clickTab(page, 'Home');
    await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
    const network = page.getByRole('button', { name: 'Choose a network', exact: true });
    assert.equal(
        await page.getByRole('link', { name: 'Terminal', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/items`
    );
    assert.equal(
        await page.getByRole('link', { name: 'Network', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/settings`
    );
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Refresh', exact: true }).count(), 1);
    assert.equal(
        await page.locator('#app header').getByRole('button', { name: 'Refresh', exact: true }).isVisible(),
        true
    );
    assert.equal(await page.getByRole('checkbox', { name: 'Automatic updates' }).count(), 0);
    assert.equal(await page.locator('#app header').getByRole('combobox').count(), 0);
    assert.equal(await page.locator('#selected-network').isVisible(), false);
    await page.locator('#home-network-panel').getByRole('heading', { name: gridA, exact: true }).waitFor();
    const scopedReads = options.requests.filter(
        (request) => request.path.endsWith('/items') || /\/cpus\/[^/]+$/.test(request.path)
    ).length;
    const discovery = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await poll(page);
    await settleResponse(page, (await discovery).request());
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/items') || /\/cpus\/[^/]+$/.test(request.path))
            .length,
        scopedReads
    );
    await network.click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridB) })
        .click();
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await network.count(), 0, 'The network selector belongs only to Home');
    await clickTab(page, 'Web settings');
    const automatic = page.getByRole('checkbox', { name: 'Automatic updates' });
    await automatic.uncheck();
    await page.reload();
    await automatic.waitFor();
    assert.equal(await automatic.isChecked(), false);
    const apiReads = options.requests.filter((request) => request.path.startsWith('/api/')).length;
    await page.clock.fastForward(6000);
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/')).length, apiReads);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

test('selected network survives global page reloads and direct routes take precedence', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
    const terminal = page.getByRole('link', { name: 'Terminal', exact: true });
    for (const route of ['/', '/web-settings', '/server-settings', '/about']) {
        await page.goto(`${base}#${route}`);
        await terminal.waitFor({ timeout: 2000 });
        assert.equal(await terminal.getAttribute('href'), `#/grids/${gridA}/items`);
        await page.reload();
        await terminal.waitFor({ timeout: 2000 });
        assert.equal(await terminal.getAttribute('href'), `#/grids/${gridA}/items`);
    }
    await page.goto(`${base}#/grids/${gridB}/cpus`);
    await page.getByRole('heading', { name: 'CPUs', exact: true }).waitFor();
    await page.goto(`${base}#/web-settings`);
    await terminal.waitFor({ timeout: 2000 });
    assert.equal(await terminal.getAttribute('href'), `#/grids/${gridB}/items`);
    options.grids = [];
    await page.reload();
    await page.getByRole('checkbox', { name: 'Automatic updates' }).waitFor();
    assert.equal(await terminal.isVisible(), false, 'Remembered identity does not grant network access');
    options.grids = undefined;
    await page.reload();
    await terminal.waitFor({ timeout: 2000 });
    assert.equal(await terminal.getAttribute('href'), `#/grids/${gridB}/items`);
});

test('real page browses API resources under a proxy prefix, with search and filters', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('quartz');
    await page.getByRole('button', { name: /Certus Quartz Crystal/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('');
    await page.getByRole('button', { name: /^Availability:/ }).click();
    await page.getByRole('button', { name: /^Availability:/ }).click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: /Certus Quartz Crystal/ }).count(), 0);
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/items')).length,
        1,
        'Local search/filter changes reuse the loaded inventory'
    );
});

// Public seam: API names become readable, safely styled browser content; section codes
// follow Minecraft's stable classic formatting rules rather than leaking into labels.
async function textStyle(locator, text) {
    return locator.evaluate((element, text) => {
        const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
            if (!walker.currentNode.textContent.includes(text)) continue;
            const style = getComputedStyle(walker.currentNode.parentElement);
            return {
                color: style.color,
                weight: style.fontWeight,
                italic: style.fontStyle,
                decoration: style.textDecorationLine
            };
        }
        throw new Error(`Visible text not found: ${text}`);
    }, text);
}

test('Minecraft item formatting renders readable names and resets decorations on colors and reset codes', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [
        {
            ...iron,
            displayName:
                'Base §aGreen §lBold §oItalic §nUnderlined §mStruck §BColorCleared §L§O§N§MHeavy §RRestored §qUnknown tail§'
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', {
        name: /^Base Green Bold Italic Underlined Struck ColorCleared Heavy Restored §qUnknown tail§/
    });
    await item.waitFor({ timeout: 3000 });
    const normal = await textStyle(item, 'Base');
    assert.equal((await textStyle(item, 'Green')).color, 'rgb(85, 255, 85)');
    assert.ok(Number((await textStyle(item, 'Bold')).weight) >= 700);
    assert.equal((await textStyle(item, 'Italic')).italic, 'italic');
    assert.match((await textStyle(item, 'Underlined')).decoration, /underline/);
    const combined = await textStyle(item, 'Struck');
    assert.equal(combined.color, 'rgb(85, 255, 85)');
    assert.ok(Number(combined.weight) >= 700);
    assert.equal(combined.italic, 'italic');
    assert.match(combined.decoration, /underline/);
    assert.match(combined.decoration, /line-through/);
    assert.deepEqual(await textStyle(item, 'ColorCleared'), { ...normal, color: 'rgb(85, 255, 255)' });
    assert.ok(Number((await textStyle(item, 'Heavy')).weight) >= 700);
    assert.deepEqual(await textStyle(item, 'Restored'), normal);
    assert.match(await item.innerText(), /§qUnknown tail§/);
});

test('Minecraft names retain formatting across crafting plans, CPU work and history', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    const displayName = '§aCobalt §lIngot';
    const metadata = {
        displayName,
        registryNamespace: 'example',
        registryPath: 'cobalt_ingot',
        componentCount: 2,
        damage: 7
    };
    const finalOutput = { ...iron, ...metadata, quantity: 12 };
    options.itemsA = [{ ...iron, ...metadata }];
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], ...metadata }] };
    options.cpuDetails = { 'cpu-a': { ...cpuWork, finalOutput, items: [{ ...cpuWork.items[0], ...metadata }] } };
    options.history = [{ ...historyEntry, finalOutput }];
    options.historyDetail = { ...historyDetail, finalOutput, items: [{ ...historyDetail.items[0], ...metadata }] };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Cobalt.*Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('textbox', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const resource = page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Cobalt Ingot/ });
    await resource.waitFor({ timeout: 3000 });
    async function assertFormatted(locator) {
        assert.doesNotMatch(await locator.innerText(), /§[0-9a-fk-or]/i);
        assert.equal((await textStyle(locator, 'Cobalt')).color, 'rgb(85, 255, 85)');
        assert.ok(Number((await textStyle(locator, 'Ingot')).weight) >= 700);
    }
    await assertFormatted(resource);
    await resource.hover();
    await page.getByRole('tooltip').getByText('example:cobalt_ingot', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    const planOutput = page.getByRole('paragraph').filter({ hasText: /^Cobalt Ingot × 12$/ });
    await assertFormatted(planOutput);
    assert.notEqual((await textStyle(planOutput, '× 12')).color, 'rgb(85, 255, 85)');
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true, finalOutput } };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const cpuSummary = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-a/ }) });
    await assertFormatted(cpuSummary);
    await cpuSummary.getByRole('link').click();
    const cpuResource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Cobalt Ingot/ });
    await cpuResource.waitFor();
    await assertFormatted(cpuResource);
    await cpuResource.hover();
    assert.equal(await page.getByRole('tooltip').getByText('example:cobalt_ingot', { exact: true }).isVisible(), true);
    const cpuOutput = page.getByRole('paragraph').filter({ hasText: /^Cobalt Ingot\s*× 12$/ });
    await assertFormatted(cpuOutput);
    await clickTab(page, 'History');
    const historyLink = page.getByRole('link', { name: /Cobalt Ingot.*#1/ });
    await historyLink.waitFor();
    await assertFormatted(historyLink);
    await historyLink.click();
    const historyResource = page.getByRole('region', { name: 'Cobalt Ingot', exact: true });
    await historyResource.waitFor();
    await assertFormatted(historyResource);
    await historyResource.locator('summary').click();
    await historyResource.locator('code').waitFor();
    assert.equal(await historyResource.getByText('example:cobalt_ingot', { exact: true }).isVisible(), true);
    await assertFormatted(page.getByRole('heading', { name: 'Cobalt Ingot', level: 3, exact: true }));
    await assertFormatted(historyResource.getByRole('heading', { name: 'Cobalt Ingot', exact: true }));
});

test('Minecraft classic palette, obfuscation and literal HTML remain safe and readable', async (t) => {
    const { page, options, base } = await fixture(t);
    const colors = [
        ['0', 'rgb(0, 0, 0)'],
        ['1', 'rgb(0, 0, 170)'],
        ['2', 'rgb(0, 170, 0)'],
        ['3', 'rgb(0, 170, 170)'],
        ['4', 'rgb(170, 0, 0)'],
        ['5', 'rgb(170, 0, 170)'],
        ['6', 'rgb(255, 170, 0)'],
        ['7', 'rgb(170, 170, 170)'],
        ['8', 'rgb(85, 85, 85)'],
        ['9', 'rgb(85, 85, 255)'],
        ['A', 'rgb(85, 255, 85)'],
        ['B', 'rgb(85, 255, 255)'],
        ['C', 'rgb(255, 85, 85)'],
        ['D', 'rgb(255, 85, 255)'],
        ['E', 'rgb(255, 255, 85)'],
        ['F', 'rgb(255, 255, 255)']
    ];
    const literal = '<img src=x onerror=alert(1)> & <script>alert(2)</script>';
    options.itemsA = [
        { ...iron, displayName: colors.map(([code]) => `§${code}Color${code}`).join(' ') },
        { ...quartz, displayName: `§kSecret Words §aVisible §KMasked §rPlain §qUnknown tail§ ${literal}` }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const palette = page.getByRole('button', { name: /^Color0/ });
    await palette.waitFor();
    for (const [code, color] of colors) assert.equal((await textStyle(palette, `Color${code}`)).color, color);
    const masked = page.getByRole('button', { name: /^Secret Words Visible Masked Plain §qUnknown tail§/ });
    await masked.waitFor();
    const masks = masked.locator('[aria-hidden="true"]').filter({ hasText: '▒' });
    assert.deepEqual(await masks.allTextContents(), ['▒▒▒▒▒▒ ▒▒▒▒▒ ', '▒▒▒▒▒▒ ']);
    assert.equal((await textStyle(masked, 'Visible')).color, 'rgb(85, 255, 85)');
    await masked.click();
    const details = page.getByRole('heading', { name: /^Secret Words Visible Masked Plain §qUnknown tail§/ });
    await details.waitFor();
    assert.ok((await details.textContent()).includes(literal));
    assert.equal(await page.locator('img[src="x"]').count(), 0, 'Markup in names must remain text');
});

test('Minecraft names search and sort as continuous plain text and keep tooltip formatting through polling', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [
        { ...iron, displayName: '§fAl§apha' },
        { ...quartz, displayName: '§0Beta §qliteral' }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const alpha = page.getByRole('button', { name: /^Alpha/ });
    await alpha.waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).waitFor();
    assert.match(
        await page
            .getByRole('button', { name: /^(Alpha|Beta)/ })
            .first()
            .innerText(),
        /^Alpha/
    );
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    await search.fill('alpha');
    await alpha.waitFor();
    assert.equal(await page.getByRole('button', { name: /^Beta/ }).count(), 0);
    await search.fill('§qliteral');
    await page.getByRole('button', { name: /^Beta/ }).waitFor();
    assert.equal(await alpha.count(), 0, 'Unknown codes remain part of the searchable literal name');
    await search.fill('');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
    await alpha.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor({ state: 'visible' });
    assert.equal((await textStyle(tooltip, 'pha')).color, 'rgb(85, 255, 85)');
    options.itemsA = [{ ...iron, displayName: '§cAlpha', quantity: 8765 }, options.itemsA[1]];
    await page.getByRole('button', { name: /^Alpha.*8,765/ }).waitFor();
    assert.equal(await tooltip.isVisible(), true);
    assert.match(await tooltip.innerText(), /8,765/);
    assert.equal((await textStyle(tooltip, 'Alpha')).color, 'rgb(255, 85, 85)');
    assert.doesNotMatch(await tooltip.innerText(), /§[0-9a-fk-or]/i);
    await alpha.click();
    const details = page.getByRole('heading', { name: 'Alpha', exact: true });
    await details.waitFor();
    assert.equal((await textStyle(details, 'Alpha')).color, 'rgb(255, 85, 85)');
});

test('search explains its rules and combines mod identifiers with item text', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [
        iron,
        quartz,
        {
            ...iron,
            displayName: 'Gold Ingot',
            itemKey: 'gold',
            registryNamespace: 'minecraft',
            registryPath: 'gold_ingot'
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await search.hover();
    await page
        .getByRole('tooltip')
        .getByText(/@minecraft iron/)
        .waitFor({ timeout: 3000 });
    await search.fill('@MINECRAFT iron');
    const resources = page.getByRole('region', { name: 'Resources', exact: true });
    assert.equal(await resources.getByRole('button').count(), 1);
    await resources.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await search.fill('@ae2');
    assert.equal(await resources.getByRole('button').count(), 1);
    await resources.getByRole('button', { name: /Certus Quartz/ }).waitFor();
    await search.fill('minecraft:gold_ingot');
    await resources.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await search.fill('@missing');
    assert.equal(await resources.getByRole('button').count(), 0);
});

test('craftable resources show an accessible crafting marker that follows refreshed availability', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const marker = item.getByRole('img', { name: 'Crafting available', exact: true });
    await marker.waitFor({ state: 'visible', timeout: 3000 });
    assert.equal(
        await page
            .getByRole('button', { name: /Certus Quartz Crystal/ })
            .getByRole('img')
            .count(),
        0
    );
    await item.hover();
    await page.getByRole('tooltip').getByText('Crafting available', { exact: true }).waitFor();
    options.itemsA = [{ ...iron, craftable: false }, quartz];
    await marker.waitFor({ state: 'hidden', timeout: 10000 });
    await page.getByRole('tooltip').getByText('Not craftable', { exact: true }).waitFor();
});

test('resource quantities abbreviate from ten thousand and retain exact tooltip amounts', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    const samples = [
        [9999, '9,999'],
        [10000, '10k'],
        [128640, '128k'],
        [999999, '999k'],
        [1000000, '1M'],
        [2457600, '2.4M'],
        [1999999999, '1.9G'],
        [1000000000000, '1T'],
        [1000000000000000, '1P'],
        [1000000000000000000, '1E']
    ];
    options.itemsA = samples.map(([quantity], index) => ({
        ...iron,
        quantity,
        displayName: `Resource ${index}`,
        itemKey: `resource-${index}`
    }));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Resource 0/ }).waitFor();
    await page.evaluate(() => document.fonts.ready);
    for (const [index, [quantity, display]] of samples.entries()) {
        await page.getByRole('searchbox', { name: 'Search resources' }).fill(`Resource ${index}`);
        const item = page.getByRole('button', { name: new RegExp(`^Resource ${index} `) });
        const amount = item.getByText(display, { exact: true });
        await amount.waitFor({ state: 'visible' });
        const marker = item.getByRole('img');
        await item.hover();
        await page
            .getByRole('tooltip')
            .getByText(`Stored: ${quantity.toLocaleString('en')}`, { exact: true })
            .waitFor();
        const itemBox = await item.boundingBox();
        const amountBox = await amount.boundingBox();
        const markerBox = await marker.boundingBox();
        assert.ok(amountBox.x + amountBox.width < itemBox.x + itemBox.width, 'quantity stays inside its slot');
        assert.ok(markerBox.x + markerBox.width < amountBox.x, 'crafting marker does not overlap the quantity');
        assert.ok(await amount.evaluate((node) => node.scrollWidth <= node.clientWidth), 'quantity is not clipped');
    }
});

// Public browser seam: one availability control cycles locally and retains keyboard focus.
test('terminal availability cycles through all stored and craftable with current tooltips', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.itemsA = [iron, quartz, { ...iron, displayName: 'Gold Ingot', itemKey: 'gold', quantity: 0 }];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const availability = page.getByRole('button', { name: /^Availability:/ });
    const tooltip = page.getByRole('tooltip');
    await availability.waitFor({ timeout: 1500 });
    assert.equal(await availability.count(), 1);
    assert.equal(await availability.getAttribute('aria-pressed'), null);
    await availability.focus();
    assert.match(await tooltip.textContent(), /All.*Stored/s);
    await page.keyboard.press('Enter');
    assert.match(await availability.getAttribute('aria-label'), /Stored/);
    assert.equal(await page.getByRole('button', { name: /Gold Ingot/ }).count(), 0);
    assert.match(await tooltip.textContent(), /Stored.*Craftable/s);
    await page.keyboard.press('Space');
    assert.equal(await page.getByRole('button', { name: /Certus Quartz Crystal/ }).count(), 0);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await availability.evaluate((button) => document.activeElement === button), true);
    await availability.click();
    await page.getByRole('button', { name: /Certus Quartz Crystal/ }).waitFor();
    assert.match(await availability.getAttribute('aria-label'), /All/);
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
});

test('terminal displays structured registry and optional resource metadata without inventing values', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.itemsA = [
        {
            displayName: 'Damaged Tool',
            registryNamespace: 'example',
            registryPath: 'tool',
            componentCount: 3,
            damage: 7,
            quantity: 2,
            craftable: false,
            resourceType: 'ITEM',
            itemKey: 'tool'
        },
        {
            displayName: 'Plain Tool',
            registryNamespace: 'example',
            registryPath: 'plain',
            componentCount: 0,
            damage: 0,
            quantity: 1,
            craftable: false,
            resourceType: 'ITEM',
            itemKey: 'plain'
        },
        {
            displayName: 'Water',
            registryNamespace: 'minecraft',
            registryPath: 'water',
            componentCount: 0,
            damage: 0,
            quantity: 1000,
            craftable: false,
            resourceType: 'FLUID',
            itemKey: 'water'
        },
        {
            displayName: 'Essentia',
            registryNamespace: null,
            registryPath: null,
            componentCount: 0,
            damage: 0,
            quantity: 12,
            craftable: false,
            resourceType: 'OTHER',
            itemKey: null
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const tool = page.getByRole('button', { name: /Damaged Tool/ });
    await tool.waitFor({ timeout: 3000 });
    await tool.hover();
    const tooltip = page.getByRole('tooltip');
    assert.equal(await tooltip.getByText('example:tool', { exact: true }).isVisible(), true);
    assert.match(await tooltip.innerText(), /Components:\s*3/);
    assert.match(await tooltip.innerText(), /Damage:\s*7/);
    await tool.click();
    const details = page.locator('#details');
    assert.equal(await details.getByText('example:tool', { exact: true }).isVisible(), true);
    assert.match(await details.innerText(), /Components:\s*3/);
    assert.match(await details.innerText(), /Damage:\s*7/);
    const plain = page.getByRole('button', { name: /Plain Tool/ });
    await plain.hover();
    assert.doesNotMatch(await tooltip.innerText(), /Components:|Damage:/);
    await plain.click();
    assert.doesNotMatch(await details.innerText(), /Components:|Damage:/);
    await page.getByRole('button', { name: /Water/ }).click();
    assert.doesNotMatch(await details.innerText(), /Components:|Damage:/);
    await page.getByRole('button', { name: /Essentia/ }).click();
    assert.doesNotMatch(await details.innerText(), /Components:|Damage:|null|undefined/);
    assert.equal(await details.locator('code').count(), 0);
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    const names = () => page.locator('#items strong').allTextContents();
    await search.fill('@example');
    assert.deepEqual(await names(), ['Damaged Tool', 'Plain Tool']);
    await search.fill('example:tool');
    assert.deepEqual(await names(), ['Damaged Tool']);
    await search.fill('@tool');
    assert.deepEqual(await names(), [], 'Mod search uses the namespace, not the display name or registry path');
    await search.fill('essentia');
    assert.deepEqual(await names(), ['Essentia']);
    await search.fill('');
    const sort = page.getByRole('button', { name: /^Sort by:/ });
    await sort.click();
    await sort.click();
    assert.deepEqual(await names(), ['Essentia', 'Plain Tool', 'Damaged Tool', 'Water']);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Plain Tool/ }).click();
    assert.doesNotMatch(await details.innerText(), /Komponenty:|Uszkodzenie:/);
    await page.getByRole('button', { name: /Damaged Tool/ }).click();
    assert.match(await details.innerText(), /Komponenty:\s*3/);
    assert.match(await details.innerText(), /Uszkodzenie:\s*7/);
});

test('CPU work, plans and history retain resources with unavailable registry metadata', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    const metadata = {
        displayName: 'Essentia',
        registryNamespace: null,
        registryPath: null,
        componentCount: 0,
        damage: 0
    };
    const finalOutput = { ...metadata, itemKey: null, icon: null, quantity: 12 };
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], ...metadata }] };
    options.cpuDetails = { 'cpu-a': { ...cpuWork, finalOutput, items: [{ ...cpuWork.items[0], ...metadata }] } };
    options.history = [{ ...historyEntry, finalOutput }];
    options.historyDetail = { ...historyDetail, finalOutput, items: [{ ...historyDetail.items[0], ...metadata }] };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resource = page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Essentia/ });
    await resource.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Essentia', { exact: true }).waitFor();
    assert.equal(await tooltip.locator('code').count(), 0);
    assert.doesNotMatch(await tooltip.innerText(), /null|undefined/);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuResource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Essentia/ });
    await cpuResource.hover();
    assert.equal(await tooltip.getByText('Essentia', { exact: true }).isVisible(), true);
    assert.equal(await tooltip.locator('code').count(), 0);
    assert.doesNotMatch(await tooltip.innerText(), /null|undefined/);
    const search = page.getByRole('searchbox', { name: 'Search CPU resources' });
    await search.fill('@example');
    assert.equal(await cpuResource.count(), 0);
    await search.fill('essentia');
    await cpuResource.waitFor();
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const historyResource = page.getByRole('region', { name: 'Essentia', exact: true });
    await historyResource.locator('summary').click();
    await historyResource.getByRole('list', { name: 'Exact intervals', exact: true }).waitFor();
    assert.equal(await historyResource.locator('code').count(), 0);
    await page.getByRole('heading', { name: 'Essentia', level: 3, exact: true }).waitFor();
    assert.doesNotMatch(await historyResource.innerText(), /null|undefined/);
});

test('terminal sort criterion cycles independently of order and persists both', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await seedAutomaticRefresh(page, base, false);
    options.itemsA = [
        { ...iron, displayName: 'Alpha', registryNamespace: 'test', registryPath: 'z', itemKey: 'a', quantity: 20 },
        { ...iron, displayName: 'Beta', registryNamespace: 'test', registryPath: 'y', itemKey: 'b', quantity: 30 },
        { ...iron, displayName: 'Gamma', registryNamespace: 'test', registryPath: 'x', itemKey: 'c', quantity: 10 }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Alpha/ }).waitFor();
    const names = () => page.locator('#items strong').allTextContents();
    const sort = page.getByRole('button', { name: /^Sort by:/ });
    const order = page.getByRole('button', { name: /^Sort order:/ });
    assert.equal(await sort.count(), 1);
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Gamma']);
    await order.waitFor({ timeout: 1500 });
    await sort.focus();
    await page.keyboard.press('Enter');
    assert.match(await sort.getAttribute('aria-label'), /Quantity/);
    assert.deepEqual(await names(), ['Gamma', 'Alpha', 'Beta']);
    await order.click();
    assert.deepEqual(await names(), ['Beta', 'Alpha', 'Gamma']);
    await sort.click();
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Gamma']);
    await sort.click();
    assert.deepEqual(await names(), ['Gamma', 'Beta', 'Alpha']);
    await page.reload();
    await page.getByRole('button', { name: /Alpha/ }).waitFor();
    assert.deepEqual(await names(), ['Gamma', 'Beta', 'Alpha']);
    assert.equal(await sort.getAttribute('aria-pressed'), null);
    assert.equal(await order.getAttribute('aria-pressed'), null);
});

test('terminal item and fluid switches intersect filters and preserve other resource kinds', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await seedAutomaticRefresh(page, base, false);
    options.itemsA = [
        iron,
        quartz,
        {
            ...iron,
            displayName: 'Water',
            registryNamespace: 'minecraft',
            registryPath: 'water',
            itemKey: 'water',
            resourceType: 'FLUID',
            damage: 0
        },
        {
            ...iron,
            displayName: 'Steam',
            registryNamespace: 'addon',
            registryPath: 'steam',
            itemKey: 'steam',
            resourceType: 'FLUID',
            damage: 0,
            quantity: 0
        },
        {
            ...iron,
            displayName: 'Energy',
            registryNamespace: 'addon',
            registryPath: 'energy',
            itemKey: 'energy',
            resourceType: 'OTHER',
            componentCount: 0,
            damage: 0
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const items = page.getByRole('button', { name: /^Items:/ });
    const fluids = page.getByRole('button', { name: /^Fluids:/ });
    await items.waitFor({ timeout: 1500 });
    assert.equal(await items.getAttribute('aria-pressed'), 'true');
    assert.equal(await fluids.getAttribute('aria-pressed'), 'true');
    await items.click();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    await page.getByRole('button', { name: /Water/ }).waitFor();
    await page.getByRole('button', { name: /^Availability:/ }).click();
    assert.equal(await page.getByRole('button', { name: /Steam/ }).count(), 0);
    await page.getByRole('searchbox').fill('@minecraft');
    assert.deepEqual(await page.locator('#items strong').allTextContents(), ['Water']);
    await fluids.focus();
    await page.keyboard.press('Space');
    assert.equal(await page.locator('#items button').count(), 0);
    await page.getByRole('searchbox').fill('');
    assert.deepEqual(await page.locator('#items strong').allTextContents(), ['Energy']);
    await page.reload();
    await page.getByRole('button', { name: /Energy/ }).waitFor();
    assert.equal(await items.getAttribute('aria-pressed'), 'false');
    assert.equal(await fluids.getAttribute('aria-pressed'), 'false');
    assert.deepEqual(await page.locator('#items strong').allTextContents(), ['Energy']);
    await items.click();
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    assert.equal(await page.getByRole('heading', { name: 'Iron Ingot', exact: true }).isVisible(), true);
    assert.equal(await fluids.getAttribute('aria-pressed'), 'false');
});

// Missing or invalid saved directions use the current default, independent of the criterion.
for (const saved of [
    { sort: 'quantity', filter: 'craftable', expected: ['Certus Quartz Crystal', 'Iron Ingot'] },
    { sort: 'quantity', filter: 'craftable', sortOrder: 'sideways', expected: ['Certus Quartz Crystal', 'Iron Ingot'] },
    {
        sort: 'quantity',
        filter: 'craftable',
        sortOrder: 'descending',
        expected: ['Iron Ingot', 'Certus Quartz Crystal']
    },
    { sort: 'name', filter: 'stored', expected: ['Certus Quartz Crystal', 'Iron Ingot'] },
    { sort: 'id', filter: 'all', expected: ['Certus Quartz Crystal', 'Iron Ingot'] }
]) {
    test(`terminal restores current ${saved.sort} preferences with ${saved.sortOrder ?? 'missing'} direction`, async (t) => {
        const { page, options, base } = await fixture(t, '/ae2');
        options.itemsA = [iron, { ...quartz, craftable: true }];
        await page.addInitScript(({ sort, filter, sortOrder }) => {
            const key = 'ae2web:/ae2/:ui';
            if (!localStorage.getItem(key))
                localStorage.setItem(key, JSON.stringify({ sort, filter, sortOrder, autoRefresh: false }));
        }, saved);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        assert.deepEqual(await page.locator('#items strong').allTextContents(), saved.expected);
        const order = page.getByRole('button', { name: /^Sort order:/ });
        assert.match(
            await order.getAttribute('aria-label'),
            saved.sortOrder === 'descending' ? /Descending/ : /Ascending/
        );
        await page.getByRole('button', { name: /^Sort by:/ }).click();
        await page.reload();
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        assert.match(
            await order.getAttribute('aria-label'),
            saved.sortOrder === 'descending' ? /Descending/ : /Ascending/
        );
    });
}

test('terminal control focus and dismissed tooltips survive polling and filtering resets pagination', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = Array.from({ length: 102 }, (_, index) => ({
        ...iron,
        displayName: `Item ${String(index).padStart(3, '0')}`,
        itemKey: `item-${index}`,
        quantity: index
    }));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Item 000/ }).waitFor();
    await page.getByRole('button', { name: 'Next page', exact: true }).click();
    await page.getByRole('button', { name: /Item 101/ }).waitFor();
    const availability = page.getByRole('button', { name: /^Availability:/ });
    await availability.focus();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Item 001/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Previous page', exact: true }).isDisabled(), true);
    const tooltip = page.getByRole('tooltip');
    const label = await tooltip.textContent();
    await poll(page);
    assert.equal(await availability.evaluate((button) => document.activeElement === button), true);
    assert.equal(await tooltip.textContent(), label);
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
});

// Public seam: browser controls plus emitted HTTP. A calculation must be explicit, keep its
// quantity/identity, finish without inventory auto-refresh, and submit the chosen stable CPU key.
test('crafting calculates a quantity, polls, preserves CPU identity and submits explicitly', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await seedAutomaticRefresh(page, base, false);
    options.pendingReads = 1;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('textbox', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor({ timeout: 5000 });
    assert.match(page.url(), /\/plans\/7$/);
    await clickTab(page, 'Web settings');
    await page.getByRole('checkbox', { name: 'Automatic updates' }).check();
    await page.goBack();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    await page
        .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
        .getByRole('radio', { name: /cpu-b/ })
        .check();
    options.cpus = { 'cpu-b': cpu, 'cpu-a': cpu };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await (await refreshed).finished();
    assert.equal(
        await page
            .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
            .getByRole('radio', { name: /cpu-b/ })
            .isChecked(),
        true
    );
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    const create = options.requests.filter(
        (request) => request.method === 'POST' && request.path.endsWith('/crafting-plans')
    );
    assert.equal(create.length, 1);
    assert.deepEqual(create[0].body, { itemKey: 'iron', quantity: 12 });
    assert.equal(create[0].headers['x-ae2-request'], 'true');
    assert.match(create[0].headers['content-type'], /application\/json/);
    assert.deepEqual(
        options.requests.filter((request) => request.path.endsWith('/submit')).map((request) => request.body),
        [{ cpuKey: 'cpu-b' }]
    );
    assert.equal(await page.getByRole('link', { name: 'Inspect CPU work', exact: true }).count(), 0);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
});

// Public seam: history links retain runtime entry identity; completed detail is read-only
// and its snapshot need not be refetched for ordinary polling.
test('history rows keep product names primary and scroll beneath a stationary heading', async (t) => {
    const { page, options, base } = await fixture(t);
    options.history = Array.from({ length: 35 }, (_, index) => ({
        ...historyEntry,
        id: 100 + index,
        wasCancelled: index % 2 === 0,
        finalOutput: { ...iron, displayName: '§bLong precision assembly component', quantity: 12 + index }
    }));
    await page.goto(`${base}#/grids/${gridA}/history`);
    const first = page.getByRole('link', { name: /Long precision assembly component.*#100/ });
    await first.waitFor();
    assert.doesNotMatch(await first.innerText(), /#100/, 'opaque job identity is not the primary title');
    const region = page.getByRole('region', { name: 'History', exact: true });
    const title = page.getByRole('heading', { name: 'History', exact: true });
    const before = await title.boundingBox();
    await region.hover();
    await page.mouse.wheel(0, 900);
    await page.waitForFunction(() => document.querySelector('[role="region"][aria-label="History"]').scrollTop > 0);
    const after = await title.boundingBox();
    assert.ok(Math.abs(before.y - after.y) < 1, 'history heading stays above its contained scroll');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    assert.equal(options.requests.filter((request) => /crafting-history\/\d+$/.test(request.path)).length, 0);
    await first.focus();
    await page.keyboard.press('Enter');
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.match(page.url(), /\/history\/100$/);
    await clickTab(page, 'History');
    await first.waitFor();
});

for (const language of ['en', 'pl']) {
    test(`history and CPU share readable millisecond durations in ${language} and entire history rows navigate`, async (t) => {
        const { page, options, base } = await fixture(t);
        const durations = [0, 59999, 60000, 3600000, 86400000, 93784005, 1728000000, 60001, 61999, 3599999];
        const expected =
            language === 'en'
                ? [
                      '0 s',
                      '59.999 s',
                      '1 min',
                      '1 h',
                      '1 d',
                      '1 d 2 h 3 min 4 s',
                      '20 d',
                      '1 min',
                      '1 min 1 s',
                      '59 min 59 s'
                  ]
                : [
                      '0 s',
                      '59,999 s',
                      '1 min',
                      '1 godz.',
                      '1 dzień',
                      '1 dzień 2 godz. 3 min 4 s',
                      '20 dni',
                      '1 min',
                      '1 min 1 s',
                      '59 min 59 s'
                  ];
        options.history = durations.map((duration, index) => ({
            ...historyEntry,
            id: index + 1,
            timeDone: historyEntry.timeStarted + duration
        }));
        options.historyDetail = {
            ...historyDetail,
            timeDone: historyEntry.timeStarted + 93784005,
            items: [
                {
                    ...historyDetail.items[0],
                    timeSpentOn: 93784005,
                    timings: [{ started: historyEntry.timeStarted, ended: historyEntry.timeStarted + 93784005 }]
                }
            ],
            interfaceShare: [{ ...historyDetail.interfaceShare[0], timingsCombined: 93784005 }]
        };
        options.cpus = { 'cpu-a': { ...cpu, isBusy: true } };
        options.cpuDetails['cpu-a'] = {
            ...cpuWork,
            timeElapsed: 93784005,
            items: [{ ...cpuWork.items[0], timeSpentCrafting: 93784005 }]
        };
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption(language);
        await page.goto(`${base}#/grids/${gridA}/history`);
        const rows = page.getByRole('listitem');
        await page.getByRole('link', { name: /Iron Ingot.*#7/ }).waitFor();
        for (let index = 0; index < durations.length; index++)
            assert.equal(await rows.nth(index).locator('dd').last().innerText(), expected[index]);
        const row = rows.nth(5);
        await row.locator('time').click();
        await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
        assert.match(page.url(), /history\/6$/);
        assert.ok(
            (
                await page.getByRole('region', { name: 'Iron Ingot', exact: true }).locator('summary').innerText()
            ).includes(expected[5])
        );
        const resource = page.getByRole('region', { name: 'Iron Ingot', exact: true });
        await resource.locator('summary').click();
        assert.ok((await resource.innerText()).includes(expected[5]));
        await page
            .getByRole('button', { name: language === 'en' ? 'Pattern providers' : 'Dostawcy wzorców', exact: true })
            .click();
        assert.ok((await page.getByRole('region', { name: 'Smelter', exact: true }).innerText()).includes(expected[5]));
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const item = page.getByRole('button', { name: /Iron Ingot/ });
        await item.waitFor();
        assert.ok(
            (
                await page
                    .getByRole('region', { name: language === 'en' ? 'Current job' : 'Bieżące zlecenie', exact: true })
                    .innerText()
            ).includes(expected[5])
        );
        await item.focus();
        assert.ok((await page.getByRole('tooltip').innerText()).includes(expected[5]));
        await page.goto(`${base}#/grids/${gridA}/history`);
        const first = page.getByRole('link', { name: /Iron Ingot.*#1$/ });
        await first.waitFor();
        const box = await first.boundingBox();
        await first.click({ position: { x: box.width - 5, y: box.height - 5 } });
        await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
        assert.match(page.url(), /history\/1$/);
        await page.goto(`${base}#/grids/${gridA}/history`);
        await first.focus();
        await page.keyboard.press('Enter');
        await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
        assert.match(page.url(), /history\/1$/);
    });
}

test('history preserves entry identity and opens measured cancelled work through direct routes', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.history = [{ ...historyEntry, id: 2 }, historyEntry];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'History', exact: true }).click({ timeout: 3000 });
    await page.getByRole('link', { name: /Iron Ingot.*#2/ }).click();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.match(page.url(), /\/history\/2$/);
    await page
        .getByRole('status')
        .filter({ hasText: /cancelled/i })
        .waitFor();
    assert.equal(
        await page.getByRole('region', { name: 'Iron Ingot', exact: true }).getByText('10', { exact: true }).count(),
        1
    );
    await (
        await page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'))
    ).finished();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/crafting-history/2')).length, 1);
    await page.reload();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
});

test('history opens a deeply scrolled job at the start of its detail and preserves ordinary reading position', async (t) => {
    const { page, options, base } = await fixture(t);
    options.history = Array.from({ length: 30 }, (_, index) => ({ ...historyEntry, id: index + 1 }));
    options.historyDetail = { ...historyDetail, items: Array.from({ length: 20 }, () => historyDetail.items[0]) };
    await page.goto(`${base}#/grids/${gridA}/history`);
    const last = page.getByRole('link', { name: /Iron Ingot.*#30$/ });
    await last.scrollIntoViewIfNeeded();
    const region = page.getByRole('region', { name: 'History', exact: true });
    assert.ok(await region.evaluate((node) => node.scrollTop > 0));
    await last.click();
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    assert.equal(await region.evaluate((node) => node.scrollTop), 0, 'new detail starts at its summary');
    await page.getByRole('region', { name: 'Iron Ingot', exact: true }).last().scrollIntoViewIfNeeded();
    const readingPosition = await region.evaluate((node) => node.scrollTop);
    assert.ok(readingPosition > 0);
    await poll(page);
    assert.equal(
        await region.evaluate((node) => node.scrollTop),
        readingPosition,
        'polling does not reset reading position'
    );
    await clickTab(page, 'History');
    await page.getByRole('link', { name: /Iron Ingot.*#1$/ }).waitFor();
    assert.equal(await region.evaluate((node) => node.scrollTop), 0, 'return to the list starts at its beginning');
});

for (const appearance of ['light', 'dark']) {
    test(`history list keeps its terminal alignment and readable rows on mobile in ${appearance} mode`, async (t) => {
        const { page, options, base } = await fixture(t);
        options.settings[gridA].isTracked = true;
        options.history = Array.from({ length: 12 }, (_, index) => ({
            ...historyEntry,
            id: 100 + index,
            wasCancelled: index % 2 === 0,
            finalOutput: {
                ...iron,
                displayName: '§bPrecision assembly component with a long formatted name',
                quantity: 12 + index
            }
        }));
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
        const measure = (locator) =>
            locator.evaluate((node) => {
                const box = node.getBoundingClientRect(),
                    style = getComputedStyle(node);
                return {
                    x: box.x,
                    y: innerWidth <= 600 ? box.y - node.closest('#window').getBoundingClientRect().y : box.y,
                    height: box.height,
                    right: box.right,
                    fontSize: style.fontSize,
                    color: style.color
                };
            });
        for (const viewport of [
            { width: 1184, height: 900 },
            { width: 390, height: 844 }
        ]) {
            await page.setViewportSize(viewport);
            await page.goto(`${base}#/grids/${gridA}/items`);
            await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
            const expected = await measure(page.getByRole('heading', { name: 'Terminal', exact: true }));
            const expectedScroll = await measure(page.getByRole('region', { name: 'Resources', exact: true }));
            await clickTab(page, 'History');
            const first = page.getByRole('link', { name: /Precision assembly.*#100/ });
            await first.waitFor();
            const heading = await measure(page.getByRole('heading', { name: 'History', exact: true }));
            for (const key of viewport.width > 600 ? ['x', 'y'] : ['x'])
                assert.ok(Math.abs(heading[key] - expected[key]) < 0.1, `${key} history heading matches terminal`);
            for (const key of ['fontSize', 'color']) assert.equal(heading[key], expected[key]);
            const region = page.getByRole('region', { name: 'History', exact: true });
            const actualScroll = await measure(region);
            for (const key of viewport.width > 600 ? ['x', 'y', 'right'] : ['x', 'right'])
                assert.ok(Math.abs(actualScroll[key] - expectedScroll[key]) < 0.1, `${key} scroll viewport matches`);
            assert.ok(
                heading.y >= 0 && actualScroll.y >= heading.y + heading.height,
                'History content stays below its heading'
            );
            const rows = region.getByRole('listitem');
            assert.equal(await rows.count(), 12);
            assert.deepEqual(
                await rows.getByRole('link').evaluateAll((links) => links.map((link) => link.hash.split('/').at(-1))),
                options.history.map((entry) => String(entry.id))
            );
            assert.equal(await rows.first().locator('time').getAttribute('datetime'), '2023-11-14T22:13:30.000Z');
            for (const row of await rows.all()) {
                const bounds = await row.evaluate((node) => ({ width: node.clientWidth, content: node.scrollWidth }));
                assert.ok(bounds.content <= bounds.width + 1, 'long names and metadata fit their row');
            }
            await first.focus();
            await poll(page);
            assert.equal(await first.evaluate((node) => node === document.activeElement), true);
            assert.equal(await page.getByRole('link', { name: 'Back to resources', exact: true }).count(), 0);
            assert.ok(
                await page.evaluate(
                    () =>
                        document.documentElement.scrollWidth <= innerWidth &&
                        document.documentElement.scrollHeight <= innerHeight
                )
            );
        }
        if (process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await page.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, `history-mobile-${appearance}.png`)
            });
        }
        await page.getByRole('link', { name: /Precision assembly.*#100/ }).click();
        await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
        const historyRegion = page.getByRole('region', { name: 'History', exact: true });
        await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
        const provider = page.getByRole('region', { name: 'Smelter', exact: true });
        await provider.locator('summary').click();
        await provider.locator('time').last().scrollIntoViewIfNeeded();
        assert.ok(await historyRegion.evaluate((node) => node.scrollTop > 0));
        assert.equal(await provider.locator('time').count(), 2);
        assert.ok(
            await page.evaluate(
                () =>
                    document.documentElement.scrollWidth <= innerWidth &&
                    document.documentElement.scrollHeight <= innerHeight
            )
        );
        if (process.env.UI_SCREENSHOT_DIR) {
            await page.getByRole('heading', { name: /Iron Ingot/, level: 3 }).scrollIntoViewIfNeeded();
            await page.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, `history-detail-mobile-${appearance}.png`)
            });
        }
        options.history = [options.history[0]];
        await clickTab(page, 'History');
        await page.getByRole('link', { name: /Precision assembly.*#100/ }).waitFor();
        await page.reload();
        await page.getByRole('link', { name: /Precision assembly.*#100/ }).waitFor();
        assert.equal(await page.getByRole('region', { name: 'History', exact: true }).getByRole('listitem').count(), 1);
        options.history = [];
        await page.reload();
        await page
            .getByRole('status')
            .filter({ hasText: /No crafting history/ })
            .waitFor();
        assert.equal(await page.getByRole('region', { name: 'History', exact: true }).getByRole('listitem').count(), 0);
    });
}

// Public seam: an authorized grid user edits a draft and saves one explicit PATCH. Access
// sources are grouped informational server text, never markup or an ownership gate.
test('grid settings preserve the draft and save explicitly while safely showing grouped access sources', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    const player = { name: '<img src=x onerror=alert(1)> Alex', uuid: '11111111-1111-1111-1111-111111111111' };
    options.accessSources = {
        [player.uuid]: [
            {
                player,
                kind: 'security_terminal',
                reason: 'security_card',
                position: { dimensionId: 'minecraft:overworld', x: 120, y: 64, z: -32 },
                side: 'north'
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'Network', exact: true }).click({ timeout: 3000 });
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    const networkName = page.getByRole('textbox', { name: 'Network name', exact: true });
    await networkName.waitFor();
    await page.evaluate(() => document.fonts.ready);
    const formPosition = () =>
        networkName.evaluate((input) => input.getBoundingClientRect().top + input.closest('[role="region"]').scrollTop);
    const cleanPosition = await formPosition();
    await tracking.check();
    assert.equal(await formPosition(), cleanPosition, 'An unsaved notice must not move the form content');
    const draftStatus = page.getByRole('status').filter({ hasText: /unsaved/i });
    await draftStatus.waitFor();
    const saveBounds = await page.getByRole('button', { name: 'Save settings', exact: true }).boundingBox();
    assert.ok((await draftStatus.boundingBox()).y >= saveBounds.y, 'Draft feedback belongs by the save action');
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await (await refreshed).finished();
    assert.equal(await tracking.isChecked(), true);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 0);
    await page.getByText(player.name, { exact: true }).waitFor();
    await page.getByText(player.name, { exact: true }).click();
    await page.getByText(player.uuid, { exact: true }).waitFor();
    await page.getByText(/minecraft:overworld.*120.*64.*-32/).waitFor();
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    const patches = options.requests.filter((request) => request.method === 'PATCH');
    assert.equal(patches.length, 1);
    assert.deepEqual(patches[0].body, { isTracked: true });
    assert.equal(patches[0].headers['x-ae2-request'], 'true');
    assert.match(patches[0].headers['content-type'], /application\/json/);
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
    await page.reload();
    await tracking.waitFor();
    assert.equal(await tracking.isChecked(), true);
});

// Public seam: interval details preserve each server resource row and provider group. Provider
// positions belong to the group; interval bounds are absolute timestamps, even at zero duration.
test('history exposes resource and provider intervals with their correct locations', async (t) => {
    const { page, options, base } = await fixture(t);
    options.historyDetail = {
        ...historyDetail,
        items: [...historyDetail.items, { ...historyDetail.items[0], timings: [] }]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('list', { name: 'Resources', exact: true }).waitFor();
    assert.equal(await page.getByRole('region', { name: 'Iron Ingot', exact: true }).count(), 2);
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    const provider = page.getByRole('region', { name: 'Smelter', exact: true });
    await provider.locator('summary').click();
    await provider.getByText(/minecraft:overworld.*120.*64.*-32/).waitFor();
    assert.deepEqual(await provider.locator('time').evaluateAll((times) => times.map((time) => time.dateTime)), [
        '2023-11-14T22:13:21.000Z',
        '2023-11-14T22:13:26.000Z'
    ]);
    await page.getByRole('button', { name: 'Resources', exact: true }).click();
    assert.equal(await page.getByRole('region', { name: 'Iron Ingot', exact: true }).count(), 2);
    options.historyDetail = { ...historyDetail, timeDone: historyDetail.timeStarted, items: [], interfaceShare: [] };
    await page.reload();
    await provider.waitFor({ state: 'hidden' });
    assert.doesNotMatch(await page.locator('body').innerText(), /NaN|Infinity/);
    await page.getByText(/No processing intervals/).waitFor();
});

// A failed persistence response can leave the in-memory value changed. GET agreement must
// not be labelled a saved result; only a deliberate successful PATCH confirms saving.
test('uncertain settings saves preserve the draft and remain unconfirmed until explicit retry succeeds', async (t) => {
    const { page, options, base } = await fixture(t);
    for (const status of ['INTERNAL_ERROR', 'TIMEOUT', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        options.settings[gridA] = {
            isTracked: false,
            name: '',
            isStorageTracked: false,
            isFlowTracked: false,
            stockSampleIntervalSeconds: 300
        };
        await page.goto(`${base}?case=${status}#/grids/${gridA}/settings`);
        const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
        await tracking.check();
        options.settings[gridA] = {
            isTracked: true,
            name: '',
            isStorageTracked: false,
            isFlowTracked: false,
            stockSampleIntervalSeconds: 300
        };
        options.fault = { operation: 'settings', status };
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /save.*not confirmed/i })
            .waitFor({ timeout: 3000 });
        const count = options.requests.filter((request) => request.method === 'PATCH').length;
        await poll(page);
        await clickTab(page, 'Terminal');
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await tracking.waitFor();
        await page
            .getByRole('status')
            .filter({ hasText: /save.*not confirmed/i })
            .waitFor();
        assert.equal(await tracking.isChecked(), true);
        assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, count);
        options.fault = null;
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /settings saved/i })
            .waitFor();
        assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, count + 1);
    }
});

test('settings drafts survive transient discovery failures and pending saves recover after access loss', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await tracking.check();
    options.gridError = 'NETWORK_ERROR';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /Cannot connect/ })
        .first()
        .waitFor();
    options.gridError = null;
    const recovered = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await (await recovered).finished();
    await page.getByRole('button', { name: 'Save settings', exact: true }).waitFor();
    assert.equal(await tracking.isChecked(), true);
    let complete;
    const pending = new Promise((resolve) => {
        complete = resolve;
    });
    await page.route('**/settings', (route) =>
        route.request().method() === 'PATCH' ? complete(route) : route.continue()
    );
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    const save = await pending;
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    await tracking.waitFor({ state: 'hidden' });
    options.settings[gridA] = {
        isTracked: true,
        name: '',
        isStorageTracked: false,
        isFlowTracked: false,
        stockSampleIntervalSeconds: 300
    };
    await save.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: {
                isTracked: true,
                name: '',
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300
            }
        })
    });
    assert.equal(await tracking.count(), 0);
    options.gridError = null;
    await poll(page);
    await tracking.waitFor();
    assert.equal(await tracking.isChecked(), true);
});

test('history reentry during grid discovery clears unavailable snapshots', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    let held = false;
    await page.route('**/api/grids', (route) => {
        if (held) return route.continue();
        held = true;
        release(route);
    });
    await poll(page);
    const discovery = await captured;
    await clickTab(page, 'History');
    await page.getByRole('link', { name: /Iron Ingot.*#1/ }).waitFor();
    options.historyError = 'TRACKING_NOT_FOUND';
    await page.getByRole('link', { name: /Iron Ingot.*#1/ }).click();
    await discovery.continue();
    await page
        .getByRole('status')
        .filter({ hasText: /history entry.*available/i })
        .waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).count(), 0);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

test('settings save remains single-flight across grids and uses the returned value on reentry', async (t) => {
    const { page, options, base } = await fixture(t);
    const player = { name: 'Alex', uuid: '11111111-1111-1111-1111-111111111111' };
    options.accessSources = {
        [player.uuid]: [
            {
                player,
                kind: 'controller',
                reason: 'node_owner',
                position: { dimensionId: 'minecraft:overworld', x: 1, y: 64, z: 2 },
                side: null
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await tracking.check();
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    await page.route('**/settings', (route) =>
        route.request().method() === 'PATCH' ? capture(route) : route.continue()
    );
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    const pending = await captured;
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await tracking.waitFor();
    assert.equal(await tracking.isChecked(), false);
    await page.goBack();
    await page
        .getByRole('status')
        .filter({ hasText: /Saving settings/i })
        .waitFor();
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: {
                isTracked: false,
                name: '',
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300
            }
        })
    });
    await page
        .getByRole('status')
        .filter({ hasText: /Settings saved/i })
        .waitFor();
    assert.equal(await tracking.isChecked(), false);
    await page.getByText('Alex', { exact: true }).waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
});

test('old settings reads cannot replace saved data and denied saves clear access sources', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await tracking.check();
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    let held = false;
    await page.route('**/settings', (route) => {
        if (held || route.request().method() !== 'GET') return route.continue();
        held = true;
        capture(route);
    });
    await poll(page);
    const delayed = await captured;
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /Settings saved/i })
        .waitFor();
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: {
                isTracked: false,
                name: '',
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300
            }
        })
    });
    await settleResponse(page, delayed.request());
    assert.equal(await tracking.isChecked(), true);
    options.settingsError = 'NO_PERMISSIONS';
    await tracking.uncheck();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await tracking.count(), 0);
    assert.equal(await page.getByRole('heading', { name: 'Network access', exact: true }).count(), 0);
});

test('late history details cannot enter another grid and denied reads remove the selected snapshot', async (t) => {
    const { page, options, base } = await fixture(t);
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route(`**/${gridA}/crafting-history/1`, (route) => release(route));
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const delayed = await captured;
    options.historyDetail = {
        ...historyDetail,
        finalOutput: { ...quartz, quantity: 9 },
        items: [],
        interfaceShare: []
    };
    await page.goto(`${base}#/grids/${gridB}/history/2`);
    await page.getByRole('heading', { name: /Certus Quartz Crystal/ }).waitFor();
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: historyDetail })
    });
    await settleResponse(page, delayed.request());
    assert.equal(await page.getByRole('heading', { name: /Iron Ingot/ }).count(), 0);
    options.historyError = 'NO_PERMISSIONS';
    await page.reload();
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await page.getByRole('heading', { name: /Certus Quartz Crystal/ }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

for (const selected of [true, false])
    test(`CPU ${selected ? 'footer' : 'overview'} stays stable through a delayed action and authoritative read`, async (t) => {
        const { page, options, base } = await fixture(t);
        await page.setViewportSize({ width: 1280, height: 800 });
        options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
        options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
        await page.goto(`${base}#/grids/${gridA}/cpus${selected ? '/cpu-a' : ''}`);
        const pause = page.getByRole('button', { name: /^Pause current work/ });
        await pause.waitFor();
        const surface = selected
            ? page.getByRole('region', { name: 'CPU resources', exact: true })
            : page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /Assembler.*cpu-a/ }) });
        const geometry = async () => [
            await surface.boundingBox(),
            await pause.boundingBox(),
            await page.getByRole('link', { name: 'Home', exact: true }).boundingBox(),
            await page.getByRole('heading', { name: 'AE2 Web Integration', exact: true }).boundingBox()
        ];
        const before = await geometry();
        let captureMutation;
        const mutationRequest = new Promise((resolve) => {
            captureMutation = resolve;
        });
        await page.route('**/pause', (route) => captureMutation(route));
        await pause.click();
        const mutation = await mutationRequest;
        assert.equal(await pause.isDisabled(), true);
        assert.deepEqual(await geometry(), before, 'Pending work must not insert a footer or card status line');
        const activity = page.getByRole('status', { name: 'Activity', exact: true });
        await activity.waitFor({ timeout: 2000 });
        if (selected && process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'cpu-action-progress.png') });
        }
        let captureRead;
        const readRequest = new Promise((resolve) => {
            captureRead = resolve;
        });
        await page.route(
            (url) => url.pathname.endsWith('/cpus'),
            (route) => captureRead(route)
        );
        options.cpus['cpu-a'].isPaused = true;
        options.cpuDetails['cpu-a'].isPaused = true;
        await mutation.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'OK', data: null }) });
        const read = await readRequest;
        assert.equal(await activity.isVisible(), true);
        assert.equal(await pause.isDisabled(), true);
        assert.deepEqual(await geometry(), before, 'The authoritative read keeps the same geometry and locks');
        await read.continue();
        const resume = page.getByRole('button', { name: /^Resume current work/ });
        await resume.waitFor();
        assert.equal(await resume.isEnabled(), true);
        await activity.waitFor({ state: 'hidden' });
    });

test('CPU cancellation waits for the authoritative read before showing completion', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-a'] = { ...cpu, isBusy: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    let captureRead;
    const readRequest = new Promise((resolve) => {
        captureRead = resolve;
    });
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).waitFor();
    await page.route(
        (url) => url.pathname.endsWith('/cpus'),
        (route) => captureRead(route)
    );
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    const read = await readRequest;
    const completed = page.getByRole('status').filter({ hasText: /Cancellation completed/i });
    assert.equal(await completed.isVisible(), false, 'Success requires the post-mutation snapshot');
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
    await read.continue();
    await completed.waitFor();
});

test('header icon controls refresh the current history entry and retain dirty settings drafts', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    const header = page.locator('#app header');
    const refresh = header.getByRole('button', { name: 'Refresh', exact: true });
    await refresh.waitFor({ timeout: 2000 });
    assert.equal(await header.getByRole('button', { name: 'Log out', exact: true }).innerText(), '');
    const before = options.requests.filter((request) => request.path.endsWith('/crafting-history/1')).length;
    options.historyDetail = { ...historyDetail, finalOutput: quartz };
    await refresh.click();
    await page.getByRole('heading', { name: 'Certus Quartz Crystal', level: 3, exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/crafting-history/1')).length, before + 1);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Unsaved workshop');
    await refresh.click();
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    assert.equal(await name.inputValue(), 'Unsaved workshop');
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('header refresh stays active through a history detail read queued behind automatic discovery', async (t) => {
    const { page, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    await page.getByRole('heading', { name: 'Iron Ingot', level: 3, exact: true }).waitFor();
    let discoveryRequests = 0;
    let captureGrids;
    const gridsRequest = new Promise((resolve) => {
        captureGrids = resolve;
    });
    let captureDetail;
    const detailRequest = new Promise((resolve) => {
        captureDetail = resolve;
    });
    await page.route(
        (url) => url.pathname.endsWith('/api/grids'),
        (route) => {
            discoveryRequests++;
            captureGrids(route);
        }
    );
    await page.route(
        (url) => url.pathname.endsWith('/crafting-history/1'),
        (route) => captureDetail(route)
    );
    await poll(page);
    const grids = await gridsRequest;
    const refresh = page.locator('#app header').getByRole('button', { name: 'Refresh', exact: true });
    await refresh.click();
    await grids.continue();
    const detail = await detailRequest;
    assert.equal(
        await refresh.getAttribute('aria-busy'),
        'true',
        'Queued detail loading remains part of visible activity'
    );
    assert.equal(await refresh.isEnabled(), true);
    await detail.continue();
    await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
    assert.equal(discoveryRequests, 1, 'Refreshing history detail reuses discovery already in flight');
});

test('CPU background refresh signals activity without disabling or dimming existing controls', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
    options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const pause = page.getByRole('button', { name: 'Pause current work', exact: true });
    await pause.waitFor();
    const refresh = page.locator('#app header').getByRole('button', { name: 'Refresh', exact: true });
    const controls = [
        pause,
        page.getByRole('button', { name: 'Cancel current work', exact: true }),
        page.getByRole('searchbox', { name: 'Search CPU resources', exact: true }),
        page.getByRole('button', { name: 'Hide stored-only resources', exact: true }),
        refresh
    ];
    const appearance = async () =>
        Promise.all(
            controls.map(async (control) => ({
                enabled: await control.isEnabled(),
                box: await control.boundingBox(),
                opacity: await control.evaluate((node) => getComputedStyle(node).opacity)
            }))
        );
    const before = await appearance();
    let captureRead;
    const readRequest = new Promise((resolve) => {
        captureRead = resolve;
    });
    await page.route(
        (url) => url.pathname.endsWith('/cpus'),
        (route) => captureRead(route)
    );
    await poll(page);
    const read = await readRequest;
    const activity = page.getByRole('status', { name: 'Activity', exact: true });
    await activity.waitFor({ timeout: 2000 });
    assert.equal(await activity.getAttribute('aria-live'), 'off');
    assert.deepEqual(await appearance(), before, 'Automatic refresh must keep the ready UI fully interactive');
    const requestsBefore = options.requests.length;
    await refresh.click();
    await refresh.click();
    assert.equal(options.requests.length, requestsBefore, 'Repeated refresh uses the in-flight application refresh');
    await read.continue();
    await activity.waitFor({ state: 'hidden' });
    assert.deepEqual(await appearance(), before);
});

for (const width of [390, 1280])
    test(`header refresh keeps icon controls stable at ${width}px and honors reduced motion`, async (t) => {
        const { page, options, base } = await fixture(t);
        await page.setViewportSize({ width, height: 844 });
        await seedAutomaticRefresh(page, base, false);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const header = page.locator('#app header');
        const refresh = header.getByRole('button', { name: 'Refresh', exact: true });
        const logout = header.getByRole('button', { name: 'Log out', exact: true });
        const geometry = async () => [
            await refresh.boundingBox(),
            await logout.boundingBox(),
            await (
                width <= 600
                    ? page.getByRole('button', { name: 'Navigation', exact: true })
                    : page.getByRole('link', { name: 'Home', exact: true })
            ).boundingBox()
        ];
        const before = await geometry();
        assert.ok(before[0].x >= before[1].x + before[1].width, 'Refresh sits to the right of logout');
        assert.ok(before[0].x + before[0].width <= width);
        let capture;
        const requested = new Promise((resolve) => {
            capture = resolve;
        });
        await page.route(
            (url) => url.pathname.endsWith('/api/grids'),
            (route) => capture(route)
        );
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await refresh.click();
        const read = await requested;
        assert.equal(await refresh.isEnabled(), true);
        assert.equal(await refresh.getAttribute('aria-busy'), 'true');
        const visibleIcon = refresh.locator('svg:visible');
        assert.equal(await visibleIcon.count(), 1, 'Only the current refresh glyph is visible');
        assert.equal(await visibleIcon.evaluate((node) => getComputedStyle(node).animationName), 'none');
        assert.deepEqual(await geometry(), before);
        await page.emulateMedia({ reducedMotion: 'no-preference' });
        assert.notEqual(await visibleIcon.evaluate((node) => getComputedStyle(node).animationName), 'none');
        await logout.focus();
        await page
            .getByRole('tooltip')
            .filter({ hasText: /^Log out$/ })
            .waitFor();
        await page.keyboard.press('Escape');
        await read.continue();
        await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
        assert.deepEqual(await geometry(), before);
        assert.equal(
            options.requests.some((request) => request.method !== 'GET'),
            false
        );
    });

test('pending CPU cancellation settles on the revisited CPU and older reads cannot override it', async (t) => {
    const { page, options, base } = await fixture(t);
    let complete;
    await page.route('**/cancel', (route) => {
        complete = (status) =>
            route.fulfill({
                status: status === 'OK' ? 200 : 503,
                contentType: 'application/json',
                body: JSON.stringify({ status, data: null })
            });
    });
    for (const result of ['OK', 'TIMEOUT']) {
        options.cpuDetails['cpu-a'] = cpuWork;
        await page.goto(`${base}?case=${result}#/grids/${gridA}/cpus/cpu-a`);
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /Cancelling/i })
            .waitFor();
        if (result === 'OK')
            options.cpuDetails['cpu-a'] = { ...cpuWork, isBusy: false, items: null, finalOutput: null };
        await complete(result);
        await page
            .getByRole('status')
            .filter({ hasText: result === 'OK' ? /^Idle$/i : /outcome.*unknown/i })
            .waitFor();
        await poll(page);
        await page
            .getByRole('status')
            .filter({ hasText: result === 'OK' ? /^Idle$/i : /outcome.*unknown/i })
            .waitFor();
    }
    await page.unroute('**/cancel');
    options.cpuDetails['cpu-a'] = cpuWork;
    await page.goto(`${base}?case=late-read#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    let held = false;
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-a'),
        (route) => {
            if (held) return route.continue();
            held = true;
            capture(route);
        }
    );
    await poll(page);
    const delayed = await captured;
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /^Idle$/i })
        .waitFor();
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: cpuWork })
    });
    await settleResponse(page, delayed.request());
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
});

// Public seam: reopening a calculation reads its runtime identity; cancelling it is a single
// explicit DELETE and navigation never repeats either mutation.
test('direct plan entry only reads, and explicit cancellation stops calculation polling', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.pendingReads = 100;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Cancel', exact: true }).click({ timeout: 3000 });
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    const reads = options.requests.filter(
        (request) => request.path.endsWith('/crafting-plans/7') && request.method === 'GET'
    ).length;
    await page.waitForTimeout(1200);
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/crafting-plans/7') && request.method === 'GET')
            .length,
        reads
    );
    assert.deepEqual(
        options.requests.filter((request) => request.method !== 'GET').map((request) => request.method),
        ['DELETE']
    );
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 1);
});

// Public seam: CPU option availability and selection across live data and full reload. Variant
// identity and capacity determine merging; a name or ordinal cannot identify a selected CPU.
test('busy CPU eligibility uses known output identity and missing selections require a new choice', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': cpu,
        'cpu-b': { ...cpu, isBusy: true, usedStorage: 2048, finalOutput: { ...iron, itemKey: 'iron' } },
        'other-output': { ...cpu, isBusy: true, finalOutput: { ...iron, itemKey: 'other-variant' } },
        'unknown-storage': { ...cpu, isBusy: true, usedStorage: -1, finalOutput: { ...iron, itemKey: 'iron' } }
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const cpus = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).evaluate((option) => option.disabled), false);
    assert.equal(await cpus.getByRole('radio', { name: /other-output/ }).evaluate((option) => option.disabled), true);
    assert.equal(
        await cpus.getByRole('radio', { name: /unknown-storage/ }).evaluate((option) => option.disabled),
        true
    );
    await cpus.getByRole('radio', { name: /cpu-b/ }).check();
    delete options.cpus['cpu-b'];
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await (await refreshed).finished();
    await cpus.getByRole('radio', { name: /cpu-b/ }).waitFor({ state: 'detached' });
    assert.equal(await cpus.getByRole('radio', { checked: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.cpus['cpu-b'] = { ...cpu, isBusy: true, finalOutput: { ...iron, itemKey: 'iron' } };
    await page.reload();
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).evaluate((option) => option.disabled), false);
    assert.equal(await cpus.getByRole('radio', { name: /other-output/ }).isDisabled(), true);
    assert.equal(await cpus.getByRole('radio', { name: /unknown-storage/ }).isDisabled(), true);
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 1);
});

// Public seam: selecting a CPU opens its terminal, with separate resource tools and CPU controls.
test('CPU memory uses binary units through exabytes in overview and current work', async (t) => {
    const { page, options, base } = await fixture(t);
    const capacities = [
        0,
        1023,
        1024,
        65536,
        1572864,
        1073741824,
        1099511627776,
        1125899906842624,
        JSON.parse('9223372036854775807')
    ];
    const expected = ['0 B', '1,023 B', '1 KB', '64 KB', '1.5 MB', '1 GB', '1 TB', '1 PB', '8 EB'];
    options.cpus = Object.fromEntries(
        capacities.map((availableStorage, index) => [`cpu-${index}`, { ...cpu, availableStorage, usedStorage: 18432 }])
    );
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    for (let index = 0; index < capacities.length; index++) {
        const card = page.getByRole('link', { name: `Assembler · cpu-${index}`, exact: true });
        await card.waitFor();
        assert.equal(await card.locator('dd').first().innerText(), expected[index]);
        assert.equal(await card.locator('dd').nth(1).innerText(), '18 KB');
    }
    options.cpuDetails['cpu-4'] = cpuWork;
    await page.getByRole('link', { name: 'Assembler · cpu-4', exact: true }).click();
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    await page
        .getByRole('status')
        .filter({ hasText: /^Busy$/ })
        .waitFor();
    assert.match(await panel.getByRole('link', { name: /cpu-4/ }).innerText(), /1\.5 MB/);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-4`);
    await page
        .getByRole('complementary', { name: 'CPU', exact: true })
        .getByRole('link', { name: /cpu-4.*1,5 MB/ })
        .waitFor();
});

for (const width of [390, 1280]) {
    test(`CPU lists show native selection modes and hide indicators when unsupported at ${width}px`, async (t) => {
        const { page, options, base } = await fixture(t, '', { viewport: { width, height: 844 } });
        options.capabilities.cpuSelectionMode = true;
        options.cpus = {
            'cpu-player': { ...cpu, selectionMode: 'PLAYER_ONLY' },
            'cpu-machine': { ...cpu, selectionMode: 'AUTOMATION_ONLY' },
            'cpu-both': { ...cpu, selectionMode: 'ALL' },
            'cpu-unknown': { ...cpu, selectionMode: null }
        };
        options.cpuDetails['cpu-player'] = { ...cpuWork, selectionMode: 'PLAYER_ONLY' };
        for (const route of ['cpus', 'cpus/cpu-player', 'plans/7']) {
            await page.goto(`${base}#/grids/${gridA}/${route}`);
            if (width === 390 && route !== 'cpus')
                await page.getByRole('button', { name: 'CPUs', exact: true }).click();
            for (const name of ['Players only', 'Automation only', 'Players and automation']) {
                await page
                    .getByRole('img', { name: `Automatic selection: ${name}`, exact: true })
                    .waitFor({ timeout: 2000 });
            }
            assert.equal(await page.getByRole('img', { name: /Automatic selection:/ }).count(), 3);
            if (process.env.UI_SCREENSHOT_DIR) {
                await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
                await page.screenshot({
                    path: path.join(
                        process.env.UI_SCREENSHOT_DIR,
                        `cpu-modes-${width}-${route.replaceAll('/', '-')}.png`
                    )
                });
            }
        }
        options.cpus['cpu-player'].selectionMode = null;
        await poll(page);
        await page
            .getByRole('img', { name: 'Automatic selection: Players only', exact: true })
            .waitFor({ state: 'hidden' });
        assert.equal(await page.getByRole('img', { name: /Automatic selection:/ }).count(), 2);
        options.capabilities.cpuSelectionMode = false;
        await page.reload();
        if (width === 390) await page.getByRole('button', { name: 'CPUs', exact: true }).click();
        await page.getByRole('radio', { name: /cpu-player/ }).waitFor();
        assert.equal(await page.getByRole('img', { name: /Automatic selection:/ }).count(), 0);
    });
}

// Public browser/HTTP seam: navigation keeps a bounded selector and never mutates CPU work.
test('CPU automation sorting uses native modes, keeps unknown last, and preserves saved intent without capability', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.cpuSelectionMode = true;
    options.cpus = {
        'cpu-c': { ...cpu, name: 'Alpha', selectionMode: 'AUTOMATION_ONLY', acceptsPlayerJobs: true },
        'cpu-a': { ...cpu, name: 'Beta', selectionMode: 'PLAYER_ONLY', acceptsPlayerJobs: false },
        'cpu-d': { ...cpu, name: 'Gamma', selectionMode: 'ALL' },
        'cpu-b': { ...cpu, name: 'Gamma', selectionMode: 'ALL' },
        'cpu-z': { ...cpu, name: 'Aardvark', selectionMode: null },
        'cpu-y': { ...cpu, name: 'Zulu', selectionMode: null }
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, selectionMode: 'PLAYER_ONLY' };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    const links = panel.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link');
    const order = () => links.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('href').split('/').at(-1)));
    for (const criterion of ['Name', 'Capacity', 'Coprocessors', 'Busy state'])
        await panel.getByRole('button', { name: `Sort CPUs: ${criterion}`, exact: true }).click();
    await panel.getByRole('button', { name: 'Sort CPUs: Automation', exact: true }).waitFor({ timeout: 2000 });
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-d', 'cpu-c', 'cpu-z', 'cpu-y']);
    await panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-b', 'cpu-d', 'cpu-a', 'cpu-z', 'cpu-y']);
    await page.mouse.move(0, 0);
    await panel.getByRole('link', { name: /cpu-c/ }).scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await panel.getByRole('link', { name: /cpu-c/ }).focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /Automatic selection: Automation only/ })
        .waitFor();
    assert.match(await page.getByRole('tooltip').innerText(), /not permission to submit work/);
    await panel.getByRole('link', { name: /cpu-z/ }).scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await panel.getByRole('link', { name: /cpu-z/ }).focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /Automatic selection: Unsupported or unknown/ })
        .waitFor();
    options.capabilities.cpuSelectionMode = false;
    await page.reload();
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).waitFor();
    assert.equal(await panel.getByRole('button', { name: 'Sort CPUs: Automation', exact: true }).count(), 0);
    await links.first().waitFor();
    assert.deepEqual(await order(), ['cpu-y', 'cpu-b', 'cpu-d', 'cpu-a', 'cpu-c', 'cpu-z']);
    await panel.getByRole('link', { name: /cpu-y/ }).focus();
    await page.getByRole('tooltip').waitFor();
    assert.doesNotMatch(await page.getByRole('tooltip').innerText(), /Automatic selection/);
    options.capabilities.cpuSelectionMode = true;
    await page.reload();
    await panel.getByRole('button', { name: 'Sort CPUs: Automation', exact: true }).waitFor();
    await links.first().waitFor();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-b', 'cpu-d', 'cpu-a', 'cpu-z', 'cpu-y']);
    options.cpus['cpu-a'].selectionMode = 'AUTOMATION_ONLY';
    options.cpuDetails['cpu-a'].selectionMode = 'ALL';
    await poll(page);
    await page.getByRole('region', { name: 'Current job', exact: true }).focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: /Automatic selection: Players and automation/ })
        .waitFor();
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('CPU selector cycles summary sorting with stable ties and remembers criterion and direction', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-c': { ...cpu, name: 'Beta', availableStorage: 8192, coProcessors: 1 },
        'cpu-b': { ...cpu, name: 'Alpha', availableStorage: 16384, coProcessors: 2, isBusy: true },
        'cpu-d': { ...cpu, name: 'Delta', availableStorage: 4096, coProcessors: 2 },
        'cpu-a': { ...cpu, name: '§aAlpha', availableStorage: 8192, coProcessors: 3, isBusy: true, isPaused: true }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    const links = panel.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link');
    const order = () => links.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('href').split('/').at(-1)));
    const initialRequests = options.requests.length;
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).waitFor({ timeout: 2000 });
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-d', 'cpu-a', 'cpu-c', 'cpu-b']);
    await panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-b', 'cpu-a', 'cpu-c', 'cpu-d']);
    await panel.getByRole('button', { name: 'Sort CPUs: Capacity', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-d', 'cpu-c']);
    await panel.getByRole('button', { name: 'CPU sort order: Descending', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-b', 'cpu-d', 'cpu-a']);
    await panel.getByRole('button', { name: 'Sort CPUs: Coprocessors', exact: true }).click();
    assert.deepEqual(await order(), ['cpu-c', 'cpu-d', 'cpu-a', 'cpu-b']);
    const direction = panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true });
    assert.equal(await direction.getAttribute('aria-pressed'), null);
    await direction.click();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    assert.equal(options.requests.length, initialRequests, 'Client sorting must not trigger HTTP work');
    await page.reload();
    await panel.getByRole('button', { name: 'Sort CPUs: Busy state', exact: true }).waitFor();
    await links.first().waitFor();
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    assert.equal(
        await panel.getByRole('button', { name: 'CPU sort order: Descending', exact: true }).isVisible(),
        true
    );
    options.cpus = Object.fromEntries(Object.entries(options.cpus).reverse());
    await poll(page);
    assert.deepEqual(await order(), ['cpu-a', 'cpu-b', 'cpu-c', 'cpu-d']);
    assert.equal(await panel.getByRole('link', { name: /cpu-a/ }).getAttribute('aria-current'), 'page');
    assert.match(page.url(), /\/cpus\/cpu-a$/);
});

test('CPU selector ignores unsupported saved sorting choices', async (t) => {
    const { page, base } = await fixture(t);
    await page.addInitScript(() =>
        localStorage.setItem(
            'ae2web:/:theme:default',
            JSON.stringify({ cpuSelectorSort: 'progress', cpuSelectorSortOrder: 'sideways' })
        )
    );
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    await panel.getByRole('button', { name: 'Sort CPUs: Name', exact: true }).waitFor();
    assert.equal(await panel.getByRole('button', { name: 'CPU sort order: Ascending', exact: true }).isVisible(), true);
    await panel.getByRole('link', { name: /cpu-a/ }).waitFor();
    assert.equal(await panel.getByRole('link', { name: /cpu-a/ }).getAttribute('aria-current'), 'page');
});

test('CPU search opens from the magnifier, focuses the field and clears its filter when closed', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-b'] = { ...cpu, name: 'Other processor' };
    for (const width of [390, 1280]) {
        await page.setViewportSize({ width, height: 844 });
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        await openMobilePane(page, 'CPUs');
        const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
        const search = panel.getByRole('searchbox', { name: 'Search CPUs', exact: true });
        const toggle = panel.getByRole('button', { name: 'Search CPUs', exact: true });
        await toggle.waitFor({ timeout: 2000 });
        assert.equal(await search.isVisible(), false);
        const before = await toggle.boundingBox();
        await toggle.click();
        assert.equal(await search.evaluate((node) => node === document.activeElement), true);
        await search.fill('Other');
        assert.equal(await panel.getByRole('link').count(), 1);
        const listBounds = await panel.getByRole('region', { name: 'CPU selector', exact: true }).boundingBox();
        const rowBounds = await panel.getByRole('link').boundingBox();
        assert.ok(listBounds.height >= rowBounds.height, 'Expanded search leaves a complete CPU result readable');
        assert.ok(
            (await page.getByRole('region', { name: 'CPU resources', exact: true }).boundingBox()).height > 100,
            'Search must not take the resource viewport away'
        );
        await panel.getByRole('button', { name: 'Close CPU search', exact: true }).click();
        assert.equal(await search.isVisible(), false);
        assert.equal(await panel.getByRole('link').count(), 2);
        assert.deepEqual(await toggle.boundingBox(), before);
        await toggle.click();
        assert.equal(await search.inputValue(), '');
        await search.fill('cpu-a');
        await search.press('Escape');
        assert.equal(await search.isVisible(), false);
        assert.equal(await toggle.evaluate((node) => node === document.activeElement), true);
        assert.equal(await panel.getByRole('link').count(), 2);
        assert.ok(await panel.evaluate((node) => node.scrollWidth <= node.clientWidth));
    }
});

test('CPU sorting preserves keyboard focus and reading position when polling moves the focused CPU', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.setViewportSize({ width: 1280, height: 650 });
    options.cpus = {
        'cpu-a': { ...cpu, name: 'Alpha', isBusy: true },
        'cpu-b': { ...cpu, name: 'Bravo', isBusy: true },
        'cpu-c': { ...cpu, name: 'Charlie' },
        'cpu-d': { ...cpu, name: 'Delta' }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    const panel = page.getByRole('complementary', { name: 'CPUs', exact: true });
    for (const criterion of ['Name', 'Capacity', 'Coprocessors'])
        await panel.getByRole('button', { name: `Sort CPUs: ${criterion}`, exact: true }).click();
    const selector = panel.getByRole('region', { name: 'CPU selector', exact: true });
    const target = selector.getByRole('link', { name: /cpu-a/ });
    await page.mouse.move(5, 5);
    await target.focus();
    await page.keyboard.press('Escape');
    const readingPosition = await selector.evaluate((node) => node.scrollTop);
    options.cpus['cpu-a'].isBusy = false;
    await poll(page);
    await page.waitForFunction(() =>
        document.querySelector('[aria-label="CPU selector"] a')?.getAttribute('href')?.endsWith('/cpu-a')
    );
    assert.equal(await target.evaluate((node) => node === document.activeElement), true);
    assert.equal(await selector.evaluate((node) => node.scrollTop), readingPosition);
    assert.equal(
        await page.getByRole('tooltip').isVisible(),
        false,
        'A dismissed tooltip must not reopen while restoring focus'
    );
});

test('CPU selector searches forty processors and preserves reading position across selection and polling', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.setViewportSize({ width: 1280, height: 800 });
    options.cpus = Object.fromEntries(
        Array.from({ length: 40 }, (_, index) => [
            `processor-${index}`,
            {
                ...cpu,
                name: index === 39 ? '' : `§aProduction ${String(index).padStart(2, '0')}`,
                isBusy: true,
                supportsPause: true
            }
        ])
    );
    options.cpuDetails = Object.fromEntries(
        Object.keys(options.cpus).map((key) => [key, { ...cpuWork, supportsPause: true }])
    );
    await page.goto(`${base}#/grids/${gridA}/cpus/processor-0`);
    const selector = page.getByRole('region', { name: 'CPU selector', exact: true });
    const search = page.getByRole('searchbox', { name: 'Search CPUs', exact: true });
    await selector.getByRole('link', { name: /Production 00/ }).waitFor({ timeout: 2000 });
    assert.equal(await selector.getByRole('link').count(), 40);
    assert.ok(await selector.evaluate((node) => node.scrollHeight > node.clientHeight));
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).click();
    await search.fill('Production');
    assert.equal(await selector.getByRole('link').count(), 39);
    const target = selector.getByRole('link', { name: /Production 25/ });
    await target.scrollIntoViewIfNeeded();
    const before = await target.boundingBox();
    const scrollBefore = await selector.evaluate((node) => node.scrollTop);
    await target.click();
    await page.getByRole('heading', { name: 'Production 25', level: 2, exact: true }).waitFor();
    assert.equal(await target.getAttribute('aria-current'), 'page');
    assert.equal(await search.inputValue(), 'Production');
    assert.equal(await selector.evaluate((node) => node.scrollTop), scrollBefore);
    assert.deepEqual(await target.boundingBox(), before, 'Selection must not expand or shift rows');
    await poll(page);
    assert.equal(await search.inputValue(), 'Production');
    assert.equal(await selector.evaluate((node) => node.scrollTop), scrollBefore);
    const resources = await page.getByRole('region', { name: 'CPU resources', exact: true }).boundingBox();
    const listBounds = await selector.boundingBox();
    const job = await page.getByRole('region', { name: 'Current job', exact: true }).boundingBox();
    const cancel = await page.getByRole('button', { name: 'Cancel current work', exact: true }).boundingBox();
    assert.ok(listBounds.x >= resources.x + resources.width - 1);
    assert.ok(job.y >= resources.y + resources.height - 1);
    assert.ok(cancel.y >= resources.y + resources.height - 1);
    assert.ok(job.y + job.height <= 800 && cancel.y + cancel.height <= 800);
    await search.fill('processor-39');
    assert.equal(await selector.getByRole('link').count(), 1);
    await selector.getByRole('link', { name: /processor-39/ }).click();
    await page.getByRole('heading', { name: 'processor-39', level: 2, exact: true }).waitFor();
    await page.goBack();
    await page.getByRole('heading', { name: 'Production 25', level: 2, exact: true }).waitFor();
    await search.fill('no-such-processor');
    assert.equal(await selector.getByRole('link').count(), 0);
    assert.match(await selector.textContent(), /No matching/);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Production 00/ }).waitFor();
    assert.equal(await search.isVisible(), false);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
    assert.ok(
        options.requests
            .filter((request) => /\/cpus\/[^/]+$/.test(request.path))
            .every((request) => /processor-(0|25|39)$/.test(request.path)),
        'Only selected CPUs need detail requests'
    );
});

test('CPU list fills its attachment and current job stays in a low footer even with long output', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.setViewportSize({ width: 1280, height: 720 });
    options.cpus = Object.fromEntries(
        Array.from({ length: 40 }, (_, index) => [
            `cpu-${index}`,
            { ...cpu, name: `Processor ${index}`, isBusy: true, supportsPause: true }
        ])
    );
    options.cpuDetails['cpu-0'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-0`);
    const details = page.getByRole('region', { name: 'Current job', exact: true });
    const selector = page.getByRole('region', { name: 'CPU selector', exact: true });
    const output = details.getByText(/^Iron Ingot/);
    await output.waitFor({ timeout: 2000 });
    const detailsBounds = await details.boundingBox();
    const outputBounds = await output.boundingBox();
    assert.ok(
        outputBounds.y >= detailsBounds.y &&
            outputBounds.y + outputBounds.height <= detailsBounds.y + detailsBounds.height,
        'An ordinary current job is visible without scrolling past hardware metadata'
    );
    const listBounds = await selector.boundingBox();
    const resources = await page.getByRole('region', { name: 'CPU resources', exact: true }).boundingBox();
    assert.ok(detailsBounds.y >= resources.y + resources.height - 1);
    assert.ok(listBounds.height >= resources.height * 0.7, 'CPU list uses the attachment height');
    assert.match(await selector.getByRole('link').first().innerText(), /8 KB/);
    options.cpuDetails['cpu-0'] = {
        ...cpuWork,
        supportsPause: true,
        finalOutput: { ...iron, displayName: 'Long product '.repeat(60) }
    };
    await poll(page);
    await details.getByText(/^Long product/).waitFor();
    assert.deepEqual(await selector.boundingBox(), listBounds);
    assert.deepEqual(await details.boundingBox(), detailsBounds);
    assert.ok(
        await details.evaluate((node) => node.scrollHeight <= node.clientHeight),
        'The job footer does not gain internal scrolling'
    );
    await details.focus();
    await page
        .getByRole('tooltip')
        .filter({ hasText: 'Long product '.repeat(60).trim() })
        .waitFor();
    await page.keyboard.press('Escape');
    const cancel = page.getByRole('button', { name: 'Cancel current work', exact: true });
    const actionBounds = await cancel.boundingBox();
    assert.ok(actionBounds.y + actionBounds.height <= 720);
    await page.setViewportSize({ width: 390, height: 844 });
    await openMobilePane(page, 'CPUs');
    const last = selector.getByRole('link', { name: /Processor 39/ });
    await last.scrollIntoViewIfNeeded();
    assert.ok(await selector.evaluate((node) => node.scrollTop > 0));
    const search = page.getByRole('searchbox', { name: 'Search CPUs', exact: true });
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).click();
    await search.fill('Processor 39');
    assert.equal(await selector.getByRole('link').count(), 1);
    await closeMobilePane(page);
    await details.scrollIntoViewIfNeeded();
    assert.ok((await details.boundingBox()).height < 80, 'The narrow job footer remains low');
    await cancel.scrollIntoViewIfNeeded();
    assert.deepEqual(
        await page.evaluate(() => ({
            x: scrollX,
            y: scrollY,
            width: document.documentElement.scrollWidth,
            height: document.documentElement.scrollHeight
        })),
        { x: 0, y: 0, width: 390, height: 844 }
    );
});

test('CPU oversized tooltip stays inside the viewport and exposes its end through keyboard scrolling', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.setViewportSize({ width: 800, height: 650 });
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        finalOutput: { ...iron, displayName: 'Oversized product '.repeat(400) }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const work = page.getByRole('region', { name: 'Current job', exact: true });
    await work.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor();
    const bounds = await tooltip.boundingBox();
    assert.ok(
        bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= 800 && bounds.y + bounds.height <= 650,
        'Long CPU details stay inside the viewport'
    );
    await page.keyboard.press('Tab');
    assert.equal(await tooltip.evaluate((node) => node === document.activeElement), true);
    await page.keyboard.press('End');
    await page.waitForFunction(() => document.querySelector('[role="tooltip"]')?.scrollTop > 0);
    const started = tooltip.getByText(/^Started:/);
    await started.scrollIntoViewIfNeeded();
    const lastBounds = await started.boundingBox();
    assert.ok(lastBounds.y >= bounds.y && lastBounds.y + lastBounds.height <= bounds.y + bounds.height);
    const readingPosition = await tooltip.evaluate((node) => node.scrollTop);
    await poll(page);
    assert.equal(await tooltip.evaluate((node) => node.scrollTop), readingPosition);
    await page.keyboard.press('Escape');
    assert.equal(await tooltip.isVisible(), false);
    assert.equal(await work.evaluate((node) => node === document.activeElement), true);
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
});

test('CPU selector keeps paused localized state and capacity inside intermediate-width attachments', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-a'] = {
        ...cpu,
        isBusy: true,
        isPaused: true,
        supportsPause: true,
        finalOutput: iron,
        availableStorage: JSON.parse('9223372036854775807')
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, isPaused: true, supportsPause: true };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const selector = page.getByRole('region', { name: 'Wybór CPU', exact: true });
    const row = selector.getByRole('link', { name: /cpu-a.*Wstrzymany.*8 EB/ });
    await row.waitFor();
    for (const width of [601, 800, 900]) {
        await page.setViewportSize({ width, height: 800 });
        const bounds = await selector.boundingBox();
        const capacity = await row.getByText('· 8 EB', { exact: true }).boundingBox();
        assert.ok(
            await selector.evaluate((node) => node.scrollWidth <= node.clientWidth),
            `Selector must not scroll horizontally at ${width}px`
        );
        assert.ok(
            capacity.x >= bounds.x && capacity.x + capacity.width <= bounds.x + bounds.width,
            'Capacity remains visible'
        );
    }
});

test('CPU tooltips expose live hardware and recorded start without extra detail reads and clear on filter or access loss', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-b'] = {
        ...cpu,
        name: 'Molecular processor',
        isBusy: true,
        finalOutput: iron,
        usedStorage: 2048,
        coProcessors: 3,
        hasTrackingInfo: true,
        timeStarted: cpuWork.timeStarted
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const row = page.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link', { name: /cpu-b/ });
    await row.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.filter({ hasText: 'Molecular processor' }).waitFor();
    assert.match(await tooltip.innerText(), /Used storage: 2 KB/);
    assert.match(await tooltip.innerText(), /Coprocessors: 3/);
    assert.match(await tooltip.innerText(), /Started: .*2023/);
    assert.equal(await row.getAttribute('aria-describedby'), await tooltip.getAttribute('id'));
    assert.equal(
        options.requests.some((request) => request.path.endsWith('/cpus/cpu-b')),
        false
    );
    options.cpus['cpu-b'] = { ...options.cpus['cpu-b'], name: 'Updated processor', usedStorage: 4096, coProcessors: 7 };
    await poll(page);
    await tooltip.filter({ hasText: 'Updated processor' }).waitFor();
    assert.match(await tooltip.innerText(), /Used storage: 4 KB/);
    assert.match(await tooltip.innerText(), /Coprocessors: 7/);
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.getByRole('button', { name: 'Search CPUs', exact: true }).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('searchbox', { name: 'Search CPUs', exact: true }).focus();
    await row.focus();
    await tooltip.filter({ hasText: 'Updated processor' }).waitFor();
    await page.getByRole('searchbox', { name: 'Search CPUs', exact: true }).fill('cpu-a');
    await row.waitFor({ state: 'hidden' });
    assert.equal(await tooltip.isVisible(), false);
    const work = page.getByRole('region', { name: 'Current job', exact: true });
    await work.focus();
    await tooltip.filter({ hasText: 'Iron Ingot' }).waitFor();
    options.detailError = 'NO_PERMISSIONS';
    await poll(page);
    await work.waitFor({ state: 'hidden' });
    assert.equal(await tooltip.isVisible(), false);
    assert.equal(await page.getByRole('region', { name: 'CPU selector', exact: true }).getByRole('link').count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('CPU terminal shows selected resources beside CPU navigation and returns to the overview', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus['cpu-b'] = {
        ...cpu,
        name: 'Selected assembler',
        isBusy: true,
        supportsPause: true,
        usedStorage: 2048,
        coProcessors: 3
    };
    options.cpuDetails['cpu-b'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    await page.getByRole('link', { name: /Selected assembler.*cpu-b/ }).click();
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).waitFor();
    assert.equal(
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).count(),
        1,
        'Opening current work keeps other CPUs available in the selector'
    );
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    await resources.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('searchbox', { name: 'Search CPU resources' }).isVisible(), true);
    for (const name of [
        'Hide stored-only resources',
        'Active first: active, pending, stored',
        'Sort by: Name',
        'Sort by: Active quantity',
        'Sort by: Pending quantity',
        'Sort by: Stored quantity'
    ]) {
        assert.equal(await page.getByRole('button', { name, exact: true }).isVisible(), true);
    }
    const details = page.getByRole('complementary', { name: 'CPUs', exact: true });
    assert.equal(
        await page.getByRole('heading', { name: 'Selected assembler', level: 2, exact: true }).isVisible(),
        true
    );
    assert.match(await details.textContent(), /8 KB/);
    await details.getByRole('link', { name: /Selected assembler/ }).focus();
    assert.match(await page.getByRole('tooltip').textContent(), /Used storage: 2 KB/);
    assert.match(await page.getByRole('tooltip').textContent(), /Coprocessors: 3/);
    assert.match(await page.getByRole('region', { name: 'Current job', exact: true }).textContent(), /Iron Ingot.*12/);
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isEnabled(), true);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
    assert.match(page.url(), /\/cpus$/);
    await resources.waitFor({ state: 'hidden' });
    assert.equal(await resources.isVisible(), false);
    assert.equal(await details.isVisible(), false);
});

// Public seam: controls select and order the current job's actual categories without server mutations.
test('CPU terminal hides stored-only resources, sorts explicit quantities and prioritizes crafting states', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        items: [
            {
                ...cpuWork.items[0],
                displayName: 'Zinc',
                registryNamespace: 'minecraft',
                registryPath: 'zinc',
                active: 10,
                pending: 0,
                stored: 0
            },
            {
                ...cpuWork.items[0],
                displayName: 'Alpha',
                registryNamespace: 'minecraft',
                registryPath: 'unique_pending',
                active: 0,
                pending: 30,
                stored: 0
            },
            {
                ...cpuWork.items[0],
                displayName: 'Beta',
                registryNamespace: 'ae2',
                registryPath: 'beta',
                active: 0,
                pending: 0,
                stored: 20
            },
            {
                ...cpuWork.items[0],
                displayName: '§aMixed',
                registryNamespace: 'other',
                registryPath: 'mixed',
                active: 3,
                pending: 8,
                stored: 50
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    await resources.getByRole('button', { name: /Mixed/ }).waitFor();
    const names = async () =>
        (await resources.getByRole('button').allTextContents()).map((text) => text.match(/Alpha|Beta|Mixed|Zinc/)[0]);
    const hideStored = page.getByRole('button', { name: 'Hide stored-only resources', exact: true });
    const activeFirst = page.getByRole('button', { name: 'Active first: active, pending, stored', exact: true });
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Mixed', 'Zinc']);
    for (const [category, expected] of [
        ['Active', ['Zinc', 'Mixed', 'Alpha', 'Beta']],
        ['Pending', ['Alpha', 'Mixed', 'Beta', 'Zinc']],
        ['Stored', ['Mixed', 'Beta', 'Alpha', 'Zinc']]
    ]) {
        const sort = page.getByRole('button', { name: `Sort by: ${category} quantity`, exact: true });
        await sort.click();
        assert.equal(await sort.getAttribute('aria-pressed'), 'true');
        assert.deepEqual(await names(), expected, `${category} must sort by its own quantity`);
    }
    await activeFirst.click();
    assert.equal(await activeFirst.getAttribute('aria-pressed'), 'true');
    assert.deepEqual(await names(), ['Mixed', 'Zinc', 'Alpha', 'Beta']);
    await page.getByRole('button', { name: 'Sort by: Active quantity', exact: true }).click();
    assert.deepEqual(await names(), ['Zinc', 'Mixed', 'Alpha', 'Beta']);
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    assert.deepEqual(await names(), ['Mixed', 'Zinc', 'Alpha', 'Beta']);
    await hideStored.click();
    assert.equal(await hideStored.getAttribute('aria-pressed'), 'true');
    assert.deepEqual(await names(), ['Mixed', 'Zinc', 'Alpha'], 'Mixed resources must remain visible');
    await activeFirst.click();
    assert.deepEqual(await names(), ['Alpha', 'Mixed', 'Zinc']);
    await hideStored.click();
    assert.equal(await resources.getByRole('button').count(), 4);
    const search = page.getByRole('searchbox', { name: 'Search CPU resources' });
    await search.fill('mIxEd');
    assert.deepEqual(await names(), ['Mixed']);
    await search.fill('unique_pending');
    assert.deepEqual(await names(), ['Alpha']);
    await search.fill('@ae2');
    assert.deepEqual(await names(), ['Beta']);
    await search.fill('@minecraft');
    assert.deepEqual(await names(), ['Alpha', 'Zinc']);
    await search.fill('no matching resource');
    assert.equal(await resources.getByRole('button').count(), 0);
    await search.fill('');
    assert.equal(await resources.getByRole('button').count(), 4);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a')).length, 1);
    await clickTab(page, 'Terminal');
    assert.equal(
        await page.getByRole('button', { name: 'Sort by: Name', exact: true }).isVisible(),
        true,
        'CPU sort controls must not overwrite inventory preferences'
    );
});

test('CPU tiles show processing shares and sort by precise shares through refresh and tracking changes', async (t) => {
    const { page, options, base } = await fixture(t);
    const items = [
        {
            ...cpuWork.items[0],
            displayName: 'Alpha',
            registryNamespace: 'example',
            registryPath: 'alpha',
            shareInCraftingTime: 0.12441
        },
        {
            ...cpuWork.items[0],
            displayName: 'Beta',
            registryNamespace: 'example',
            registryPath: 'beta',
            shareInCraftingTime: 0.12449
        },
        {
            ...cpuWork.items[0],
            displayName: 'Stored',
            registryNamespace: 'example',
            registryPath: 'stored',
            active: 0,
            pending: 0,
            shareInCraftingTime: 0.7511
        },
        {
            ...cpuWork.items[0],
            displayName: 'Waiting',
            registryNamespace: 'example',
            registryPath: 'waiting',
            active: 0,
            shareInCraftingTime: 0
        }
    ];
    options.cpuDetails['cpu-a'] = { ...cpuWork, items };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    const alpha = resources.getByRole('button', { name: /^Alpha/ });
    await alpha.waitFor();
    assert.match(await alpha.innerText(), /12\.4%/);
    assert.match(await alpha.getAttribute('aria-label'), /Share of processing time: 12\.4%/);
    assert.match(await resources.getByRole('button', { name: /^Waiting/ }).innerText(), /0%/);
    const names = async () =>
        (await resources.getByRole('button').allTextContents()).map(
            (text) => text.match(/Alpha|Beta|Stored|Waiting/)[0]
        );
    const sortShare = page.getByRole('button', { name: 'Sort by: Share of processing time', exact: true });
    await sortShare.click();
    assert.deepEqual(await names(), ['Stored', 'Beta', 'Alpha', 'Waiting']);
    const activeFirst = page.getByRole('button', { name: 'Active first: active, pending, stored', exact: true });
    await activeFirst.click();
    assert.deepEqual(await names(), ['Beta', 'Alpha', 'Waiting', 'Stored']);
    await activeFirst.click();
    options.cpuDetails['cpu-a'].items = items.map((item, i) => ({
        ...item,
        shareInCraftingTime: [0.9, 0.01, 0.09, 0][i]
    }));
    let refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.deepEqual(await names(), ['Alpha', 'Stored', 'Beta', 'Waiting']);
    assert.match(await alpha.innerText(), /90%/);
    options.cpuDetails['cpu-a'].hasTrackingInfo = false;
    refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.equal(await sortShare.isVisible(), false);
    assert.doesNotMatch(await alpha.innerText(), /%/);
    assert.doesNotMatch(await alpha.getAttribute('aria-label'), /Share/);
    assert.deepEqual(await names(), ['Alpha', 'Beta', 'Stored', 'Waiting']);
    assert.equal(
        await page.getByRole('button', { name: 'Sort by: Name', exact: true }).getAttribute('aria-pressed'),
        'true'
    );
});

test('CPU resources omit zero counts and update visible categories during polling', async (t) => {
    const { page, options, base } = await fixture(t);
    const item = { ...cpuWork.items[0], active: 0, pending: 12, stored: 0 };
    options.cpuDetails['cpu-a'] = { ...cpuWork, hasTrackingInfo: false, items: [item] };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await resource.waitFor();
    assert.match(await resource.innerText(), /Pending\s*12/);
    assert.doesNotMatch(await resource.innerText(), /Active|Stored/);
    assert.doesNotMatch(await resource.getAttribute('aria-label'), /Active|Stored/);
    await resource.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor({ state: 'visible' });
    assert.match(await tooltip.innerText(), /Pending: 12/);
    assert.doesNotMatch(await tooltip.innerText(), /Active|Stored/);

    options.cpuDetails['cpu-a'].items = [{ ...item, active: 3, pending: 0, stored: 7 }];
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.match(await resource.innerText(), /Active\s*3/);
    assert.match(await resource.innerText(), /Stored[^\n]*\s+7/);
    assert.doesNotMatch(await resource.innerText(), /Pending/);
    assert.doesNotMatch(await resource.getAttribute('aria-label'), /Pending/);
    assert.equal(await tooltip.isVisible(), true);
    assert.match(await tooltip.innerText(), /Active: 3/);
    assert.match(await tooltip.innerText(), /Stored[^\n]*: 7/);
    assert.doesNotMatch(await tooltip.innerText(), /Pending/);
});

test('CPU resource tooltip preserves exact counts and tracking through polling and clears on route or access changes', async (t) => {
    const { page, options, base } = await fixture(t);
    const item = { ...cpuWork.items[0], active: 12345, pending: 23456, stored: 34567 };
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [item] };
    options.cpuDetails['cpu-b'] = {
        ...cpuWork,
        hasTrackingInfo: false,
        items: [{ ...item, active: 91, pending: 0, stored: 0 }]
    };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
    const resource = resources.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await resource.hover();
    await tooltip.waitFor({ state: 'visible' });
    for (const metric of [
        /minecraft:iron_ingot/,
        /Active.*12,345/,
        /Pending.*23,456/,
        /Stored.*34,567/,
        /Processing time.*5/,
        /Crafted total.*10/,
        /Produced per second.*2\/s/,
        /Share of elapsed time.*50%/,
        /Share of processing time.*40%/
    ]) {
        assert.match(await tooltip.textContent(), metric);
    }
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...item, active: 45678, craftedTotal: 99 }] };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.equal(await tooltip.isVisible(), true, 'Polling must keep the hovered resource tooltip visible');
    assert.match(await tooltip.textContent(), /Active.*45,678/);
    assert.match(await tooltip.textContent(), /Crafted total.*99/);
    await page.mouse.move(0, 0);
    await resource.focus();
    await tooltip.waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    await clickTab(page, 'CPUs');
    assert.equal(await tooltip.isVisible(), false);
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await resource.focus();
    await tooltip.waitFor({ state: 'visible' });
    assert.match(await tooltip.textContent(), /Active.*91/);
    assert.doesNotMatch(await tooltip.textContent(), /45,678|Crafted total|Processing time|Share of/);
    options.detailError = 'NO_PERMISSIONS';
    await poll(page);
    await resource.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false, 'Lost access must clear private metrics');
});

test('CPU terminal scrolls resources within desktop and mobile viewports while controls remain usable', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        supportsPause: true,
        items: Array.from({ length: 120 }, (_, index) => ({
            ...cpuWork.items[0],
            registryNamespace: 'example',
            registryPath: `resource_${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`
        }))
    };
    for (const viewport of [
        { width: 1280, height: 800 },
        { width: 390, height: 844 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const resources = page.getByRole('region', { name: 'CPU resources', exact: true });
        await resources.getByRole('button', { name: /Resource 000/ }).waitFor();
        const search = page.getByRole('searchbox', { name: 'Search CPU resources' });
        await search.fill('');
        const before = await search.boundingBox();
        const bounds = await resources.boundingBox();
        const panel = await page.locator('#cpu-panel').boundingBox();
        assert.ok(bounds.height > 100, 'Resources need a usable scroll viewport');
        if (viewport.width > 600) assert.ok(panel.x >= bounds.x + bounds.width - 1, 'CPU list sits beside resources');
        else assert.equal(panel, null, 'Phone CPU list is opened on demand');
        await resources.getByRole('button').last().scrollIntoViewIfNeeded();
        assert.ok(
            await resources.evaluate((node) => node.scrollTop > 0),
            `The resource region itself must scroll at ${viewport.width}px: ${JSON.stringify(await resources.evaluate((node) => ({ top: node.scrollTop, height: node.clientHeight, content: node.scrollHeight })))}`
        );
        assert.deepEqual(await search.boundingBox(), before);
        const documentSize = await page.evaluate(() => ({
            width: document.documentElement.scrollWidth,
            height: document.documentElement.scrollHeight,
            top: window.scrollY,
            left: window.scrollX
        }));
        assert.ok(documentSize.width <= viewport.width, 'The CPU page must not overflow horizontally');
        assert.ok(documentSize.height <= viewport.height, 'The CPU page must not overflow vertically');
        assert.equal(documentSize.top, 0);
        assert.equal(documentSize.left, 0);
        for (const control of [
            search,
            viewport.width <= 600
                ? page.getByRole('button', { name: 'CPUs', exact: true })
                : page.getByRole('link', { name: 'CPUs', exact: true }),
            viewport.width <= 600
                ? page.getByRole('button', { name: 'Filters and sorting', exact: true })
                : page.getByRole('button', { name: 'Hide stored-only resources', exact: true }),
            page.getByRole('button', { name: 'Cancel current work', exact: true })
        ]) {
            await control.scrollIntoViewIfNeeded();
            const box = await control.boundingBox();
            assert.ok(
                box.x >= 0 &&
                    box.y >= 0 &&
                    box.x + box.width <= viewport.width + 1 &&
                    box.y + box.height <= viewport.height + 1,
                `Navigation, filtering and actions must remain reachable at ${viewport.width}px: ${(await control.getAttribute('aria-label')) || (await control.textContent())} ${JSON.stringify(box)}`
            );
        }
        assert.deepEqual(
            await page.evaluate(() => ({ top: window.scrollY, left: window.scrollX })),
            { top: 0, left: 0 },
            'Reaching CPU actions may scroll the panel, but must not move the document'
        );
        const pause = page.getByRole('button', { name: /^(Pause|Resume) current work$/ });
        const previous = await pause.textContent();
        await pause.click();
        await page
            .getByRole('button', {
                name: previous.startsWith('Pause') ? 'Resume current work' : 'Pause current work',
                exact: true
            })
            .waitFor();
        await search.fill('Resource 000');
        assert.equal(await resources.getByRole('button').count(), 1);
        assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), false);
    }
    assert.equal(options.requests.filter((request) => request.path.endsWith('/pause')).length, 2);
});

test('CPU resource reordering does not leave a stale hover tooltip or reopen keyboard-dismissed metrics', async (t) => {
    const { page, options, base } = await fixture(t);
    const second = {
        ...cpuWork.items[0],
        registryNamespace: 'example',
        registryPath: 'copper',
        displayName: 'Copper',
        active: 50,
        pending: 0,
        stored: 0
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...cpuWork.items[0], active: 100 }, second] };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page.getByRole('button', { name: 'Sort by: Active quantity', exact: true }).click();
    const item = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await item.hover();
    const refreshedTooltip = page.evaluate(
        () =>
            new Promise((resolve) => {
                const observer = new MutationObserver(() => {
                    const item = [...document.querySelectorAll('button')].find((button) =>
                        /Iron Ingot.*Active: 1 ·/.test(button.getAttribute('aria-label'))
                    );
                    if (!item) return;
                    observer.disconnect();
                    const tooltip = [...document.querySelectorAll('[role="tooltip"]')].find((node) =>
                        node.checkVisibility()
                    );
                    resolve({ visible: !!tooltip, text: tooltip?.textContent || '' });
                });
                observer.observe(document.body, {
                    subtree: true,
                    childList: true,
                    attributes: true,
                    characterData: true
                });
            })
    );
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...cpuWork.items[0], active: 1 }, second] };
    await poll(page);
    const refreshed = await refreshedTooltip;
    assert.ok(
        !refreshed.visible || !refreshed.text.includes('Iron Ingot'),
        'A resource that moves away from a stationary pointer must not keep its old hover tooltip'
    );
    await page.mouse.move(0, 0);
    await item.focus();
    await tooltip.waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    options.cpuDetails['cpu-a'] = { ...cpuWork, items: [{ ...cpuWork.items[0], active: 200 }, second] };
    const read = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await read).request());
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), false, 'Polling and sorting must preserve keyboard dismissal');
});

// Public seam: CPU links and current-work controls plus HTTP. Display names may duplicate;
// navigation and cancellation must continue to address the selected stable CPU key.
test('CPU list and detail pause controls use capability, busy state and stable CPU identity', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true },
        unsupported: { ...cpu, isBusy: true },
        idle: { ...cpu, supportsPause: true }
    };
    options.cpuDetails['cpu-b'] = { ...cpuWork, supportsPause: true };
    options.cpuDetails.unsupported = cpuWork;
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = (key) => page.getByRole('listitem').filter({ has: page.getByRole('link', { name: new RegExp(key) }) });
    await row('cpu-b').getByRole('button', { name: /Pause/ }).waitFor({ timeout: 3000 });
    assert.equal(
        await row('unsupported')
            .getByRole('button', { name: /Pause|Resume/ })
            .count(),
        0
    );
    assert.equal(await row('idle').getByRole('button').count(), 0);
    await row('cpu-b').getByRole('button', { name: /Pause/ }).click();
    await row('cpu-b')
        .getByRole('button', { name: /Resume/ })
        .waitFor();
    options.cpus = { ...Object.fromEntries(Object.entries(options.cpus).reverse()) };
    await poll(page);
    await row('cpu-b').getByRole('link').click();
    await page.getByRole('button', { name: 'Resume current work', exact: true }).click();
    await page.getByRole('button', { name: 'Pause current work', exact: true }).waitFor();
    const requests = options.requests.filter((request) => request.path.endsWith('/pause'));
    assert.deepEqual(
        requests.map(({ path, method, body }) => ({ path, method, body })),
        [
            { path: `/api/grids/${gridA}/cpus/cpu-b/pause`, method: 'POST', body: { paused: true } },
            { path: `/api/grids/${gridA}/cpus/cpu-b/pause`, method: 'POST', body: { paused: false } }
        ]
    );
    assert.ok(requests.every((request) => request.headers['x-ae2-request'] === 'true'));
    await clickTab(page, 'CPUs');
    await row('unsupported').getByRole('link').click();
    await page.getByRole('button', { name: /^(Pause|Resume) current work$/ }).waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('button', { name: /^(Pause|Resume) current work$/ }).count(), 0);
});

test('CPU cancellation requires confirmation from both list rows and detail', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true }, 'cpu-b': { ...cpu, isBusy: true } };
    page.removeAllListeners('dialog');
    let accept = false;
    const confirmations = [];
    page.on('dialog', async (dialog) => {
        confirmations.push(dialog.message());
        if (accept) await dialog.accept();
        else await dialog.dismiss();
    });
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-b/ }) });
    await row.getByRole('button', { name: /Cancel/ }).click();
    assert.equal(confirmations.length, 1);
    assert.match(confirmations[0], /cpu-b/);
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 0);
    accept = true;
    await row.getByRole('button', { name: /Cancel/ }).click();
    await row.getByRole('button', { name: /Cancel/ }).waitFor({ state: 'hidden' });
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).click();
    accept = false;
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    assert.match(confirmations.at(-1), /cpu-a/);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cancel')).length, 1);
    accept = true;
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).waitFor({ state: 'hidden' });
    assert.deepEqual(
        options.requests.filter((request) => request.path.endsWith('/cancel')).map((request) => request.path),
        [`/api/grids/${gridA}/cpus/cpu-b/cancel`, `/api/grids/${gridA}/cpus/cpu-a/cancel`]
    );
});

// Public seam: a pending or uncertain operation follows its grid/CPU across views. Neither
// a refreshed snapshot nor visiting another network authorizes a duplicate mutation.
test('pending and uncertain CPU pause stays locked across list, detail and grid navigation', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
    let release;
    const pending = new Promise((resolve) => {
        release = resolve;
    });
    let mutations = 0;
    await page.route('**/pause', (route) => {
        mutations++;
        release(route);
    });
    const row = (key) => page.getByRole('listitem').filter({ has: page.getByRole('link', { name: new RegExp(key) }) });
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    await row('cpu-a').getByRole('button', { name: /Pause/ }).click();
    const held = await pending;
    await row('cpu-a').getByRole('link').click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
    await page.evaluate((key) => {
        location.hash = `#/grids/${key}/cpus`;
    }, gridB);
    await row('cpu-a').getByRole('button', { name: /Pause/ }).waitFor();
    assert.equal(await row('cpu-a').getByRole('button', { name: /Pause/ }).isEnabled(), true);
    await held.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'TIMEOUT', data: null })
    });
    await settleResponse(page, held.request());
    assert.equal(
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .count(),
        0
    );
    await page.evaluate((key) => {
        location.hash = `#/grids/${key}/cpus`;
    }, gridA);
    await row('cpu-a')
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    await poll(page);
    assert.equal(await row('cpu-a').getByRole('button', { name: /Pause/ }).isDisabled(), true);
    assert.equal(
        await row('cpu-a')
            .getByRole('button', { name: /Cancel/ })
            .isDisabled(),
        true
    );
    assert.equal(await row('cpu-b').getByRole('button', { name: /Pause/ }).isEnabled(), true);
    await row('cpu-a').getByRole('link').click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isDisabled(), true);
    assert.equal(mutations, 1);
});

test('CPU pause rejection refreshes capability and hides stale controls', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true, supportsPause: true } };
    options.cpuDetails['cpu-a'] = { ...cpuWork, supportsPause: true };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page.getByRole('button', { name: 'Pause current work', exact: true }).waitFor();
    options.pauseStatus = 'CPU_PAUSE_UNSUPPORTED';
    options.cpus['cpu-a'] = { ...options.cpus['cpu-a'], supportsPause: false };
    options.cpuDetails['cpu-a'] = cpuWork;
    await page.getByRole('button', { name: 'Pause current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /does not support pausing/i })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: /Pause|Resume/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isEnabled(), true);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/pause')).length, 1);
});

test('concurrent CPU pause results remain non-actionable until fresh CPU data arrives', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = (key) => page.getByRole('listitem').filter({ has: page.getByRole('link', { name: new RegExp(key) }) });
    const operations = {};
    await page.route('**/pause', (route) => {
        operations[route.request().url().split('/').at(-2)] = route;
    });
    await row('cpu-a').getByRole('button', { name: /Pause/ }).click();
    await row('cpu-b').getByRole('button', { name: /Pause/ }).click();
    let captureFirst;
    let captureSecond;
    const firstRead = new Promise((resolve) => {
        captureFirst = resolve;
    });
    const secondRead = new Promise((resolve) => {
        captureSecond = resolve;
    });
    let reads = 0;
    await page.route('**/cpus', (route) => {
        if (++reads === 1) captureFirst(route);
        else captureSecond(route);
    });
    const successful = {
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: null })
    };
    await operations['cpu-a'].fulfill(successful);
    const stale = await firstRead;
    await operations['cpu-b'].fulfill(successful);
    const fresh = await secondRead;
    await stale.fulfill({ ...successful, body: JSON.stringify({ status: 'OK', data: options.cpus }) });
    await settleResponse(page, stale.request());
    assert.equal(await row('cpu-a').getByRole('button', { name: /Pause/ }).isDisabled(), true);
    assert.equal(await row('cpu-b').getByRole('button', { name: /Pause/ }).isDisabled(), true);
    assert.equal(
        await row('cpu-b')
            .getByRole('button', { name: /Cancel/ })
            .isDisabled(),
        true
    );
    const paused = Object.fromEntries(
        Object.entries(options.cpus).map(([key, value]) => [key, { ...value, isPaused: true }])
    );
    await fresh.fulfill({ ...successful, body: JSON.stringify({ status: 'OK', data: paused }) });
    await row('cpu-a')
        .getByRole('button', { name: /Resume/ })
        .waitFor();
    assert.equal(
        await row('cpu-b')
            .getByRole('button', { name: /Resume/ })
            .isEnabled(),
        true
    );
});

test('CPU list reports a removed action target without substituting another CPU', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-a/ }) });
    await row.getByRole('button', { name: /Pause/ }).waitFor();
    options.pauseStatus = 'CPU_NOT_FOUND';
    delete options.cpus['cpu-a'];
    await row.getByRole('button', { name: /Pause/ }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor({ timeout: 3000 });
    assert.equal(await row.count(), 0);
    assert.equal(await page.getByRole('link', { name: /cpu-b/ }).count(), 1);
    assert.deepEqual(
        options.requests.filter((request) => request.method === 'POST').map((request) => request.path),
        [`/api/grids/${gridA}/cpus/cpu-a/pause`]
    );
});

test('a removed CPU mutation target cannot strand another CPU detail load', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true },
        'cpu-b': { ...cpu, isBusy: true, supportsPause: true }
    };
    options.cpuDetails['cpu-b'] = { ...cpuWork, supportsPause: true };
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    let captureMutation;
    const mutation = new Promise((resolve) => {
        captureMutation = resolve;
    });
    await page.route('**/pause', captureMutation);
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: /cpu-a/ }) });
    await row.getByRole('button', { name: /Pause/ }).click();
    const action = await mutation;
    let captureDetail;
    const detail = new Promise((resolve) => {
        captureDetail = resolve;
    });
    let held = false;
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-b'),
        (route) => {
            if (held) return route.continue();
            held = true;
            captureDetail(route);
        }
    );
    await page.getByRole('link', { name: /cpu-b/ }).click();
    const oldRead = await detail;
    delete options.cpus['cpu-a'];
    await action.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'CPU_NOT_FOUND', data: null })
    });
    await oldRead.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: options.cpuDetails['cpu-b'] })
    });
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('button', { name: 'Pause current work', exact: true }).isEnabled(), true);
    assert.match(page.url(), /cpus\/cpu-b$/);
});

test('CPU monitoring opens stable current work and explicitly cancels it without unrelated requests', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, finalOutput: { ...iron, quantity: 12 } },
        'cpu-b': { ...cpu, isBusy: true, finalOutput: { ...iron, quantity: 12 } }
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'CPUs', exact: true }).click({ timeout: 3000 });
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    const resource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await resource.focus();
    await page.getByRole('tooltip').waitFor({ state: 'visible' });
    assert.match(await page.getByRole('tooltip').textContent(), /Active.*4/);
    assert.match(await page.getByRole('tooltip').textContent(), /Crafted total.*10/);
    options.cpus = { 'cpu-b': options.cpus['cpu-b'], 'cpu-a': options.cpus['cpu-a'] };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-b'));
    await poll(page);
    await (await refreshed).finished();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /^Idle$/i })
        .waitFor();
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    const cancel = options.requests.filter((request) => request.path.endsWith('/cancel'));
    assert.equal(cancel.length, 1);
    assert.equal(cancel[0].path, `/api/grids/${gridA}/cpus/cpu-b/cancel`);
    assert.equal(cancel[0].method, 'POST');
    assert.equal(cancel[0].headers['x-ae2-request'], 'true');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a')).length, 0);
});

// Public seam: a missing selected key cannot become another CPU, and access/read failures
// must remove current-work data and the ability to cancel from stale observations.
test('CPU removal and denied reads clear current work without selecting a replacement', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-b`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    delete options.cpus['cpu-b'];
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor({ timeout: 3000 });
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).click();
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.detailError = 'NO_PERMISSIONS';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await page.getByRole('link', { name: /Assembler/ }).count(), 0);
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    options.detailError = null;
    await poll(page);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.cpuError = 'GRID_NOT_FOUND';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /network.*available/i })
        .waitFor();
    assert.equal(await page.getByRole('link', { name: /Assembler/ }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('known CPU cancellation rejections refresh stale work or clear unavailable private data', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.cancelStatus = 'CPU_NOT_BUSY';
    options.cpuDetails['cpu-a'] = { ...cpuWork, isBusy: false, items: null, finalOutput: null, hasTrackingInfo: false };
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /^Idle$/i })
        .waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    for (const status of ['CPU_NOT_FOUND', 'NO_PERMISSIONS', 'GRID_NOT_FOUND']) {
        options.cancelStatus = status;
        options.cpuDetails['cpu-a'] = cpuWork;
        await poll(page);
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor({ state: 'hidden' });
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    }
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cancel')).length, 4);
});

test('late CPU reads cannot leak across selection or grid changes and busy state does not need output metadata', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpuDetails['cpu-b'] = { ...cpuWork, finalOutput: null, items: [], hasTrackingInfo: false };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-a'),
        (route) => release(route)
    );
    await poll(page);
    const delayed = await captured;
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await page.getByText('Current output unavailable', { exact: true }).waitFor();
    await page
        .getByRole('status')
        .filter({ hasText: /^Busy$/i })
        .waitFor();
    await delayed.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: cpuWork })
    });
    await settleResponse(page, delayed.request());
    assert.equal(
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        0
    );
    assert.equal(await page.getByRole('tooltip').isVisible(), false);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isEnabled(), true);
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

// No expected-job token exists: a retry could cancel newer work on this same CPU. Unknown
// outcomes retain a local lock across reads/navigation and never issue another cancel request.
test('uncertain CPU cancellation never replays after refresh or route reentry', async (t) => {
    const { page, options, base } = await fixture(t);
    for (const status of ['TIMEOUT', 'INTERNAL_ERROR', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        await page.goto(`${base}?case=${status}#/grids/${gridA}/cpus/cpu-a`);
        options.fault = { operation: 'cancel', status };
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor({ timeout: 3000 });
        const count = options.requests.filter((request) => request.path.endsWith('/cancel')).length;
        await poll(page);
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        await page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor();
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
        assert.equal(options.requests.filter((request) => request.path.endsWith('/cancel')).length, count);
    }
});

// Known native failures retain the computed resource details and safely display opaque text.
test('known submission rejection retains the plan and permits deliberate correction', async (t) => {
    const { page, options, base } = await fixture(t);
    options.submitStatus = 'FAIL';
    options.submitReason = '<img src=x onerror=alert(1)> Native ingredients disappeared';
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.getByText(options.submitReason, { exact: true }).waitFor({ timeout: 3000 });
    assert.equal(
        await page
            .getByRole('list', { name: 'Plan resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .count(),
        1
    );
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, 1);
    options.submitStatus = 'CPU_NOT_FOUND';
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor();
    assert.equal(
        await page
            .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
            .getByRole('radio', { checked: true })
            .count(),
        0
    );
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    await page
        .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
        .getByRole('radio', { name: /cpu-b/ })
        .check();
    options.submitStatus = 'OK';
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, 3);
});

// Public seam: unusable plans and access failures must remove mutation authority, not merely
// add an error next to stale actionable private data.
test('unavailable and incomplete plans cannot submit, and denied refresh clears private plan data', async (t) => {
    const { page, options, base } = await fixture(t);
    options.planStatus = 'INVALID_ID';
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page
        .getByRole('status')
        .filter({ hasText: /plan.*unavailable/i })
        .waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('button', { name: 'Cancel', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.planStatus = 'OK';
    options.plan = { ...readyPlan, isSimulating: true };
    await page.reload();
    await page
        .getByRole('status')
        .filter({ hasText: /missing resources/i })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], missing: 8 }] };
    await page.reload();
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Missing: 8/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.plan = readyPlan;
    await page.reload();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    options.cpuError = 'NO_PERMISSIONS';
    await poll(page);
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
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    options.cpuError = null;
    await poll(page);
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor({ state: 'hidden' });
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

// The API has no idempotency receipt. Transport failures must never trigger mutation replay,
// including automatic polling and ordinary navigation back to the same runtime plan.
test('uncertain submission outcomes cannot be retried by polling or route reentry', async (t) => {
    const { page, options, base } = await fixture(t);
    let planId = 7;
    for (const status of ['error', 500, 'OK', 'TIMEOUT', 'INTERNAL_ERROR', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId++}`);
        options.fault = { operation: 'submit', status };
        await page.getByRole('button', { name: 'Start', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor({ timeout: 3000 });
        const count = options.requests.filter((request) => request.path.endsWith('/submit')).length;
        await poll(page);
        await clickTab(page, 'Terminal');
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
        assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, count);
    }
});

test('uncertain creation and deletion remain explicit and are never replayed', async (t) => {
    const { page, options, base } = await fixture(t);
    options.fault = { operation: 'create', status: 'TIMEOUT' };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('button', { name: 'Calculate plan', exact: true }).isDisabled(), true);
    await poll(page);
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await page.goBack();
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: 'Calculate plan', exact: true }).isDisabled(), true);
    options.fault = { operation: 'delete', status: 'TIMEOUT' };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    await poll(page);
    assert.equal(await page.getByRole('button', { name: 'Cancel', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    assert.deepEqual(
        options.requests.filter((request) => request.method !== 'GET').map((request) => request.method),
        ['POST', 'DELETE']
    );
});

test('plan resources share custom formatted tooltips with exact values and dismiss without polling reopen', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingPlanSteps = true;
    options.plan = {
        ...readyPlan,
        plan: [
            {
                ...readyPlan.plan[0],
                displayName: '§aIron §lIngot',
                stored: 123456,
                requested: 234567,
                missing: 34567,
                damage: 4,
                componentCount: 2,
                steps: 17,
                usedPercent: 0.25
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resource = page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await resource.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Iron Ingot', { exact: true }).waitFor({ timeout: 3000 });
    assert.equal(await resource.getAttribute('title'), null);
    assert.match(await tooltip.innerText(), /minecraft:iron_ingot\s+Damage: 4\s+Components: 2/);
    for (const value of ['123,456', '234,567', '34,567', '17', '25%'])
        assert.ok((await tooltip.innerText()).includes(value));
    assert.equal((await textStyle(tooltip, 'Iron')).color, 'rgb(85, 255, 85)');
    assert.equal(
        await tooltip.getByText('Components: 2').evaluate((node) => getComputedStyle(node).color),
        'rgb(170, 170, 170)'
    );
    await page.keyboard.press('Escape');
    assert.equal(await tooltip.isVisible(), false);
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.mouse.move(0, 0);
    await resource.focus();
    await tooltip.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.equal(await resource.getAttribute('aria-describedby'), await tooltip.getAttribute('id'));
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('quartz');
    assert.equal(await resource.count(), 0);
    assert.equal(await tooltip.isVisible(), false);
});

test('plan tools search and CPU identifiers use current custom tooltips and clear removed or dismissed targets', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = { 'cpu-a': cpu, 'cpu-b': { ...cpu, acceptsPlayerJobs: false } };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const sort = page.getByRole('button', { name: 'Sort by: Name', exact: true });
    await sort.hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText(/Sort by.*Name/).waitFor({ timeout: 3000 });
    assert.equal(await sort.getAttribute('title'), null);
    await sort.click();
    await tooltip.getByText(/Sort by.*Quantity/).waitFor();
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    const order = page.getByRole('button', { name: 'Sort order: Ascending', exact: true });
    await page.mouse.move(0, 0);
    await order.focus();
    await tooltip.getByText(/Sort order.*Ascending/).waitFor();
    await page.keyboard.press('Enter');
    await tooltip.getByText(/Sort order.*Descending/).waitFor();
    const search = page.getByRole('searchbox', { name: 'Search plan resources' });
    await search.focus();
    await tooltip.getByText(/@/).first().waitFor();
    await search.fill('iron');
    assert.equal(await tooltip.isVisible(), false);
    const disabled = page.getByRole('radio', { name: /cpu-b/ });
    await page.mouse.move(0, 0);
    await disabled.locator('..').hover();
    await tooltip.getByText('cpu-b', { exact: true }).waitFor();
    assert.equal(await disabled.isDisabled(), true);
    assert.equal(await disabled.locator('..').getAttribute('title'), null);
    const enabled = page.getByRole('radio', { name: /cpu-a/ });
    await enabled.focus();
    await tooltip.getByText('cpu-a', { exact: true }).waitFor();
    await page.mouse.move(0, 0);
    await disabled.locator('..').hover();
    await tooltip.getByText('cpu-b', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await poll(page);
    assert.equal(await tooltip.isVisible(), false);
    await page.mouse.move(0, 0);
    await disabled.locator('..').hover();
    await tooltip.getByText('cpu-b', { exact: true }).waitFor();
    delete options.cpus['cpu-b'];
    await poll(page);
    await disabled.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false);
});

test('plan CPU tooltip shows live capacity work and state after polling', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': {
            ...cpu,
            name: '§aAssembly',
            isBusy: true,
            isPaused: true,
            availableStorage: 65536,
            usedStorage: 12345,
            coProcessors: 8,
            finalOutput: { ...iron, displayName: '§bIron', quantity: 640 }
        }
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByText('Plan ready', { exact: true }).waitFor();
    const choice = page.getByRole('radio', { name: /cpu-a/ });
    await choice.scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await choice.locator('..').hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Assembly', { exact: true }).waitFor({ timeout: 3000 });
    assert.equal((await textStyle(tooltip, 'Assembly')).color, 'rgb(85, 255, 85)');
    assert.match(await tooltip.innerText(), /Paused/);
    assert.match(await tooltip.innerText(), /64 KB/);
    assert.match(await tooltip.innerText(), /12\.06 KB/);
    assert.match(await tooltip.innerText(), /8 coprocessors/);
    assert.match(await tooltip.innerText(), /Iron × 640/);
    assert.equal((await textStyle(tooltip, 'Iron')).color, 'rgb(85, 255, 255)');
    assert.ok((await tooltip.innerText()).endsWith('cpu-a'));
    options.cpus['cpu-a'] = { ...cpu, name: 'Updated CPU', usedStorage: -1, coProcessors: 4 };
    await poll(page);
    await page.getByRole('radio', { name: /Updated CPU/ }).waitFor();
    await page.mouse.move(0, 0);
    await choice.scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await choice.locator('..').hover();
    await tooltip.getByText('Updated CPU', { exact: true }).waitFor();
    assert.match(await tooltip.innerText(), /Idle/);
    assert.match(await tooltip.innerText(), /8 KB/);
    assert.match(await tooltip.innerText(), /4 coprocessors/);
    assert.match(await tooltip.innerText(), /Used storage unavailable/);
    assert.doesNotMatch(await tooltip.innerText(), /Iron|Paused|12\.06 KB/);
});

test('plan tooltips clear on scroll resize navigation and access denial', async (t) => {
    const { page, options, base } = await fixture(t);
    options.plan = {
        ...readyPlan,
        plan: Array.from({ length: 80 }, (_, index) => ({ ...readyPlan.plan[0], displayName: `Ingredient ${index}` }))
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const region = page.getByRole('region', { name: 'Plan resources', exact: true });
    const resource = region.getByRole('button', { name: /^Ingredient 0 ·/ });
    const tooltip = page.getByRole('tooltip');
    await resource.hover();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    await region.evaluate((node) => {
        node.scrollTop = 100;
    });
    await page.waitForFunction(() => document.querySelector('#resource-tooltip').hidden);
    await region.evaluate((node) => {
        node.scrollTop = 0;
    });
    await page.mouse.move(0, 0);
    await resource.focus();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    await page.setViewportSize({ width: 1100, height: 800 });
    await page.waitForFunction(() => document.querySelector('#resource-tooltip').hidden);
    await resource.evaluate((node) => node.blur());
    await resource.focus();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    await clickTab(page, 'Terminal');
    await page.getByRole('searchbox', { name: 'Search resources', exact: true }).waitFor();
    assert.equal(await tooltip.isVisible(), false);
    await page.goBack();
    await resource.hover();
    await tooltip.getByText('Ingredient 0', { exact: true }).waitFor();
    options.cpuError = 'NO_PERMISSIONS';
    await poll(page);
    await resource.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false);
});

test('crafting popup and plan share optional atlas icons and honor native CPU admission', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], itemKey: 'iron', icon: options.itemsA[0].icon }] };
    options.cpus = { 'cpu-a': { ...cpu, acceptsPlayerJobs: false }, 'cpu-b': cpu };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    await page.getByRole('dialog').locator('.resource-icon-ready').waitFor({ timeout: 3000 });
    await page.getByRole('dialog').getByRole('textbox', { name: 'Craft quantity' }).fill('64');
    options.itemsA = [{ ...iron, icon: { page: 0, x: 64, y: 0 } }];
    await poll(page);
    await page.waitForFunction(() => {
        const icon = document.querySelector('dialog .resource-icon');
        return parseFloat(getComputedStyle(icon).backgroundPositionX) === -icon.getBoundingClientRect().width;
    });
    assert.equal(await page.getByRole('dialog').getByRole('textbox', { name: 'Craft quantity' }).inputValue(), '64');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const resources = page.getByRole('list', { name: 'Plan resources', exact: true });
    await resources.locator('.resource-icon-ready').waitFor({ timeout: 3000 });
    const cpus = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
    assert.equal(await cpus.getByRole('radio', { name: /cpu-a/ }).isDisabled(), true);
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).isChecked(), true);
    assert.ok(
        options.requests.some(
            (request) => request.path.endsWith('/crafting-plans/7') && request.query === '?icons=true'
        )
    );
});

test('plan icon pack replacement supersedes a delayed completed-plan mapping', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], itemKey: 'iron', icon: { page: 0, x: 0, y: 0 } }] };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('both');
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    let first = true;
    await page.route('**/crafting-plans/7?icons=true', async (route) => {
        if (!first) return route.continue();
        first = false;
        const response = await route.fetch();
        release(() => route.fulfill({ response }));
    });
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const finish = await captured;
    options.icons = { ...options.icons, packId: 'c'.repeat(64) };
    options.pack = { ...options.pack, packId: options.icons.packId };
    const discovery = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/icon-pack'));
    await poll(page);
    await (await discovery).finished();
    await finish();
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .locator('.resource-icon-ready')
        .waitFor({ timeout: 3000 });
    assert.ok(options.requests.some((request) => request.path.includes(`/icon-packs/${'c'.repeat(64)}/`)));
});

for (const [width, height] of [
    [1280, 900],
    [390, 844],
    [390, 720]
]) {
    test(`plan grid matches CPU slots and forty CPU choices scroll independently at ${width}x${height}`, async (t) => {
        const { page, options, base } = await fixture(t, '', { viewport: { width, height } });
        options.cpuDetails['cpu-a'] = { ...cpuWork, hasTrackingInfo: false };
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const cpuResource = page
            .getByRole('region', { name: 'CPU resources', exact: true })
            .getByRole('button')
            .first();
        await cpuResource.waitFor();
        const cpuSlot = await cpuResource.boundingBox();
        options.cpus = Object.fromEntries(
            Array.from({ length: 40 }, (_, index) => [
                `choice-${index}`,
                { ...cpu, name: `Processor ${index}`, acceptsPlayerJobs: index !== 38 }
            ])
        );
        options.itemsA = [{ ...iron, displayName: 'Iron Ingot assembly component '.repeat(8) }];
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).click();
        await page.getByRole('button', { name: 'Craft', exact: true }).click();
        await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
        const resource = page.getByRole('region', { name: 'Plan resources', exact: true }).getByRole('button').first();
        await resource.waitFor();
        const planSlot = await resource.boundingBox();
        await openMobilePane(page, 'CPUs');
        await page
            .locator('aside p')
            .filter({ hasText: /Iron Ingot assembly component/ })
            .focus();
        await page.getByRole('tooltip').waitFor({ timeout: 2000 });
        assert.ok((await page.getByRole('tooltip').innerText()).includes(options.itemsA[0].displayName));
        assert.ok(Math.abs(planSlot.width - cpuSlot.width) < 2, 'plan and three-row CPU slots share width');
        assert.ok(Math.abs(planSlot.height - cpuSlot.height) < 2, 'plan and three-row CPU slots share height');
        const choices = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
        const bounds = await choices.evaluate((node) => ({ height: node.clientHeight, content: node.scrollHeight }));
        assert.ok(bounds.content > bounds.height, 'CPU choices overflow their own bounded viewport');
        const last = choices.getByRole('radio', { name: /choice-39/ });
        const firstRow = await choices.getByRole('radio').first().boundingBox();
        assert.ok(
            bounds.height >= firstRow.height,
            `at least one complete choice fits (${bounds.height}/${firstRow.height})`
        );
        const heading = page.getByRole('heading', { name: 'Crafting CPU', exact: true });
        const before = await heading.boundingBox();
        await last.scrollIntoViewIfNeeded();
        await last.check();
        assert.equal(await last.isChecked(), true);
        assert.equal(await choices.getByRole('radio', { name: /choice-38/ }).isDisabled(), true);
        const after = await heading.boundingBox();
        assert.ok(Math.abs(after.y - before.y) < 1, 'list scrolling leaves the CPU heading stationary');
        assert.ok(await choices.evaluate((node) => node.scrollTop > 0));
        const visible = await choices.boundingBox();
        const selected = await last.boundingBox();
        assert.ok(selected.y >= visible.y && selected.y + selected.height <= visible.y + visible.height + 1);
        assert.ok(await page.getByRole('button', { name: 'Start', exact: true }).isEnabled());
        await last.focus();
        await page.keyboard.press('ArrowUp');
        assert.equal(await choices.getByRole('radio', { name: /choice-37/ }).isChecked(), true);
        const readingPosition = await choices.evaluate((node) => node.scrollTop);
        await poll(page);
        assert.equal(await choices.getByRole('radio', { name: /choice-37/ }).isChecked(), true);
        assert.equal(await choices.evaluate((node) => node.scrollTop), readingPosition);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        assert.equal(
            options.requests.filter((request) => request.method !== 'GET').length,
            1,
            'only explicit plan calculation mutates'
        );
    });
}

for (const width of [1280, 390]) {
    test(`plan frame, search, CPU attachment and footer remain usable at ${width}px`, async (t) => {
        const { page, options, base } = await fixture(
            t,
            '',
            { viewport: { width, height: 900 } },
            { mockClock: false }
        );
        options.plan = {
            ...readyPlan,
            plan: Array.from({ length: 90 }, (_, index) => ({
                ...readyPlan.plan[0],
                displayName: `Resource ${index}`,
                missing: index === 3 ? 8 : 0
            }))
        };
        await page.goto(`${base}#/grids/${gridA}/plans/7`);
        const region = page.getByRole('region', { name: 'Plan resources', exact: true });
        const resource = region.getByRole('button').first();
        await resource.waitFor();
        const card = await resource.boundingBox();
        assert.ok(card.width > card.height, 'plan resource cards remain rectangular');
        const search = await page.getByRole('searchbox', { name: 'Search plan resources' }).boundingBox();
        const heading = await page.getByRole('heading', { name: 'Crafting plan', exact: true }).boundingBox();
        const footer = await page.getByRole('button', { name: 'Cancel', exact: true }).boundingBox();
        const viewport = await region.boundingBox();
        assert.ok(Math.abs(heading.x - card.x) < 2, 'heading aligns to the resource grid');
        assert.ok(search.y + search.height <= card.y, 'search stays above resources');
        assert.ok(footer.y >= viewport.y + viewport.height, 'actions stay below the scroll viewport');
        assert.ok(footer.y + footer.height <= 900, 'actions stay within the viewport');
        await openMobilePane(page, 'CPUs');
        assert.ok(await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).isVisible());
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    });
}

test('crafting plan persists independent name total quantity and steps sorting within fixed groups', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingPlanSteps = true;
    options.plan = {
        ...readyPlan,
        plan: [
            ['Alpha', 60, 0, 0, 4],
            ['Zeta', 2, 10, 50, 1],
            ['Yotta', 30, 5, 0, 2],
            ['Gamma', 50, 0, 0, 8],
            ['Beta', 10, 40, 2, 7],
            ['Delta', 4, 20, 0, 9]
        ].map(([displayName, stored, requested, missing, steps]) => ({
            ...readyPlan.plan[0],
            displayName,
            stored,
            requested,
            missing,
            steps
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
    await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resources = page.getByRole('list', { name: 'Plan resources', exact: true });
    const names = async () =>
        await resources
            .getByRole('button')
            .evaluateAll((buttons) => buttons.map((button) => button.getAttribute('aria-label').split(' · ')[0]));
    const cases = [
        [
            'Name',
            ['Beta', 'Zeta', 'Delta', 'Yotta', 'Alpha', 'Gamma'],
            ['Zeta', 'Beta', 'Yotta', 'Delta', 'Gamma', 'Alpha']
        ],
        [
            'Quantity',
            ['Beta', 'Zeta', 'Delta', 'Yotta', 'Gamma', 'Alpha'],
            ['Zeta', 'Beta', 'Yotta', 'Delta', 'Alpha', 'Gamma']
        ],
        [
            'Crafting steps',
            ['Zeta', 'Beta', 'Yotta', 'Delta', 'Alpha', 'Gamma'],
            ['Beta', 'Zeta', 'Delta', 'Yotta', 'Gamma', 'Alpha']
        ]
    ];
    for (const [criterion, ascending, descending] of cases) {
        await resources.getByRole('button', { name: /^Alpha ·/ }).waitFor();
        assert.deepEqual(await names(), ascending, `${criterion} ascending within fixed groups`);
        await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
        assert.deepEqual(await names(), descending, `${criterion} descending within fixed groups`);
        await page.reload();
        await page.getByRole('button', { name: `Sort by: ${criterion}`, exact: true }).waitFor({ timeout: 3000 });
        await resources.getByRole('button', { name: /^Alpha ·/ }).waitFor();
        assert.deepEqual(await names(), descending, `${criterion} and direction survive reload`);
        await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).click();
        await page.getByRole('button', { name: `Sort by: ${criterion}`, exact: true }).click();
    }
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('ta');
    assert.deepEqual(await names(), ['Beta', 'Zeta', 'Delta', 'Yotta']);
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: 'Sort by: Registry ID', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).isVisible());
});

test('direct pending plan and reload restore the original order and options without replaying autostart', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities = { craftingLightMode: true, craftingIgnoreMissing: true };
    options.plan = {
        ...readyPlan,
        finalOutput: { ...quartz, itemKey: 'requested-quartz', quantity: 37 },
        options: { lightMode: true, ignoreMissing: true },
        autoStart: true,
        isSimulating: true
    };
    options.pendingReads = 1;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const output = page.getByText(/Certus Quartz Crystal × 37/);
    await output.waitFor({ timeout: 3000 });
    const more = page.getByRole('button', { name: 'Recalculate options', exact: true });
    assert.ok(await more.isDisabled());
    await poll(page);
    await more.click();
    const menu = page.getByRole('menu', { name: 'Recalculate options', exact: true });
    assert.equal(
        await menu
            .getByRole('menuitem', { name: 'Recalculate ignoring missing + autostart', exact: true })
            .evaluate((node) => node === node.parentElement.firstElementChild),
        true
    );
    await page.reload();
    await output.waitFor();
    await more.click();
    assert.equal(
        await menu
            .getByRole('menuitem', { name: 'Recalculate ignoring missing + autostart', exact: true })
            .evaluate((node) => node === node.parentElement.firstElementChild),
        true
    );
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, 0);
    options.nextPlanId = 8;
    await menu.getByRole('menuitem', { name: 'Recalculate ignoring missing', exact: true }).click();
    await page.waitForURL(/\/plans\/8$/);
    assert.deepEqual(
        options.requests.filter((entry) => entry.method !== 'GET').map((entry) => entry.method),
        ['DELETE', 'POST']
    );
    assert.deepEqual(options.requests.find((entry) => entry.path.endsWith('/crafting-plans')).body, {
        itemKey: 'requested-quartz',
        quantity: 37,
        lightMode: true,
        ignoreMissing: true
    });
});

test('delayed plan metadata cannot restore another grid order or automatic intent', async (t) => {
    const { page, options, base } = await fixture(t);
    let complete;
    const requested = new Promise((resolve) => {
        complete = resolve;
    });
    await page.route(`**/api/grids/${gridA}/crafting-plans/7`, (route) => complete(route));
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const pending = await requested;
    options.plan = {
        ...readyPlan,
        finalOutput: { ...quartz, quantity: 19 },
        autoStart: true
    };
    await page.goto(`${base}#/grids/${gridB}/plans/7`);
    await page.getByText(/Certus Quartz Crystal × 19/).waitFor();
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: readyPlan })
    });
    await poll(page);
    assert.ok(await page.getByText(/Certus Quartz Crystal × 19/).isVisible());
    assert.equal(await page.getByText(/Iron Ingot × 12/).count(), 0);
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, 0);
});

test('plan recalculation preserves the order and light mode, supports autostart and locks uncertain replacement', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingLightMode = true;
    options.plan = { ...readyPlan, isSimulating: true };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('textbox', { name: 'Craft quantity' }).fill('32');
    await page.getByRole('checkbox', { name: 'Light mode', exact: true }).check();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const start = page.getByRole('button', { name: 'Start', exact: true });
    await start.waitFor();
    assert.ok(await start.isDisabled());
    const more = page.getByRole('button', { name: 'Recalculate options', exact: true });
    await more.click();
    const menu = page.getByRole('menu', { name: 'Recalculate options', exact: true });
    assert.equal(await menu.getByRole('menuitem').count(), 2, 'unsupported ignore-missing variants are absent');
    options.nextPlanId = 8;
    await menu.getByRole('menuitem', { name: 'Recalculate', exact: true }).click();
    await page.waitForURL(/\/plans\/8$/);
    assert.deepEqual(
        options.requests.filter((entry) => entry.method !== 'GET').map((entry) => entry.method),
        ['POST', 'DELETE', 'POST']
    );
    assert.deepEqual(options.requests.filter((entry) => entry.path.endsWith('/crafting-plans')).at(-1).body, {
        itemKey: 'iron',
        quantity: 32,
        lightMode: true
    });
    await more.click();
    options.plan = readyPlan;
    options.nextPlanId = 9;
    await menu.getByRole('menuitem', { name: 'Recalculate with autostart', exact: true }).click();
    await page.waitForURL(/\/items$/);
    assert.ok(options.requests.some((entry) => entry.path.endsWith('/crafting-plans/9/submit')));
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    options.nextPlanId = 10;
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.waitForURL(/\/plans\/10$/);
    await more.click();
    options.fault = { operation: 'delete', status: 'TIMEOUT' };
    await menu.getByRole('menuitem', { name: 'Recalculate', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    assert.ok(await more.isDisabled());
    assert.ok(await start.isDisabled());
    const count = options.requests.filter((entry) => entry.method !== 'GET').length;
    await poll(page);
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, count);
});

test('ignore-missing recalculation keeps original options first and never retries uncertain creation', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingLightMode = true;
    options.capabilities.craftingIgnoreMissing = true;
    options.submitStatus = 'FAIL';
    options.submitReason = 'Ingredients changed before starting';
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Start automatically', exact: true }).check();
    await page.getByRole('checkbox', { name: 'Light mode', exact: true }).check();
    await page.getByRole('checkbox', { name: 'Ignore missing ingredients', exact: true }).check();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.waitForURL(/\/plans\/7$/);
    assert.deepEqual(options.requests.find((entry) => entry.path.endsWith('/crafting-plans')).body, {
        itemKey: 'iron',
        quantity: 1,
        lightMode: true,
        ignoreMissing: true,
        autoStart: true
    });
    await page.getByText(options.submitReason, { exact: true }).waitFor();
    await page.reload();
    await page.getByText(/Iron Ingot × 1/).waitFor();
    await poll(page);
    assert.equal(options.requests.filter((entry) => entry.path.endsWith('/submit')).length, 1);
    const more = page.getByRole('button', { name: 'Recalculate options', exact: true });
    await more.click();
    const menu = page.getByRole('menu', { name: 'Recalculate options', exact: true });
    assert.equal(await menu.getByRole('menuitem').count(), 4);
    assert.equal(await menu.getByRole('menuitem').first().innerText(), 'Recalculate ignoring missing + autostart');
    await menu.getByRole('menuitem', { name: 'Recalculate ignoring missing', exact: true }).focus();
    await poll(page);
    assert.equal(
        await menu
            .getByRole('menuitem', { name: 'Recalculate ignoring missing', exact: true })
            .evaluate((node) => node === document.activeElement),
        true
    );
    options.nextPlanId = 8;
    await page.keyboard.press('Enter');
    await page.waitForURL(/\/plans\/8$/);
    assert.deepEqual(options.requests.filter((entry) => entry.path.endsWith('/crafting-plans')).at(-1).body, {
        itemKey: 'iron',
        quantity: 1,
        lightMode: true,
        ignoreMissing: true
    });
    await more.click();
    assert.equal(await menu.getByRole('menuitem').first().innerText(), 'Recalculate ignoring missing');
    options.fault = { operation: 'create', status: 'TIMEOUT' };
    await menu.getByRole('menuitem', { name: 'Recalculate', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /calculation.*unknown/i })
        .waitFor();
    assert.ok(await more.isDisabled());
    const count = options.requests.filter((entry) => entry.method !== 'GET').length;
    await poll(page);
    assert.equal(options.requests.filter((entry) => entry.method !== 'GET').length, count);
});

test('plan tiles omit zero metrics and show ordered counts with whole storage percentages', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingPlanSteps = true;
    options.plan = {
        ...readyPlan,
        plan: [
            { ...readyPlan.plan[0], displayName: 'Combined resource', missing: 2, requested: 12, steps: 3, stored: 4 },
            {
                ...readyPlan.plan[0],
                displayName: 'Stored resource',
                missing: 0,
                requested: 0,
                steps: 0,
                stored: 15,
                usedPercent: 0.376
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resources = page.getByRole('region', { name: 'Plan resources', exact: true });
    const combined = resources.getByRole('button', { name: /^Combined resource/ });
    await combined.waitFor();
    assert.match(await combined.innerText(), /Missing\s+2\s+Requested\s+12\s+Steps\s+3\s+Stored\s+4/);
    const stored = resources.getByRole('button', { name: /^Stored resource/ });
    assert.match(await stored.innerText(), /Stored\s+15\s+\(38%\)/);
    assert.doesNotMatch(await stored.innerText(), /Missing|Requested|Steps/);
    await stored.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Stored resource', { exact: true }).waitFor();
    assert.match(await tooltip.innerText(), /Stored: 15 \(38%\)/);
    assert.doesNotMatch(await tooltip.innerText(), /Missing|Requested|Crafting steps/);
});

test('unsupported plan steps fall back without overwriting the saved choice and omit unavailable metrics', async (t) => {
    const { page, options, base } = await fixture(t);
    options.capabilities.craftingPlanSteps = true;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Crafting steps', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
    options.capabilities.craftingPlanSteps = false;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).isVisible());
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .hover();
    const tooltip = page.getByRole('tooltip');
    await tooltip.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.equal(await tooltip.getByText(/Crafting steps/).count(), 0);
    options.capabilities.craftingPlanSteps = true;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Crafting steps', exact: true }).waitFor();
    options.capabilities.craftingPlanSteps = false;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    options.capabilities.craftingPlanSteps = true;
    await page.reload();
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).isVisible());
});

test('crafting plan resources search and sort while CPU choice and actions stay available outside scroll', async (t) => {
    const { page, options, base } = await fixture(t);
    options.plan = {
        ...readyPlan,
        plan: Array.from({ length: 80 }, (_, index) => ({
            ...readyPlan.plan[0],
            displayName: `Resource ${String(index).padStart(2, '0')}`,
            stored: index + 1,
            requested: 20
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resources = page.getByRole('list', { name: 'Plan resources', exact: true });
    await resources.getByRole('button', { name: /Resource 00/ }).waitFor();
    const cpus = page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true });
    await cpus.getByRole('radio', { name: /cpu-b/ }).check();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    assert.match(await resources.getByRole('button').first().getAttribute('aria-label'), /Resource 00/);
    await page.getByRole('button', { name: /Sort order: Ascending/ }).click();
    assert.match(await resources.getByRole('button').first().getAttribute('aria-label'), /Resource 79/);
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('Resource 03');
    assert.equal(await resources.getByRole('button').count(), 1);
    await page.getByRole('searchbox', { name: 'Search plan resources' }).fill('');
    const start = page.getByRole('button', { name: 'Start', exact: true });
    const before = await start.boundingBox();
    await resources.evaluate((list) => {
        list.parentElement.scrollTop = list.parentElement.scrollHeight;
    });
    const after = await start.boundingBox();
    assert.deepEqual(after, before);
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).isChecked(), true);
    await start.click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    assert.equal(options.requests.find((request) => request.path.endsWith('/submit')).body.cpuKey, 'cpu-b');
});

test('quantity expressions and adjustments send exact positive integers through HTTP', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click({ button: 'middle' });
    const dialog = page.getByRole('dialog');
    const quantity = dialog.getByRole('textbox', { name: 'Craft quantity' });
    await quantity.fill('10.5m + (100k / 2) - 64');
    await dialog.getByRole('button', { name: '+64', exact: true }).click();
    assert.equal(await quantity.inputValue(), '10550000');
    await quantity.fill('1');
    await dialog.getByRole('button', { name: '-1000', exact: true }).click();
    assert.equal(await quantity.inputValue(), '1');
    for (const invalid of ['1/3', '1/0', '1.0000000000000001', '9007199254740992', '2**3', 'abc']) {
        await quantity.fill(invalid);
        await dialog.getByRole('button', { name: 'Calculate plan', exact: true }).click();
        assert.equal(await quantity.getAttribute('aria-invalid'), 'true');
    }
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 0);
    await quantity.fill('-(2-3) * (10,5m + 100k) / 2');
    await dialog.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.waitForURL(/\/plans\/7$/);
    assert.equal(options.requests.find((request) => request.path.endsWith('/crafting-plans')).body.quantity, 5300000);
});

test('quantity editing survives refresh and only positive safe integer quantities reach HTTP', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
    const quantity = page.getByRole('textbox', { name: 'Craft quantity' });
    await quantity.fill('37');
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/items'));
    await poll(page);
    await (await refreshed).finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await quantity.evaluate((input) => input === document.activeElement), true);
    assert.equal(await quantity.inputValue(), '37');
    for (const invalid of ['0', '-1', '1.5', '9007199254740992']) {
        await quantity.fill(invalid);
        await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    }
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 0);
    await quantity.fill('9007199254740991');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    assert.equal(
        options.requests.find((request) => request.path.endsWith('/crafting-plans')).body.quantity,
        9007199254740991
    );
});

test('deletion rejection makes unavailable or denied plans non-actionable', async (t) => {
    const { page, options, base } = await fixture(t);
    let planId = 20;
    for (const status of ['NO_PERMISSIONS', 'GRID_NOT_FOUND', 'INVALID_ID']) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId++}`);
        await page
            .getByRole('list', { name: 'Plan resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor();
        options.fault = { operation: 'delete', status };
        await page.getByRole('button', { name: 'Cancel', exact: true }).click();
        await page
            .getByRole('list', { name: 'Plan resources', exact: true })
            .getByRole('button', { name: /Iron Ingot/ })
            .waitFor({ state: 'hidden', timeout: 3000 });
        assert.equal(await page.getByRole('button', { name: 'Start', exact: true }).isDisabled(), true);
    }
});

test('pending submission resolves after route reentry and older CPU reads cannot overwrite its result', async (t) => {
    const { page, base } = await fixture(t);
    let completeSubmit;
    await page.route('**/submit', (route) => {
        completeSubmit = (status) =>
            route.fulfill({
                status: status === 'OK' ? 200 : 503,
                contentType: 'application/json',
                body: JSON.stringify({ status, data: null })
            });
    });
    for (const [planId, status] of [
        [30, 'OK'],
        [31, 'TIMEOUT']
    ]) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId}`);
        await page.getByRole('button', { name: 'Start', exact: true }).click();
        await clickTab(page, 'Terminal');
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await page.getByRole('heading', { name: 'Crafting plan', exact: true }).waitFor();
        await completeSubmit(status);
        await page
            .getByRole('status')
            .filter({ hasText: status === 'OK' ? /submitted/i : /outcome.*unknown/i })
            .waitFor();
        await poll(page);
        await page
            .getByRole('status')
            .filter({ hasText: status === 'OK' ? /submitted/i : /outcome.*unknown/i })
            .waitFor();
    }
    await page.unroute('**/submit');
    await page.goto(`${base}#/grids/${gridA}/plans/32`);
    await page.getByRole('radiogroup', { name: 'Crafting CPU', exact: true }).waitFor();
    let releaseCpus;
    const captured = new Promise((resolve) => {
        releaseCpus = resolve;
    });
    await page.route('**/cpus', (route) => releaseCpus(route));
    await poll(page);
    const cpuRequest = await captured;
    await page.getByRole('button', { name: 'Start', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    await cpuRequest.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'NO_PERMISSIONS', data: null })
    });
    await page.waitForTimeout(100);
    assert.match(page.url(), new RegExp(`/grids/${gridA}/items$`));
});

test('mobile login shows one usable form at a time and submits the existing registration fields', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.loggedOut = true;
    await page.setViewportSize({ width: 320, height: 640 });
    await page.goto(base);
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(await page.getByLabel('Username (in-game)', { exact: true }).isVisible(), false);
    assert.equal(await page.getByRole('dialog').count(), 0);
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-mobile.png'), fullPage: true });
        await page.setViewportSize({ width: 1280, height: 800 });
        await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-desktop.png'), fullPage: true });
        await page.setViewportSize({ width: 320, height: 640 });
    }
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByLabel('Username (in-game)', { exact: true }).fill('TestPlayer');
    const password = page.getByLabel('Password', { exact: true }).filter({ visible: true });
    await password.fill('test-password');
    await page.getByRole('button', { name: 'Show password', exact: true }).filter({ visible: true }).click();
    assert.equal(await password.getAttribute('type'), 'text');
    await page.setViewportSize({ width: 320, height: 360 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.route(base, async (route) => {
        if (route.request().method() !== 'POST') return route.continue();
        const body = new URLSearchParams(route.request().postData());
        assert.equal(body.get('register'), 'TestPlayer');
        assert.equal(body.get('password'), 'test-password');
        await route.fulfill({ status: 200, body: 'Registered' });
    });
    const submitted = page.waitForRequest((request) => request.method() === 'POST');
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await submitted;
});

for (const error of ['INVALID_USER', 'INVALID_PASSWORD', 'NOT_ONLINE']) {
    test(`login displays ${error} without losing the destination`, async (t) => {
        const { page, options, base } = await fixture(t, '/ae2');
        options.loggedOut = true;
        await page.goto(`${base}?${error}#/grids/${gridA}/items`);
        await page.getByRole('alert').waitFor();
        assert.ok((await page.getByRole('alert').innerText()).length > 0);
        assert.equal(new URL(page.url()).search, '');
        assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    });
}

test('registration confirmation keeps the command readable and supports manual copy without clipboard access', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.loggedOut = true;
    await page.addInitScript(() => Object.defineProperty(navigator, 'clipboard', { value: undefined }));
    await page.setViewportSize({ width: 390, height: 700 });
    const token = 'sample-token-<img src=x onerror=alert(1)>';
    await page.goto(`${base}?confirmregistration&token=${encodeURIComponent(token)}#/grids/${gridA}/items`);
    const command = page.getByRole('textbox', { name: 'Registration command' });
    await command.waitFor();
    assert.equal(await command.inputValue(), `/ae2webintegration auth ${token}`);
    assert.equal(await page.getByLabel('Username', { exact: true }).isVisible(), false);
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    await page.getByRole('button', { name: 'Copy command' }).click();
    assert.equal(
        await command.evaluate((node) => node.selectionEnd - node.selectionStart),
        (await command.inputValue()).length
    );
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({
            path: path.join(process.env.UI_SCREENSHOT_DIR, 'login-confirmation.png'),
            fullPage: true
        });
    }
    await page.getByRole('button', { name: 'Back to login' }).click();
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(await command.isVisible(), false);
    assert.equal(
        (await page.context().cookies()).some((cookie) => cookie.name === 'cookiesAccepted'),
        false
    );
});

test('login errors clear transient parameters and preserve destination without loading a theme', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}?INVALID_PASSWORD#/grids/${gridA}/items`);
    await page.waitForFunction(() => !location.search.includes('INVALID_PASSWORD'));
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    assert.equal(
        options.requests.some((request) => /\/themes\/.*\.(js|css)$/.test(request.path)),
        false
    );
});

test('logout returns to the login document even when its URL matches the current route', async (t) => {
    const { page, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/`);
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByPlaceholder('Enter password', { exact: true }).first().waitFor({ timeout: 5000 });
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, '#/');
});

test('changing networks cannot publish a delayed response from the previous network', async (t) => {
    const { page, options, base } = await fixture(t);
    options.delayA = 400;
    await page.goto(base);
    const requested = page.waitForRequest((request) => request.url().includes(gridA + '/items'));
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .click();
    await requested;
    await page.goto(`${base}#/grids/${gridB}/items`);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    // Let the deliberately delayed old response reach the browser before observing the final view.
    await page.waitForTimeout(550);
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.match(page.url(), new RegExp(gridB));
    await page.goBack();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.match(page.url(), new RegExp(gridA));
    await clickTab(page, 'Home');
    await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
    assert.equal(
        await page.getByRole('link', { name: 'Terminal', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/items`
    );
});

// A sample theme consumes only public helpers; assertions observe its controls and rendered values.
async function themeFixture(t, script, markup = '') {
    const current = await fixture(t, '/ae2');
    const url = new URL('theme-fixture', current.base).href;
    await current.page.route(url, (route) =>
        route.fulfill({
            contentType: 'text/html',
            body: `<!doctype html><html lang="en"><body>${markup}<output aria-label="Result"></output>
                <script type="module">
                    try {
                        const { createThemeContext } = await import('./assets/web/app/theme-context.js');
                        const base = new URL('./', location.href);
                        const result = document.querySelector('output');
                        ${script}
                        document.body.dataset.ready = 'true';
                    } catch (error) {
                        document.querySelector('output').textContent = String(error);
                        document.body.dataset.ready = 'error';
                    }
                </script></body></html>`
        })
    );
    const open = async () => {
        if (current.page.url() === url) await current.page.reload();
        else await current.page.goto(url);
        await current.page.locator('body[data-ready]').waitFor({ timeout: 3000 });
        assert.equal(
            await current.page.locator('body').getAttribute('data-ready'),
            'true',
            await current.page.getByLabel('Result').textContent()
        );
    };
    return { ...current, open };
}

test('theme settings isolate deployments and themes and round-trip JSON through reload and removal', async (t) => {
    const { page, open } = await themeFixture(
        t,
        `
        const primary = createThemeContext(base, 'sample').settings;
        const otherTheme = createThemeContext(base, 'other').settings;
        const otherDeployment = createThemeContext(new URL('../second/', base), 'sample').settings;
        function render() {
            result.textContent = JSON.stringify([
                primary.get('layout', 'default'), otherTheme.get('layout', 'default'),
                otherDeployment.get('layout', 'default')
            ]);
        }
        document.querySelector('#save').onclick = () => {
            primary.set('layout', { compact: false, columns: 0, labels: ['ore', 'ingot'], note: null });
            otherTheme.set('layout', 'other theme');
            otherDeployment.set('layout', 'other deployment');
            render();
        };
        document.querySelector('#remove').onclick = () => { primary.remove('layout'); render(); };
        render();
    `,
        '<button id="save">Save layouts</button><button id="remove">Reset layout</button>'
    );
    await open();
    assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), ['default', 'default', 'default']);
    await page.getByRole('button', { name: 'Save layouts' }).click();
    await open();
    assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), [
        { compact: false, columns: 0, labels: ['ore', 'ingot'], note: null },
        'other theme',
        'other deployment'
    ]);
    await page.getByRole('button', { name: 'Reset layout' }).click();
    await open();
    assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), [
        'default',
        'other theme',
        'other deployment'
    ]);
});

test('theme settings recover from corrupt storage and remain usable when browser storage is blocked', async (t) => {
    for (const fault of ['corrupt', 'unavailable', 'quota']) {
        await t.test(fault, async (t) => {
            const { page, open } = await themeFixture(
                t,
                `
                const settings = createThemeContext(base, 'sample').settings;
                const render = () => { result.textContent = JSON.stringify([
                    settings.get('layout', 'default'), settings.get('unset')
                ]); };
                document.querySelector('#save').onclick = () => { settings.set('layout', false); render(); };
                document.querySelector('#remove').onclick = () => { settings.remove('layout'); render(); };
                render();
            `,
                '<button id="save">Save layout</button><button id="remove">Reset layout</button>'
            );
            await page.addInitScript((fault) => {
                if (fault === 'unavailable') {
                    Object.defineProperty(window, 'localStorage', {
                        get() {
                            throw new DOMException('Blocked', 'SecurityError');
                        }
                    });
                } else if (fault === 'quota') {
                    Storage.prototype.setItem = () => {
                        throw new DOMException('Full', 'QuotaExceededError');
                    };
                } else {
                    const getItem = Storage.prototype.getItem;
                    Storage.prototype.getItem = function (key) {
                        return getItem.call(this, key) ?? '{invalid';
                    };
                }
            }, fault);
            await open();
            assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), ['default', null]);
            await page.getByRole('button', { name: 'Save layout' }).click();
            assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), [false, null]);
            await page.getByRole('button', { name: 'Reset layout' }).click();
            assert.deepEqual(JSON.parse(await page.getByLabel('Result').textContent()), ['default', null]);
        });
    }
});

test('theme translations register independently and separate own and common fallbacks', async (t) => {
    const { page, open } = await themeFixture(
        t,
        `
        const own = createThemeContext(base, 'sample').i18n;
        const other = createThemeContext(base, 'other').i18n;
        const commonHome = own.forLanguage('pl').common('home');
        document.querySelector('button').onclick = () => {
            own.register({
                en: { home: 'Theme home', greeting: 'Hello {name}', units: {one: '{count} item', other: '{count} items'} },
                pl: { greeting: 'Witaj {name}', units: {one: '{count} rzecz', few: '{count} rzeczy', many: '{count} rzeczy', other: '{count} rzeczy'} }
            });
            own.register({en: {extra: 'Additional message'}});
            const polish = own.forLanguage('pl');
            const french = own.forLanguage('fr');
            result.textContent = JSON.stringify({
                ownHome: polish.t('home'), commonHome, afterRegister: polish.common('home'),
                selected: polish.t('greeting', {name: 'Ada'}), plural: polish.t('units', {count: 2}),
                englishFallback: french.t('units', {count: 0}), extra: polish.t('extra'),
                missing: polish.t('missing-key'), commonMissing: polish.common('missing-key'),
                isolated: other.forLanguage('pl').t('home'),
                commonFallback: french.common('cpuCount', {count: 0}),
                englishCommon: own.forLanguage('en').common('cpuCount', {count: 0}),
                number: own.forLanguage('en').number(1234.5)
            });
        };
    `,
        '<button>Register theme translations</button>'
    );
    await open();
    await page.getByRole('button', { name: 'Register theme translations' }).click();
    const output = JSON.parse(await page.getByLabel('Result').textContent());
    assert.equal(output.ownHome, 'Theme home');
    assert.equal(output.afterRegister, output.commonHome, 'Theme keys cannot overwrite the common namespace');
    assert.notEqual(output.afterRegister, output.ownHome);
    assert.equal(output.selected, 'Witaj Ada');
    assert.equal(output.plural, '2 rzeczy');
    assert.equal(output.englishFallback, '0 items', 'English fallback uses English plural rules');
    assert.equal(output.extra, 'Additional message');
    assert.equal(output.missing, 'missing-key');
    assert.equal(output.commonMissing, 'missing-key');
    assert.equal(output.isolated, 'home');
    assert.equal(output.commonFallback, output.englishCommon);
    assert.equal(output.number, '1,234.5');
});

test('a theme can persist its own supported language in shared preferences', async (t) => {
    const { page, open, base } = await themeFixture(
        t,
        `
        const { createPreferences } = await import('./assets/web/app/preferences.js');
        const preferences = createPreferences(base);
        const i18n = createThemeContext(base, 'french-theme').i18n;
        i18n.register({en: {welcome: 'Welcome'}, fr: {welcome: 'Bienvenue'}});
        function render() {
            const translator = i18n.forLanguage(preferences.values.language);
            result.textContent = JSON.stringify([
                translator.t('welcome'), translator.number(1234.5), translator.common('cpuCount', {count: 0})
            ]);
        }
        document.querySelector('button').onclick = () => { preferences.set('language', 'fr'); render(); };
        render();
    `,
        '<button>Use French</button>'
    );
    await open();
    const englishCommon = JSON.parse(await page.getByLabel('Result').textContent())[2];
    await page.getByRole('button', { name: 'Use French' }).click();
    await open();
    const output = JSON.parse(await page.getByLabel('Result').textContent());
    assert.equal(output[0], 'Bienvenue');
    assert.equal(output[1].replace(/\s/g, ' '), '1 234,5');
    assert.equal(output[2], englishCommon);
    await page.goto(`${base}#/web-settings`);
    const language = page.getByRole('combobox', { name: 'Language' });
    await language.waitFor();
    assert.equal(await language.inputValue(), 'fr', 'Returning to the default theme retains the shared language');
});

test('appearance, language and terminal preferences survive reload and direct links', async (t) => {
    const { page, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('button', { name: /^Sort by:/ }).click();
    await page.getByRole('button', { name: /^Sort order:/ }).click();
    await clickTab(page, 'Web settings');
    await page.getByRole('checkbox', { name: 'Automatic updates' }).uncheck();
    await page.getByRole('combobox', { name: 'Appearance' }).selectOption('dark');
    await page.getByRole('combobox', { name: 'Language' }).selectOption('pl');
    await page.reload();
    await page.getByRole('combobox', { name: 'Wygląd' }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Wygląd' }).inputValue(), 'dark');
    assert.equal(await page.getByRole('checkbox', { name: 'Automatyczne aktualizacje' }).isChecked(), false);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Sortuj według: Ilości', exact: true }).isVisible(), true);
    await page.getByRole('navigation').locator('a[href="#/"]').click();
    await page.getByRole('button', { name: 'Wybierz sieć', exact: true }).waitFor();
    assert.equal(
        await page.getByRole('link', { name: 'Terminal', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/items`
    );
});

test('the default theme ignores obsolete shared appearance and preserves current choices and reset', async (t) => {
    const { page, base, open } = await themeFixture(
        t,
        `
        const settings = createThemeContext(base, 'default').settings;
        document.querySelector('button').onclick = () => { settings.remove('appearance'); result.textContent = 'Reset'; };
    `,
        '<button>Reset default appearance</button>'
    );
    // Appearance is owned by the theme; old shared values must not affect current defaults.
    await page.addInitScript(() => {
        const key = 'ae2web:/ae2/:ui';
        if (localStorage.getItem(key) === null) {
            localStorage.setItem(key, JSON.stringify({ appearance: 'dark', language: 'en', autoRefresh: false }));
        }
    });
    await page.goto(`${base}#/web-settings`);
    const appearance = page.getByRole('combobox', { name: 'Appearance' });
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'system');
    await appearance.selectOption('light');
    await page.reload();
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'light');
    await open();
    await page.getByRole('button', { name: 'Reset default appearance' }).click();
    await page.goto(`${base}#/web-settings`);
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'system', 'Reset must not adopt the obsolete shared appearance');
});

test('current theme appearance survives a storage write failure without adopting shared appearance', async (t) => {
    const { page, base } = await fixture(t, '/ae2');
    await page.addInitScript(() => {
        if (sessionStorage.getItem('storageFailureSeeded')) return;
        sessionStorage.setItem('storageFailureSeeded', 'true');
        localStorage.setItem('ae2web:/ae2/:ui', JSON.stringify({ appearance: 'dark', language: 'en' }));
        localStorage.setItem('ae2web:/ae2/:theme:default', JSON.stringify({ appearance: 'light' }));
        Storage.prototype.setItem = function () {
            throw new DOMException('Full', 'QuotaExceededError');
        };
    });
    await page.goto(`${base}#/web-settings`);
    const appearance = page.getByRole('combobox', { name: 'Appearance' });
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'light');
    await appearance.selectOption('system');
    assert.equal(await appearance.inputValue(), 'system');
    await page.reload();
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'light', 'Failed writes must preserve the current persisted choice');
});

test('lost access clears resource data and subsequent polling recovers', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    options.status = 403;
    await poll(page);
    await page.getByText('You no longer have access to this network.').waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('heading', { name: 'Iron Ingot' }).count(), 0);
    options.status = 200;
    await poll(page);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
});

test('hovered resource tooltip stays current through polling and closes when the resource disappears', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await page.getByRole('button', { name: /^Sort by:/ }).click();
    await page.getByRole('button', { name: /^Sort order:/ }).click();
    await item.hover();
    await tooltip.waitFor({ state: 'visible' });
    const updatedTooltip = page.evaluate(
        () =>
            new Promise((resolve) => {
                const observer = new MutationObserver(() => {
                    if (
                        ![...document.querySelectorAll('button')].some((button) =>
                            /Iron Ingot.*8,765/.test(button.textContent)
                        )
                    )
                        return;
                    const tooltip = [...document.querySelectorAll('[role="tooltip"]')].find((node) =>
                        node.checkVisibility()
                    );
                    observer.disconnect();
                    resolve({ visible: !!tooltip, content: tooltip?.textContent || '' });
                });
                observer.observe(document.body, {
                    childList: true,
                    subtree: true,
                    characterData: true,
                    attributes: true
                });
            })
    );
    options.itemsA = [{ ...iron, quantity: 8765 }, quartz];
    const refreshed = await updatedTooltip;
    assert.equal(refreshed.visible, true, 'Polling must not dismiss a stationary pointer tooltip');
    assert.match(refreshed.content, /8,765/);
    options.itemsA = [{ ...iron, quantity: 1 }, quartz];
    await page.getByRole('button', { name: /Iron Ingot.*\b1\b/ }).waitFor();
    assert.ok(
        !(await tooltip.isVisible()) || !(await tooltip.textContent()).includes('Iron Ingot'),
        'A reordered resource must not retain its tooltip under the pointer at its old position'
    );
    await item.hover();
    await tooltip.waitFor({ state: 'visible' });
    options.itemsA = [quartz];
    await item.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false, 'A removed resource must not leave its tooltip behind');
});

test('resource tooltips appear on the first hover frame and respect keyboard dismissal across refresh', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    await item.waitFor();
    const visibleOnHover = item.evaluate(
        (button) =>
            new Promise((resolve) => {
                button.addEventListener(
                    'pointerenter',
                    () =>
                        requestAnimationFrame(() => {
                            resolve(
                                [...document.querySelectorAll('[role="tooltip"]')].some(
                                    (node) => node.checkVisibility() && node.textContent.includes('Iron Ingot')
                                )
                            );
                        }),
                    { once: true }
                );
            })
    );
    await item.hover();
    assert.equal(await visibleOnHover, true, 'An item tooltip should be visible on the first hover frame');
    await page.mouse.move(0, 0);
    await item.focus();
    await tooltip.waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    options.itemsA = [{ ...iron, displayName: 'A Iron Ingot', quantity: 3456 }, quartz];
    await page.getByRole('button', { name: /Iron Ingot.*3,456/ }).waitFor();
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), false, 'Reordering must not reopen a dismissed tooltip');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Shift+Tab');
    await tooltip.waitFor({ state: 'visible' });
    assert.match(await tooltip.textContent(), /3,456/);
    options.gridError = 'NO_PERMISSIONS';
    await item.waitFor({ state: 'detached' });
    assert.equal(await tooltip.isVisible(), false, 'Access loss must remove private tooltip contents');
});

test('hovered tool keeps its tooltip when a keyboard-focused resource refreshes', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: /Iron Ingot/ });
    const tooltip = page.getByRole('tooltip');
    const tool = page.getByRole('button', { name: /^Sort by:/ });
    await item.focus();
    await tool.hover();
    const label = await tooltip.textContent();
    assert.ok(label.includes(await tool.getAttribute('aria-label')));
    options.itemsA = [{ ...iron, quantity: 2345 }, quartz];
    await page.getByRole('button', { name: /Iron Ingot.*2,345/ }).waitFor();
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), true);
    assert.equal(
        await tooltip.textContent(),
        label,
        'Polling must not replace the hovered tool tooltip with resource details'
    );
    options.itemsA = [{ ...iron, displayName: 'A Iron Ingot', quantity: 1234 }, quartz];
    await page.getByRole('button', { name: /Iron Ingot.*1,234/ }).waitFor();
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), true);
    assert.equal(
        await tooltip.textContent(),
        label,
        'Restoring focus after reordering must not replace the hovered tooltip'
    );
});

test('empty discovery and unavailable bookmarked networks stay usable', async (t) => {
    const { page, options, base } = await fixture(t);
    options.empty = true;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByText('This network is not available.').waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    await clickTab(page, 'Home');
    await page
        .getByRole('status')
        .filter({ hasText: /No accessible networks/ })
        .waitFor();
    options.empty = false;
    await poll(page);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
});

test('home network links keep keyboard focus when polling refreshes the list', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    const network = page.getByRole('link', { name: new RegExp(gridA) });
    await network.focus();
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await (await refreshed).finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await network.evaluate((link) => link === document.activeElement), true);
    const secondNetwork = page.getByRole('link', { name: new RegExp(gridB) });
    await secondNetwork.focus();
    options.reverseGrids = true;
    await (
        await page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'))
    ).finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await secondNetwork.evaluate((link) => link === document.activeElement), true);
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
});

test('resource grid stays centered and stationary when scrolling becomes unnecessary', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = Array.from({ length: 90 }, (_, index) => ({
        ...iron,
        displayName: `Resource ${String(index).padStart(3, '0')}`,
        itemKey: `key-${index}`
    }));
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const resources = page.getByRole('region', { name: 'Resources', exact: true });
    const first = resources.getByRole('button', { name: /Resource 000/ });
    await first.waitFor();
    const before = await first.boundingBox();
    assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), true);
    const bounds = await resources.boundingBox();
    const grid = await resources.getByRole('list').boundingBox();
    assert.ok(
        Math.abs(grid.x + grid.width / 2 - (bounds.x + bounds.width / 2)) < 1,
        'The resource grid must be centered independently of its scrollbar'
    );
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('Resource 000');
    assert.equal(await resources.getByRole('button').count(), 1);
    assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), false);
    const filledGrid = await resources.getByRole('list').boundingBox();
    assert.ok(
        Math.abs(filledGrid.height - (await resources.evaluate((node) => node.clientHeight))) < 1,
        'The empty grid continues to the bottom of the viewport without adding scrolling'
    );
    const filtered = await first.boundingBox();
    assert.equal(filtered.x, before.x);
    assert.equal(filtered.width, before.width);
    const populatedViewport = await resources.boundingBox();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('no matching resources');
    assert.equal(await resources.getByRole('button').count(), 0);
    assert.equal(await resources.evaluate((node) => node.scrollHeight > node.clientHeight), false);
    assert.deepEqual(await resources.boundingBox(), populatedViewport, 'No matches must not move or shrink the grid');
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('');
    const restored = await first.boundingBox();
    assert.equal(restored.x, before.x);
    assert.equal(restored.width, before.width);
});

test('terminal scrolls resources inside the viewport while search and navigation remain reachable', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.itemsA = Array.from({ length: 105 }, (_, index) => ({
        ...iron,
        displayName: `Resource ${String(index).padStart(3, '0')}`,
        itemKey: `key-${index}`
    }));
    for (const viewport of [
        { width: 1280, height: 800 },
        { width: 390, height: 844 },
        { width: 844, height: 390 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Resource 000/ }).waitFor();
        const resources = page.getByRole('region', { name: 'Resources', exact: true });
        await resources.waitFor();
        const search = page.getByRole('searchbox', { name: 'Search resources' });
        const before = await search.boundingBox();
        const grid = await resources.getByRole('list').boundingBox();
        const heading = await page.getByRole('heading', { name: 'Terminal', exact: true }).boundingBox();
        assert.ok(Math.abs(heading.x - grid.x) < 1, 'Terminal title aligns with the first slot');
        assert.ok(Math.abs(before.x + before.width - grid.x - grid.width) < 1, 'Search aligns with the last slot');
        await resources.focus();
        await page.keyboard.press('End');
        const last = resources.getByRole('button').last();
        await page.waitForFunction(
            ([region, item]) => {
                const bounds = region.getBoundingClientRect();
                const target = item.getBoundingClientRect();
                return bounds.height > 0 && target.bottom > bounds.top && target.bottom <= bounds.bottom + 1;
            },
            [await resources.elementHandle(), await last.elementHandle()]
        );
        assert.deepEqual(await search.boundingBox(), before, 'Scrolling items must not move the search field');
        const documentSize = await page.evaluate(() => ({
            width: document.documentElement.scrollWidth,
            height: document.documentElement.scrollHeight,
            top: window.scrollY,
            left: window.scrollX
        }));
        assert.ok(documentSize.width <= viewport.width, 'The page must fit the viewport horizontally');
        assert.ok(documentSize.height <= viewport.height, 'The page must fit the viewport vertically');
        assert.equal(documentSize.top, 0);
        assert.equal(documentSize.left, 0);
        const next = page.getByRole('button', { name: 'Next page' });
        const nextBounds = await next.boundingBox();
        assert.ok(nextBounds.y >= 0 && nextBounds.y + nextBounds.height <= viewport.height);
        await next.click();
        await page.getByRole('button', { name: /Resource 104/ }).waitFor();
        await page.getByRole('button', { name: 'Previous page' }).click();
    }
});

test('resource details are text, keyboard accessible and paging does not require new requests', async (t) => {
    const { page, options, base } = await fixture(t);
    const untrustedName = '<img src=x onerror=alert(1)> Quartz';
    options.itemsA = [
        { ...quartz, displayName: untrustedName },
        ...Array.from({ length: 105 }, (_, index) => ({
            ...iron,
            displayName: `Resource ${String(index).padStart(3, '0')}`,
            itemKey: `key-${index}`
        }))
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', { name: untrustedName + ' 42' });
    await item.focus();
    await page.getByRole('tooltip').waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await page.getByRole('tooltip').waitFor({ state: 'hidden' });
    await item.click();
    await page.getByRole('heading', { name: untrustedName, exact: true }).waitFor();
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    await page.getByRole('button', { name: 'Next page' }).click();
    await page.getByRole('button', { name: /Resource 104/ }).waitFor();
    await page.getByRole('button', { name: 'Previous page' }).click();
    assert.equal(await item.getAttribute('aria-pressed'), 'true');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
});

// HTTP/bootstrap and visible controls are the seam: no terminal requests before runtime identity is known.
test('bootstrap failure keeps the terminal unmounted and retry loads current server metadata', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.bootstrapStatus = 503;
    await page.goto(`${base}#/about`);
    await page.getByRole('button', { name: 'Try again', exact: true }).waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('button', { name: 'Choose a network', exact: true }).count(), 0);
    assert.equal(
        options.requests.some((request) => request.path === '/api/grids'),
        false
    );
    options.bootstrapStatus = 200;
    options.username = 'AfterRetry';
    options.modVersion = 'server-after-retry';
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await page.getByText('AfterRetry', { exact: true }).waitFor({ timeout: 3000 });
    await page.getByText(/Version: server-after-retry/).waitFor({ timeout: 3000 });
    assert.equal(new URL(page.url()).hash, '#/about');
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 2);
});

test('login waits for bootstrap and offers private mode only after retry succeeds', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.loggedOut = true;
    options.bootstrapStatus = 503;
    options.publicMode = false;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: 'Retry', exact: true }).waitFor({ timeout: 3000 });
    assert.equal(await page.getByLabel('Username', { exact: true }).isVisible(), false);
    assert.equal(await page.getByPlaceholder('Enter password', { exact: true }).first().isVisible(), false);
    options.bootstrapStatus = 200;
    await page.getByRole('button', { name: 'Retry', exact: true }).click();
    await page.getByPlaceholder('Enter password', { exact: true }).first().waitFor({ timeout: 3000 });
    assert.equal(await page.getByLabel('Username', { exact: true }).inputValue(), 'Admin');
    assert.equal(await page.getByLabel('Username', { exact: true }).getAttribute('readonly'), '');
    assert.equal(await page.getByRole('button', { name: 'Continue', exact: true }).isVisible(), false);
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
});

test('login bootstrap keeps public forms hidden until the server mode arrives', async (t) => {
    const { page, options, base } = await fixture(t);
    options.loggedOut = true;
    let release;
    const held = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/context', async (route) => {
        await held;
        await route.continue();
    });
    await page.goto(base);
    assert.equal(await page.getByLabel('Username', { exact: true }).isVisible(), false);
    assert.equal(await page.getByRole('button', { name: 'Continue', exact: true }).isVisible(), false);
    release();
    await page.getByLabel('Username', { exact: true }).waitFor({ timeout: 3000 });
    assert.equal(await page.getByLabel('Username', { exact: true }).getAttribute('readonly'), null);
    assert.equal(await page.getByRole('button', { name: 'Continue', exact: true }).isVisible(), false);
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByRole('button', { name: 'Continue', exact: true }).waitFor({ timeout: 3000 });
});

test('a session lost before bootstrap returns to login with the destination intact', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.route('**/api/context', async (route) => {
        options.loggedOut = true;
        await route.continue();
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByLabel('Username', { exact: true }).waitFor({ timeout: 3000 });
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    assert.equal(
        options.requests.some((request) => request.path === '/api/grids'),
        false
    );
});

// Public rendered geometry: empty inventory cells follow the occupied grid, without becoming resources.
for (const deviceScaleFactor of [1.25, 1.5]) {
    test(`empty slot frames share occupied geometry without phantom resources at DPR ${deviceScaleFactor}`, async (t) => {
        // Native animation frames must follow ResizeObserver delivery in this layout test.
        const { page, options, base } = await fixture(t, '', { deviceScaleFactor }, { mockClock: false });
        await atlasFixture(page, options);
        options.itemsA = [iron, quartz, { ...iron, itemKey: 'gold', displayName: 'Gold Ingot' }];
        await page.goto(`${base}#/grids/${gridA}/items`);
        const grid = page.locator('#items');
        await grid.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.evaluate(() => document.fonts.ready);
        for (const display of ['both', 'icons', 'names']) {
            await clickTab(page, 'Web settings');
            await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption(display);
            await page.goto(`${base}#/grids/${gridA}/items`);
            await grid.getByRole('button', { name: /Iron Ingot/ }).waitFor();
            for (const width of [997, 1031, 391]) {
                await page.setViewportSize({ width, height: 844 });
                await page.evaluate(
                    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
                );
                const geometry = await grid.evaluate((list) => {
                    const bounds = list.getBoundingClientRect();
                    const viewport = list.parentElement;
                    const cells = [...list.children].map((cell) => {
                        const control = cell.querySelector('button');
                        const frame = control || cell;
                        const rect = frame.getBoundingClientRect();
                        const style = getComputedStyle(frame);
                        return {
                            x: rect.x - bounds.x,
                            y: rect.y - bounds.y,
                            width: rect.width,
                            height: rect.height,
                            real: !!control,
                            hidden: cell.getAttribute('aria-hidden'),
                            edges: [
                                style.borderTopWidth,
                                style.borderRightWidth,
                                style.borderBottomWidth,
                                style.borderLeftWidth,
                                style.borderTopColor,
                                style.borderRightColor,
                                style.borderBottomColor,
                                style.borderLeftColor
                            ]
                        };
                    });
                    return {
                        cells,
                        width: bounds.width,
                        viewport: viewport.getBoundingClientRect().height,
                        content: viewport.scrollHeight
                    };
                });
                const real = geometry.cells.filter((cell) => cell.real);
                const empty = geometry.cells.filter((cell) => !cell.real);
                assert.equal(real.length, 3, 'decorative cells do not add resources');
                assert.ok(empty.length > 0, 'the unoccupied viewport contains real decorative frames');
                for (const cell of empty) {
                    assert.equal(cell.hidden, 'true', 'empty cells are excluded from accessibility');
                    assert.deepEqual(cell.edges, real[0].edges, 'empty and occupied frames use the same borders');
                }
                for (const cell of geometry.cells) {
                    const row = geometry.cells.find((candidate) => Math.abs(candidate.y - cell.y) < 0.03);
                    assert.ok(
                        Math.abs(cell.y + cell.height - row.y - row.height) < 0.03,
                        'occupied and decorative frames in a row share their bottom edge'
                    );
                }
                const firstRow = geometry.cells.filter((cell) => Math.abs(cell.y) < 0.03);
                for (let index = 1; index < firstRow.length; index++) {
                    assert.ok(
                        Math.abs(firstRow[index].x - firstRow[index - 1].x - firstRow[index - 1].width) < 0.03,
                        'adjacent real and empty cells meet without gaps or overlap'
                    );
                }
                assert.ok(
                    Math.abs(firstRow.at(-1).x + firstRow.at(-1).width - geometry.width) < 0.03,
                    'decorative frames follow the same final column edge'
                );
                const last = geometry.cells.at(-1);
                assert.ok(
                    last.y + last.height <= geometry.viewport + 0.1,
                    'only complete empty rows fit inside the viewport'
                );
                assert.equal(await grid.getByRole('button').count(), 3);
                assert.equal(await grid.getByRole('listitem').count(), 3);
                const stable = await grid.evaluate((list) => ({
                    count: list.children.length,
                    height: list.parentElement.scrollHeight
                }));
                await page.evaluate(
                    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
                );
                assert.deepEqual(
                    await grid.evaluate((list) => ({
                        count: list.children.length,
                        height: list.parentElement.scrollHeight
                    })),
                    stable,
                    'fillers cannot increase their own required row count'
                );
            }
        }
    });
}

test('empty slots remain decorative through selection, zero matches and CPU row-height changes', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    const grid = page.locator('#items');
    const ironButton = grid.getByRole('button', { name: /Iron Ingot/ });
    await ironButton.click();
    await page.getByRole('heading', { name: 'Iron Ingot', exact: true }).waitFor();
    const empty = grid.locator('li[aria-hidden="true"]').first();
    await empty.waitFor({ state: 'visible' });
    const bounds = await empty.boundingBox();
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    assert.equal(await ironButton.getAttribute('aria-pressed'), 'true', 'empty cells cannot change selection');
    assert.equal(await page.getByRole('tooltip').isVisible(), false, 'empty cells do not create tooltips');
    const search = page.getByRole('searchbox', { name: 'Search resources' });
    await search.fill('no matches');
    assert.equal(await grid.getByRole('button').count(), 0);
    await page.setViewportSize({ width: 1031, height: 844 });
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.ok((await grid.locator('li[aria-hidden="true"]').count()) > 0, 'an empty result still has decorative rows');
    assert.equal(await grid.getByRole('listitem').count(), 0);
    assert.equal(
        await grid.evaluate((list) => list.parentElement.scrollHeight > list.parentElement.clientHeight),
        false
    );
    await search.fill('');
    await ironButton.waitFor();
    assert.equal(await grid.getByRole('button').count(), 2);

    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuResources = page.getByRole('region', { name: 'CPU resources', exact: true });
    const cpuButton = cpuResources.getByRole('button', { name: /Iron Ingot/ });
    await cpuButton.waitFor();
    const trackedHeight = (await cpuButton.boundingBox()).height;
    await cpuResources.locator('li[aria-hidden="true"]').first().waitFor({ state: 'visible' });
    const cpuGeometry = () =>
        cpuResources.evaluate((region) => {
            const cells = [...region.querySelector('ul').children];
            const control = cells[0].querySelector('button');
            return {
                real: control.getBoundingClientRect().height,
                empty: cells
                    .filter((cell) => cell.getAttribute('aria-hidden') === 'true')
                    .map((cell) => cell.getBoundingClientRect().height)
            };
        });
    let measured = await cpuGeometry();
    assert.ok(measured.empty.length > 0, 'The sparse CPU grid must contain decorative slots');
    assert.ok(measured.empty.every((height) => Math.abs(height - measured.real) < 0.03));
    options.cpuDetails['cpu-a'] = { ...cpuWork, hasTrackingInfo: false };
    const updated = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await updated).request());
    measured = await cpuGeometry();
    assert.ok(measured.real < trackedHeight, 'untracked CPU rows preserve their more compact layout');
    assert.ok(measured.empty.length > 0, 'Changing row height must retain decorative slots');
    assert.ok(measured.empty.every((height) => Math.abs(height - measured.real) < 0.03));
    assert.equal(await cpuResources.getByRole('button').count(), 1);
    await clickTab(page, 'Web settings');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await ironButton.waitFor();
    assert.equal(await grid.getByRole('button').count(), 2, 'returning to a hidden grid does not duplicate resources');
});

test('CPU overview keeps names primary and exposes identity without extra detail reads', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': {
            ...cpu,
            name: '§aAssembler',
            isBusy: true,
            supportsPause: true,
            usedStorage: 2048,
            finalOutput: { ...iron, quantity: 12 }
        },
        'cpu-b': { ...cpu, name: '§aAssembler', isBusy: true, isPaused: true, supportsPause: true, usedStorage: -1 },
        'cpu-unnamed': { ...cpu, name: '' }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const first = page.getByRole('link', { name: /Assembler.*cpu-a/ });
    await first.waitFor();
    assert.equal((await first.textContent()).includes('cpu-a'), false, 'opaque identity is not the visible CPU title');
    const row = page.getByRole('listitem').filter({ has: first });
    await row.getByText('Iron Ingot', { exact: true }).waitFor();
    assert.match(await row.textContent(), /2 KB/);
    assert.match(await row.textContent(), /8 KB/);
    await row.getByText('CPU identifier', { exact: true }).click();
    await row.getByText('cpu-a', { exact: true }).waitFor({ state: 'visible' });
    const unnamed = page.locator(`a[href$="/cpus/cpu-unnamed"]`);
    assert.ok((await unnamed.textContent()).trim().length > 0, 'unnamed CPUs have a visible fallback');
    assert.equal((await unnamed.textContent()).includes('cpu-unnamed'), false);
    assert.equal(
        options.requests.some((request) => /\/cpus\/[^/]+$/.test(request.path)),
        false
    );
    await first.focus();
    options.cpus = {
        'cpu-unnamed': options.cpus['cpu-unnamed'],
        'cpu-b': options.cpus['cpu-b'],
        'cpu-a': { ...options.cpus['cpu-a'], usedStorage: 3072 }
    };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await settleResponse(page, (await refreshed).request());
    assert.equal(await first.evaluate((link) => link === document.activeElement), true);
    await page.keyboard.press('Enter');
    await page.getByRole('region', { name: 'CPU resources', exact: true }).waitFor();
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/cpus/cpu-a`);
});

test('CPU overview opens from product, metrics and summary padding while identifier and actions stay independent', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, supportsPause: true, finalOutput: { ...iron, quantity: 12 } }
    };
    await page.goto(`${base}#/grids/${gridA}/cpus`);
    const link = page.getByRole('link', { name: /Assembler.*cpu-a/ });
    const card = page.getByRole('listitem').filter({ has: link });
    await link.waitFor();
    for (const open of [
        () => card.getByText('Iron Ingot', { exact: true }).click(),
        () => card.getByText('8 KB', { exact: true }).click(),
        () => card.click({ position: { x: 6, y: 6 } })
    ]) {
        await open();
        await page.waitForURL(`**#/grids/${gridA}/cpus/cpu-a`, { timeout: 3000 });
        await page.getByRole('region', { name: 'CPU resources', exact: true }).waitFor();
        await clickTab(page, 'CPUs');
        await link.waitFor();
    }
    await card.getByText('CPU identifier', { exact: true }).click();
    await card.getByText('cpu-a', { exact: true }).waitFor({ state: 'visible' });
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/cpus`);
    await card.getByRole('button', { name: /Pause.*cpu-a/ }).click();
    await card.getByRole('button', { name: /Resume.*cpu-a/ }).waitFor();
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/cpus`);
});

for (const appearance of ['light', 'dark']) {
    test(`CPU overview panels keep long names and uncertain controls reachable in ${appearance} mode`, async (t) => {
        const { page, options, base } = await fixture(t);
        options.cpus = {
            'cpu-a': {
                ...cpu,
                name: '§bA long assembler name that must wrap inside its own panel',
                isBusy: true,
                supportsPause: true,
                usedStorage: 1024,
                finalOutput: { ...iron, quantity: 123456 }
            },
            'cpu-b': { ...cpu, name: '§a   ', isBusy: true, supportsPause: true, isPaused: true, usedStorage: -1 },
            unsupported: { ...cpu, name: 'Assembler', isBusy: true, finalOutput: quartz },
            idle: { ...cpu, name: 'Assembler' }
        };
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
        if (appearance === 'dark')
            await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
        await page.goto(`${base}#/grids/${gridA}/cpus`);
        const link = page.locator('a[href$="/cpus/cpu-a"]');
        await link.waitFor();
        const card = page.getByRole('listitem').filter({ has: link });
        for (const viewport of [
            { width: 1280, height: 800 },
            { width: 390, height: 844 }
        ]) {
            await page.setViewportSize(viewport);
            for (const row of await page
                .getByRole('listitem')
                .filter({ has: page.locator('a[href*="/cpus/"]') })
                .all()) {
                const geometry = await row.evaluate((node) => ({
                    width: node.clientWidth,
                    content: node.scrollWidth,
                    height: node.clientHeight,
                    contentHeight: node.scrollHeight
                }));
                assert.ok(geometry.content <= geometry.width + 1, 'card content does not scroll horizontally');
                assert.ok(
                    geometry.contentHeight <= geometry.height + 1,
                    'cards grow instead of introducing internal scroll areas'
                );
                for (const button of await row.getByRole('button').all()) {
                    await button.scrollIntoViewIfNeeded();
                    const bounds = await button.boundingBox();
                    assert.ok(
                        bounds.x >= 0 && bounds.x + bounds.width <= viewport.width,
                        'each work control stays reachable'
                    );
                }
            }
        }
        options.fault = { operation: 'pause', status: 'NETWORK_ERROR' };
        const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
        await card.getByRole('button', { name: /(?:Pause|Wstrzymaj).*cpu-a/ }).click();
        await settleResponse(page, (await refreshed).request());
        await card
            .getByRole('status')
            .filter({ hasText: /unknown|nieznany/ })
            .waitFor({ state: 'visible' });
        for (const button of await card.getByRole('button').all()) assert.equal(await button.isDisabled(), true);
        const after = await card.evaluate((node) => ({ height: node.clientHeight, content: node.scrollHeight }));
        assert.ok(after.content <= after.height + 1, 'an uncertainty notice expands the panel without clipping');
        await link.scrollIntoViewIfNeeded();
        if (process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await page.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, `cpu-overview-mobile-${appearance}.png`)
            });
            await card.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, `cpu-overview-card-${appearance}.png`)
            });
        }
        assert.equal(
            options.requests.some((request) => /\/cpus\/[^/]+$/.test(request.path)),
            false
        );
    });
}

// Public geometry seam: the CPU overview shares terminal alignment and keeps scrolling inside its viewport.
test('CPU overview aligns with the terminal and scrolls only real processor panels', async (t) => {
    const { page, options, base } = await fixture(t);
    const many = Object.fromEntries(
        Array.from({ length: 18 }, (_, index) => [`cpu-${index}`, { ...cpu, name: `Processor ${index}` }])
    );
    const measure = async (locator) =>
        locator.evaluate((node) => {
            const rect = node.getBoundingClientRect();
            const style = getComputedStyle(node);
            return {
                x: rect.x,
                y: innerWidth <= 600 ? rect.y - node.closest('#window').getBoundingClientRect().y : rect.y,
                height: rect.height,
                right: rect.right,
                fontSize: style.fontSize,
                fontWeight: style.fontWeight,
                fontFamily: style.fontFamily,
                color: style.color
            };
        });
    for (const viewport of [
        { width: 1184, height: 900 },
        { width: 390, height: 844 },
        { width: 1184, height: 540 },
        { width: 390, height: 540 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const heading = await measure(page.getByRole('heading', { name: 'Terminal', exact: true }));
        const resources = await measure(page.getByRole('region', { name: 'Resources', exact: true }));
        const items = await measure(page.locator('#items'));
        options.cpus = many;
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Processor 0 .*cpu-0/ }).waitFor();
        const cpuHeading = page.getByRole('heading', { name: 'CPUs', exact: true });
        const actual = await measure(cpuHeading);
        for (const key of viewport.width > 600 ? ['x', 'y'] : ['x'])
            assert.ok(
                Math.abs(actual[key] - heading[key]) < 0.1,
                `${key} heading position matches at ${viewport.width}x${viewport.height}`
            );
        for (const key of ['fontSize', 'fontWeight', 'fontFamily', 'color']) assert.equal(actual[key], heading[key]);
        assert.equal(await page.getByRole('link', { name: 'Back to resources', exact: true }).count(), 0);
        const region = page.getByRole('region', { name: 'CPUs', exact: true });
        const bounds = await measure(region);
        for (const key of viewport.width > 600 ? ['x', 'y', 'right'] : ['x', 'right'])
            assert.ok(Math.abs(bounds[key] - resources[key]) < 0.1, `${key} scroll viewport matches`);
        assert.ok(actual.y >= 0 && bounds.y >= actual.y + actual.height, 'CPU list stays below its heading');
        const cards = region.getByRole('listitem');
        assert.equal(await cards.count(), 18);
        const first = await measure(cards.first());
        assert.ok(Math.abs(first.x - items.x) < 0.1, 'left card edge matches items');
        const right = await cards.evaluateAll((nodes) =>
            Math.max(...nodes.map((node) => node.getBoundingClientRect().right))
        );
        assert.ok(Math.abs(right - items.right) < 0.1, 'right card edge matches items');
        await cards.last().getByRole('link').scrollIntoViewIfNeeded();
        assert.ok(await region.evaluate((node) => node.scrollTop > 0));
        assert.deepEqual(await measure(cpuHeading), actual, 'heading stays fixed while cards scroll');
        assert.deepEqual(await page.evaluate(() => ({ x: scrollX, y: scrollY })), { x: 0, y: 0 });
        assert.ok(
            await page.evaluate(
                () =>
                    document.documentElement.scrollWidth <= innerWidth &&
                    document.documentElement.scrollHeight <= innerHeight
            )
        );
        options.cpus = { 'cpu-0': many['cpu-0'] };
        const updated = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
        await poll(page);
        await settleResponse(page, (await updated).request());
        assert.equal(await cards.count(), 1, 'sparse overview has no decorative CPU placeholders');
    }
});

// Public geometry seam: the selected CPU keeps the same terminal header and resource viewport.
test('selected CPU aligns its heading search and resource viewport with the main terminal', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = { 'cpu-a': { ...cpu, isBusy: true, supportsPause: true } };
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        supportsPause: true,
        items: Array.from({ length: 80 }, (_, index) => ({
            ...cpuWork.items[0],
            registryNamespace: 'test',
            registryPath: `part_${index}`,
            displayName: `Part ${String(index).padStart(3, '0')}`
        }))
    };
    const measure = (locator) =>
        locator.evaluate((node) => {
            const r = node.getBoundingClientRect(),
                s = getComputedStyle(node);
            return {
                x: r.x,
                y: r.y,
                right: r.right,
                height: r.height,
                fontSize: s.fontSize,
                fontWeight: s.fontWeight,
                fontFamily: s.fontFamily,
                color: s.color
            };
        });
    for (const viewport of [
        { width: 1184, height: 900 },
        { width: 390, height: 844 },
        { width: 1184, height: 540 }
    ]) {
        await page.setViewportSize(viewport);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        const normalHeading = await measure(page.getByRole('heading', { name: 'Terminal', exact: true }));
        const normalSearch = await measure(page.getByRole('searchbox', { name: 'Search resources', exact: true }));
        const normalScroll = await measure(page.getByRole('region', { name: 'Resources', exact: true }));
        await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
        const region = page.getByRole('region', { name: 'CPU resources', exact: true });
        await region.getByRole('button', { name: /Part 000/ }).waitFor();
        const heading = page.getByRole('heading', { name: 'Assembler', level: 2, exact: true });
        const actualHeading = await measure(heading);
        for (const key of ['x', 'y'])
            assert.ok(Math.abs(actualHeading[key] - normalHeading[key]) < 0.1, `${key} heading matches`);
        for (const key of ['fontSize', 'fontWeight', 'fontFamily', 'color'])
            assert.equal(actualHeading[key], normalHeading[key]);
        const search = page.getByRole('searchbox', { name: 'Search CPU resources', exact: true });
        const actualSearch = await measure(search);
        for (const key of ['y', 'right', 'height'])
            assert.ok(Math.abs(actualSearch[key] - normalSearch[key]) < 0.1, `${key} search matches`);
        const actualScroll = await measure(region);
        for (const key of ['x', 'y', 'right'])
            assert.ok(Math.abs(actualScroll[key] - normalScroll[key]) < 0.1, `${key} resources match`);
        assert.equal(await page.getByRole('link', { name: 'Back to CPUs', exact: true }).count(), 0);
        await region.getByRole('button').last().scrollIntoViewIfNeeded();
        assert.ok(await region.evaluate((node) => node.scrollTop > 0));
        assert.deepEqual(await measure(heading), actualHeading);
        assert.deepEqual(await measure(search), actualSearch);
        await clickTab(page, 'CPUs');
        await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
    }
    options.cpus['cpu-a'].name = 'A very long processor name that must not push the search outside the terminal';
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const longHeading = page.getByRole('heading', { name: options.cpus['cpu-a'].name, level: 2, exact: true });
    await longHeading.waitFor();
    const search = page.getByRole('searchbox', { name: 'Search CPU resources', exact: true });
    await search.fill('Part 000');
    assert.equal(await page.getByRole('region', { name: 'CPU resources', exact: true }).getByRole('button').count(), 1);
    const nameBox = await measure(longHeading),
        searchBox = await measure(search);
    assert.ok(nameBox.y + nameBox.height <= searchBox.y, 'search stays below the long title on mobile');
    const toolsBox = await page.getByRole('button', { name: 'Filters and sorting', exact: true }).boundingBox();
    assert.ok(nameBox.right <= toolsBox.x, 'long title does not overlap the mobile panel buttons');
    assert.ok(searchBox.right - searchBox.x >= 80, 'search remains usable on mobile');
    assert.ok(
        await page.evaluate(
            () =>
                document.documentElement.scrollWidth <= innerWidth &&
                document.documentElement.scrollHeight <= innerHeight
        )
    );
});

// Home discovery must not enumerate crafting CPUs until the user opens a network's CPU view.
test('Home initial discovery does not request CPU lists', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
    await page.waitForLoadState('networkidle');
    if (process.env.UI_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'home-network-only.png') });
    }
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
});

// Refreshing discovery or opening the chooser must never fan out to per-network CPU reads.
test('Home manual automatic and icon preference refreshes do not request CPU lists', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    await atlasFixture(page, options);
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
    await page.waitForLoadState('networkidle');
    options.requests.length = 0;
    const manual = page.waitForResponse((response) => response.url().endsWith('/api/grids'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await settleResponse(page, (await manual).request());
    await poll(page);
    await page.waitForLoadState('networkidle');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridB) })
        .waitFor();
    await poll(page);
    await page.waitForLoadState('networkidle');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    await page.keyboard.press('Escape');
    for (const preference of ['both', 'names']) {
        await clickTab(page, 'Web settings');
        const display = page.getByRole('combobox', { name: 'Terminal display', exact: true });
        await page.waitForFunction(
            (select) => !select.querySelector('option[value="both"]').disabled,
            await display.elementHandle()
        );
        await display.selectOption(preference);
        await clickTab(page, 'Home');
        await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
        await page.waitForLoadState('networkidle');
        assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    }
});

test('Home keyboard chooser selects a network and CPUs only load for the selected network', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.cpusByGrid = {
        [gridA]: { 'cpu-a': { ...cpu, name: 'Alpha processor' } },
        [gridB]: { 'cpu-b': { ...cpu, name: 'Beta processor' } }
    };
    await page.goto(base);
    const choose = page.getByRole('button', { name: 'Choose a network', exact: true });
    await choose.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    assert.ok((await dialog.boundingBox()).height < page.viewportSize().height * 0.9);
    await page.keyboard.press('Escape');
    assert.equal(await choose.evaluate((node) => node === document.activeElement), true);
    await choose.click();
    await dialog.getByRole('link', { name: new RegExp(gridB) }).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await dialog.isVisible(), false);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
    await clickTab(page, 'CPUs');
    await page.getByRole('link', { name: /Beta processor/ }).waitFor();
    const cpuRequests = options.requests.filter((request) => request.path.endsWith('/cpus'));
    assert.ok(cpuRequests.length > 0);
    assert.ok(cpuRequests.every((request) => request.path === `/api/grids/${gridB}/cpus`));
    await clickTab(page, 'Home');
    await choose.waitFor();
    await page.waitForLoadState('networkidle');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, cpuRequests.length);
});

test('Home keeps denied discovery explicit in the chooser and supports retry', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    await page.goto(base);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('link', { name: new RegExp(gridA) }).waitFor();
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    await dialog.getByRole('status').waitFor();
    assert.equal(await dialog.getByRole('link').count(), 0);
    options.gridError = null;
    await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
    await dialog.getByRole('link', { name: new RegExp(gridA) }).waitFor();
    await page.waitForLoadState('networkidle');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
});

// An empty successful discovery still needs an explicit recovery action with polling disabled.
test('Home can retry empty discovery without automatic refresh', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.empty = true;
    await page.goto(base);
    await page
        .getByRole('status')
        .filter({ hasText: /No accessible networks/ })
        .waitFor();
    options.empty = false;
    await page.getByRole('button', { name: 'Try again', exact: true }).click({ timeout: 2000 });
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridA) })
        .waitFor();
});

test('Home and its chooser contain long identities on mobile in light and dark appearances', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 844 } });
    const longName = 'LongUnbrokenIdentity'.repeat(8);
    options.grids = [
        { key: gridA, name: longName, owner: 'Owner', cpuCount: 1, accessSources: {} },
        { key: gridB, name: longName, owner: 'Owner', cpuCount: 1, accessSources: {} }
    ];
    options.settings[gridA].name = longName;
    options.settings[gridB].name = longName;
    for (const appearance of ['light', 'dark']) {
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
        await clickTab(page, 'Home');
        await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        assert.ok(await page.locator('#workspace').evaluate((node) => node.scrollWidth <= node.clientWidth));
        await openMobilePane(page, 'Network');
        await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
        const dialog = page.getByRole('dialog');
        const firstChoice = dialog
            .locator('.network-choice')
            .filter({ has: page.locator(`a[href="#/grids/${gridA}/items"]`) });
        if (!(await firstChoice.getByText(gridA, { exact: true }).isVisible()))
            await firstChoice.getByText('Network identifier', { exact: true }).click();
        await firstChoice.getByText(gridA, { exact: true }).waitFor();
        const network = dialog.locator(`a[href="#/grids/${gridB}/items"]`);
        await network.focus();
        options.grids = options.grids.toReversed();
        await poll(page);
        assert.equal(await network.evaluate((node) => node === document.activeElement), true);
        assert.equal(await dialog.getByText(gridA, { exact: true }).isVisible(), true);
        assert.ok(await dialog.evaluate((node) => node.scrollWidth <= node.clientWidth));
        const bounds = await dialog.boundingBox();
        assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
        if (process.env.UI_SCREENSHOT_DIR) {
            await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
            await page.screenshot({
                path: path.join(process.env.UI_SCREENSHOT_DIR, `home-chooser-mobile-${appearance}.png`)
            });
        }
        await page.keyboard.press('Escape');
        if (page.viewportSize().width <= 600)
            await page.getByRole('button', { name: 'Network', exact: true }).waitFor();
        else await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
    }
});

test('Home keeps failed logout visible and allows an explicit retry', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    options.logoutError = 'INTERNAL_ERROR';
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByRole('alert').waitFor({ timeout: 2000 });
    assert.equal(options.requests.filter((request) => request.path === '/api/auth/logout').length, 1);
    options.logoutError = null;
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/auth/logout').length, 2);
});

// Public settings/HTTP seam: the server-backed name is shared presentation, independent of tracking.
test('current network preserves desktop alignment and shares the second phone row with account actions', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    for (const width of [1280, 800, 560, 390]) {
        await page.setViewportSize({ width, height: 844 });
        let previous;
        for (const name of ['Factory', 'VeryLongNetworkName'.repeat(6)]) {
            options.settings[gridA].name = name;
            await page.goto(`${base}#/grids/${gridA}/settings`);
            await page.reload();
            const network = page.locator('#app header').getByText(name, { exact: true });
            await network.waitFor();
            const brand = await page.getByRole('heading', { name: 'AE2 Web Integration' }).boundingBox();
            const account = await page.getByRole('button', { name: 'Log out', exact: true }).boundingBox();
            const label = await network.boundingBox();
            const header = await page.locator('#app header').boundingBox();
            if (width === 1280)
                assert.ok(
                    await page
                        .getByText(options.username, { exact: true })
                        .evaluate((node) => node.scrollWidth <= node.clientWidth),
                    'The account name must use available header space before being shortened'
                );
            if (width > 600) {
                assert.ok(
                    Math.abs(label.y + label.height / 2 - brand.y - brand.height / 2) < 2,
                    `brand alignment at ${width}`
                );
                assert.ok(
                    Math.abs(label.y + label.height / 2 - account.y - account.height / 2) < 2,
                    `account alignment at ${width}`
                );
                assert.ok(label.x >= brand.x + brand.width && label.x + label.width <= account.x);
                assert.ok(
                    label.x - (brand.x + brand.width) <= 24,
                    'The brand must not reserve empty space after its visible text'
                );
            } else {
                const user = await page.getByText(options.username, { exact: true }).boundingBox();
                const menu = await page.getByRole('button', { name: 'Navigation', exact: true }).boundingBox();
                assert.ok(Math.abs(user.y + user.height / 2 - menu.y - menu.height / 2) < 2);
                assert.ok(brand.x >= menu.x + menu.width && user.x >= brand.x + brand.width);
                assert.ok(account.y >= brand.y + brand.height);
                assert.ok(Math.abs(label.y + label.height / 2 - account.y - account.height / 2) < 2);
                assert.ok(label.x + label.width <= account.x);
            }
            assert.ok(label.width > 0 && account.x + account.width <= width);
            if (previous)
                assert.deepEqual(
                    { brand, account, header },
                    previous,
                    'A long network name cannot move the surrounding header'
                );
            previous = { brand, account, header };
        }
    }
});

test('network identity uses its name or full identifier, with scoped header and shared tooltip', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.settings[gridB].name = 'Remote factory';
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const header = page.locator('#app header');
    const current = header.getByText(gridA, { exact: true });
    await current.waitFor({ timeout: 2000 });
    assert.doesNotMatch(await header.innerText(), /Alpha/);
    assert.equal(await current.getAttribute('title'), null);
    await current.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor();
    assert.match(await tooltip.innerText(), /Current network/);
    assert.match(await tooltip.innerText(), new RegExp(gridA));
    await page.keyboard.press('Escape');
    assert.equal(await tooltip.isVisible(), false);
    for (const route of ['cpus', 'cpus/cpu-a', 'history', 'history/1', 'plans/1', 'settings']) {
        await page.goto(`${base}#/grids/${gridA}/${route}`);
        await current.waitFor();
        assert.equal(await current.innerText(), gridA);
    }
    for (const route of ['/', '/web-settings', '/server-settings', '/about']) {
        await page.goto(`${base}#${route}`);
        assert.equal(await current.isVisible(), false, `Network context is not shown on ${route}`);
    }
    await page.goto(`${base}#/`);
    const panel = page.locator('#home-network-panel');
    await panel.getByText(gridA, { exact: true }).waitFor();
    assert.equal(await panel.getByText(gridA, { exact: true }).count(), 1);
    assert.doesNotMatch(await panel.innerText(), /Alpha/);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    const dialog = page.getByRole('dialog');
    assert.doesNotMatch(await dialog.innerText(), /Alpha|Beta/);
    await dialog.getByRole('link', { name: /Remote factory/ }).click();
    await header.getByText('Remote factory', { exact: true }).waitFor();
    await clickTab(page, 'Network');
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Renamed network');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await header.getByText('Renamed network', { exact: true }).waitFor();
    await page
        .getByRole('region', { name: 'Network details', exact: true })
        .getByText('Beta', { exact: true })
        .waitFor();
    await header.getByText('Renamed network', { exact: true }).hover();
    await tooltip.waitFor();
    assert.match(await tooltip.innerText(), new RegExp(gridB));
    await page.keyboard.press('Escape');
    await name.fill('');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await header.getByText(gridB, { exact: true }).waitFor();
    await clickTab(page, 'Home');
    await panel.getByText(gridB, { exact: true }).waitFor();
});

test('Network details save and clear a name without resending tracking, and update Home immediately', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('  Main factory  ', { timeout: 2000 });
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.equal(await name.inputValue(), 'Main factory');
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, {
        name: 'Main factory'
    });
    await page.getByRole('button', { name: 'Dismiss notification', exact: true }).click();
    const navigation = page.locator('nav').getByRole('link');
    assert.deepEqual((await navigation.allTextContents()).slice(0, 3), ['Home', 'Network', 'Terminal']);
    await clickTab(page, 'Home');
    const panel = page.locator('#home-network-panel');
    await panel.getByText('Main factory', { exact: true }).waitFor();
    assert.equal(await panel.getByText('Owner: Alpha', { exact: true }).count(), 0);
    await panel.getByText(gridA, { exact: true }).waitFor();
    await panel.getByRole('link', { name: 'Network details', exact: true }).click();
    await name.fill('');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, { name: '' });
    await page.reload();
    await name.waitFor();
    assert.equal(await name.inputValue(), '');
});

test('tracking notices use the current network, update after saving, and retain previously recorded history', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.settings[gridA].isTracked = false;
    options.settings[gridB].isTracked = true;
    await page.goto(`${base}#/grids/${gridA}/items`);
    const enable = page.getByRole('link', { name: 'Enable tracking', exact: true });
    await enable.waitFor({ timeout: 2000 });
    assert.equal(await enable.getAttribute('href'), `#/grids/${gridA}/settings`);
    await enable.click();
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).check();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await page.getByRole('button', { name: 'Dismiss notification', exact: true }).click();
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.count(), 0);
    await clickTab(page, 'Network');
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).uncheck();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await clickTab(page, 'History');
    await page.getByRole('link', { name: /Iron Ingot.*#1/ }).waitFor();
    await enable.waitFor();
    assert.equal(await enable.getAttribute('href'), `#/grids/${gridA}/settings`);
    await page
        .getByRole('region', { name: 'History', exact: true })
        .getByText(/tracking is disabled/i)
        .waitFor();
    await clickTab(page, 'Home');
    await page.getByRole('heading', { name: 'Home', exact: true }).waitFor();
    assert.equal(await enable.count(), 0);
    await page.goto(`${base}#/grids/${gridB}/cpus`);
    await page
        .getByRole('link', { name: /Assembler/ })
        .first()
        .waitFor();
    assert.equal(await enable.count(), 0);
});

// A discovery snapshot predating a PATCH cannot undo the confirmed metadata in another view.
test('late discovery cannot undo a confirmed name or tracking save', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Factory');
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).check();
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/grids', (route) => release(route));
    await poll(page);
    const oldDiscovery = await captured;
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await oldDiscovery.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            status: 'OK',
            data: [
                { key: gridA, name: '', owner: 'Alpha', cpuCount: 2, isTrackingEnabled: false, accessSources: {} },
                { key: gridB, name: '', owner: 'Beta', cpuCount: 1, isTrackingEnabled: false, accessSources: {} }
            ]
        })
    });
    await settleResponse(page, oldDiscovery.request());
    await clickTab(page, 'Home');
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Factory', exact: true }).waitFor();
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('link', { name: 'Enable tracking', exact: true }).count(), 0);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 1);
});

test('Network keeps field drafts across grids, refreshes unedited fields, and rejects invalid names before HTTP', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await name.fill('Draft A');
    options.settings[gridA].isTracked = true;
    const read = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await settleResponse(page, (await read).request());
    assert.equal(await name.inputValue(), 'Draft A');
    assert.equal(await tracking.isChecked(), true);
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await name.fill('Draft B');
    await page.goBack();
    await name.waitFor();
    assert.equal(await name.inputValue(), 'Draft A');
    const save = page.getByRole('button', { name: 'Save settings', exact: true });
    for (const invalid of ['🙂'.repeat(65), 'Invalid\u0001name']) {
        await name.fill(invalid);
        assert.equal(await save.isDisabled(), true);
    }
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 0);
    await name.fill('Draft A');
    await save.click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, { name: 'Draft A' });
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await name.waitFor();
    assert.equal(await name.inputValue(), 'Draft B');
});

test('Network pending and uncertain name saves retain source identity and notify Home after navigation', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    const name = page.getByRole('textbox', { name: 'Network name', exact: true });
    await name.fill('Requested');
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/settings', (route) =>
        route.request().method() === 'PATCH' ? release(route) : route.continue()
    );
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    const pending = await captured;
    await clickTab(page, 'Home');
    options.settings[gridA] = { ...options.settings[gridA], name: 'Canonical', isTracked: true };
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: options.settings[gridA] })
    });
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Canonical', exact: true }).waitFor();
    await page.unroute('**/settings');
    await clickTab(page, 'Network');
    await name.fill('Unconfirmed');
    options.fault = { operation: 'settings', status: 'INTERNAL_ERROR' };
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /not confirmed/i })
        .waitFor();
    await page.goto(`${base}#/grids/${gridB}/settings`);
    await name.waitFor();
    assert.equal(await name.inputValue(), '');
    await page.goBack();
    await page
        .getByRole('status')
        .filter({ hasText: /not confirmed/i })
        .waitFor();
    assert.equal(await name.inputValue(), 'Unconfirmed');
    assert.equal(
        options.requests.filter((request) => request.method === 'PATCH').length,
        1,
        'The intercepted first save is outside fixture logging; failed PATCH must not replay'
    );
    options.fault = null;
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .filter({ hasText: 'Unconfirmed' })
        .waitFor();
    assert.deepEqual(options.requests.filter((request) => request.method === 'PATCH').at(-1).body, {
        name: 'Unconfirmed'
    });
});

test('Network groups explicit people with collapsed source lists and keeps disclosure and focus during polling', async (t) => {
    const { page, options, base } = await fixture(t);
    const first = { uuid: 'person-a', name: 'Alice' };
    const second = { uuid: 'person-b', name: 'Bob' };
    const source = (player, x) => ({
        player,
        kind: 'security_terminal',
        reason: 'security_card',
        position: { dimensionId: 'minecraft:overworld', x, y: 64, z: -32 },
        side: null
    });
    options.accessSources = {
        [first.uuid]: [source(first, 120)],
        [second.uuid]: Array.from({ length: 18 }, (_, x) => source(second, x + 200))
    };
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.getByText('Bob', { exact: true }).waitFor();
    const alice = page.locator('details').filter({ has: page.getByText('Alice', { exact: true }) });
    const bob = page.locator('details').filter({ has: page.getByText('Bob', { exact: true }) });
    assert.equal(await page.getByText(/minecraft:overworld/).count(), 0);
    await bob.locator('summary').click();
    await bob.getByRole('listitem').last().waitFor();
    assert.equal(await bob.getByRole('listitem').count(), 18);
    assert.equal(await alice.getByRole('listitem').count(), 0);
    await bob.locator('summary').focus();
    options.accessSources = {
        ...options.accessSources,
        [second.uuid]: [...options.accessSources[second.uuid], source(second, 999)]
    };
    const read = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await settleResponse(page, (await read).request());
    assert.equal(await bob.getByRole('listitem').count(), 19);
    assert.equal(await bob.locator('summary').evaluate((node) => node === document.activeElement), true);
    await clickTab(page, 'Home');
    assert.equal(
        await page
            .locator('#home, #home-network-panel, #selected-network')
            .getByText(/minecraft:overworld/)
            .count(),
        0
    );
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    assert.equal(
        await page
            .getByRole('dialog')
            .getByText(/minecraft:overworld/)
            .count(),
        0
    );
});

test('Network details scroll long access lists under a fixed heading and keep mobile settings usable in both appearances', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 844 } });
    options.settings[gridA].name = 'LongNetworkName'.repeat(8);
    const player = { uuid: 'source-owner', name: 'LongPlayerName'.repeat(8) };
    options.accessSources = {
        [player.uuid]: Array.from({ length: 30 }, (_, x) => ({
            player,
            kind: 'security_terminal',
            reason: 'security_card',
            position: { dimensionId: 'minecraft:overworld', x, y: 64, z: -32 },
            side: null
        }))
    };
    for (const appearance of ['light', 'dark']) {
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
        await page.goto(`${base}#/grids/${gridA}/settings`);
        const heading = page.getByRole('heading', { name: 'Network details', exact: true });
        await heading.waitFor();
        const current = page.locator('#app header').getByText(options.settings[gridA].name, { exact: true });
        await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
        await page.evaluate(
            () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
        );
        await current.focus();
        const tooltip = page.getByRole('tooltip');
        await tooltip.waitFor();
        assert.ok((await tooltip.innerText()).includes(options.settings[gridA].name));
        for (const target of [
            current,
            page.locator('#app header').getByRole('button', { name: 'Log out', exact: true })
        ]) {
            const box = await target.boundingBox();
            assert.ok(box.width > 0 && box.x >= 0 && box.x + box.width <= 390);
        }
        await page.keyboard.press('Tab');
        const logout = page.locator('#app header').getByRole('button', { name: 'Log out', exact: true });
        assert.ok(await logout.evaluate((node) => node === document.activeElement));
        await tooltip.getByText(await logout.getAttribute('aria-label'), { exact: true }).waitFor();
        assert.equal((await tooltip.innerText()).includes(options.settings[gridA].name), false);
        await page.keyboard.press('Escape');
        assert.equal(await tooltip.isVisible(), false);
        const before = await heading.boundingBox();
        const person = page.locator('details').filter({ has: page.getByText(player.name, { exact: true }) });
        if (!(await person.evaluate((node) => node.open))) await person.locator('summary').click();
        await person.getByRole('listitem').last().waitFor();
        const name = page.getByRole('textbox', { name: 'Network name', exact: true });
        await name.scrollIntoViewIfNeeded();
        assert.deepEqual(await heading.boundingBox(), before);
        await name.fill(`Mobile ${appearance}`);
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: `Mobile ${appearance}` })
            .waitFor();
        const region = page.getByRole('region', { name: 'Network details', exact: true });
        assert.ok(await region.evaluate((node) => node.scrollWidth <= node.clientWidth));
        assert.ok(
            await page.evaluate(
                () =>
                    document.documentElement.scrollWidth <= innerWidth &&
                    document.documentElement.scrollHeight <= innerHeight
            )
        );
    }
});

for (const height of [844, 600]) {
    test(`icon and tracking notices coexist without hiding the terminal at mobile height ${height}`, async (t) => {
        const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height } });
        await atlasFixture(page, options);
        await page.addInitScript(() =>
            localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ terminalDisplay: 'names' }))
        );
        await page.goto(`${base}#/grids/${gridA}/items`);
        const icons = page.getByRole('button', { name: 'Enable icons', exact: true });
        const tracking = page.getByRole('link', { name: 'Enable tracking', exact: true });
        await icons.waitFor();
        await tracking.waitFor();
        const search = page.getByRole('searchbox', { name: 'Search resources', exact: true });
        await search.fill('Iron');
        const region = page.getByRole('region', { name: 'Resources', exact: true });
        const bounds = await region.boundingBox();
        assert.ok(bounds.height >= 44, `Notices must leave a usable resource viewport at ${height}px`);
        assert.ok(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight));
        await page.getByRole('button', { name: /Iron Ingot/ }).click();
        await closeMobilePane(page);
        const information = page.getByRole('region', { name: 'Information', exact: true });
        await information.focus();
        await page.keyboard.press('End');
        // Finish the End-key scroll before focus starts another browser scroll.
        await page.waitForFunction(
            (node) => node.scrollTop > 0 && node.scrollTop + node.clientHeight >= node.scrollHeight - 1,
            await information.elementHandle()
        );
        for (const control of [icons, tracking]) {
            await control.focus();
            await page.waitForFunction(
                ([control, region]) => {
                    const box = control.getBoundingClientRect(),
                        area = region.getBoundingClientRect();
                    return box.y >= area.y && box.bottom <= area.bottom;
                },
                [await control.elementHandle(), await information.elementHandle()],
                { timeout: 2000 }
            );
        }
        await page.getByRole('button', { name: 'Dismiss icon suggestion', exact: true }).click();
        assert.equal(await icons.count(), 0);
        await tracking.waitFor();
        assert.equal(await tracking.getAttribute('href'), `#/grids/${gridA}/settings`);
        const searchBounds = await search.boundingBox();
        assert.ok(searchBounds.y >= 0 && searchBounds.y + searchBounds.height <= height);
        await tracking.focus();
        await page.keyboard.press('Enter');
        await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).waitFor();
        assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/settings`);
    });
}

test('Network refetch publishes remote name and tracking changes with polling disabled', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'Enable tracking', exact: true }).waitFor();
    options.settings[gridA] = { ...options.settings[gridA], name: 'Remote factory', isTracked: true };
    await clickTab(page, 'Network');
    await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
    assert.equal(await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).isChecked(), true);
    await clickTab(page, 'Home');
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Remote factory', exact: true }).waitFor();
    await clickTab(page, 'Terminal');
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('link', { name: 'Enable tracking', exact: true }).count(), 0);
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});
// Public browser geometry: center the main frame, keeping its attachments on screen.
for (const deviceScaleFactor of [1, 1.25, 1.5]) {
    test(`main terminal centers independently of its attachments at DPR ${deviceScaleFactor}`, async (t) => {
        const { page, base } = await fixture(t, '', { deviceScaleFactor }, { mockClock: false });
        await seedAutomaticRefresh(page, base, false);
        for (const width of [1920, 1600, 1500, 1400, 1184, 800, 600, 390]) {
            await page.setViewportSize({ width, height: 900 });
            await page.goto(`${base}#/grids/${gridA}/items`);
            await page.getByRole('button', { name: /Iron Ingot/ }).click();
            await page.evaluate(() => document.fonts.ready);
            const frame = await page.locator('#window').boundingBox();
            assert.ok(frame.x >= 0 && frame.x + frame.width <= width, `main frame fits at ${width}`);
            if (width > 600) {
                const details = await page.getByRole('complementary').filter({ visible: true }).boundingBox();
                const tools = await page.getByRole('group', { name: 'Resources', exact: true }).boundingBox();
                assert.ok(details.x >= 0 && details.x + details.width <= width, `attached details fit at ${width}`);
                assert.ok(tools.x >= 0 && tools.x + tools.width <= frame.x + 1, `tools fit beside frame at ${width}`);
                assert.ok(Math.abs(details.x - (frame.x + frame.width)) <= 3, `details remain docked at ${width}`);
            } else {
                const details = page.getByRole('dialog', { name: 'Resource details', exact: true });
                const sheet = await details.boundingBox();
                assert.ok(sheet.x >= 0 && sheet.x + sheet.width <= width, `details sheet fits at ${width}`);
                assert.ok(sheet.y >= 0 && sheet.y + sheet.height <= 900, 'details stay inside the viewport');
                await closeMobilePane(page);
                assert.deepEqual(
                    await page.locator('#window').boundingBox(),
                    frame,
                    'closing details preserves the terminal frame'
                );
                await openMobilePane(page, 'Filters and sorting');
                const panel = await page
                    .getByRole('dialog', { name: 'Filters and sorting', exact: true })
                    .boundingBox();
                const tools = await page.getByRole('group', { name: 'Resources', exact: true }).boundingBox();
                assert.ok(
                    tools.x >= panel.x && tools.x + tools.width <= panel.x + panel.width,
                    'filters stay inside their sheet'
                );
                assert.ok(tools.y >= panel.y && tools.y + tools.height <= panel.y + panel.height);
                await closeMobilePane(page);
                assert.deepEqual(
                    await page.locator('#window').boundingBox(),
                    frame,
                    'closing filters preserves the terminal frame'
                );
            }
            if (width >= 1600) {
                assert.ok(
                    Math.abs(frame.x + frame.width / 2 - width / 2) < 1,
                    `main frame center ${frame.x + frame.width / 2} matches viewport center ${width / 2}`
                );
            }
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        }
        await page.setViewportSize({ width: 1920, height: 900 });
        for (const name of ['CPUs', 'History', 'Network', 'Web settings', 'About', 'Home', 'Terminal']) {
            await page.getByRole('navigation').getByRole('link', { name, exact: true }).click();
            const frame = page.locator(name === 'Home' ? '#home' : '#window');
            await frame.waitFor();
            const bounds = await frame.boundingBox();
            assert.ok(Math.abs(bounds.x + bounds.width / 2 - 960) < 1, `${name} frame retains shared center`);
            const header = await page.getByRole('navigation').boundingBox();
            assert.ok(Math.abs(header.x + header.width / 2 - 960) < 1, `${name} header retains shared center`);
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        }
    });
}

// Public browser/HTTP seam: large histories remain bounded while every resource and exact interval is reachable.
test('large history analysis paginates resources and exact intervals without dropping the tail', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    const started = historyDetail.timeStarted;
    options.historyDetail = {
        ...historyDetail,
        timeDone: started + 200000,
        interfaceShare: [
            {
                ...historyDetail.interfaceShare[0],
                location: Array.from({ length: 60 }, (_, index) => ({
                    dimensionId: 'test:world',
                    x: index,
                    y: 1,
                    z: 2
                }))
            }
        ],
        items: Array.from({ length: 61 }, (_, index) => ({
            ...historyDetail.items[0],
            displayName: `Resource ${String(index).padStart(2, '0')}`,
            craftedTotal: index + 1,
            timeSpentOn: (index + 1) * 1000,
            timings: Array.from({ length: index === 60 ? 10001 : 3 }, (_, interval) => ({
                started: started + interval * 10,
                ended: started + interval * 10 + 5
            }))
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const resources = page.getByRole('list', { name: 'Resources', exact: true });
    await resources.waitFor({ timeout: 3000 });
    assert.equal(await resources.getByRole('listitem').count(), 25);
    assert.equal(await resources.getByRole('heading').first().innerText(), 'Resource 60');
    assert.equal(await resources.locator('time').count(), 0, 'exact intervals stay unmounted until expansion');
    const pagination = page.getByRole('navigation', { name: 'History pages', exact: true });
    await pagination.getByRole('button', { name: 'Next page', exact: true }).click();
    await pagination.getByRole('button', { name: 'Next page', exact: true }).click();
    assert.equal(await resources.getByRole('heading').count(), 11);
    assert.equal(await resources.getByRole('heading').last().innerText(), 'Resource 00');
    await page.getByRole('searchbox', { name: 'Search history', exact: true }).fill('Resource 60');
    const target = resources.getByRole('region', { name: 'Resource 60', exact: true });
    const overview = target.getByRole('img', { name: 'Activity overview', exact: true });
    await page.waitForFunction(() => document.querySelector('[aria-label="Activity overview"][aria-busy="false"] svg'));
    assert.ok(
        (await overview.locator('*').count()) < 20,
        'overview geometry is bounded despite ten thousand intervals'
    );
    await target.locator('summary').click();
    const intervals = target.getByRole('list', { name: 'Exact intervals', exact: true });
    await intervals.waitFor();
    assert.equal(await intervals.getByRole('listitem').count(), 50);
    await target.getByRole('button', { name: 'Last page', exact: true }).click();
    assert.equal(await intervals.getByRole('listitem').count(), 1);
    assert.equal(
        await intervals.locator('time').last().getAttribute('datetime'),
        new Date(started + 100005).toISOString()
    );
    await target.locator('summary').click();
    await target.locator('time').first().waitFor({ state: 'detached' });
    assert.equal(await target.locator('time').count(), 0, 'closing releases mounted exact intervals');
    await page.getByRole('searchbox', { name: 'Search history', exact: true }).fill('');
    await page.getByRole('combobox', { name: 'Sort by', exact: true }).selectOption('name');
    assert.equal(await resources.getByRole('heading').first().innerText(), 'Resource 00');
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    const provider = page.getByRole('region', { name: 'Smelter', exact: true });
    await provider.waitFor();
    assert.equal(await resources.count(), 0);
    await provider.locator('summary').click();
    await provider.getByRole('list', { name: 'Exact intervals', exact: true }).waitFor();
    const locations = provider.getByRole('navigation', { name: 'Location pages', exact: true });
    await locations.getByRole('button', { name: 'Last page', exact: true }).click();
    await provider.getByText(/test:world.*59.*1.*2/).waitFor();
    assert.equal(
        await provider.getByRole('list', { name: 'Exact intervals', exact: true }).getByRole('listitem').count(),
        1
    );
});

// Public browser/HTTP seam: local calendar boundaries determine when history needs a date.
for (const language of ['en', 'pl']) {
    test(`history timestamps use contextual dates and 24-hour whole seconds (${language})`, async (t) => {
        const { page, options, base } = await fixture(t, '', { timezoneId: 'Europe/Warsaw' });
        const started = Date.parse('2025-12-31T22:58:00.123Z');
        const timings = [
            { started: started + 1000, ended: started + 2000 },
            { started: Date.parse('2025-12-31T22:59:59.456Z'), ended: Date.parse('2025-12-31T23:00:01.789Z') },
            { started: Date.parse('2025-12-31T23:01:00.123Z'), ended: Date.parse('2025-12-31T23:01:30.456Z') }
        ];
        options.historyDetail = {
            ...historyDetail,
            timeStarted: started,
            timeDone: Date.parse('2025-12-31T23:02:00.789Z'),
            items: [{ ...historyDetail.items[0], timings }]
        };
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption(language);
        await page.goto(`${base}#/grids/${gridA}/history/1`);
        const row = page.getByRole('region', { name: 'Iron Ingot', exact: true });
        await row.locator('summary').click();
        const times = row.locator('time');
        assert.equal(await times.nth(0).innerText(), '23:58:01');
        assert.equal(await times.nth(1).innerText(), '23:58:02');
        assert.equal(await times.nth(2).innerText(), '23:59:59');
        assert.match(await times.nth(3).innerText(), /2026.*00:00:01/);
        assert.match(await times.nth(4).innerText(), /2026.*00:01:00/);
        assert.equal(await times.nth(5).innerText(), '00:01:30');
        assert.equal(await times.nth(3).getAttribute('datetime'), '2025-12-31T23:00:01.789Z');
        const startedLabel = language === 'en' ? 'Started' : 'Rozpoczęcie';
        const finishedLabel = language === 'en' ? 'Finished' : 'Zakończenie';
        const summaryValue = (label) =>
            page
                .locator('dl > div')
                .filter({ has: page.getByText(label, { exact: true }) })
                .locator('dd');
        assert.match(await summaryValue(startedLabel).innerText(), /2025.*23:58:00/);
        assert.match(await summaryValue(finishedLabel).innerText(), /2026.*00:02:00/);
        options.historyDetail.timeDone = started + 30000;
        await page.reload();
        await row.locator('summary').waitFor();
        assert.equal(await summaryValue(finishedLabel).innerText(), '23:58:30');
    });
}

test('history resource summaries pair processing time with its share of total elapsed crafting time', async (t) => {
    const { page, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const summary = page.getByRole('region', { name: 'Iron Ingot', exact: true }).locator('summary');
    await summary.waitFor();
    assert.match(await summary.innerText(), /5 s\s*\(50%\)/);
    assert.doesNotMatch(await summary.innerText(), /40%/);
    await page.getByRole('button', { name: 'Pattern providers', exact: true }).click();
    const provider = page.getByRole('region', { name: 'Smelter', exact: true }).locator('summary');
    assert.doesNotMatch(await provider.innerText(), /%/);
});

test('history preserves subsecond timeline scale and distinguishes very slow production from zero', async (t) => {
    const { page, options, base } = await fixture(t);
    options.historyDetail = {
        ...historyDetail,
        timeDone: historyDetail.timeStarted + 500,
        items: [{ ...historyDetail.items[0], craftsPerSec: 0.00001 }]
    };
    await page.goto(`${base}#/grids/${gridA}/history/1`);
    const row = page.getByRole('region', { name: 'Iron Ingot', exact: true });
    await row.locator('summary').click();
    await row.getByText('<0.001/s', { exact: true }).waitFor({ timeout: 3000 });
    const axis = page.getByLabel('Elapsed time from the start of this craft', { exact: true });
    assert.deepEqual(await axis.innerText(), '0 s\n0.25 s\n0.5 s');
    options.historyDetail.items[0].craftsPerSec = 0;
    await page.reload();
    await row.locator('summary').click();
    await row.getByText('0/s', { exact: true }).waitFor();
});

// Public settings/UI seam: saved preferred columns constrain resource grids without forcing mobile overflow.
test('preferred resource columns persist and adapt across terminal CPU and plan grids', async (t) => {
    const { page, base } = await fixture(t, '/ae2', { viewport: { width: 1600, height: 1000 } }, { mockClock: false });
    await page.goto(`${base}#/web-settings`);
    const columns = page.getByRole('combobox', { name: /Resource columns/ });
    await columns.waitFor({ timeout: 3000 });
    assert.equal(await columns.inputValue(), 'auto');
    await columns.selectOption('3');
    await page.reload();
    assert.equal(await columns.inputValue(), '3');
    for (const [route, selector] of [
        [`items`, '#items'],
        [`cpus/cpu-a`, '.cpu-terminal .cpu-items'],
        [`plans/17`, '.crafting-plan .cpu-items']
    ]) {
        await page.goto(`${base}#/grids/${gridA}/${route}`);
        const grid = page.locator(selector);
        await grid.waitFor({ timeout: 3000 });
        await page.waitForFunction(
            (selector) =>
                getComputedStyle(document.querySelector(selector)).gridTemplateColumns.split(' ').length === 3,
            selector
        );
        await page.setViewportSize({ width: 390, height: 844 });
        await page.waitForFunction(
            (selector) => getComputedStyle(document.querySelector(selector)).gridTemplateColumns.split(' ').length < 3,
            selector
        );
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        await page.setViewportSize({ width: 1600, height: 1000 });
        await page.waitForFunction(
            (selector) =>
                getComputedStyle(document.querySelector(selector)).gridTemplateColumns.split(' ').length === 3,
            selector
        );
    }
    await page.goto(`${base}#/web-settings`);
    assert.equal(await columns.inputValue(), '3');
    await columns.selectOption('auto');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.waitForFunction(
        () => getComputedStyle(document.querySelector('#items')).gridTemplateColumns.split(' ').length > 3
    );
});

test('outdated mod notice is admin only and supports session close and seven day snooze', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await seedAutomaticRefresh(page, base, false);
    options.isOutdated = true;
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('heading', { name: 'Web settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('link', { name: 'View releases' }).count(), 0);
    options.isAdmin = true;
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    const releases = page.getByRole('link', { name: 'View releases' });
    await releases.waitFor({ timeout: 3000 });
    assert.equal(await releases.getAttribute('href'), options.availableUpdate.releaseUrl);
    await page.getByRole('button', { name: 'Dismiss update notice' }).click();
    assert.equal(await releases.isVisible(), false);
    await clickTab(page, 'Home');
    assert.equal(await releases.isVisible(), false);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    await releases.waitFor();
    await page.getByRole('button', { name: 'Remind me later' }).click();
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await releases.isVisible(), false);
    const now = await page.evaluate(() => Date.now());
    await page.clock.setSystemTime(now + 6 * 24 * 60 * 60 * 1000);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await releases.isVisible(), false);
    await page.clock.setSystemTime(now + 7 * 24 * 60 * 60 * 1000 + 1000);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    await releases.waitFor();
    options.isOutdated = false;
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await releases.isVisible(), false);
});

test('preferred columns preserve square icon slots and valid empty rows while rejecting invalid saved choices', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '/ae2',
        { viewport: { width: 1600, height: 1000 } },
        { mockClock: false }
    );
    await page.goto(base);
    await atlasFixture(page, options);
    await page.evaluate(() =>
        localStorage.setItem(
            'ae2web:/ae2/:theme:default',
            JSON.stringify({ resourceColumns: 100, terminalDisplay: 'icons' })
        )
    );
    await page.reload();
    await page.goto(`${base}#/web-settings`);
    const columns = page.getByRole('combobox', { name: /Resource columns/ });
    assert.equal(await columns.inputValue(), 'auto');
    for (const count of [1, 8]) {
        await columns.selectOption(String(count));
        await clickTab(page, 'Home');
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.locator('#items[data-display="icons"] .item').first().waitFor();
        await page.waitForFunction((count) => {
            const grid = document.querySelector('#items');
            const tracks = getComputedStyle(grid).gridTemplateColumns.split(' ').length;
            return (
                tracks === count &&
                grid.children.length % tracks === 0 &&
                (count === 1 || grid.querySelector('.empty-slot'))
            );
        }, count);
        const cells = await page.locator('#items > li').evaluateAll((nodes) =>
            nodes.map((node) => {
                const bounds = node.getBoundingClientRect();
                return { width: bounds.width, height: bounds.height };
            })
        );
        for (const cell of cells)
            assert.ok(Math.abs(cell.width - cell.height) < 1, 'Occupied and empty icon slots remain square');
        await page.goto(`${base}#/web-settings`);
    }
});

test('update notice keeps close top right and adjacent actions bottom right across screen sizes', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 500, height: 850 } },
        { mockClock: false }
    );
    options.isOutdated = true;
    options.isAdmin = true;
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/web-settings`);
    const close = page.getByRole('button', { name: 'Dismiss update notice' });
    await close.waitFor();
    for (const [width, height] of [
        [1280, 720],
        [500, 850],
        [390, 850]
    ]) {
        await page.setViewportSize({ width, height });
        const notice = page.getByRole('region', { name: 'Information', exact: true });
        const bounds = await notice.boundingBox();
        const closeBounds = await close.boundingBox();
        const text = await page.locator('#update-notice > p').first().boundingBox();
        const snooze = await page.getByRole('button', { name: 'Remind me later' }).boundingBox();
        assert.ok(
            closeBounds.y >= bounds.y && closeBounds.y + closeBounds.height <= bounds.y + bounds.height,
            'Close stays visible without scrolling the notice region'
        );
        const skip = await page.getByRole('button', { name: 'Skip this version', exact: true }).boundingBox();
        assert.ok(snooze.y >= text.y + text.height - 1, 'Actions stay below the message');
        assert.ok(Math.abs(snooze.y - skip.y) < 1, 'Both actions remain on the same row');
        assert.ok(skip.x > snooze.x, 'Skip follows snooze');
        assert.ok(
            skip.y + skip.height <= bounds.y + bounds.height,
            'Both actions are visible without scrolling the notice'
        );
        assert.ok(
            Math.abs(skip.x + skip.width - closeBounds.x - closeBounds.width) < 1,
            'Actions align to the right edge with X'
        );
        assert.ok(closeBounds.x >= text.x + text.width - 1, 'Close stays at the right of the message');
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    }
    await close.click();
    assert.equal(await close.isVisible(), false);
});

test('administrator skips the displayed update only after confirmed response and server state survives browser reset', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.isOutdated = true;
    options.isAdmin = true;
    await seedAutomaticRefresh(page, base, false);
    let release;
    const held = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/updates/skip', async (route) => {
        await held;
        await route.continue();
    });
    await page.goto(`${base}#/web-settings`);
    const skip = page.getByRole('button', { name: 'Skip this version', exact: true });
    const snooze = page.getByRole('button', { name: 'Remind me later', exact: true });
    await skip.waitFor({ timeout: 3000 });
    const notice = page.locator('#update-notice');
    const before = await notice.boundingBox();
    const request = page.waitForRequest((request) => request.url().endsWith('/api/updates/skip'));
    await skip.click();
    await request;
    assert.equal(await skip.isDisabled(), true);
    assert.equal(await snooze.isDisabled(), true);
    assert.equal(await notice.isVisible(), true);
    assert.equal(await page.locator('#refresh').getAttribute('aria-busy'), 'true');
    assert.deepEqual(await notice.boundingBox(), before, 'Pending mutation does not insert a loading row');
    release();
    await notice.waitFor({ state: 'hidden' });
    const sent = options.requests.filter((request) => request.path === '/api/updates/skip');
    assert.equal(sent.length, 1);
    assert.equal(sent[0].method, 'POST');
    assert.equal(sent[0].headers['x-ae2-request'], 'true');
    assert.deepEqual(sent[0].body, { tag: options.availableUpdate.tag });
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await notice.isVisible(), false);
    options.availableUpdate = { ...options.availableUpdate, tag: '1.7.10-10.0.1', version: '10.0.1', skipped: false };
    await page.reload();
    await skip.waitFor();
    assert.match(await notice.innerText(), /10\.0\.1/);
});

test('failed and stale update skip keep notice usable and never retry the mutation automatically', async (t) => {
    const { page, options, base } = await fixture(t);
    options.isOutdated = true;
    options.isAdmin = true;
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/web-settings`);
    const notice = page.locator('#update-notice');
    const skip = page.getByRole('button', { name: 'Skip this version', exact: true });
    for (const status of ['INTERNAL_ERROR', 'UPDATE_CHANGED']) {
        options.skipUpdateStatus = status;
        await skip.click();
        await notice.getByRole('alert').waitFor();
        assert.equal(await skip.isEnabled(), true);
        assert.equal(await page.getByRole('button', { name: 'Remind me later' }).isEnabled(), true);
        assert.equal(await notice.isVisible(), true);
        const count = options.requests.filter((request) => request.path === '/api/updates/skip').length;
        await page.clock.fastForward(6000);
        assert.equal(options.requests.filter((request) => request.path === '/api/updates/skip').length, count);
        assert.equal(options.availableUpdate.skipped, false);
    }
    await page.getByRole('button', { name: 'Dismiss update notice' }).click();
    assert.equal(await notice.isVisible(), false);
});

test('update notice needs release metadata and an unconfirmed skip is reconciled by reloading server state', async (t) => {
    const { page, options, base } = await fixture(t);
    options.isOutdated = true;
    options.isAdmin = true;
    const update = options.availableUpdate;
    options.availableUpdate = null;
    await seedAutomaticRefresh(page, base, false);
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    const notice = page.locator('#update-notice');
    assert.equal(await notice.isVisible(), false);
    options.availableUpdate = update;
    await page.reload();
    const skip = page.getByRole('button', { name: 'Skip this version', exact: true });
    await skip.waitFor();
    await page.route('**/api/updates/skip', async (route) => {
        await route.fetch();
        await route.fulfill({ status: 200, contentType: 'text/html', body: 'Broken proxy response' });
    });
    await skip.click();
    await notice.getByRole('alert').waitFor();
    assert.match(await notice.getByRole('alert').innerText(), /confirm/);
    assert.equal(await notice.isVisible(), true);
    assert.equal(options.availableUpdate.skipped, true);
    await page.clock.fastForward(6000);
    assert.equal(options.requests.filter((request) => request.path === '/api/updates/skip').length, 1);
    await page.reload();
    await page.getByRole('link', { name: 'Home', exact: true }).waitFor();
    assert.equal(await notice.isVisible(), false);
});

test('default terminal opens without an interface selector or legacy navigation', async (t) => {
    const { page, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items .item').first().waitFor();
    assert.equal(new URL(page.url()).search, '');
    assert.equal(await page.getByRole('link', { name: /previous interface/i }).count(), 0);
    await clickTab(page, 'Web settings');
    await page.reload();
    await page.getByRole('heading', { name: 'Web settings', exact: true }).waitFor();
    assert.equal(new URL(page.url()).search, '');
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByLabel('Username', { exact: true }).waitFor();
    assert.equal(new URL(page.url()).pathname, '/ae2/');
    assert.equal(new URL(page.url()).search, '');
    assert.equal(new URL(page.url()).hash, '#/web-settings');
});

test('phone navigation and resource panels reuse controls with modal focus and crafting transitions', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 390, height: 844 } },
        { mockClock: false }
    );
    await page.goto(`${base}#/grids/${gridA}/items`);
    const menu = page.getByRole('button', { name: 'Navigation', exact: true });
    await menu.waitFor({ timeout: 3000 });
    assert.equal(await page.getByRole('link', { name: 'Web settings', exact: true }).isVisible(), false);
    await menu.click();
    await page.getByRole('dialog', { name: 'Navigation', exact: true }).waitFor();
    await clickTab(page, 'Web settings');
    await page.getByRole('heading', { name: 'Web settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('dialog').count(), 0);
    await menu.click();
    await clickTab(page, 'Terminal');
    const item = page.locator('#items .item').filter({ hasText: 'Iron Ingot' });
    await item.click();
    const details = page.getByRole('dialog', { name: 'Resource details', exact: true });
    await details.waitFor();
    await details.getByRole('button', { name: 'Craft', exact: true }).click();
    const craft = page.getByRole('dialog', { name: 'Craft', exact: true });
    await craft.waitFor();
    assert.equal(await details.isVisible(), false);
    assert.equal(await page.getByRole('dialog').count(), 1);
    await page.keyboard.press('Escape');
    await craft.waitFor({ state: 'hidden' });
    const filters = page.getByRole('button', { name: 'Filters and sorting', exact: true });
    await filters.click();
    const filterPanel = page.getByRole('dialog', { name: 'Filters and sorting', exact: true });
    await filterPanel.getByRole('button', { name: /Availability: All/ }).click();
    await page.keyboard.press('Escape');
    assert.equal(await filters.evaluate((node) => document.activeElement === node), true);
    await filters.click();
    await page.setViewportSize({ width: 1280, height: 850 });
    await filterPanel.waitFor({ state: 'hidden' });
    assert.equal(
        await page
            .locator('#terminal-tools')
            .getByRole('button', { name: /Availability: Stored/ })
            .isVisible(),
        true
    );
    assert.equal(await page.getByRole('link', { name: 'Web settings', exact: true }).isVisible(), true);
    assert.equal(
        options.requests.some((request) => request.method !== 'GET'),
        false
    );
});

test('phone CPU plan and Home selectors open on demand and reach their last entries', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 390, height: 844 } },
        { mockClock: false }
    );
    options.cpus = Object.fromEntries(
        Array.from({ length: 40 }, (_, index) => [
            `cpu-${index}`,
            { ...cpu, name: `Processor ${String(index).padStart(2, '0')}` }
        ])
    );
    options.cpuDetails['cpu-0'] = cpuWork;
    options.cpuDetails['cpu-39'] = cpuWork;
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-0`);
    const cpus = page.getByRole('button', { name: 'CPUs', exact: true });
    await cpus.click();
    const panel = page.getByRole('dialog', { name: 'CPUs', exact: true });
    await panel.getByRole('link', { name: /Processor 39/ }).click();
    await page.waitForURL(/cpus\/cpu-39$/);
    await panel.waitFor({ state: 'hidden' });
    await page.goto(`${base}#/grids/${gridA}/plans/17`);
    await cpus.click();
    await panel.getByRole('radio', { name: /cpu-39/ }).check();
    await page.getByRole('button', { name: 'Close panel', exact: true }).click();
    assert.equal(await cpus.evaluate((node) => document.activeElement === node), true);
    await cpus.click();
    assert.equal(await panel.getByRole('radio', { name: /cpu-39/ }).isChecked(), true);
    await page.keyboard.press('Escape');
    await page.goto(base);
    await page.getByRole('button', { name: 'Network', exact: true }).click();
    await page
        .getByRole('dialog', { name: 'Network', exact: true })
        .getByRole('button', { name: 'Choose a network', exact: true })
        .click();
    const networks = page.getByRole('dialog', { name: 'Choose a network', exact: true });
    await networks.waitFor();
    assert.equal(await page.getByRole('dialog').count(), 1);
    await networks.getByRole('link').last().click();
    await page.waitForURL(new RegExp(`/grids/${gridB}/items$`));
    await networks.waitFor({ state: 'hidden' });
});

test('phone icon slots use available width and account identity stays readable', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 390, height: 844 } },
        { mockClock: false }
    );
    await page.goto(base);
    await atlasFixture(page, options);
    await page.evaluate(() =>
        localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ terminalDisplay: 'icons' }))
    );
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.reload();
    for (const width of [390, 360]) {
        await page.setViewportSize({ width, height: 844 });
        const item = page.locator('#items[data-display="icons"] .item').first();
        await item.waitFor();
        const slot = await item.boundingBox();
        assert.ok(slot.width >= 48 && slot.width < 64);
        assert.ok(Math.abs(slot.width - slot.height) < 1);
        const icon = await item.locator('.resource-icon').boundingBox();
        assert.equal(icon.width, 32);
        const user = page.locator('#username');
        assert.equal(await user.innerText(), options.username);
        assert.equal(await user.evaluate((node) => node.scrollWidth <= node.clientWidth), true);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        const window = await page.locator('#window').boundingBox();
        assert.ok(window.width >= width - 16);
    }
});

test('phone panels survive polling and dismiss current navigation and backdrop without background actions', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 844 } });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items .item').first().waitFor();
    const menu = page.getByRole('button', { name: 'Navigation', exact: true });
    await menu.click();
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    const filters = page.getByRole('button', { name: 'Filters and sorting', exact: true });
    await filters.click();
    const panel = page.getByRole('dialog', { name: 'Filters and sorting', exact: true });
    const sort = panel.getByRole('button', { name: 'Sort by: Name', exact: true });
    await sort.focus();
    await poll(page);
    assert.equal(await panel.isVisible(), true);
    assert.equal(await sort.evaluate((node) => document.activeElement === node), true);
    const requests = options.requests.length;
    const refresh = await page.locator('#refresh').boundingBox();
    await page.mouse.click(refresh.x + refresh.width / 2, refresh.y + refresh.height / 2);
    await panel.waitFor({ state: 'hidden' });
    assert.equal(options.requests.length, requests, 'A backdrop tap cannot trigger background refresh');
    assert.equal(await filters.evaluate((node) => document.activeElement === node), true);
});

test('phone sheets fit short viewports and keep the last resource action reachable', async (t) => {
    const { page, base } = await fixture(t, '', { viewport: { width: 600, height: 360 } }, { mockClock: false });
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items .item').filter({ hasText: 'Iron Ingot' }).click();
    const details = page.getByRole('dialog', { name: 'Resource details', exact: true });
    const craft = details.getByRole('button', { name: 'Craft', exact: true });
    await craft.scrollIntoViewIfNeeded();
    const sheet = await details.boundingBox();
    const action = await craft.boundingBox();
    assert.ok(sheet.y >= 0 && sheet.y + sheet.height <= 360);
    assert.ok(action.y >= sheet.y && action.y + action.height <= sheet.y + sheet.height);
    await craft.click();
    const popup = page.getByRole('dialog', { name: 'Craft', exact: true });
    await popup.getByRole('button', { name: 'Calculate plan', exact: true }).scrollIntoViewIfNeeded();
    const bounds = await popup.boundingBox();
    assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 360);
    await page.keyboard.press('Escape');
    await popup.waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('dialog').count(), 0);
});

test('phone panel icon buttons belong to the window heading with search below', async (t) => {
    const { page, base } = await fixture(t, '', { viewport: { width: 360, height: 800 } }, { mockClock: false });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const filters = page.getByRole('button', { name: 'Filters and sorting', exact: true });
    await filters.waitFor();
    const heading = await page.getByRole('heading', { name: 'Terminal', exact: true }).boundingBox();
    const button = await filters.boundingBox();
    const search = await page.getByRole('searchbox', { name: 'Search resources', exact: true }).boundingBox();
    assert.ok(Math.abs(button.y + button.height / 2 - heading.y - heading.height / 2) < 2);
    assert.ok(button.x >= heading.x + heading.width);
    assert.ok(search.y >= button.y + button.height);
    assert.equal((await filters.innerText()).trim(), '', 'The opener is icon-only with an accessible name');
    await page.locator('#items .item').first().click();
    await page.getByRole('button', { name: 'Close panel', exact: true }).click();
    const details = page.getByRole('button', { name: 'Resource details', exact: true });
    assert.equal((await details.innerText()).trim(), '');
    await details.click();
    await page.getByRole('dialog', { name: 'Resource details', exact: true }).waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await details.evaluate((node) => document.activeElement === node), true);
});

// Public theme/browser/HTTP seam: recovery must release presentation resources while preserving
// successful server data and the application operation already started by the user's action.
test('theme partial mount failure releases external nodes and aborts event handlers before retry', async (t) => {
    const { page, options, base } = await fixture(t);
    page.setDefaultTimeout(3000);
    page.setDefaultNavigationTimeout(5000);
    options.themeEntry = `
        import { boot } from './assets/web/app/boot.js';
        let mounts = 0;
        boot(document.getElementById('app'), {
            id: 'mount-fault', name: 'Mount fault', supportedModVersion: '1.2.x', views: ['home'],
            mount(root, application, context) {
                mounts++;
                const external = document.createElement('aside');
                external.textContent = 'External presentation resource';
                external.dataset.external = String(mounts);
                document.body.append(external);
                context.lifetime.onCleanup(() => external.remove());
                window.addEventListener('theme-probe', () => { document.title += '!'; }, { signal: context.lifetime.signal });
                if (mounts === 1) throw new Error('Test theme partial mount failure');
                const output = document.createElement('output');
                output.setAttribute('aria-label', 'Theme data');
                root.append(output);
                application.subscribe(state => { output.textContent = state.gridStatus; });
            }
        });`;
    await page.goto(base);
    await page.getByRole('dialog').getByRole('alert').waitFor();
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await page.locator('#app').getByRole('alert').waitFor();
    assert.equal(await page.locator('[data-external]').count(), 0);
    await page.evaluate(() => window.dispatchEvent(new Event('theme-probe')));
    assert.equal(await page.title(), 'Theme fixture');
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await page.getByRole('status', { name: 'Theme data' }).filter({ hasText: 'ready' }).waitFor();
    assert.equal(await page.locator('[data-external]').count(), 1);
    await page.evaluate(() => window.dispatchEvent(new Event('theme-probe')));
    assert.equal(await page.title(), 'Theme fixture!');
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 1);
});

test('theme render failure preserves loaded resources and pending crafting without replay on retry', async (t) => {
    const { page, options, base } = await fixture(t);
    page.setDefaultTimeout(3000);
    page.setDefaultNavigationTimeout(5000);
    let release;
    const pending = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/api/grids/*/crafting-plans', async (route) => {
        await pending;
        await route.continue();
    });
    options.themeEntry = `
        import { boot } from './assets/web/app/boot.js';
        let mounts = 0;
        boot(document.getElementById('app'), {
            id: 'render-fault', name: 'Render fault', supportedModVersion: '1.2.x', views: ['items', 'plan'],
            mount(root, application) {
                mounts++;
                const output = document.createElement('output');
                output.setAttribute('aria-label', 'Theme data');
                const calculate = document.createElement('button');
                calculate.textContent = 'Calculate';
                calculate.onclick = () => application.crafting.create(application.state.items[0], 12);
                root.append(output, calculate);
                application.subscribe(state => {
                    if (mounts === 1 && state.crafting.mutation === 'create') throw new Error('Test renderer failure');
                    output.textContent = JSON.stringify({
                        itemStatus: state.itemStatus, items: state.items.map(item => item.displayName),
                        grids: state.grids.length, mutation: state.crafting.mutation, craftingStatus: state.crafting.status
                    });
                    calculate.disabled = state.itemStatus !== 'ready' || !!state.crafting.mutation;
                });
            }
        });`;
    await page.goto(`${base}#/grids/${gridA}/items`);
    const data = page.getByRole('status', { name: 'Theme data' });
    await data.filter({ hasText: 'Iron Ingot' }).waitFor();
    const request = page.waitForRequest(
        (request) => request.method() === 'POST' && request.url().endsWith('/crafting-plans')
    );
    await page.getByRole('button', { name: 'Calculate', exact: true }).click();
    await request;
    await page.getByRole('dialog').getByRole('alert').waitFor();
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await page.locator('#app').getByRole('alert').waitFor();
    const reads = options.requests.filter(
        (request) => request.path.startsWith('/api/') && request.method === 'GET'
    ).length;
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    const snapshot = JSON.parse(await data.textContent());
    assert.equal(snapshot.itemStatus, 'ready');
    assert.deepEqual(snapshot.items, [iron.displayName, quartz.displayName]);
    assert.equal(snapshot.grids, 2);
    assert.equal(snapshot.mutation, 'create');
    assert.equal(await page.getByRole('button', { name: 'Calculate', exact: true }).isDisabled(), true);
    assert.equal(
        options.requests.filter((request) => request.path.startsWith('/api/') && request.method === 'GET').length,
        reads
    );
    release();
    await page.waitForURL(`**/#/grids/${gridA}/plans/7`);
    await data.filter({ hasText: '"craftingStatus":"ready"' }).waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 1);
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 1);
});

test('a restored cached page reloads its disposed application', async (t) => {
    const { page, options, base } = await fixture(t);
    page.setDefaultTimeout(3000);
    page.setDefaultNavigationTimeout(5000);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('searchbox', { name: 'Search resources', exact: true }).waitFor();
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
    const reload = page.waitForRequest((request) => new URL(request.url()).pathname === '/api/context');
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
    await reload;
    await page.getByRole('searchbox', { name: 'Search resources', exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/context').length, 2);
});

test('resource tracking is independent of CPU history and renders exact flow counts', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.locator('#network-flow-tracking').check();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('#network-flow-tracking').disabled);
    assert.equal(options.settings[gridA].isFlowTracked, true);
    assert.equal(options.settings[gridA].isStorageTracked, false);
    await page.locator('#network-storage-tracking').check();
    await page.locator('#network-stock-interval').fill('60');
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('#network-storage-tracking').disabled);
    assert.equal(options.settings[gridA].isStorageTracked, true);
    assert.equal(options.settings[gridA].isTracked, false);
    assert.equal(options.settings[gridA].stockSampleIntervalSeconds, 60);
    await page.locator('#network-stock-interval').fill('0');
    assert.equal(await page.getByRole('button', { name: 'Save settings', exact: true }).isDisabled(), true);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.goto(`${base}#/grids/${gridA}/settings`);
    await page.waitForFunction(() => !document.querySelector('#network-flow-tracking').disabled);
    assert.equal(await page.locator('#network-flow-tracking').isChecked(), true);
    assert.equal(await page.locator('#network-stock-interval').inputValue(), '0');
    await page.reload();
    await page.waitForFunction(() => !document.querySelector('#network-flow-tracking').disabled);
    assert.equal(await page.locator('#network-stock-interval').inputValue(), '60');
    options.resourceHistory = {
        baseline: {
            observedAt: 1690000000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '1234567890123456789' },
            craftable: false,
            input: '0',
            output: '0',
            complete: true
        },
        points: [
            {
                observedAt: 1700000000000,
                generation: 'run',
                kind: 'FLOW',
                resource: null,
                input: '9223372036854775807',
                output: '0',
                complete: false
            }
        ],
        complete: false,
        truncated: true
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items button').first().click();
    await page.locator('.resource-history canvas').waitFor();
    assert.equal(await page.locator('.resource-history ol').count(), 0);
    assert.equal(await page.locator('.resource-history .hint').first().isVisible(), true);
    options.resourceHistory.points = [];
    options.resourceHistory.complete = true;
    options.resourceHistory.truncated = false;
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('.resource-history .hint').hidden);
    await page.locator('.resource-history canvas').waitFor();
    assert.equal(await page.locator('.resource-history .hint').first().isVisible(), false);
});

test('tracking cold links load their own atlas mappings and respect names-only through reload and pack replacement', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.historyIcons = { ...options.icons, pages: [{ digest: 'c'.repeat(64), width: 128, height: 64 }] };
    options.packDelay = 150;
    options.resourceHistory = {
        baseline: {
            observedAt: Date.now() - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '10', icon: { page: 0, x: 64, y: 0 } },
            complete: true
        },
        points: [],
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    const icon = page.locator('.tracking-view .resource-icon');
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    const reads = () => options.requests.filter((request) => request.path.endsWith('/resource-history'));
    assert.equal(new URLSearchParams(reads()[0].query).has('icons'), false);
    assert.equal(new URLSearchParams(reads().at(-1).query).get('icons'), 'true');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 0);
    assert.ok(
        options.requests.some((request) => request.path.includes(`/pages/${'c'.repeat(64)}`)),
        'Uses history page table rather than terminal page table'
    );
    await page.reload();
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    assert.equal(await icon.isVisible(), true);
    options.packDelay = 0;
    options.pack = { ...options.pack, packId: 'd'.repeat(64) };
    options.historyIcons = { ...options.historyIcons, packId: options.pack.packId };
    const replaced = page.waitForRequest((request) => request.url().includes(`/icon-packs/${'d'.repeat(64)}/`));
    await poll(page);
    await replaced;
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    const atlasReads = options.requests.filter((request) => request.path.includes('/icon-packs/')).length;
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    assert.equal(await icon.isVisible(), false);
    assert.equal(new URLSearchParams(reads().at(-1).query).has('icons'), false);
    assert.equal(options.requests.filter((request) => request.path.includes('/icon-packs/')).length, atlasReads);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 0);
});

test('late tracking icon responses cannot restore images after names-only selection', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    const history = {
        baseline: {
            observedAt: Date.now() - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '10', icon: { page: 0, x: 0, y: 0 } },
            complete: true
        },
        points: [],
        complete: true,
        truncated: false,
        enabled: true
    };
    options.resourceHistory = history;
    let delayed;
    const intercepted = new Promise((resolve) => {
        page.route('**/resource-history?**', (route) => {
            if (new URL(route.request().url()).searchParams.get('icons') === 'true' && !delayed) {
                delayed = route;
                resolve();
            } else void route.continue();
        });
    });
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await intercepted;
    await clickTab(page, 'Web settings');
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.evaluate((key) => {
        location.hash = `/grids/${key}/tracking/iron`;
    }, gridA);
    await page.locator('.tracking-view canvas').waitFor();
    await delayed.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: history, icons: options.icons })
    });
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await page.locator('.tracking-view .resource-icon:visible').count(), 0);
    assert.equal(options.requests.filter((request) => request.path.includes('/icon-packs/')).length, 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 0);
});

test('tracking uses historical mappings after terminal entry and clears them on denied history reads', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.itemsA = [{ ...iron, icon: { page: 0, x: 0, y: 0 } }];
    options.historyIcons = { ...options.icons, pages: [{ digest: 'c'.repeat(64), width: 128, height: 64 }] };
    options.resourceHistory = {
        baseline: {
            observedAt: Date.now() - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '10', icon: { page: 0, x: 64, y: 0 } },
            complete: true
        },
        points: [],
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#items button').first().click();
    await page.locator('.resource-history canvas').waitFor();
    await page.locator('.resource-history .tracking-open').click();
    await page.waitForFunction(() => document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    assert.ok(options.requests.some((request) => request.path.includes(`/pages/${'c'.repeat(64)}`)));
    assert.equal(
        await page
            .locator('.tracking-view .resource-icon')
            .evaluate((node) => getComputedStyle(node).backgroundPositionX),
        '-32px'
    );
    options.resourceHistoryError = 'NO_PERMISSIONS';
    await page.locator('#refresh').click();
    await page.waitForFunction(() => !document.querySelector('.tracking-view .resource-icon')?.style.backgroundImage);
    assert.equal(await page.locator('.tracking-view canvas:visible').count(), 0);
});

for (const touch of [false, true]) {
    test(`compact stock trend uses one hour and preserves measured values and gaps (${touch ? 'touch' : 'mouse'})`, async (t) => {
        const { page, options, base } = await fixture(
            t,
            '',
            touch ? { viewport: { width: 390, height: 844 }, hasTouch: true } : {}
        );
        const now = Date.now();
        const stock = (age, quantity) => ({
            observedAt: now - age,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity },
            complete: true
        });
        options.resourceHistory = {
            baseline: stock(3700000, '9223372036854775807'),
            points: [
                stock(1800000, '9223372036854775808'),
                { observedAt: now - 900000, generation: '1', kind: 'GAP', resource: null, complete: false },
                stock(300000, '42')
            ],
            enabled: true,
            complete: false,
            truncated: false
        };
        await page.goto(`${base}#/grids/${gridA}/items`);
        const request = page.waitForRequest((request) => request.url().includes('/resource-history?'));
        await page.locator('#items button').first().click();
        const first = await request;
        const from = Number(new URL(first.url()).searchParams.get('from'));
        assert.ok(Math.abs(now - from - 3600000) < 5000, 'The first selected-item history request covers one hour');
        if (touch && !(await page.getByRole('dialog', { name: 'Resource details', exact: true }).isVisible()))
            await openMobilePane(page, 'Resource details');
        const chart = page.locator('.resource-history .tracking-chart');
        await chart.locator('canvas').waitFor();
        assert.equal(await page.locator('.resource-history select:visible').count(), 0);
        assert.equal(await chart.locator('.u-axis:visible, .u-legend:visible').count(), 0);
        const overlay = chart.locator('.u-over');
        await overlay.scrollIntoViewIfNeeded();
        const bounds = await overlay.boundingBox();
        const host = await chart.boundingBox();
        assert.ok(bounds.width > host.width * 0.9, 'The sparkline uses its width without axis gutters');
        const selectAt = async (fraction) => {
            const position = { x: bounds.x + bounds.width * fraction, y: bounds.y + bounds.height / 2 };
            if (touch) await page.touchscreen.tap(position.x, position.y);
            else await page.mouse.move(position.x, position.y);
        };
        await selectAt(0.25);
        const readout = chart.locator('.tracking-sparkline-readout');
        await readout.waitFor();
        assert.deepEqual(await chart.boundingBox(), host, 'Hovering the trend does not change its layout');
        assert.ok((await readout.innerText()).replace(/[^0-9]/g, '').includes('9223372036854775807'));
        assert.equal(await readout.locator('time').getAttribute('datetime'), new Date(now - 3700000).toISOString());
        if (touch) {
            await page.mouse.move(1, 1);
            assert.equal(await readout.isVisible(), true);
        }
        await selectAt(0.85);
        assert.match(await readout.innerText(), /—/);
        assert.equal(await readout.locator('time').count(), 0);
        await selectAt(0.97);
        assert.ok((await readout.innerText()).includes('42'));
        assert.equal(await readout.locator('time').getAttribute('datetime'), new Date(now - 300000).toISOString());
        assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 1);
        await page.locator('.resource-history .tracking-open').click();
        await page.locator('.tracking-view canvas').waitFor();
        await Promise.all([
            page.waitForResponse((response) => response.url().includes('/resource-history?')),
            page.locator('.tracking-view select').first().selectOption('86400000')
        ]);
        await page.locator('.tracking-view .tracking-back').click();
        if (touch && !(await page.getByRole('dialog', { name: 'Resource details', exact: true }).isVisible()))
            await openMobilePane(page, 'Resource details');
        await page.locator('.resource-history canvas').waitFor();
        await page.locator('.resource-history .tracking-open').click();
        await page.locator('.tracking-view canvas').waitFor();
        assert.equal(await page.locator('.tracking-view select').first().inputValue(), '86400000');
        const requests = options.requests.filter((request) => request.path.endsWith('/resource-history'));
        const windows = requests.map((request) =>
            Math.round((now - Number(new URLSearchParams(request.query).get('from'))) / 60000)
        );
        assert.deepEqual(windows, [60, 15, 1440, 60, 1440], 'Each view fetches its intended period once');
    });
}

test('tracking opens from resources, retains terminal state, and survives a direct-link refresh', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    options.recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: now,
        flowSince: now - 120000,
        stockSince: now - 3600000,
        flowComplete: true,
        stockComplete: true,
        resources: { iron: { input: '9223372036854775807', output: '8', stockChange: '12' } }
    };
    options.resourceHistory = {
        baseline: {
            observedAt: now - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '12' },
            complete: true
        },
        points: [
            {
                observedAt: now - 10000,
                generation: '1',
                kind: 'FLOW',
                input: '9223372036854775807',
                output: '8',
                resource: null,
                complete: true
            }
        ],
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#search').fill('iron');
    const item = page.locator('#items button').first();
    await item.hover();
    await page.locator('#resource-tooltip .recent-flow').waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 0);
    assert.equal(await page.locator('#resource-tooltip [aria-label="+9223372036854775807"]').count(), 1);
    await item.click();
    await page.locator('.resource-history canvas').waitFor();
    await page.locator('.resource-history .tracking-open').click();
    await page.locator('.tracking-view canvas').waitFor();
    assert.ok(
        (await page.locator('.tracking-view .tracking-totals').innerText())
            .replace(/[^0-9]/g, '')
            .includes('9223372036854775807')
    );
    await Promise.all([
        page.waitForResponse((response) => response.url().includes('/resource-history?')),
        page.locator('.tracking-view select').first().selectOption('3600000')
    ]);
    await page.locator('.tracking-view select').nth(1).selectOption('smooth');
    await page.locator('.tracking-view .tracking-back').click();
    assert.equal(await page.locator('#search').inputValue(), 'iron');
    await page.locator('#items button').first().click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'View tracking' }).click();
    await page.locator('.tracking-view canvas').waitFor();
    await page.reload();
    await page.locator('.tracking-view canvas').waitFor();
    assert.equal(await page.locator('.tracking-view select').nth(1).inputValue(), 'smooth');
    assert.ok((await page.locator('.tracking-view .tracking-resource-name').innerText()).includes('Iron'));
});

test('tracking chart keeps missing periods blank and pixel rendering survives narrow zoom', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    const stock = (age, quantity) => ({
        observedAt: now - age,
        generation: '1',
        kind: 'STOCK',
        resource: { ...iron, quantity: String(quantity) },
        complete: true
    });
    options.resourceHistory = {
        baseline: stock(1000000, 10),
        complete: false,
        truncated: false,
        points: [
            stock(750000, 20),
            stock(600000, 50),
            { observedAt: now - 500000, generation: '1', kind: 'GAP', complete: false },
            stock(250000, 60),
            stock(100000, 30)
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    const raster = await page.locator('.tracking-view .uplot').evaluate((root) => {
        const canvas = root.querySelector('canvas'),
            box = canvas.getBoundingClientRect(),
            plot = root.querySelector('.u-over').getBoundingClientRect();
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        const scale = canvas.width / box.width;
        let gold = 0,
            gap = 0;
        for (let y = 0; y < canvas.height; y++)
            for (let x = 0; x < canvas.width; x++) {
                const i = (y * canvas.width + x) * 4;
                if (pixels[i] === 214 && pixels[i + 1] === 155 && pixels[i + 2] === 40) {
                    gold++;
                    const fraction = (x / scale + box.left - plot.left) / plot.width;
                    if (fraction > 0.52 && fraction < 0.65) gap++;
                }
            }
        return { gold, gap };
    });
    assert.ok(raster.gold > 10, 'Known stock is rendered');
    assert.equal(raster.gap, 0, 'No data stroke may cross the missing interval');
    const bounds = await page.locator('.tracking-view .u-over').boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.2, bounds.y + 50);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.2 + 2, bounds.y + 50);
    await page.mouse.up();
    await page.locator('.tracking-view select').nth(1).selectOption('smooth', { timeout: 3000 });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
});

test('tracking legend choices survive automatic history refresh and chart rebuilds', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    options.resourceHistory = {
        baseline: {
            observedAt: now - 1000000,
            generation: '1',
            kind: 'STOCK',
            resource: { ...iron, quantity: '20' },
            complete: true
        },
        complete: true,
        truncated: false,
        points: [
            { observedAt: now - 10000, generation: '1', kind: 'FLOW', input: '12', output: '4', complete: true },
            { observedAt: now - 5000, generation: '1', kind: 'FLOW_OBSERVATION', complete: true }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    const label = (name) => page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('th');
    await label('Stored').waitFor();
    for (const name of ['Stored', 'Input']) await label(name).click();
    const choices = async () =>
        Promise.all(
            ['Input', 'Output', 'Stored', 'Net'].map((name) =>
                label(name).evaluate((element) => Number(getComputedStyle(element).opacity) >= 1)
            )
        );
    assert.deepEqual(await choices(), [false, true, false, true]);
    const refreshed = page.waitForResponse((response) => response.url().includes('/resource-history?'));
    await page.clock.fastForward(31000);
    await (await refreshed).finished();
    await page.waitForFunction(() => document.querySelector('#refresh').getAttribute('aria-busy') === 'false');
    assert.deepEqual(await choices(), [false, true, false, true], 'Automatic refresh preserves each legend choice');
    await page.locator('.tracking-view select').nth(1).selectOption('smooth');
    assert.deepEqual(await choices(), [false, true, false, true], 'Stroke changes preserve visibility');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(
        () => document.querySelector('.tracking-view canvas').getBoundingClientRect().width < 390
    );
    assert.deepEqual(await choices(), [false, true, false, true], 'Responsive rebuilds preserve visibility');
    const bounds = await page.locator('.tracking-view .u-over').boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.25, bounds.y + 30);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.75, bounds.y + 30);
    await page.mouse.up();
    assert.deepEqual(await choices(), [false, true, false, true], 'Zoom regrouping preserves visibility');
    for (const name of ['Stored', 'Input']) await label(name).click();
    await page.getByRole('button', { name: 'Reset zoom', exact: true }).click();
    assert.deepEqual(await choices(), [true, true, true, true], 'Re-enabled series also survive reset');
});

test('flow grouping adapts from original counters on zoom reset and resize while Pixel Net stays crisp', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Math.floor(Date.now() / 30000) * 30000;
    await page.clock.setFixedTime(now);
    const amount = 100000000000000003n;
    const points = [];
    for (let age = 900000, index = 0; age >= 0; age -= 5000, index++) {
        const common = { observedAt: now - age, generation: '1', complete: true };
        points.push({
            ...common,
            kind: 'FLOW',
            input: String(amount),
            output: String(amount - BigInt(7 * ((index % 3) + 1)))
        });
        points.push({ ...common, kind: 'FLOW_OBSERVATION' });
        if (age === 600000) points.push({ ...common, kind: 'STOCK', resource: { ...iron, quantity: '60' } });
    }
    options.resourceHistory = {
        baseline: {
            observedAt: now - 1000000,
            generation: '1',
            kind: 'STOCK',
            complete: true,
            resource: { ...iron, quantity: '20' }
        },
        points,
        complete: true,
        truncated: false
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.waitForFunction(
        () =>
            document.querySelector('.tracking-view .uplot') &&
            document.querySelector('#refresh').getAttribute('aria-busy') === 'false'
    );
    await page.evaluate(() => document.fonts.ready);
    const area = page.locator('.tracking-view .u-over');
    const value = async (name) =>
        BigInt(
            (
                await page
                    .locator('.tracking-view .u-legend tr')
                    .filter({ hasText: name })
                    .locator('.u-value')
                    .innerText()
            ).replace(/[^0-9-]/g, '')
        );
    const hover = async (position) => {
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        const box = await area.boundingBox();
        await page.mouse.move(box.x + box.width * position, box.y + 40);
        return box;
    };
    let box = await hover(0.5);
    const coarse = await value('Input');
    assert.ok(coarse > amount && coarse % amount === 0n, 'The full range groups multiple raw flushes');
    const inputCoarse = coarse;
    await page.mouse.move(box.x + box.width * 0.48, box.y + 40);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.52, box.y + 40);
    await page.mouse.up();
    await hover(0.5);
    assert.equal(await value('Input'), amount, 'Zoom exposes original five-second counters, not sums of previous bins');
    assert.ok([7n, 14n, 21n].includes(await value('Net')));
    box = await hover(0.5);
    await page.mouse.click(box.x + box.width * 0.5, box.y + 40);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.querySelector('.tracking-view .tracking-chart-pinned'));
    assert.equal(await value('Input'), amount);
    await page.locator('.tracking-view .tracking-toolbar button').click();
    await hover(0.5);
    const phone = await value('Input');
    assert.ok(phone > inputCoarse && phone % amount === 0n, 'A narrow full-range chart uses wider, exact bins');
    await page.setViewportSize({ width: 1280, height: 900 });
    await hover(0.5);
    assert.equal(await value('Input'), inputCoarse, 'Returning to desktop reaggregates the original counters');
    options.resourceHistory = {
        ...options.resourceHistory,
        points: points.map((point) =>
            point.kind === 'FLOW'
                ? { ...point, input: '100', output: String(20 + (((point.observedAt - now + 900000) / 5000) % 7) * 10) }
                : point
        )
    };
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('#refresh').getAttribute('aria-busy') === 'false');
    for (const name of ['Input', 'Output'])
        await page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('th').click();
    const raster = async () =>
        page.locator('.tracking-view canvas').evaluate((canvas) => {
            const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
            let blue = 0,
                translucent = 0;
            for (let i = 0; i < data.length; i += 4)
                if (
                    Math.abs(data[i] - 128) <= 2 &&
                    Math.abs(data[i + 1] - 201) <= 2 &&
                    Math.abs(data[i + 2] - 237) <= 2 &&
                    data[i + 3] > 0
                ) {
                    blue++;
                    if (data[i + 3] < 255) translucent++;
                }
            return { blue, translucent };
        });
    let pixels = await raster();
    assert.ok(pixels.blue > 20);
    assert.equal(pixels.translucent, 0, 'Pixel Net has a hard-edged raster');
    await page.locator('.tracking-view select').nth(1).selectOption('smooth');
    pixels = await raster();
    assert.ok(pixels.blue > 20 && pixels.translucent > 0, `Smooth Net retains antialiasing: ${JSON.stringify(pixels)}`);
    for (const name of ['Input', 'Output'])
        await page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('th').click();
    options.resourceHistory = {
        ...options.resourceHistory,
        complete: false,
        points: [
            { observedAt: now - 900000, generation: '1', kind: 'FLOW', input: '11', output: '0', complete: true },
            { observedAt: now - 600000, generation: '1', kind: 'GAP', complete: false },
            ...[450000, 447000].flatMap((age, index) => [
                {
                    observedAt: now - age,
                    generation: '1',
                    kind: 'FLOW',
                    input: String(index ? 7 : 5),
                    output: '0',
                    complete: true
                },
                { observedAt: now - age, generation: '1', kind: 'FLOW_OBSERVATION', complete: true }
            ])
        ]
    };
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('#refresh').getAttribute('aria-busy') === 'false');
    assert.equal(
        await page.locator('.tracking-view .tracking-count-full').first().innerText(),
        '12',
        'Full-range totals exclude the flush ending exactly at the range start'
    );
    box = await hover(0.48);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.52, box.y + 40);
    await page.mouse.up();
    box = await hover(0.55);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.57, box.y + 40);
    await page.mouse.up();
    await hover(0.5);
    assert.equal(await value('Input'), 7n, 'The first cropped bin excludes the previous flush at its left boundary');
    const intervalLabel = await page
        .locator('.tracking-view .u-legend tr')
        .filter({ hasText: 'Time' })
        .locator('.u-value')
        .innerText();
    const times = [...intervalLabel.matchAll(/(\d{2}):(\d{2}):(\d{2})/g)].map(
        (match) => Number(match[1]) * 3600 + Number(match[2]) * 60 + Number(match[3])
    );
    assert.equal(
        (times[1] - times[0] + 86400) % 86400,
        3,
        'Fine zoom reaches an observed cadence outside the nice-interval list'
    );
    assert.equal(
        await page.locator('.tracking-view .u-legend tr').filter({ hasText: 'Stored' }).locator('.u-value').innerText(),
        '—',
        'Cropping after an offscreen gap never revives an earlier stock baseline'
    );
});

test('sparse stock remains held between real measurements and Net keeps exact interval differences', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    await page.clock.setFixedTime(now);
    const point = (age, kind, fields = {}) => ({
        observedAt: now - age,
        generation: '1',
        kind,
        complete: true,
        ...fields
    });
    options.resourceHistory = {
        baseline: point(1000000, 'STOCK', { resource: { ...iron, quantity: '20' } }),
        complete: true,
        truncated: false,
        points: [
            point(452500, 'OBSERVATION'),
            point(451000, 'FLOW', { input: '9223372036854775807', output: '9223372036854775804' }),
            point(450000, 'STOCK', { resource: { ...iron, quantity: '80' } }),
            point(446000, 'FLOW', { input: '3', output: '9' }),
            point(441000, 'FLOW_OBSERVATION')
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.waitForFunction(
        () =>
            document.querySelector('.tracking-view .uplot') &&
            document.querySelector('#refresh').getAttribute('aria-busy') === 'false'
    );
    await page.evaluate(() => document.fonts.ready);
    const area = page.locator('.tracking-view .u-over');
    await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
    let bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.48, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.52, bounds.y + 40);
    await page.mouse.up();
    for (const style of ['pixel', 'smooth']) {
        await page.locator('.tracking-view select').nth(1).selectOption(style);
        bounds = await area.boundingBox();
        for (const [position, net, stored] of [
            [0.45, '3', '20'],
            [0.6, '-6', '80'],
            [0.72, '0', '80'],
            [0.9, '—', '80']
        ]) {
            await page.mouse.move(bounds.x + bounds.width * position, bounds.y + 40);
            const value = (name) =>
                page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('.u-value');
            assert.equal(await value('Net').innerText(), net);
            assert.ok((await value('Stored').innerText()).startsWith(stored));
        }
        const raster = await page.locator('.tracking-view .uplot').evaluate((root) => {
            const canvas = root.querySelector('canvas'),
                box = canvas.getBoundingClientRect(),
                plot = root.querySelector('.u-over').getBoundingClientRect();
            const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
            const scale = canvas.width / box.width;
            const goldRows = (fraction) => {
                const x = Math.round((plot.left - box.left + plot.width * fraction) * scale),
                    rows = [];
                for (let y = Math.ceil((plot.top - box.top) * scale); y < (plot.bottom - box.top) * scale; y++) {
                    const i = (y * canvas.width + x) * 4;
                    if (pixels[i] === 214 && pixels[i + 1] === 155 && pixels[i + 2] === 40 && pixels[i + 3] > 100)
                        rows.push(y);
                }
                return rows;
            };
            let markerHeight = 0;
            for (let fraction = 0.39; fraction < 0.46; fraction += 1 / plot.width)
                markerHeight = Math.max(markerHeight, goldRows(fraction).length);
            return { before: goldRows(0.3), held: goldRows(0.47), markerHeight };
        });
        assert.ok(raster.before.length > 0 && raster.held.length > 0, `${style}: ${JSON.stringify(raster)}`);
        assert.ok(Math.abs(raster.before[0] - raster.held[0]) <= 2, 'Stored stays level until the next measurement');
        assert.ok(
            raster.markerHeight > raster.before.length,
            `An unchanged real observation has a marker; synthetic carry points do not: ${JSON.stringify(raster)}`
        );
    }
});

test('flow bars and hover span the entire aggregation interval independently of stock samples', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Math.floor(Date.now() / 30000) * 30000;
    await page.clock.setFixedTime(now);
    const stock = (age, quantity) => ({
        observedAt: now - age,
        generation: '1',
        kind: 'STOCK',
        resource: { ...iron, quantity: String(quantity) },
        complete: true
    });
    options.resourceHistory = {
        baseline: stock(1000000, 10),
        complete: true,
        truncated: false,
        points: [
            stock(454900, 20),
            stock(454000, 40),
            stock(452500, 15),
            stock(450100, 60),
            { observedAt: now - 450001, generation: '1', kind: 'FLOW', input: '123', output: '45', complete: true }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    const area = page.locator('.tracking-view .u-over');
    await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
    let bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.48, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.52, bounds.y + 40);
    await page.mouse.up();
    bounds = await area.boundingBox();
    for (const position of [0.38, 0.49]) {
        await page.mouse.move(bounds.x + bounds.width * position, bounds.y + 40);
        await page.waitForFunction(
            () =>
                [...document.querySelectorAll('.tracking-view .u-legend tr')]
                    .find((row) => row.querySelector('th')?.textContent.includes('Input'))
                    ?.querySelector('.u-value')?.textContent === '123'
        );
        assert.equal(
            await page
                .locator('.tracking-view .u-legend tr')
                .filter({ hasText: 'Output' })
                .locator('.u-value')
                .innerText(),
            '45'
        );
    }
    const filled = await page.locator('.tracking-view .uplot').evaluate((root) => {
        const canvas = root.querySelector('canvas'),
            box = canvas.getBoundingClientRect(),
            plot = root.querySelector('.u-over').getBoundingClientRect();
        const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        return [0.38, 0.49].map((fraction) => {
            const x = Math.round(((plot.left - box.left + plot.width * fraction) * canvas.width) / box.width);
            let green = 0;
            for (let y = 0; y < canvas.height; y++) {
                const i = (y * canvas.width + x) * 4;
                if (
                    data[i] >= 80 &&
                    data[i] <= 100 &&
                    data[i + 1] >= 155 &&
                    data[i + 1] <= 175 &&
                    data[i + 2] >= 100 &&
                    data[i + 2] <= 115 &&
                    data[i + 3] > 30
                )
                    green++;
            }
            return green;
        });
    });
    assert.ok(
        filled.every((pixels) => pixels > 10),
        'The bar fills both sides of its time interval: ' + JSON.stringify(filled)
    );
    // A missing neighboring interval must never borrow the previous bucket's counters.
    await page.mouse.move(bounds.x + bounds.width * 0.7, bounds.y + 40);
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('.tracking-view .u-legend tr')]
                .find((row) => row.querySelector('th')?.textContent.includes('Input'))
                ?.querySelector('.u-value')?.textContent === '—'
    );
    await page.mouse.move(bounds.x + bounds.width * 0.38, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.49, bounds.y + 40);
    await page.mouse.up();
    bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.5, bounds.y + 40);
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('.tracking-view .u-legend tr')]
                .find((row) => row.querySelector('th')?.textContent.includes('Input'))
                ?.querySelector('.u-value')?.textContent === '123'
    );
});

for (const touch of [false, true]) {
    test(`tracking cursor pins, moves and releases without confusing a drag with a click (${touch ? 'touch' : 'mouse'})`, async (t) => {
        const { page, options, base } = await fixture(
            t,
            '',
            touch ? { viewport: { width: 390, height: 844 }, hasTouch: true } : {}
        );
        const now = Math.floor(Date.now() / 30000) * 30000;
        await page.clock.setFixedTime(now);
        options.resourceHistory = {
            baseline: {
                observedAt: now - 1000000,
                generation: '1',
                kind: 'STOCK',
                resource: { ...iron, quantity: '10' },
                complete: true
            },
            complete: true,
            truncated: false,
            points: [675001, 225001].map((age, index) => ({
                observedAt: now - age,
                generation: '1',
                kind: 'FLOW',
                input: String(index ? 222 : 111),
                output: '0',
                complete: true
            }))
        };
        await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
        const area = page.locator('.tracking-view .u-over');
        await page.waitForFunction(
            () =>
                document.querySelector('.tracking-view .uplot') &&
                document.querySelector('#refresh').getAttribute('aria-busy') === 'false'
        );
        await page.evaluate(() => document.fonts.ready);
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        let bounds = await area.boundingBox();
        const choose = async (fraction) => {
            await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
            bounds = await area.boundingBox();
            const x = bounds.x + bounds.width * fraction,
                y = bounds.y + 40;
            if (touch) await page.touchscreen.tap(x, y);
            else await page.mouse.click(x, y);
        };
        const input = page.locator('.tracking-view .u-legend tr').filter({ hasText: 'Input' }).locator('.u-value');
        await choose(0.247);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '111', 'Selection remains after the pointer/finger leaves');
        await choose(0.747);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '222', 'A new click moves the pinned timestamp');
        await page.locator('.tracking-view select').nth(1).selectOption('smooth');
        assert.equal(await input.innerText(), '222', 'Changing rendering style preserves the pinned timestamp');
        await page.locator('#refresh').click();
        await page.waitForFunction(() => document.querySelector('#refresh').getAttribute('aria-busy') === 'false');
        assert.equal(await input.innerText(), '222', 'Refreshing data preserves the pinned timestamp');
        const originalViewport = page.viewportSize();
        const time = page.locator('.tracking-view .u-legend tr').filter({ hasText: 'Time' }).locator('.u-value');
        const pinnedTime = await time.innerText();
        await page.setViewportSize({ ...originalViewport, width: originalViewport.width + 40 });
        await page.evaluate(
            () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
        );
        assert.equal(await input.innerText(), '222');
        assert.equal(
            await time.innerText(),
            pinnedTime,
            'Resizing preserves the selected time, not its old pixel position'
        );
        await page.setViewportSize(originalViewport);
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        bounds = await area.boundingBox();
        await choose(0.747);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '—', 'Clicking the same point releases selection');
        await choose(0.247);
        await page.keyboard.press('Escape');
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '—');
        await choose(0.247);
        await page.locator('.tracking-view').getByRole('button', { name: 'Reset zoom' }).click();
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '—', 'Reset zoom also clears the pinned timestamp');
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        bounds = await area.boundingBox();
        await choose(0.747);
        const y = bounds.y + 40;
        if (touch) {
            const client = await page.context().newCDPSession(page);
            await client.send('Input.dispatchTouchEvent', {
                type: 'touchStart',
                touchPoints: [{ x: bounds.x + bounds.width * 0.2, y }]
            });
            await client.send('Input.dispatchTouchEvent', {
                type: 'touchMove',
                touchPoints: [{ x: bounds.x + bounds.width * 0.25, y }]
            });
            await client.send('Input.dispatchTouchEvent', {
                type: 'touchMove',
                touchPoints: [{ x: bounds.x + bounds.width * 0.3, y }]
            });
            await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
            await client.detach();
        } else {
            await page.mouse.move(bounds.x + bounds.width * 0.2, y);
            await page.mouse.down();
            await page.mouse.move(bounds.x + bounds.width * 0.3, y, { steps: 4 });
            await page.mouse.up();
        }
        await choose(0.47);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '111', 'Dragging while pinned zooms instead of clicking another point');
        if (!touch) {
            await page.mouse.dblclick(bounds.x + bounds.width * 0.5, bounds.y + 40);
            await page.mouse.move(1, 1);
            assert.equal(await input.innerText(), '—');
        } else await page.locator('.tracking-view').getByRole('button', { name: 'Reset zoom' }).click();
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        bounds = await area.boundingBox();
        await choose(0.747);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '222', 'Reset restores the original time range');
        if (!touch) {
            await page.mouse.move(bounds.x + bounds.width * 0.2, y);
            await page.mouse.down();
            await page.mouse.move(bounds.x + bounds.width * 0.3, y);
            await page.mouse.move(bounds.x + bounds.width * 0.2, y);
            await page.mouse.up();
            await page.mouse.move(1, 1);
            assert.equal(await input.innerText(), '—', 'A drag returning to its origin must not become a click');
        }
    });
}

test('recent tracking shows independent measured periods and does not advance stock time with flow updates', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    options.liveView = { generation: '1', revision: '1' };
    options.recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: now - 60000,
        flowSince: now - 30000,
        stockSince: now - 360000,
        flowComplete: true,
        stockComplete: true,
        resources: { iron: { input: '100', output: '10', stockChange: '42' } }
    };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    const item = page.locator('#items button').filter({ hasText: 'Iron' }).first();
    await item.hover();
    const tooltip = page.locator('#resource-tooltip');
    await tooltip.locator('.recent-flow').waitFor();
    assert.match(await tooltip.locator('.recent-flow').innerText(), /\(2 min\)/);
    assert.match(await tooltip.locator('.recent-stock').innerText(), /\(1 h\)/);
    assert.match(await tooltip.locator('.recent-stock-period').innerText(), /5 min/);
    assert.match(await tooltip.locator('.recent-flow-period').innerText(), /30 s/);
    const sampledAt = new Date(now - 60000).toISOString();
    assert.equal(await tooltip.locator('time').getAttribute('datetime'), sampledAt);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [], recent: { ...options.recent, observedAt: now + 30000, flowObservedAt: now + 30000, resources: { iron: { input: '200', output: '10', stockChange: '42' } } } })}\n\n`
    );
    await tooltip.locator('[aria-label="+200"]').waitFor();
    assert.equal(await tooltip.locator('time').getAttribute('datetime'), sampledAt);
    assert.match(await tooltip.locator('.recent-stock-period').innerText(), /5 min/);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 0);
    await item.click();
    const details = page.locator('#details');
    await details.locator('.recent-stock').waitFor();
    assert.match(await details.locator('.recent-stock').innerText(), /\(1 h\)/);
    assert.equal(await details.locator('time').getAttribute('datetime'), sampledAt);
});

test('flow-only SSE updates and expiry refresh hovered summaries without history reads', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.now();
    options.liveView = { generation: '1', revision: '1' };
    options.recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: null,
        flowSince: now - 120000,
        stockSince: null,
        flowComplete: true,
        stockComplete: false,
        resources: { iron: { input: '100', output: '100', stockChange: null } }
    };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    await page.locator('#items button').filter({ hasText: 'Iron' }).first().hover();
    await page.locator('#resource-tooltip [aria-label="+100"]').waitFor();
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [], recent: { ...options.recent, observedAt: now + 120000, resources: { iron: { input: '0', output: '0', stockChange: null } } } })}\n\n`
    );
    await page.locator('#resource-tooltip [aria-label="−0"]').waitFor();
    assert.equal(await page.locator('#resource-tooltip [aria-label="+100"]').count(), 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 0);
});

test('header confirms live events and manual resync waits for a new stream before showing live', async (t) => {
    const { page, options, base } = await fixture(t);
    options.liveView = { generation: '1', revision: '1' };
    options.delayStreamReady = true;
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    const control = page.locator('#refresh');
    const dot = control.locator('.live-icon');
    assert.equal(await dot.isVisible(), false, 'A subscription is not a confirmed live connection');
    const initialBounds = await control.boundingBox();
    const frame = () =>
        control.evaluate((button) => {
            const style = getComputedStyle(button);
            return { background: style.backgroundColor, border: style.borderTopColor, shadow: style.boxShadow };
        });
    const initialFrame = await frame();
    options.itemStreams[0].write('event: ready\ndata: {}\n\n');
    await dot.waitFor({ state: 'visible' });
    assert.deepEqual(await control.boundingBox(), initialBounds);
    assert.deepEqual(await frame(), initialFrame, 'The live control retains its normal button frame');
    await control.focus();
    const time = page.locator('#resource-tooltip time');
    await time.waitFor();
    const firstEvent = await time.getAttribute('datetime');
    await page.clock.fastForward(1000);
    options.itemStreams[0].write(': keepalive\n\n');
    assert.equal(await time.getAttribute('datetime'), firstEvent);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [], icons: null })}\n\n`
    );
    await page.waitForFunction(
        (previous) => document.querySelector('#resource-tooltip time')?.dateTime !== previous,
        firstEvent
    );
    const reads = options.requests.filter((request) => request.path.endsWith('/items')).length;
    const reconnect = page.waitForResponse((response) => response.url().includes('/items/events'));
    await control.click();
    await reconnect;
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, reads + 1);
    assert.equal(await dot.isVisible(), false);
    assert.equal(await control.getAttribute('aria-busy'), 'true');
    options.itemStreams.at(-1).write('event: ready\ndata: {}\n\n');
    await dot.waitFor({ state: 'visible' });
    options.itemStreams.at(-1).end();
    await dot.waitFor({ state: 'hidden' });
    await page.locator('a[data-view="web-settings"]').click();
    assert.equal(await dot.isVisible(), false);
    await page.locator('#auto-refresh').uncheck();
    await page.locator('#terminal-link').click();
    await page.locator('#items button').first().waitFor();
    assert.equal(await dot.isVisible(), false);
    assert.equal(await control.getAttribute('aria-busy'), 'false');
    const refresh = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/items'));
    await control.click();
    const snapshot = await refresh;
    assert.equal(new URL(snapshot.url()).searchParams.has('live'), false);
    assert.equal(options.itemStreams.length, 0);
});

test('live status can be focused and resynced by touch without changing header geometry', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 844 }, hasTouch: true });
    options.liveView = { generation: '1', revision: '1' };
    options.delayStreamReady = true;
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    const control = page.locator('#refresh');
    const before = await control.boundingBox();
    options.itemStreams[0].write('event: ready\ndata: {}\n\n');
    await control.locator('.live-icon').waitFor({ state: 'visible' });
    assert.deepEqual(await control.boundingBox(), before);
    await control.focus();
    await page.getByRole('tooltip').locator('time').waitFor();
    const reconnected = page.waitForResponse((response) => response.url().includes('/items/events'));
    await control.tap();
    await reconnected;
    assert.equal(await control.locator('.live-icon').isVisible(), false);
    assert.deepEqual(await control.boundingBox(), before);
});

test('terminal applies compact updates, fetches a fresh snapshot on reconnect and stops outside terminal', async (t) => {
    const { page, options, base } = await fixture(t);
    options.liveView = { generation: '9223372036854775000', revision: '1' };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    await page.locator('#items button').first().waitFor();
    const initialReads = options.requests.filter((request) => request.path.endsWith('/items')).length;
    options.itemStreams[0].write(
        `event: delta\nid: 9223372036854775000:2\ndata: ${JSON.stringify({
            view: { generation: '9223372036854775000', revision: '2' },
            updates: [{ itemKey: iron.itemKey, quantity: 17, craftable: iron.craftable }],
            definitions: [{ ...iron, displayName: 'Live updated iron', quantity: 17 }],
            removed: [],
            unkeyed: [{ ...iron, itemKey: null, displayName: 'Live essentia', quantity: 3 }],
            icons: null
        })}\n\n`
    );
    await page.getByText('Live updated iron', { exact: true }).waitFor();
    await page.getByText('Live essentia', { exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, initialReads);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '9223372036854775000', revision: '3' },
            updates: [{ itemKey: iron.itemKey, quantity: 23, craftable: iron.craftable }],
            definitions: [],
            removed: []
        })}\n\n`
    );
    await page
        .locator('#items button')
        .filter({ hasText: 'Live updated iron' })
        .getByText('23', { exact: true })
        .waitFor();
    assert.equal(await page.getByText('Live essentia', { exact: true }).isVisible(), true);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '9223372036854775000', revision: '4' },
            updates: [],
            definitions: [],
            removed: [iron.itemKey],
            unkeyed: [],
            icons: null
        })}\n\n`
    );
    await page.getByText('Live updated iron', { exact: true }).waitFor({ state: 'hidden' });
    await page.getByText('Live essentia', { exact: true }).waitFor({ state: 'hidden' });
    options.liveView = { generation: '9223372036854775000', revision: '4' };
    options.itemsA = [{ ...iron, displayName: 'Fresh snapshot iron' }];
    const reconnected = page.waitForResponse((response) => response.url().includes('subscription=subscription-4'));
    options.itemStreams[0].end();
    await reconnected;
    await page.getByText('Fresh snapshot iron', { exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, initialReads + 1);
    options.liveView = { generation: '9223372036854775000', revision: '5' };
    options.itemsA = [{ ...iron, displayName: 'Reset snapshot iron' }];
    options.itemStreams[0].write('event: invalidate\ndata: {}\n\n');
    await page.getByText('Reset snapshot iron', { exact: true }).waitFor();
    await page.goto(`${base}#/web-settings`);
    await page.locator('#auto-refresh').uncheck();
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByText('Reset snapshot iron', { exact: true }).waitFor();
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(options.itemStreams.length, 0);
});
