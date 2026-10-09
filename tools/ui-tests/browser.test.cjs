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
const iron = {
    displayName: 'Iron Ingot',
    registryNamespace: 'minecraft',
    registryPath: 'iron_ingot',
    componentCount: 0,
    damage: 0,
    quantity: 128640,
    craftable: true,
    resourceType: 'ITEM',
    itemKey: 'iron'
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
    itemKey: 'quartz'
};
const readyPlan = {
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
    name: 'Assembler',
    isBusy: false,
    supportsPause: false,
    isPaused: false,
    availableStorage: 8192,
    usedStorage: 0,
    coProcessors: 1
};
const cpuWork = {
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
        settings: { [gridA]: { isTracked: false, name: '' }, [gridB]: { isTracked: false, name: '' } },
        accessSources: {}
    };
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
                                      user: options.loggedOut
                                          ? null
                                          : { username: options.username, isAdmin: options.isAdmin }
                                  }
                                : null
                    })
                );
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
                response.statusCode = 202;
                response.end(JSON.stringify({ status: 'OK', data: { jobId: 7 } }));
            } else if (resource.endsWith('/submit')) {
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
                                  ? { isDone: false, isSimulating: false, bytesTotal: 0, plan: null }
                                  : options.plan
                    })
                );
            } else if (resource.endsWith('/cpus')) {
                const gridKey = resource.split('/')[3];
                const cpuError = options.cpuErrors?.[gridKey] || options.cpuError;
                const result = JSON.stringify({
                    status: cpuError || 'OK',
                    data: cpuError ? null : options.cpusByGrid?.[gridKey] || options.cpus,
                    icons:
                        url.searchParams.get('icons') === 'true'
                            ? options.iconsByGrid?.[gridKey] || options.icons
                            : null
                });
                options.activeCpus = (options.activeCpus || 0) + 1;
                options.maxActiveCpus = Math.max(options.maxActiveCpus || 0, options.activeCpus);
                response.once('close', () => options.activeCpus--);
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
            } else if (resource.endsWith('/items')) {
                const first = resource.includes(gridA);
                const send = () => {
                    response.statusCode = options.status;
                    response.end(
                        JSON.stringify(
                            options.status === 200
                                ? {
                                      status: 'OK',
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
        const resourceRoot = relative.endsWith('.js') ? compiledResources : resources;
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
    return { page, options, base: `http://127.0.0.1:${server.address().port}${mount}/?ui=next` };
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

test('autostart calculates once with native CPU choice and preserves the resource terminal', async (t) => {
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
    await page.clock.fastForward(1200);
    await page
        .getByRole('status')
        .filter({ hasText: /submitted/i })
        .waitFor();
    assert.match(page.url(), /\/items$/);
    assert.equal(await dialog.isVisible(), false);
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).getAttribute('aria-pressed'), 'true');
    assert.deepEqual(
        options.requests.filter((r) => r.path.endsWith('/submit')).map((r) => r.body),
        [{}]
    );
    assert.deepEqual(
        options.requests.filter((r) => r.method === 'POST' && r.path.endsWith('/crafting-plans')).map((r) => r.body),
        [{ itemKey: 'iron', quantity: 23 }]
    );
    await page.clock.fastForward(6000);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/submit')).length, 1);
});

test('autostart failures open the plan without repeating mutations or losing the native reason', async (t) => {
    for (const failure of ['missing', 'FAIL', 'TIMEOUT', 'NO_PERMISSIONS']) {
        const { page, options, base } = await fixture(t);
        if (failure === 'missing') options.plan = { ...readyPlan, isSimulating: true };
        else if (failure === 'TIMEOUT') options.fault = { operation: 'submit', status: failure };
        else {
            options.submitStatus = failure;
            options.submitReason = '<CPU became unavailable>';
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
        if (failure === 'NO_PERMISSIONS')
            assert.equal(
                await page
                    .getByRole('list', { name: 'Plan resources', exact: true })
                    .getByRole('button', { name: /Iron Ingot/ })
                    .count(),
                0
            );
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
    assert.equal(await page.getByText('Iron Ingot', { exact: true }).isVisible(), false);
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
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /submitted/i })
        .waitFor();
    options.fault = { operation: 'create', status: 'BAD_PARAM' };
    await page.getByRole('button', { name: 'Craft', exact: true }).click();
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
    assert.equal(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query, '');
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
    assert.equal(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query, '');
    await page.waitForFunction(() => document.querySelector('#items .resource-icon')?.style.backgroundImage, null, {
        timeout: 4000
    });
    options.packDelay = 0;
    options.packError = true;
    await poll(page);
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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

test('legacy compact icon choice migrates once and survives reload', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.addInitScript(() => {
        const key = 'ae2web:/:theme:default';
        if (!localStorage.getItem(key))
            localStorage.setItem(key, JSON.stringify({ resourceIcons: true, terminalDisplay: 'compact' }));
    });
    await page.goto(`${base}#/web-settings`);
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await page.waitForFunction(() => document.querySelector('#terminal-display').value === 'icons');
    await mode.selectOption('names');
    await page.reload();
    await mode.waitFor();
    assert.equal(await mode.inputValue(), 'names');
});

test('legacy names choice receives a dismissible icon notice outside the terminal', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.addInitScript(() => {
        const key = 'ae2web:/:theme:default';
        if (!localStorage.getItem(key))
            localStorage.setItem(key, JSON.stringify({ resourceIcons: false, terminalDisplay: 'compact' }));
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const enable = page.getByRole('button', { name: 'Enable icons', exact: true });
    await enable.waitFor({ timeout: 3000 });
    assert.equal(options.requests.filter((r) => r.path.endsWith('/items')).at(-1).query, '');
    const panel = await page.getByRole('navigation').boundingBox();
    const notice = await enable.boundingBox();
    const terminal = await page.getByRole('searchbox').boundingBox();
    assert.ok(notice.y > panel.y + panel.height && notice.y + notice.height < terminal.y);
    await page.getByRole('button', { name: 'Dismiss icon suggestion', exact: true }).click();
    await page.reload();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.isVisible(), false);
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'names');
});

// Public browser seam: compact presentation persists without changing resource identity or CPU presentation.
test('enabling suggested icons persists and a later names choice does not nag', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    await page.addInitScript(() => {
        const key = 'ae2web:/:theme:default';
        if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({ resourceIcons: false }));
    });
    await page.goto(`${base}#/grids/${gridA}/items`);
    const enable = page.getByRole('button', { name: 'Enable icons', exact: true });
    await enable.click({ timeout: 3000 });
    await page.reload();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.isVisible(), false);
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    const mode = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    assert.equal(await mode.inputValue(), 'both');
    await mode.selectOption('names');
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
    assert.equal(await item.locator('.resource-icon').isVisible(), false);
    await page.getByRole('link', { name: 'CPUs', exact: true }).click();
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuItem = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ });
    await cpuItem.waitFor();
    assert.equal(await cpuItem.locator('.resource-icon').isVisible(), false);
    assert.equal(options.requests.filter((r) => r.path.endsWith('/cpus/cpu-a')).at(-1).query, '');
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
    assert.equal(await cpuItem.getByText(iron.displayName, { exact: true }).isVisible(), true);
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await mode.selectOption('both');
    await page.reload();
    await mode.waitFor();
    await page.waitForFunction(
        (select) => !select.querySelector('option[value="icons"]').disabled,
        await mode.elementHandle()
    );
    assert.equal(await mode.inputValue(), 'both');
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
                assert.equal(icon.width, 40);
                assert.equal(icon.height, 40);
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
        busy: { ...cpu, name: 'Busy processor', isBusy: true, finalOutput: iron, icon },
        idle: { ...cpu, name: 'Idle processor', finalOutput: iron, icon },
        unknown: { ...cpu, name: 'Unknown output', isBusy: true, finalOutput: null, icon }
    };
    options.history = [
        { ...historyEntry, icon },
        { ...historyEntry, id: 2, icon: null }
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
    await page.getByRole('link', { name: 'History', exact: true }).click();
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
    await page.goto(`${base}#/grids/${gridA}/history`);
    await first.waitFor();
    assert.ok((await first.innerText()).includes('Iron Ingot'), 'history remains readable in terminal icons-only mode');
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
        icon,
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
        icon: { page: 0, x: 0, y: 0 },
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
        options.cpus = { busy: { ...cpu, name: 'Busy processor', isBusy: true, finalOutput: iron, icon } };
        options.history = [{ ...historyEntry, icon }];
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
        options.cpus = { busy: { ...cpu, name: 'Busy processor', isBusy: true, finalOutput: iron, icon } };
        options.history = [{ ...historyEntry, icon }];
        const endpoint = view === 'cpus' ? '/cpus' : '/crafting-history';
        let release;
        const captured = new Promise((resolve) => {
            release = resolve;
        });
        await page.route(`**${endpoint}?icons=true`, (route) => release(route));
        await page.goto(`${base}#/grids/${gridA}/${view}`);
        const delayed = await captured;
        await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
        'cpu-a': { ...cpuWork, items: [{ ...cpuWork.items[0], itemKey: 'iron', icon: { page: 0, x: 0, y: 0 } }] }
    };
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
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).at(-1).query, '?icons=true');
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.reload();
    assert.equal(await page.getByRole('combobox', { name: 'Terminal display', exact: true }).inputValue(), 'names');
    const before = pages().length;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(pages().length, before);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).at(-1).query, '');
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
    const header = page.locator('header');
    await header.getByText(options.username, { exact: true }).waitFor();
    await header.getByRole('button', { name: 'Log out', exact: true }).waitFor();
    assert.equal(await header.getByRole('combobox').count(), 0);
    const theme = page.getByRole('combobox', { name: 'Theme', exact: true });
    assert.equal(await theme.isDisabled(), true);
    assert.deepEqual(await theme.getByRole('option').allTextContents(), ['Default']);
    const appearance = page.getByRole('combobox', { name: 'Appearance', exact: true });
    await appearance.selectOption('dark');
    await page.getByRole('link', { name: 'Server settings', exact: true }).click();
    assert.match(page.url(), /#\/server-settings$/);
    await page.getByRole('heading', { name: 'Server settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Appearance', exact: true }).isVisible(), false);
    assert.equal(
        await page.getByRole('link', { name: 'Network', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/settings`
    );
    await page.getByRole('link', { name: 'Network', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).waitFor();
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
    const headings = await about.getByRole('heading').allTextContents();
    assert.ok(headings.indexOf('Mod') >= 0);
    assert.ok(headings.indexOf('Mod') < headings.indexOf('Active theme'));
    assert.ok(headings.indexOf('Active theme') < headings.indexOf('Included resources'));
    await about.getByText('AE2 Web Integration', { exact: true }).waitFor();
    assert.equal(await about.getByText('kuba6000').count(), 2);
    assert.equal(await about.getByText('LGPL-3.0-or-later').count(), 2);
    await about.getByText('Default', { exact: true }).waitFor();
    await about.getByText('Monocraft', { exact: true }).waitFor();
    await about.getByText(/SIL Open Font License\s*1\.1/).waitFor();
    await about
        .getByRole('row')
        .filter({ hasText: 'HackerNoon Pixel Icon Library' })
        .getByText('MIT', { exact: true })
        .waitFor({ timeout: 3000 });
    for (const href of [
        'https://github.com/kuba6000/AE2-Web-Integration',
        'https://github.com/kuba6000/AE2-Web-Integration/issues',
        'https://github.com/IdreesInc/Monocraft',
        'https://github.com/hackernoon/pixel-icon-library'
    ])
        assert.ok(await about.locator(`a[href="${href}"]`).count(), `About must link to ${href}`);
    assert.doesNotMatch(await about.innerText(), /Lucide|Font Awesome|Material Icons|TERMS AND CONDITIONS|PREAMBLE/i);
    await page.getByRole('link', { name: 'Network', exact: true }).click();
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    const aboutLink = page.locator('a[href="#/about"]');
    assert.notEqual(await aboutLink.innerText(), 'About');
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
    await page.getByRole('link', { name: 'Home', exact: true }).click();
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
    assert.equal(await page.getByRole('button', { name: 'Refresh', exact: true }).count(), 0);
    assert.equal(await page.getByRole('checkbox', { name: 'Refresh automatically' }).count(), 0);
    assert.equal(await page.locator('header').getByRole('combobox').count(), 0);
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    const automatic = page.getByRole('checkbox', { name: 'Refresh automatically' });
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
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).waitFor();
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
    await page.getByRole('link', { name: 'History', exact: true }).click();
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
    const timeline = page.getByRole('region', { name: 'Cobalt Ingot', exact: true });
    await timeline.waitFor();
    assert.equal(await timeline.getByText('example:cobalt_ingot', { exact: true }).isVisible(), true);
    await assertFormatted(timeline.getByRole('heading', { name: 'Cobalt Ingot', exact: true }));
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
    assert.equal(await tooltip.isVisible(), false);
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
            damage: null,
            quantity: 1000,
            craftable: false,
            resourceType: 'FLUID',
            itemKey: 'water'
        },
        {
            displayName: 'Essentia',
            registryNamespace: null,
            registryPath: null,
            componentCount: null,
            damage: null,
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
        componentCount: null,
        damage: null
    };
    const finalOutput = { ...metadata, itemKey: null, quantity: 12 };
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], ...metadata }] };
    options.cpuDetails = { 'cpu-a': { ...cpuWork, finalOutput, items: [{ ...cpuWork.items[0], ...metadata }] } };
    options.history = [{ ...historyEntry, finalOutput }];
    options.historyDetail = { ...historyDetail, finalOutput, items: [{ ...historyDetail.items[0], ...metadata }] };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    const resource = page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Essentia/ });
    await resource.waitFor();
    assert.equal(await resource.locator('code').count(), 0);
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    const cpuResource = page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Essentia/ });
    await cpuResource.hover();
    const tooltip = page.getByRole('tooltip');
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
    await historyResource.waitFor();
    assert.equal(await historyResource.locator('code').count(), 0);
    await page.getByRole('heading', { name: 'Essentia', level: 3, exact: true }).waitFor();
    const timeline = page.getByRole('region', { name: 'Essentia', exact: true });
    await timeline.waitFor();
    assert.equal(await timeline.locator('code').count(), 0);
    assert.doesNotMatch(await timeline.innerText(), /null|undefined/);
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
            damage: null
        },
        {
            ...iron,
            displayName: 'Steam',
            registryNamespace: 'addon',
            registryPath: 'steam',
            itemKey: 'steam',
            resourceType: 'FLUID',
            damage: null,
            quantity: 0
        },
        {
            ...iron,
            displayName: 'Energy',
            registryNamespace: 'addon',
            registryPath: 'energy',
            itemKey: 'energy',
            resourceType: 'OTHER',
            componentCount: null,
            damage: null
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

// Saved browser preferences are a supported public format; missing direction adopts the old criterion order.
for (const saved of [
    { sort: 'quantity', filter: 'craftable', expected: ['Iron Ingot', 'Certus Quartz Crystal'] },
    { sort: 'name', filter: 'stored', expected: ['Certus Quartz Crystal', 'Iron Ingot'] },
    { sort: 'id', filter: 'all', expected: ['Certus Quartz Crystal', 'Iron Ingot'] }
]) {
    test(`terminal restores legacy ${saved.sort} preferences without coupling later order changes`, async (t) => {
        const { page, options, base } = await fixture(t, '/ae2');
        options.itemsA = [iron, { ...quartz, craftable: true }];
        await page.addInitScript(({ sort, filter }) => {
            const key = 'ae2web:/ae2/:ui';
            if (!localStorage.getItem(key))
                localStorage.setItem(key, JSON.stringify({ sort, filter, autoRefresh: false }));
        }, saved);
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        assert.deepEqual(await page.locator('#items strong').allTextContents(), saved.expected);
        const order = page.getByRole('button', { name: /^Sort order:/ });
        assert.match(await order.getAttribute('aria-label'), saved.sort === 'quantity' ? /Descending/ : /Ascending/);
        await page.getByRole('button', { name: /^Sort by:/ }).click();
        await page.reload();
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        assert.match(await order.getAttribute('aria-label'), saved.sort === 'quantity' ? /Descending/ : /Ascending/);
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).check();
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
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
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
    await page.getByRole('link', { name: 'History', exact: true }).click();
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
        assert.ok((await page.locator('#cpu-panel').innerText()).includes(expected[5]));
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
    await page.getByRole('link', { name: 'History', exact: true }).click();
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
                return { x: box.x, y: box.y, right: box.right, fontSize: style.fontSize, color: style.color };
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
            await page.getByRole('link', { name: 'History', exact: true }).click();
            const first = page.getByRole('link', { name: /Precision assembly.*#100/ });
            await first.waitFor();
            const heading = await measure(page.getByRole('heading', { name: 'History', exact: true }));
            for (const key of ['x', 'y'])
                assert.ok(Math.abs(heading[key] - expected[key]) < 0.1, `${key} history heading matches terminal`);
            for (const key of ['fontSize', 'color']) assert.equal(heading[key], expected[key]);
            const region = page.getByRole('region', { name: 'History', exact: true });
            const actualScroll = await measure(region);
            for (const key of ['x', 'y', 'right'])
                assert.ok(Math.abs(actualScroll[key] - expectedScroll[key]) < 0.1, `${key} scroll viewport matches`);
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
        await page.getByRole('link', { name: 'History', exact: true }).click();
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
        options.settings[gridA] = { isTracked: false, name: '' };
        await page.goto(`${base}&case=${status}#/grids/${gridA}/settings`);
        const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
        await tracking.check();
        options.settings[gridA] = { isTracked: true, name: '' };
        options.fault = { operation: 'settings', status };
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /save.*not confirmed/i })
            .waitFor({ timeout: 3000 });
        const count = options.requests.filter((request) => request.method === 'PATCH').length;
        await poll(page);
        await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    options.settings[gridA] = { isTracked: true, name: '' };
    await save.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: { isTracked: true, name: '' } })
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
    await page.getByRole('link', { name: 'History', exact: true }).click();
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
        body: JSON.stringify({ status: 'OK', data: { isTracked: false, name: '' } })
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
        body: JSON.stringify({ status: 'OK', data: { isTracked: false, name: '' } })
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
        await page.goto(`${base}&case=${result}#/grids/${gridA}/cpus/cpu-a`);
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isDisabled(), true);
        await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
    await page.goto(`${base}&case=late-read#/grids/${gridA}/cpus/cpu-a`);
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
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
    options.cpus['cpu-b'] = { ...cpu, isBusy: true, finalOutput: { ...iron, itemKey: 'iron' } };
    await page.reload();
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('radio', { name: /cpu-b/ }).evaluate((option) => option.disabled), true);
    await page.getByText(/Only idle CPUs/).waitFor();
    assert.equal(options.requests.filter((request) => request.method === 'POST').length, 1);
});

