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
    itemname: 'Iron Ingot',
    itemid: 'minecraft:iron_ingot',
    quantity: 128640,
    craftable: true,
    itemKey: 'iron'
};
const quartz = {
    itemname: 'Certus Quartz Crystal',
    itemid: 'ae2:certus_quartz_crystal',
    quantity: 42,
    craftable: false,
    itemKey: 'quartz'
};
const readyPlan = {
    isDone: true,
    isSimulating: false,
    bytesTotal: 2048,
    plan: [
        {
            itemid: 'minecraft:iron_ingot',
            itemname: 'Iron Ingot',
            stored: 4,
            requested: 12,
            missing: 0,
            steps: 3,
            usedPercent: 0
        }
    ]
};
const cpu = {
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
            itemid: 'minecraft:iron_ingot',
            itemname: 'Iron Ingot',
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
            itemname: 'Iron Ingot',
            itemid: 'minecraft:iron_ingot',
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
            location: [{ dimid: 'minecraft:overworld', x: 120, y: 64, z: -32 }],
            timings: [{ started: 1700000001000, ended: 1700000006000 }]
        }
    ]
};

async function fixture(t, mount = '', contextOptions = {}) {
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
        settings: { [gridA]: { isTracked: false }, [gridB]: { isTracked: false } },
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
            if (resource === '/api/icon-pack') {
                if (options.packError) {
                    response.writeHead(503).end(JSON.stringify({ status: 'INTERNAL_ERROR', data: null }));
                    return;
                }
                const pack = options.pack || { available: false, packId: null, width: 0, height: 0 };
                if (options.packDelay) await new Promise((resolve) => setTimeout(resolve, options.packDelay));
                response.end(JSON.stringify({ status: 'OK', data: pack }));
            } else if (resource === '/api/auth/logout') {
                options.loggedOut = true;
                response.end(JSON.stringify({ status: 'OK', data: null }));
            } else if (resource === '/api/grids') {
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
                        data: options.empty ? [] : options.reverseGrids ? grids.reverse() : grids
                    })
                );
            } else if (resource.endsWith('/crafting-plans')) {
                response.statusCode = 202;
                response.end(JSON.stringify({ status: 'OK', data: { jobID: 7 } }));
            } else if (resource.endsWith('/submit')) {
                response.statusCode = options.submitStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.submitStatus, data: options.submitReason || null }));
            } else if (/\/crafting-plans\/\d+$/.test(resource)) {
                response.statusCode = options.planStatus === 'OK' ? 200 : 404;
                response.end(
                    JSON.stringify({
                        status: options.planStatus,
                        data:
                            request.method === 'DELETE'
                                ? null
                                : options.pendingReads-- > 0
                                  ? { isDone: false, isSimulating: false, bytesTotal: 0, plan: null }
                                  : options.plan
                    })
                );
            } else if (resource.endsWith('/cpus')) {
                if (options.cpuError) {
                    response.statusCode = 403;
                    response.end(JSON.stringify({ status: options.cpuError, data: null }));
                    return;
                }
                response.end(JSON.stringify({ status: 'OK', data: options.cpus }));
            } else if (resource.endsWith('/crafting-history')) {
                response.end(JSON.stringify({ status: options.historyError || 'OK', data: options.history }));
            } else if (/\/crafting-history\/\d+$/.test(resource)) {
                response.end(JSON.stringify({ status: options.historyError || 'OK', data: options.historyDetail }));
            } else if (resource.endsWith('/settings')) {
                const key = resource.includes(gridA) ? gridA : gridB;
                if (request.method === 'PATCH' && !options.settingsError)
                    options.settings[key] = options.settingsReply || options.requests.at(-1).body;
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
                                                    itemname: 'Gold Ingot',
                                                    itemid: 'minecraft:gold_ingot',
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
                    : file.endsWith('.css')
                      ? 'text/css'
                      : file.endsWith('.woff2')
                        ? 'font/woff2'
                        : 'text/html'
            );
            const content = await fs.readFile(file);
            response.end(
                login
                    ? content.toString().replace('_REPLACE_ME_IS_PUBLIC_MODE', 'true')
                    : file.endsWith('.html')
                      ? content
                            .toString()
                            .replace(
                                '_REPLACE_ME_USER',
                                JSON.stringify({ username: options.username, isAdmin: options.isAdmin }).replace(
                                    /</g,
                                    '\\u003c'
                                )
                            )
                            .replace(
                                '<!--_REPLACE_ME_MOD_VERSION-->',
                                JSON.stringify(options.modVersion).replace(/</g, '\\u003c')
                            )
                      : content
            );
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
    await page.clock.install();
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
    assert.equal(await item.getByText(iron.itemname, { exact: true }).isVisible(), true);
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
    assert.equal(await item.getByText(iron.itemname, { exact: true }).isVisible(), true);
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
    assert.equal(await mode.inputValue(), 'icons');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await item.waitFor();
    const compact = await item.boundingBox();
    assert.ok(Math.abs(compact.width - compact.height) < 1, 'compact item slots are square');
    assert.ok(compact.width < original.width);
    const nameBox = await item.getByText(iron.itemname, { exact: true }).boundingBox();
    assert.ok(!nameBox || nameBox.width <= 1, 'the item name is not permanently painted in the slot');
    await item.hover();
    await page.getByRole('tooltip').getByText(iron.itemname, { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await item.focus();
    await page.getByRole('tooltip').getByText(iron.itemname, { exact: true }).waitFor();
    await page.keyboard.press('Enter');
    assert.equal(await item.getAttribute('aria-pressed'), 'true');
    await page.getByRole('spinbutton', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).waitFor();
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
    assert.equal(await cpuItem.getByText(iron.itemname, { exact: true }).isVisible(), true);
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await mode.selectOption('both');
    await page.reload();
    await mode.waitFor();
    assert.equal(await mode.inputValue(), 'both');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await item.waitFor();
    assert.equal(await item.getByText(iron.itemname, { exact: true }).isVisible(), true);
});

// Public rendered geometry seam: resizing and hovering must not put compact sprites between device pixels.
for (const deviceScaleFactor of [1, 1.25, 1.5, 1.75, 2]) {
    test(`compact sprite layout aligns pixels and stays stable on hover at DPR ${deviceScaleFactor}`, async (t) => {
        const { page, options, base } = await fixture(t, '', { deviceScaleFactor });
        await atlasFixture(page, options);
        options.itemsA = Array.from({ length: 80 }, (_, index) => ({
            ...iron,
            itemname: `Resource ${index}`,
            itemKey: `resource-${index}`,
            icon: { page: 0, x: 0, y: 0 }
        }));
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
        await page.goto(`${base}#/grids/${gridA}/items`);
        const items = page.locator('#items').getByRole('button');
        await items.first().waitFor();
        await page.evaluate(() => document.fonts.ready);
        const failures = [];
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
                assert.equal(icon.width, 32);
                assert.equal(icon.height, 32);
                assert.ok(Math.abs(icon.slotWidth - icon.slotHeight) < 0.02, 'slots remain square');
                for (const axis of ['x', 'y']) {
                    const physical = icon[axis] * deviceScaleFactor;
                    if (Math.abs(physical - Math.round(physical)) > 0.03) failures.push({ width, axis, physical });
                }
            }
            await items.nth(35).scrollIntoViewIfNeeded();
            const scrolledIcon = items.nth(35).locator('.resource-icon');
            const beforeHover = await scrolledIcon.boundingBox();
            await items.nth(35).hover();
            assert.deepEqual(await scrolledIcon.boundingBox(), beforeHover, 'scrolled hover does not move sprites');
            // Browser-owned scroll offsets are not quantized; verify content alignment and stable hover.
            const scrolled = await scrolledIcon.evaluate((icon) => {
                const box = icon.getBoundingClientRect();
                return { x: box.x, y: box.y + document.querySelector('#item-scroll').scrollTop };
            });
            for (const axis of ['x', 'y']) {
                const physical = scrolled[axis] * deviceScaleFactor;
                if (Math.abs(physical - Math.round(physical)) > 0.03)
                    failures.push({ width, scrolled: true, axis, physical });
            }
        }
        assert.deepEqual(failures, [], 'sprite edges lie on physical pixels');
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
            [iron.itemname, '9,999'],
            [quartz.itemname, '1E']
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
            assert.ok(markerBox.x + markerBox.width < quantityBox.x, 'craftability never overlaps the quantity');
            assert.ok(quantityBox.y > itemBox.y + itemBox.height / 2, 'quantity overlays the bottom of the slot');
            assert.ok(markerBox.y > itemBox.y + itemBox.height / 2, 'craftability overlays the bottom of the slot');
        }
    }
    await missing.focus();
    await page.getByRole('tooltip').getByText(quartz.itemname, { exact: true }).waitFor();
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('names');
    await page.getByRole('link', { name: 'Terminal', exact: true }).click();
    await page.getByRole('button', { name: /Iron Ingot/ }).focus();
    await page.getByRole('tooltip').getByText(iron.itemname, { exact: true }).waitFor();
    await page.keyboard.press('Enter');
    await page.getByRole('spinbutton', { name: 'Craft quantity' }).waitFor();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('quartz');
    await missing.waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
});