// Public seam: selecting a CPU opens its terminal, with separate resource tools and CPU controls.
test('CPU terminal replaces the CPU list with selected resources and returns to the list', async (t) => {
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
        0,
        'Opening current work must hide the CPU selection list'
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
    const details = page.getByRole('complementary', { name: 'CPU details', exact: true });
    assert.equal(
        await page.getByRole('heading', { name: 'Selected assembler', level: 2, exact: true }).isVisible(),
        true
    );
    assert.match(await details.textContent(), /8,192/);
    assert.match(await details.textContent(), /2,048/);
    assert.equal(
        await details
            .locator('dl > div')
            .filter({ has: page.getByText('Coprocessors', { exact: true }) })
            .locator('dd')
            .innerText(),
        '3'
    );
    assert.match(await details.textContent(), /Iron Ingot.*12/);
    assert.equal(await details.getByRole('button', { name: 'Pause current work', exact: true }).isEnabled(), true);
    await page.getByRole('link', { name: 'CPUs', exact: true }).click();
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).waitFor();
    assert.match(page.url(), /\/cpus$/);
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
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
        const panel = await page.getByRole('complementary', { name: 'CPU details', exact: true }).boundingBox();
        assert.ok(bounds.height > 100, 'Resources need a usable scroll viewport');
        if (viewport.width > 600) assert.ok(panel.x >= bounds.x + bounds.width - 1, 'CPU details sit beside resources');
        else assert.ok(panel.y >= bounds.y + bounds.height - 1, 'Mobile CPU details sit below resources');
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
            page.getByRole('link', { name: 'CPUs', exact: true }),
            page.getByRole('button', { name: 'Hide stored-only resources', exact: true }),
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
    await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
    await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
    await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
        await page.goto(`${base}&case=${status}#/grids/${gridA}/cpus/cpu-a`);
        options.fault = { operation: 'cancel', status };
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor({ timeout: 3000 });
        const count = options.requests.filter((request) => request.path.endsWith('/cancel')).length;
        await poll(page);
        await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
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
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
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
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
    await page
        .getByRole('radiogroup', { name: 'Crafting CPU', exact: true })
        .getByRole('radio', { name: /cpu-b/ })
        .check();
    options.submitStatus = 'OK';
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
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
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).count(), 0);
    options.planStatus = 'OK';
    options.plan = { ...readyPlan, isSimulating: true };
    await page.reload();
    await page
        .getByRole('status')
        .filter({ hasText: /missing resources/i })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], missing: 8 }] };
    await page.reload();
    await page
        .getByRole('list', { name: 'Plan resources', exact: true })
        .getByRole('button', { name: /Missing: 8/ })
        .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
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
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).count(), 0);
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
    for (const status of ['TIMEOUT', 'INTERNAL_ERROR', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId++}`);
        options.fault = { operation: 'submit', status };
        await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor({ timeout: 3000 });
        const count = options.requests.filter((request) => request.path.endsWith('/submit')).length;
        await poll(page);
        await page.getByRole('link', { name: 'Terminal', exact: true }).click();
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
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
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
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
    assert.match(await tooltip.innerText(), /65,536 B/);
    assert.match(await tooltip.innerText(), /12,345 B/);
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
    assert.match(await tooltip.innerText(), /8,192 B/);
    assert.match(await tooltip.innerText(), /4 coprocessors/);
    assert.match(await tooltip.innerText(), /Used storage unavailable/);
    assert.doesNotMatch(await tooltip.innerText(), /Iron|Paused|12,345/);
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
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    await page.waitForFunction(() =>
        document.querySelector('dialog .resource-icon').style.backgroundPosition.startsWith('calc(-1')
    );
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
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
    await page.getByRole('button', { name: 'Sort by: Registry ID', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Sort order: Descending', exact: true }).isVisible());
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
    const start = page.getByRole('button', { name: 'Start crafting', exact: true });
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
        assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).count(), 0);
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
        await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
        await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
    await page.waitForURL(new RegExp(`/grids/${gridA}/items$`));
    await cpuRequest.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'NO_PERMISSIONS', data: null })
    });
    await page.waitForTimeout(100);
    assert.match(page.url(), new RegExp(`/grids/${gridA}/items$`));
});

test('login errors keep the new UI selector and destination without loading a theme', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}&INVALID_PASSWORD#/grids/${gridA}/items`);
    await page.waitForFunction(() => !location.search.includes('INVALID_PASSWORD'));
    assert.equal(new URL(page.url()).searchParams.get('ui'), 'next');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    assert.equal(
        options.requests.some((request) => request.path.includes('/assets/web/themes/')),
        false
    );
});