// Public browser seam: atlas delivery, rendered sprites and the persistent display preference.
// Shared pages must not be fetched per item or again for quantity-only polling updates.
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
        itemname: `Resource ${String(i).padStart(2, '0')}`,
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
    assert.equal(await page.getByRole('spinbutton').isEnabled(), true);
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
        itemname: `Resource ${i}`,
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
        await page.getByRole('link', { name: 'Grid settings', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/settings`
    );
    await page.getByRole('link', { name: 'Grid settings', exact: true }).click();
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
    await page.getByRole('link', { name: 'Grid settings', exact: true }).click();
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
    const network = page.getByRole('combobox', { name: 'Network', exact: true });
    assert.equal(await network.inputValue(), gridA);
    assert.equal(
        await page.getByRole('link', { name: 'Grid settings', exact: true }).getAttribute('href'),
        `#/grids/${gridA}/settings`
    );
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Refresh', exact: true }).count(), 0);
    assert.equal(await page.getByRole('checkbox', { name: 'Refresh automatically' }).count(), 0);
    assert.equal(await page.locator('header').getByRole('combobox').count(), 0);
    assert.match(await page.getByRole('definition').innerText(), new RegExp(`Alpha.*${gridA}`));
    const scopedReads = options.requests.filter((request) => request.path.startsWith('/api/grids/')).length;
    const discovery = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await poll(page);
    await settleResponse(page, (await discovery).request());
    assert.equal(options.requests.filter((request) => request.path.startsWith('/api/grids/')).length, scopedReads);
    await network.selectOption(gridB);
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

test('real page browses API resources under a proxy prefix, with search and filters', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(base);
    await page.getByRole('combobox', { name: 'Network' }).selectOption(gridA);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('quartz');
    await page.getByRole('button', { name: /Certus Quartz Crystal/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    await page.getByRole('searchbox', { name: 'Search resources' }).fill('');
    await page.getByRole('button', { name: 'Craftable', exact: true }).click();
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
            itemname:
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
    const itemname = '§aCobalt §lIngot';
    const finalOutput = { ...iron, itemname, quantity: 12 };
    options.itemsA = [{ ...iron, itemname }];
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], itemname }] };
    options.cpuDetails = { 'cpu-a': { ...cpuWork, finalOutput, items: [{ ...cpuWork.items[0], itemname }] } };
    options.history = [{ ...historyEntry, finalOutput }];
    options.historyDetail = { ...historyDetail, finalOutput, items: [{ ...historyDetail.items[0], itemname }] };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Cobalt.*Ingot/ }).click();
    await page.getByRole('spinbutton', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const resource = page.getByRole('cell', { name: /Cobalt Ingot/ });
    await resource.waitFor({ timeout: 3000 });
    async function assertFormatted(locator) {
        assert.doesNotMatch(await locator.innerText(), /§[0-9a-fk-or]/i);
        assert.equal((await textStyle(locator, 'Cobalt')).color, 'rgb(85, 255, 85)');
        assert.ok(Number((await textStyle(locator, 'Ingot')).weight) >= 700);
    }
    await assertFormatted(resource);
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
    const cpuOutput = page.getByRole('paragraph').filter({ hasText: /^[^§]*: Cobalt Ingot × 12$/ });
    await assertFormatted(cpuOutput);
    await page.getByRole('link', { name: 'History', exact: true }).click();
    const historyLink = page.getByRole('link', { name: /Cobalt Ingot.*#1/ });
    await historyLink.waitFor();
    await assertFormatted(historyLink);
    await historyLink.click();
    await resource.waitFor();
    await assertFormatted(resource);
    await assertFormatted(page.getByRole('heading', { name: 'Cobalt Ingot × 12', exact: true }));
    const timeline = page.getByRole('region', { name: 'Cobalt Ingot', exact: true });
    await timeline.waitFor();
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
        { ...iron, itemname: colors.map(([code]) => `§${code}Color${code}`).join(' ') },
        { ...quartz, itemname: `§kSecret Words §aVisible §KMasked §rPlain §qUnknown tail§ ${literal}` }
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
        { ...iron, itemname: '§fAl§apha' },
        { ...quartz, itemname: '§0Beta §qliteral' }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const alpha = page.getByRole('button', { name: /^Alpha/ });
    await alpha.waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
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
    options.itemsA = [{ ...iron, itemname: '§cAlpha', quantity: 8765 }, options.itemsA[1]];
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
        { ...iron, itemname: 'Gold Ingot', itemKey: 'gold', itemid: 'minecraft:gold_ingot' }
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
        itemname: `Resource ${index}`,
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
            .getByText(`Quantity: ${quantity.toLocaleString('en')}`, { exact: true })
            .waitFor();
        const itemBox = await item.boundingBox();
        const amountBox = await amount.boundingBox();
        const markerBox = await marker.boundingBox();
        assert.ok(amountBox.x + amountBox.width < itemBox.x + itemBox.width, 'quantity stays inside its slot');
        assert.ok(markerBox.x + markerBox.width < amountBox.x, 'crafting marker does not overlap the quantity');
        assert.ok(await amount.evaluate((node) => node.scrollWidth <= node.clientWidth), 'quantity is not clipped');
    }
});

test('terminal icon tools expose tooltips and support keyboard filtering and sorting', async (t) => {
    const { page, options, base } = await fixture(t);
    await seedAutomaticRefresh(page, base, false);
    options.itemsA = [
        iron,
        quartz,
        { ...iron, itemname: 'Gold Ingot', itemid: 'minecraft:gold_ingot', itemKey: 'gold', quantity: 0 }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    const all = page.getByRole('button', { name: 'All', exact: true });
    const craftable = page.getByRole('button', { name: 'Craftable', exact: true });
    const stored = page.getByRole('button', { name: 'In storage', exact: true });
    const tooltip = page.getByRole('tooltip');
    assert.equal(await all.getAttribute('aria-pressed'), 'true');
    await craftable.hover();
    await tooltip.waitFor({ state: 'visible' });
    assert.equal(await tooltip.textContent(), await craftable.getAttribute('aria-label'));
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    await craftable.focus();
    await tooltip.waitFor({ state: 'visible' });
    await page.keyboard.press('Enter');
    assert.equal(await craftable.getAttribute('aria-pressed'), 'true');
    assert.equal(await all.getAttribute('aria-pressed'), 'false');
    assert.equal(await page.getByRole('button', { name: /Certus Quartz Crystal/ }).count(), 0);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    await stored.focus();
    await page.keyboard.press('Space');
    assert.equal(await stored.getAttribute('aria-pressed'), 'true');
    assert.equal(await craftable.getAttribute('aria-pressed'), 'false');
    await page.getByRole('button', { name: /Certus Quartz Crystal/ }).waitFor();
    assert.equal(await page.getByRole('button', { name: /Gold Ingot/ }).count(), 0);
    const quantity = page.getByRole('button', { name: 'Sort by: Quantity', exact: true });
    await quantity.focus();
    await tooltip.waitFor({ state: 'visible' });
    assert.equal(await tooltip.textContent(), await quantity.getAttribute('aria-label'));
    await page.keyboard.press('Enter');
    assert.equal(await quantity.getAttribute('aria-pressed'), 'true');
    assert.equal(
        await page.getByRole('button', { name: 'Sort by: Name', exact: true }).getAttribute('aria-pressed'),
        'false'
    );
    assert.match(
        await page
            .getByRole('button', { name: /Iron Ingot|Certus Quartz Crystal/ })
            .first()
            .textContent(),
        /Iron Ingot/
    );
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/items')).length,
        1,
        'Icon tools change the loaded inventory locally'
    );
});