test('logout returns to the login document even when its URL matches the current route', async (t) => {
    const { page, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/`);
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByPlaceholder('Enter password', { exact: true }).first().waitFor({ timeout: 5000 });
    assert.equal(new URL(page.url()).searchParams.get('ui'), 'next');
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
    await page.getByRole('link', { name: 'Home', exact: true }).click();
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
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).uncheck();
    await page.getByRole('combobox', { name: 'Appearance' }).selectOption('dark');
    await page.getByRole('combobox', { name: 'Language' }).selectOption('pl');
    await page.reload();
    await page.getByRole('combobox', { name: 'Wygląd' }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Wygląd' }).inputValue(), 'dark');
    assert.equal(await page.getByRole('checkbox', { name: 'Odświeżaj automatycznie' }).isChecked(), false);
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

test('the default theme adopts saved appearance once and preserves subsequent choices and reset', async (t) => {
    const { page, base, open } = await themeFixture(
        t,
        `
        const settings = createThemeContext(base, 'default').settings;
        document.querySelector('button').onclick = () => { settings.remove('appearance'); result.textContent = 'Reset'; };
    `,
        '<button>Reset default appearance</button>'
    );
    // This is the published browser data format from before themes owned appearance.
    await page.addInitScript(() => {
        const key = 'ae2web:/ae2/:ui';
        if (localStorage.getItem(key) === null) {
            localStorage.setItem(key, JSON.stringify({ appearance: 'dark', language: 'en', autoRefresh: false }));
        }
    });
    await page.goto(`${base}#/web-settings`);
    const appearance = page.getByRole('combobox', { name: 'Appearance' });
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'dark');
    await appearance.selectOption('light');
    await page.reload();
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'light');
    await open();
    await page.getByRole('button', { name: 'Reset default appearance' }).click();
    await page.goto(`${base}#/web-settings`);
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'system', 'Reset must not resurrect the previously adopted appearance');
});