// Public seam: browser controls plus emitted HTTP. A calculation must be explicit, keep its
// quantity/identity, finish without inventory auto-refresh, and submit the chosen stable CPU key.
test('crafting calculates a quantity, polls, preserves CPU identity and submits explicitly', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await seedAutomaticRefresh(page, base, false);
    options.pendingReads = 1;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('spinbutton', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).waitFor({ timeout: 5000 });
    assert.match(page.url(), /\/plans\/7$/);
    await page.getByRole('link', { name: 'Web settings', exact: true }).click();
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).check();
    await page.goBack();
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).waitFor();
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).selectOption('cpu-b');
    options.cpus = { 'cpu-b': cpu, 'cpu-a': cpu };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await (await refreshed).finished();
    assert.equal(await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).inputValue(), 'cpu-b');
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /submitted/i })
        .waitFor();
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
    await page.getByRole('link', { name: 'Inspect CPU work', exact: true }).click({ timeout: 3000 });
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
});

// Public seam: history links retain runtime entry identity; completed detail is read-only
// and its snapshot need not be refetched for ordinary polling.
test('history preserves entry identity and opens measured cancelled work through direct routes', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.history = [{ ...historyEntry, id: 2 }, historyEntry];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'History', exact: true }).click({ timeout: 3000 });
    await page.getByRole('link', { name: /Iron Ingot.*#2/ }).click();
    await page.getByRole('columnheader', { name: 'Crafted total', exact: true }).waitFor();
    assert.match(page.url(), /\/history\/2$/);
    await page
        .getByRole('status')
        .filter({ hasText: /cancelled/i })
        .waitFor();
    assert.equal(await page.getByRole('cell', { name: '10', exact: true }).count(), 1);
    await (
        await page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'))
    ).finished();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/crafting-history/2')).length, 1);
    await page.reload();
    await page.getByRole('columnheader', { name: 'Crafted total', exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cpus')).length, 0);
});

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
                position: { dimid: 'minecraft:overworld', x: 120, y: 64, z: -32 },
                side: 'north'
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'Grid settings', exact: true }).click({ timeout: 3000 });
    const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
    await tracking.check();
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/settings'));
    await poll(page);
    await (await refreshed).finished();
    assert.equal(await tracking.isChecked(), true);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 0);
    await page.getByRole('heading', { name: player.name, exact: true }).waitFor();
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
    const provider = page.getByRole('region', { name: 'Smelter', exact: true });
    await provider.waitFor({ timeout: 3000 });
    await provider.getByText(/minecraft:overworld.*120.*64.*-32/).waitFor();
    await provider.getByText(/Intervals/).click();
    assert.deepEqual(await provider.locator('time').evaluateAll((times) => times.map((time) => time.dateTime)), [
        '2023-11-14T22:13:21.000Z',
        '2023-11-14T22:13:26.000Z'
    ]);
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
        options.settings[gridA] = { isTracked: false };
        await page.goto(`${base}&case=${status}#/grids/${gridA}/settings`);
        const tracking = page.getByRole('checkbox', { name: 'Record crafting history', exact: true });
        await tracking.check();
        options.settings[gridA] = { isTracked: true };
        options.fault = { operation: 'settings', status };
        await page.getByRole('button', { name: 'Save settings', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: /save.*not confirmed/i })
            .waitFor({ timeout: 3000 });
        const count = options.requests.filter((request) => request.method === 'PATCH').length;
        await poll(page);
        await page.getByRole('link', { name: 'Back to resources', exact: true }).click();
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
    options.settings[gridA] = { isTracked: true };
    await save.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: { isTracked: true } })
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
    await page.getByRole('columnheader', { name: 'Crafted total', exact: true }).waitFor();
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
    assert.equal(await page.getByRole('columnheader', { name: 'Crafted total', exact: true }).count(), 0);
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
                position: { dimid: 'minecraft:overworld', x: 1, y: 64, z: 2 },
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
        body: JSON.stringify({ status: 'OK', data: { isTracked: false } })
    });
    await page
        .getByRole('status')
        .filter({ hasText: /Settings saved/i })
        .waitFor();
    assert.equal(await tracking.isChecked(), false);
    await page.getByRole('heading', { name: 'Alex', exact: true }).waitFor({ timeout: 3000 });
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
        body: JSON.stringify({ status: 'OK', data: { isTracked: false } })
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
    assert.equal(await page.getByRole('heading', { name: 'Players with access', exact: true }).count(), 0);
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
        await page.getByRole('link', { name: 'Back to CPUs', exact: true }).click();
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
            .filter({ hasText: result === 'OK' ? /CPU is idle/i : /outcome.*unknown/i })
            .waitFor();
        await poll(page);
        await page
            .getByRole('status')
            .filter({ hasText: result === 'OK' ? /CPU is idle/i : /outcome.*unknown/i })
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
        .filter({ hasText: /CPU is idle/i })
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
    await page.getByRole('button', { name: 'Delete calculation', exact: true }).click({ timeout: 3000 });
    await page
        .getByRole('status')
        .filter({ hasText: /calculation deleted/i })
        .waitFor();
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
    await page.getByRole('link', { name: 'Back to resources' }).click();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 1);
});