test('saved appearance remains recoverable when its first theme storage write fails', async (t) => {
    const { page, base } = await fixture(t, '/ae2');
    await page.addInitScript(() => {
        if (sessionStorage.getItem('adoptionAttempted')) return;
        sessionStorage.setItem('adoptionAttempted', 'true');
        const legacyKey = 'ae2web:/ae2/:ui';
        localStorage.setItem(legacyKey, JSON.stringify({ appearance: 'dark', language: 'en' }));
        const setItem = Storage.prototype.setItem;
        Storage.prototype.setItem = function (key, value) {
            // Existing legacy data can be updated, but writing a new storage entry exceeds quota.
            if (key === legacyKey) return setItem.call(this, key, value);
            throw new DOMException('Full', 'QuotaExceededError');
        };
    });
    await page.goto(`${base}#/web-settings`);
    const appearance = page.getByRole('combobox', { name: 'Appearance' });
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'dark');
    await page.reload();
    await appearance.waitFor();
    assert.equal(await appearance.inputValue(), 'dark', 'Failed adoption must preserve the recoverable saved choice');
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
    await page.getByRole('link', { name: 'Home', exact: true }).click();
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
    await page.getByRole('button', { name: 'Accept Cookies', exact: true }).click();
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
    assert.equal(new URL(page.url()).searchParams.get('ui'), 'next');
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
            await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
    assert.ok(measured.empty.every((height) => Math.abs(height - measured.real) < 0.03));
    options.cpuDetails['cpu-a'] = { ...cpuWork, hasTrackingInfo: false };
    const updated = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus/cpu-a'));
    await poll(page);
    await settleResponse(page, (await updated).request());
    measured = await cpuGeometry();
    assert.ok(measured.real < trackedHeight, 'untracked CPU rows preserve their more compact layout');
    assert.ok(measured.empty.every((height) => Math.abs(height - measured.real) < 0.03));
    assert.equal(await cpuResources.getByRole('button').count(), 1);
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
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
    assert.match(await row.textContent(), /2,048 B/);
    assert.match(await row.textContent(), /8,192 B/);
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
        () => card.getByText('8,192 B', { exact: true }).click(),
        () => card.click({ position: { x: 6, y: 6 } })
    ]) {
        await open();
        await page.waitForURL(`**#/grids/${gridA}/cpus/cpu-a`, { timeout: 3000 });
        await page.getByRole('region', { name: 'CPU resources', exact: true }).waitFor();
        await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
                y: rect.y,
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
        await page.getByRole('link', { name: 'CPUs', exact: true }).click();
        await page.getByRole('link', { name: /Processor 0 .*cpu-0/ }).waitFor();
        const cpuHeading = page.getByRole('heading', { name: 'CPUs', exact: true });
        const actual = await measure(cpuHeading);
        for (const key of ['x', 'y'])
            assert.ok(
                Math.abs(actual[key] - heading[key]) < 0.1,
                `${key} heading position matches at ${viewport.width}x${viewport.height}`
            );
        for (const key of ['fontSize', 'fontWeight', 'fontFamily', 'color']) assert.equal(actual[key], heading[key]);
        assert.equal(await page.getByRole('link', { name: 'Back to resources', exact: true }).count(), 0);
        const region = page.getByRole('region', { name: 'CPUs', exact: true });
        const bounds = await measure(region);
        for (const key of ['x', 'y', 'right'])
            assert.ok(Math.abs(bounds[key] - resources[key]) < 0.1, `${key} scroll viewport matches`);
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
        await page.getByRole('link', { name: 'CPUs', exact: true }).click();
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
    assert.ok(nameBox.right <= searchBox.x, 'long title does not overlap search');
    assert.ok(searchBox.right - searchBox.x >= 80, 'search remains usable on mobile');
    assert.ok(
        await page.evaluate(
            () =>
                document.documentElement.scrollWidth <= innerWidth &&
                document.documentElement.scrollHeight <= innerHeight
        )
    );
});

// Public browser/HTTP seam: Home must retain source identity across grids and expose a keyboard chooser.
test('Home shows current work across networks and opens the exact CPU or a keyboard-selected terminal', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpusByGrid = {
        [gridA]: { shared: { ...cpu, isBusy: true, finalOutput: iron } },
        [gridB]: { shared: { ...cpu, isBusy: true, isPaused: true, finalOutput: quartz } }
    };
    await page.goto(base);
    const ironWork = page.getByRole('link', { name: /Iron Ingot/ });
    const quartzWork = page.getByRole('link', { name: /Certus Quartz Crystal/ });
    await quartzWork.waitFor();
    assert.equal(await ironWork.getAttribute('href'), `#/grids/${gridA}/cpus/shared`);
    assert.equal(await quartzWork.getAttribute('href'), `#/grids/${gridB}/cpus/shared`);
    assert.equal(
        options.requests.some((request) => /\/cpus\/[^/]+$/.test(request.path)),
        false
    );
    const choose = page.getByRole('button', { name: 'Choose a network', exact: true });
    await choose.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    assert.ok(
        (await dialog.boundingBox()).height < page.viewportSize().height * 0.9,
        'A short network list should keep its dialog sized to content'
    );
    await page.keyboard.press('Escape');
    assert.equal(await choose.evaluate((node) => node === document.activeElement), true);
    await choose.click();
    const beta = dialog.getByRole('link', { name: new RegExp(gridB) });
    await beta.focus();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    assert.equal(await dialog.isVisible(), false);
    await page.getByRole('link', { name: 'Home', exact: true }).click();
    await ironWork.waitFor();
    await quartzWork.waitFor();
});