// Public seam: CPU option availability and selection across live data and full reload. Variant
// identity and capacity determine merging; a name or ordinal cannot identify a selected CPU.
test('busy CPU eligibility uses known output identity and missing selections require a new choice', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpus = {
        'cpu-a': cpu,
        'cpu-b': { ...cpu, isBusy: true, usedStorage: 2048, finalOutput: { itemKey: 'iron' } },
        'other-output': { ...cpu, isBusy: true, finalOutput: { itemKey: 'other-variant' } },
        'unknown-storage': { ...cpu, isBusy: true, usedStorage: -1, finalOutput: { itemKey: 'iron' } }
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    const cpus = page.getByRole('combobox', { name: 'Crafting CPU', exact: true });
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('option', { name: /cpu-b/ }).evaluate((option) => option.disabled), false);
    assert.equal(await cpus.getByRole('option', { name: /other-output/ }).evaluate((option) => option.disabled), true);
    assert.equal(
        await cpus.getByRole('option', { name: /unknown-storage/ }).evaluate((option) => option.disabled),
        true
    );
    await cpus.selectOption('cpu-b');
    delete options.cpus['cpu-b'];
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/cpus'));
    await poll(page);
    await (await refreshed).finished();
    await cpus.getByRole('option', { name: /cpu-b/ }).waitFor({ state: 'detached' });
    assert.equal(await cpus.inputValue(), '');
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
    options.cpus['cpu-b'] = { ...cpu, isBusy: true, finalOutput: { itemKey: 'iron' } };
    await page.reload();
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('option', { name: /cpu-b/ }).evaluate((option) => option.disabled), true);
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
    assert.match(await details.textContent(), /Selected assembler/);
    assert.match(await details.textContent(), /8,192/);
    assert.match(await details.textContent(), /2,048/);
    assert.match(await details.textContent(), /3 coprocessors/);
    assert.match(await details.textContent(), /Iron Ingot.*12/);
    assert.equal(await details.getByRole('button', { name: 'Pause current work', exact: true }).isEnabled(), true);
    await page.getByRole('link', { name: 'Back to CPUs', exact: true }).click();
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
            { ...cpuWork.items[0], itemname: 'Zinc', itemid: 'minecraft:zinc', active: 10, pending: 0, stored: 0 },
            {
                ...cpuWork.items[0],
                itemname: 'Alpha',
                itemid: 'minecraft:unique_pending',
                active: 0,
                pending: 30,
                stored: 0
            },
            { ...cpuWork.items[0], itemname: 'Beta', itemid: 'ae2:beta', active: 0, pending: 0, stored: 20 },
            { ...cpuWork.items[0], itemname: '§aMixed', itemid: 'other:mixed', active: 3, pending: 8, stored: 50 }
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
        await page.getByRole('button', { name: 'Sort by: Name', exact: true }).getAttribute('aria-pressed'),
        'true',
        'CPU sort controls must not overwrite inventory preferences'
    );
});