// Product rendering uses the public response atlas, and a failed source cannot masquerade as idle.
test('Home renders summary icons and keeps unavailable networks explicit in the open chooser', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    await atlasFixture(page, options);
    options.cpusByGrid = {
        [gridA]: { shared: { ...cpu, isBusy: true, finalOutput: iron, icon: { page: 0, x: 0, y: 0 } } },
        [gridB]: { shared: { ...cpu, isBusy: true, finalOutput: quartz, icon: { page: 0, x: 64, y: 0 } } }
    };
    await page.goto(base);
    const product = page.getByRole('link', { name: /Iron Ingot/ });
    await product.waitFor();
    await product.locator('.resource-icon').waitFor();
    await page.waitForFunction(() => document.querySelector('#home .resource-icon')?.style.backgroundImage);
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    options.cpuErrors = { [gridA]: 'NO_PERMISSIONS' };
    await poll(page);
    await page
        .getByRole('dialog')
        .getByRole('link', { name: new RegExp(gridB) })
        .waitFor();
    await page.keyboard.press('Escape');
    await page.getByText(/no longer have access/).waitFor();
    assert.equal(await product.count(), 0);
    await page.getByRole('link', { name: /Certus Quartz Crystal/ }).waitFor();
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('status').waitFor();
    assert.equal(await dialog.getByRole('link').count(), 0);
    options.gridError = null;
    options.cpuErrors = {};
    await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
    await dialog.getByRole('link', { name: new RegExp(gridA) }).waitFor();
});

// Discovery must supersede pending summaries even when an icon/route refresh started first.
test('Home clears revoked sources during pending reads and stops reading summaries outside Home', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, true);
    options.cpus = { running: { ...cpu, isBusy: true, finalOutput: iron } };
    await page.goto(`${base}#/web-settings`);
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).waitFor();
    options.cpuDelay = 800;
    options.gridDelay = 100;
    options.grids = [{ key: gridB, owner: 'Beta', cpuCount: 1, accessSources: {} }];
    const oldRead = page.waitForRequest((request) => request.url().includes(`${gridA}/cpus`));
    await page.getByRole('link', { name: 'Home', exact: true }).click();
    const pending = await oldRead;
    await poll(page);
    await settleResponse(page, pending);
    await page.getByRole('link', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('link', { name: /Iron Ingot/ }).count(), 1);
    assert.equal(
        await page.getByRole('link', { name: /Iron Ingot/ }).getAttribute('href'),
        `#/grids/${gridB}/cpus/running`
    );
    await page.getByRole('button', { name: 'Choose a network', exact: true }).click();
    assert.equal(
        await page
            .getByRole('dialog')
            .getByRole('link', { name: new RegExp(gridA) })
            .count(),
        0
    );
    await page.keyboard.press('Escape');
    const refresh = page.waitForRequest((request) => request.url().endsWith(`${gridB}/cpus`));
    await poll(page);
    const late = await refresh;
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await settleResponse(page, late);
    const reads = options.requests.filter((request) => request.path.endsWith('/cpus')).length;
    await poll(page);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, reads);
    assert.equal(await page.getByRole('link', { name: /Iron Ingot/ }).count(), 0);
});

test('Home bounds cross-network reads and keeps per-response atlas identity and name-only preferences', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    await atlasFixture(page, options);
    options.cpusByGrid = {
        [gridA]: { running: { ...cpu, isBusy: true, finalOutput: iron, icon: { page: 0, x: 0, y: 0 } } },
        [gridB]: { running: { ...cpu, isBusy: true, finalOutput: quartz, icon: { page: 0, x: 64, y: 0 } } }
    };
    options.iconsByGrid = {
        [gridA]: options.icons,
        [gridB]: { ...options.icons, pages: [{ ...options.icons.pages[0], digest: 'c'.repeat(64) }] }
    };
    options.grids = [
        { key: gridA, owner: 'Alpha', cpuCount: 1, accessSources: {} },
        { key: gridB, owner: 'Beta', cpuCount: 1, accessSources: {} },
        ...Array.from({ length: 6 }, (_, index) => ({
            key: `network-${index}`,
            owner: `Owner ${index}`,
            cpuCount: 1,
            accessSources: {}
        }))
    ];
    options.cpuDelay = 100;
    // Capability discovery may legitimately restart a Home read; measure one load after it settles.
    await page.goto(`${base}#/web-settings`);
    const display = page.getByRole('combobox', { name: 'Terminal display', exact: true });
    await page.waitForFunction(
        (select) => !select.querySelector('option[value="both"]').disabled,
        await display.elementHandle()
    );
    options.requests.length = 0;
    await page.getByRole('link', { name: 'Home', exact: true }).click();
    const first = page.getByRole('link', { name: /Iron Ingot/ });
    const second = page.getByRole('link', { name: /Certus Quartz Crystal/ });
    await second.waitFor();
    await page.waitForFunction(() =>
        [...document.querySelectorAll('#home .resource-icon')].every((icon) => icon.style.backgroundImage)
    );
    const firstImage = await first.locator('.resource-icon').evaluate((icon) => icon.style.backgroundImage);
    const secondImage = await second.locator('.resource-icon').evaluate((icon) => icon.style.backgroundImage);
    assert.notEqual(firstImage, secondImage, 'Each response-local page=0 must resolve its own digest');
    assert.notEqual(
        await first.locator('.resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPosition),
        await second.locator('.resource-icon').evaluate((icon) => getComputedStyle(icon).backgroundPosition)
    );
    await page.waitForFunction(() => !document.querySelector('#home .home-status')?.textContent.includes('Loading'));
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, options.grids.length);
    assert.ok(options.maxActiveCpus <= 3, 'Home must bound concurrent per-grid reads');
    assert.equal(
        options.requests.some((request) => /\/cpus\/[^/]+$/.test(request.path)),
        false
    );
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    options.requests.length = 0;
    await page.getByRole('link', { name: 'Home', exact: true }).click();
    await second.waitFor();
    assert.equal(await first.locator('.resource-icon').isVisible(), false);
    assert.ok(
        options.requests
            .filter((request) => request.path.endsWith('/cpus'))
            .every((request) => !request.query.includes('icons=true'))
    );
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
    options.cpusByGrid = {
        [gridA]: { running: { ...cpu, name: longName, isBusy: true, finalOutput: { ...iron, displayName: longName } } },
        [gridB]: { running: { ...cpu, isBusy: true, isPaused: true, finalOutput: null } }
    };
    for (const appearance of ['light', 'dark']) {
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption(appearance);
        await page.getByRole('link', { name: 'Home', exact: true }).click();
        await page.getByRole('link', { name: /Current output unavailable/ }).waitFor();
        const work = page.getByRole('link', { name: new RegExp(`^${longName} ×`) });
        await work.focus();
        await poll(page);
        assert.equal(await work.evaluate((node) => node === document.activeElement), true);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        assert.ok(await page.locator('#workspace').evaluate((node) => node.scrollWidth <= node.clientWidth));
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
        await page.keyboard.press('Escape');
        await page.getByRole('button', { name: 'Choose a network', exact: true }).waitFor();
    }
});