test('CPU tiles show processing shares and sort by precise shares through refresh and tracking changes', async (t) => {
    const { page, options, base } = await fixture(t);
    const items = [
        { ...cpuWork.items[0], itemname: 'Alpha', itemid: 'example:alpha', shareInCraftingTime: 0.12441 },
        { ...cpuWork.items[0], itemname: 'Beta', itemid: 'example:beta', shareInCraftingTime: 0.12449 },
        {
            ...cpuWork.items[0],
            itemname: 'Stored',
            itemid: 'example:stored',
            active: 0,
            pending: 0,
            shareInCraftingTime: 0.7511
        },
        { ...cpuWork.items[0], itemname: 'Waiting', itemid: 'example:waiting', active: 0, shareInCraftingTime: 0 }
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
    await page.getByRole('link', { name: 'Back to CPUs', exact: true }).click();
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
            itemid: `example:resource_${index}`,
            itemname: `Resource ${String(index).padStart(3, '0')}`
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
            page.getByRole('link', { name: 'Back to CPUs', exact: true }),
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
        itemid: 'example:copper',
        itemname: 'Copper',
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
    await page.getByRole('link', { name: 'Back to CPUs', exact: true }).click();
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
        .filter({ hasText: /CPU is idle/i })
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
    await page.getByRole('link', { name: 'Back to CPUs', exact: true }).click();
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
        .filter({ hasText: /CPU is idle/i })
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
    await page.getByRole('link', { name: 'Back to CPUs', exact: true }).click();
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await page.getByText('Current output unavailable', { exact: true }).waitFor();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU is crafting/i })
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
        await page.getByRole('link', { name: 'Back to CPUs', exact: true }).click();
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
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 1);
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/submit')).length, 1);
    options.submitStatus = 'CPU_NOT_FOUND';
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).inputValue(), '');
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).selectOption('cpu-b');
    options.submitStatus = 'OK';
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /submitted/i })
        .waitFor();
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
    assert.equal(await page.getByRole('button', { name: 'Delete calculation', exact: true }).count(), 0);
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
    await page.getByRole('cell', { name: '8', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
    options.plan = readyPlan;
    await page.reload();
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).waitFor();
    options.cpuError = 'NO_PERMISSIONS';
    await poll(page);
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).count(), 0);
    options.cpuError = null;
    await poll(page);
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    options.gridError = 'NO_PERMISSIONS';
    await poll(page);
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor({ state: 'hidden' });
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
        await page.getByRole('link', { name: 'Back to resources' }).click();
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
    assert.equal(await page.getByRole('button', { name: 'Calculate plan', exact: true }).isDisabled(), true);
    options.fault = { operation: 'delete', status: 'TIMEOUT' };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Delete calculation', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /outcome.*unknown/i })
        .waitFor();
    await poll(page);
    assert.equal(await page.getByRole('button', { name: 'Delete calculation', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).isDisabled(), true);
    assert.deepEqual(
        options.requests.filter((request) => request.method !== 'GET').map((request) => request.method),
        ['POST', 'DELETE']
    );
});