test('Home keeps failed logout visible and allows an explicit retry', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    options.logoutError = 'NETWORK_ERROR';
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByRole('alert').waitFor({ timeout: 2000 });
    assert.equal(options.requests.filter((request) => request.path === '/api/auth/logout').length, 1);
    options.logoutError = null;
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.getByRole('button', { name: 'Continue', exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path === '/api/auth/logout').length, 2);
});

// Public settings/HTTP seam: the server-backed name is shared presentation, independent of tracking.
test('current network stays on the brand and account row at narrow widths without changing header geometry', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    for (const width of [1280, 800, 560, 390]) {
        await page.setViewportSize({ width, height: 844 });
        let previous;
        for (const name of ['Factory', 'VeryLongNetworkName'.repeat(6)]) {
            options.settings[gridA].name = name;
            await page.goto(`${base}#/grids/${gridA}/settings`);
            await page.reload();
            const network = page.locator('header').getByText(name, { exact: true });
            await network.waitFor();
            const brand = await page.getByRole('heading', { name: 'AE2 Web Integration' }).boundingBox();
            const account = await page.getByRole('button', { name: 'Log out', exact: true }).boundingBox();
            const label = await network.boundingBox();
            const header = await page.locator('header').boundingBox();
            if (width === 1280)
                assert.ok(
                    await page
                        .getByText(options.username, { exact: true })
                        .evaluate((node) => node.scrollWidth <= node.clientWidth),
                    'The account name must use available header space before being shortened'
                );
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
    const header = page.locator('header');
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
    await page.getByRole('link', { name: 'Network', exact: true }).click();
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
    await page.getByRole('link', { name: 'Home', exact: true }).click();
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
    const navigation = page.locator('nav').getByRole('link');
    assert.deepEqual((await navigation.allTextContents()).slice(0, 3), ['Home', 'Network', 'Terminal']);
    await page.getByRole('link', { name: 'Home', exact: true }).click();
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
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await enable.count(), 0);
    await page.getByRole('link', { name: 'Network', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).uncheck();
    await page.getByRole('button', { name: 'Save settings', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /settings saved/i })
        .waitFor();
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await page.getByRole('link', { name: /Iron Ingot.*#1/ }).waitFor();
    await enable.waitFor();
    assert.equal(await enable.getAttribute('href'), `#/grids/${gridA}/settings`);
    await page
        .getByRole('region', { name: 'History', exact: true })
        .getByText(/tracking is disabled/i)
        .waitFor();
    await page.getByRole('link', { name: 'Home', exact: true }).click();
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
    await page.getByRole('link', { name: 'Home', exact: true }).click();
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Factory', exact: true }).waitFor();
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
    await page.getByRole('link', { name: 'Home', exact: true }).click();
    options.settings[gridA] = { name: 'Canonical', isTracked: true };
    await pending.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: options.settings[gridA] })
    });
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Canonical', exact: true }).waitFor();
    await page.unroute('**/settings');
    await page.getByRole('link', { name: 'Network', exact: true }).click();
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
    await page.getByRole('link', { name: 'Home', exact: true }).click();
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
        const current = page.locator('header').getByText(options.settings[gridA].name, { exact: true });
        await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
        await page.evaluate(
            () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
        );
        await current.focus();
        const tooltip = page.getByRole('tooltip');
        await tooltip.waitFor();
        assert.ok((await tooltip.innerText()).includes(options.settings[gridA].name));
        for (const target of [current, page.locator('header').getByRole('button', { name: 'Log out', exact: true })]) {
            const box = await target.boundingBox();
            assert.ok(box.width > 0 && box.x >= 0 && box.x + box.width <= 390);
        }
        await page.keyboard.press('Tab');
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
            .filter({ hasText: /settings saved/i })
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
            localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ resourceIcons: false }))
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
    options.settings[gridA] = { name: 'Remote factory', isTracked: true };
    await page.getByRole('link', { name: 'Network', exact: true }).click();
    await page.getByRole('textbox', { name: 'Network name', exact: true }).waitFor();
    assert.equal(await page.getByRole('checkbox', { name: 'Record crafting history', exact: true }).isChecked(), true);
    await page.getByRole('link', { name: 'Home', exact: true }).click();
    await page.locator('#home-network-panel').getByRole('heading', { name: 'Remote factory', exact: true }).waitFor();
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
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
            const details = await page.getByRole('complementary').filter({ visible: true }).boundingBox();
            const tools = await page.getByRole('group', { name: 'Resources', exact: true }).boundingBox();
            assert.ok(frame.x >= 0 && frame.x + frame.width <= width, `main frame fits at ${width}`);
            assert.ok(details.x >= 0 && details.x + details.width <= width, `attached details fit at ${width}`);
            assert.ok(tools.x >= 0 && tools.x + tools.width <= frame.x + 1, `tools fit beside frame at ${width}`);
            if (width >= 1600) {
                assert.ok(
                    Math.abs(frame.x + frame.width / 2 - width / 2) < 1,
                    `main frame center ${frame.x + frame.width / 2} matches viewport center ${width / 2}`
                );
            }
            if (width > 600) {
                assert.ok(Math.abs(details.x - (frame.x + frame.width)) <= 3, `details remain docked at ${width}`);
            } else {
                assert.ok(details.y >= frame.y + frame.height - 1, `details stack below at ${width}`);
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