test('quantity editing survives refresh and only positive safe integer quantities reach HTTP', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    const quantity = page.getByRole('spinbutton', { name: 'Craft quantity' });
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
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).waitFor();
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
        await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
        options.fault = { operation: 'delete', status };
        await page.getByRole('button', { name: 'Delete calculation', exact: true }).click();
        await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor({ state: 'hidden', timeout: 3000 });
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
        await page.getByRole('link', { name: 'Back to resources' }).click();
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
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).waitFor();
    let releaseCpus;
    const captured = new Promise((resolve) => {
        releaseCpus = resolve;
    });
    await page.route('**/cpus', (route) => releaseCpus(route));
    await poll(page);
    const cpuRequest = await captured;
    await page.getByRole('button', { name: 'Start crafting', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /submitted/i })
        .waitFor();
    await cpuRequest.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'NO_PERMISSIONS', data: null })
    });
    await page.waitForTimeout(100);
    await page
        .getByRole('status')
        .filter({ hasText: /submitted/i })
        .waitFor();
});

test('login errors keep the new UI selector and destination without loading a theme', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    await page.goto(`${base}&INVALID_PASSWORD#/grids/${gridA}/items`);
    await page.waitForFunction(() => !location.search.includes('INVALID_PASSWORD'));
    assert.equal(new URL(page.url()).searchParams.get('ui'), 'next');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    assert.equal(
        options.requests.some((request) => request.path.includes('/assets/web/')),
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
    await page.getByRole('combobox', { name: 'Network' }).selectOption(gridA);
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
    await page.getByRole('combobox', { name: 'Network' }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Network' }).inputValue(), gridA);
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
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
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
    assert.equal(
        await page.getByRole('button', { name: 'Sortuj według: Ilości', exact: true }).getAttribute('aria-pressed'),
        'true'
    );
    await page.locator('a[href="#/"]').click();
    await page.getByRole('combobox', { name: 'Sieć', exact: true }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Sieć', exact: true }).inputValue(), gridA);
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
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
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
    options.itemsA = [{ ...iron, itemname: 'A Iron Ingot', quantity: 3456 }, quartz];
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
    const tool = page.getByRole('button', { name: 'Sort by: Quantity', exact: true });
    await item.focus();
    await tool.hover();
    const label = await tool.getAttribute('aria-label');
    assert.equal(await tooltip.textContent(), label);
    options.itemsA = [{ ...iron, quantity: 2345 }, quartz];
    await page.getByRole('button', { name: /Iron Ingot.*2,345/ }).waitFor();
    assert.equal(await item.evaluate((button) => button === document.activeElement), true);
    assert.equal(await tooltip.isVisible(), true);
    assert.equal(
        await tooltip.textContent(),
        label,
        'Polling must not replace the hovered tool tooltip with resource details'
    );
    options.itemsA = [{ ...iron, itemname: 'A Iron Ingot', quantity: 1234 }, quartz];
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
    await page.getByText(/No accessible networks/).waitFor();
    options.empty = false;
    await poll(page);
    await page.getByRole('combobox', { name: 'Network' }).selectOption(gridA);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
});

test('home network links keep keyboard focus when polling refreshes the list', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    const network = page.getByRole('link', { name: /Alpha/ });
    await network.focus();
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/grids'));
    await (await refreshed).finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await network.evaluate((link) => link === document.activeElement), true);
    const secondNetwork = page.getByRole('link', { name: /Beta/ });
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
        itemname: `Resource ${String(index).padStart(3, '0')}`,
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
        itemname: `Resource ${String(index).padStart(3, '0')}`,
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
        { ...quartz, itemname: untrustedName },
        ...Array.from({ length: 105 }, (_, index) => ({
            ...iron,
            itemname: `Resource ${String(index).padStart(3, '0')}`,
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
