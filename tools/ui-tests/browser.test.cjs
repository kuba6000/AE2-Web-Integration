const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright');

const resources = path.resolve(__dirname, '../../src/main/resources');
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
const cpu = { name: 'Assembler', isBusy: false, availableStorage: 8192, usedStorage: 0, coProcessors: 1 };
const cpuWork = {
    size: 8192,
    isBusy: true,
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

async function fixture(t, mount = '') {
    const options = {
        delayA: 0,
        status: 200,
        requests: [],
        empty: false,
        reverseGrids: false,
        loggedOut: false,
        itemsA: [iron, quartz],
        plan: readyPlan,
        pendingReads: 0,
        cpus: { 'cpu-a': cpu, 'cpu-b': cpu },
        planStatus: 'OK',
        submitStatus: 'OK',
        cpuDetails: { 'cpu-a': cpuWork, 'cpu-b': cpuWork },
        cancelStatus: 'OK',
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
            method: request.method,
            headers: request.headers,
            body: body.length ? JSON.parse(Buffer.concat(body).toString()) : null
        });
        if (resource.startsWith('/api/')) {
            response.setHeader('Content-Type', 'application/json');
            const mutation =
                request.method === 'PATCH'
                    ? 'settings'
                    : request.method === 'DELETE'
                      ? 'delete'
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
            if (resource === '/api/auth/logout') {
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
                response.end(JSON.stringify({ status, data: status === 'OK' ? options.cpuDetails[key] : null }));
            } else if (resource.endsWith('/items')) {
                const first = resource.includes(gridA);
                const send = () => {
                    response.statusCode = options.status;
                    response.end(
                        JSON.stringify(
                            options.status === 200
                                ? {
                                      status: 'OK',
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
        const file = path.resolve(resources, relative);
        if (!file.startsWith(resources + path.sep)) {
            response.writeHead(404).end();
            return;
        }
        try {
            response.setHeader(
                'Content-Type',
                file.endsWith('.mjs')
                    ? 'text/javascript'
                    : file.endsWith('.css')
                      ? 'text/css'
                      : file.endsWith('.woff2')
                        ? 'font/woff2'
                        : 'text/html'
            );
            const content = await fs.readFile(file);
            response.end(login ? content.toString().replace('_REPLACE_ME_IS_PUBLIC_MODE', 'true') : content);
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
    const page = await browser.newPage({ locale: 'en-US' });
    const errors = [];
    page.on('pageerror', (error) => errors.push(String(error)));
    t.after(() => assert.deepEqual(errors, []));
    return { page, options, base: `http://127.0.0.1:${server.address().port}${mount}/?ui=next` };
}

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

test('terminal icon tools expose tooltips and support keyboard filtering and sorting', async (t) => {
    const { page, options, base } = await fixture(t);
    options.itemsA = [
        iron,
        quartz,
        { ...iron, itemname: 'Gold Ingot', itemid: 'minecraft:gold_ingot', itemKey: 'gold', quantity: 0 }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).uncheck();
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
    options.pendingReads = 1;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).uncheck();
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    await page.getByRole('spinbutton', { name: 'Craft quantity' }).fill('12');
    await page.getByRole('button', { name: 'Calculate plan', exact: true }).click();
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).waitFor({ timeout: 5000 });
    assert.match(page.url(), /\/plans\/7$/);
    await page.getByRole('combobox', { name: 'Crafting CPU', exact: true }).selectOption('cpu-b');
    options.cpus = { 'cpu-b': cpu, 'cpu-a': cpu };
    const refreshed = page.waitForResponse((response) => response.url().endsWith('/cpus'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
});

// Public seam: history links retain runtime entry identity; completed detail is read-only
// and its snapshot need not be refetched for unrelated preferences or ordinary polling.
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
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption('dark');
    await (await page.waitForResponse((response) => response.url().endsWith('/api/grids'))).finished();
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByRole('combobox', { name: 'Language', exact: true }).selectOption('pl');
    await page.getByRole('combobox', { name: 'Język', exact: true }).selectOption('en');
    assert.equal(await tracking.isChecked(), true);
    assert.equal(options.requests.filter((request) => request.method === 'PATCH').length, 0);
    await page.getByRole('heading', { name: player.name, exact: true }).waitFor();
    await page.getByText(player.uuid, { exact: true }).waitFor();
    await page.getByText(/minecraft:overworld.*120.*64.*-32/).waitFor();
    assert.equal(await page.locator('img').count(), 0);
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /Cannot connect/ })
        .first()
        .waitFor();
    options.gridError = null;
    const recovered = page.waitForResponse((response) => response.url().endsWith('/settings'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await tracking.waitFor({ state: 'hidden' });
    options.settings[gridA] = { isTracked: true };
    await save.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', data: { isTracked: true } })
    });
    assert.equal(await tracking.count(), 0);
    options.gridError = null;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await tracking.waitFor();
    assert.equal(await tracking.isChecked(), true);
});

test('explicit history refresh is retained during grid discovery and unavailable entries clear their snapshot', async (t) => {
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
    const discovery = await captured;
    options.historyError = 'TRACKING_NOT_FOUND';
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption('dark');
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
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption('dark');
    assert.equal(await page.getByRole('heading', { name: /Iron Ingot/ }).count(), 0);
    options.historyError = 'NO_PERMISSIONS';
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
        await page.getByRole('link', { name: 'Back to resources', exact: true }).click();
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
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
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
        await page
            .getByRole('status')
            .filter({ hasText: result === 'OK' ? /CPU is idle/i : /outcome.*unknown/i })
            .waitFor();
    }
    await page.unroute('**/cancel');
    options.cpuDetails['cpu-a'] = cpuWork;
    await page.goto(`${base}&case=late-read#/grids/${gridA}/cpus/cpu-a`);
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    let capture;
    const captured = new Promise((resolve) => {
        capture = resolve;
    });
    let held = false;
    await page.route('**/cpus/cpu-a', (route) => {
        if (held) return route.continue();
        held = true;
        capture(route);
    });
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption('dark');
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
});

// Public seam: reopening a calculation reads its runtime identity; cancelling it is a single
// explicit DELETE and navigation never repeats either mutation.
test('direct plan entry only reads, and explicit cancellation stops calculation polling', async (t) => {
    const { page, options, base } = await fixture(t);
    options.pendingReads = 100;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Delete calculation', exact: true }).click({ timeout: 3000 });
    await page
        .getByRole('status')
        .filter({ hasText: /calculation deleted/i })
        .waitFor();
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).uncheck();
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
    const refreshed = page.waitForResponse((response) => response.url().endsWith('/cpus'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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

// Public seam: CPU links and current-work controls plus HTTP. Display names may duplicate;
// navigation and cancellation must continue to address the selected stable CPU key.
test('CPU monitoring opens stable current work and explicitly cancels it without unrelated requests', async (t) => {
    const { page, options, base } = await fixture(t, '/ae2');
    options.cpus = {
        'cpu-a': { ...cpu, isBusy: true, finalOutput: { ...iron, quantity: 12 } },
        'cpu-b': { ...cpu, isBusy: true, finalOutput: { ...iron, quantity: 12 } }
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('link', { name: 'CPUs', exact: true }).click({ timeout: 3000 });
    await page.getByRole('link', { name: /Assembler.*cpu-b/ }).click();
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    assert.equal(await page.getByRole('columnheader', { name: 'Active', exact: true }).count(), 1);
    assert.equal(await page.getByRole('columnheader', { name: 'Crafted total', exact: true }).count(), 1);
    options.cpus = { 'cpu-b': options.cpus['cpu-b'], 'cpu-a': options.cpus['cpu-a'] };
    const refreshed = page.waitForResponse((response) => response.url().endsWith('/cpus/cpu-b'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await (await refreshed).finished();
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU is idle/i })
        .waitFor();
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 0);
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
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    delete options.cpus['cpu-b'];
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /CPU.*available/i })
        .waitFor({ timeout: 3000 });
    assert.match(page.url(), /\/cpus\/cpu-b$/);
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).click();
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    options.detailError = 'NO_PERMISSIONS';
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await page.getByRole('link', { name: /Assembler/ }).count(), 0);
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 0);
    options.detailError = null;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    options.cpuError = 'GRID_NOT_FOUND';
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
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
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
        await page.getByRole('button', { name: 'Cancel current work', exact: true }).click();
        await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor({ state: 'hidden' });
        assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).count(), 0);
    }
    assert.equal(options.requests.filter((request) => request.path.endsWith('/cancel')).length, 4);
});

test('late CPU reads cannot leak across selection or grid changes and busy state does not need output metadata', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpuDetails['cpu-b'] = { ...cpuWork, finalOutput: null, items: [], hasTrackingInfo: false };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    let release;
    const captured = new Promise((resolve) => {
        release = resolve;
    });
    await page.route('**/cpus/cpu-a', (route) => release(route));
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    const delayed = await captured;
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
    await page.getByRole('combobox', { name: 'Appearance', exact: true }).selectOption('dark');
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('columnheader', { name: 'Crafted total', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Cancel current work', exact: true }).isEnabled(), true);
    await page.getByRole('combobox', { name: 'Network', exact: true }).selectOption(gridB);
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
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
        await page.getByRole('link', { name: 'Back to resources', exact: true }).click();
        await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
        await page.goBack();
        await page
            .getByRole('status')
            .filter({ hasText: /outcome.*unknown/i })
            .waitFor();
        await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
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
    assert.equal(await page.locator('img').count(), 0);
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page
        .getByRole('status')
        .filter({ hasText: /no longer have access/i })
        .waitFor();
    assert.equal(await page.getByRole('cell', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Start crafting', exact: true }).count(), 0);
    options.cpuError = null;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor();
    options.gridError = 'NO_PERMISSIONS';
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByRole('cell', { name: /Iron Ingot/ }).waitFor({ state: 'hidden' });
    assert.equal(options.requests.filter((request) => request.method !== 'GET').length, 0);
});

// The API has no idempotency receipt. Transport failures must never trigger mutation replay,
// including explicit refresh and ordinary navigation back to the same runtime plan.
test('uncertain submission outcomes cannot be retried by polling, refresh or route reentry', async (t) => {
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
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByRole('combobox', { name: 'Network', exact: true }).selectOption(gridB);
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    const refreshed = page.waitForResponse((response) => response.url().endsWith('/items'));
    await page.getByRole('button', { name: 'Refresh', exact: true }).evaluate((button) => button.click());
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
        await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
    await page.getByRole('combobox', { name: 'Network' }).selectOption(gridB);
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
    // Let the deliberately delayed old response reach the browser before observing the final view.
    await page.waitForTimeout(550);
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.match(page.url(), new RegExp(gridB));
    await page.goBack();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Network' }).inputValue(), gridA);
});

test('appearance, language and terminal preferences survive reload and direct links', async (t) => {
    const { page, base } = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    await page.getByRole('checkbox', { name: 'Refresh automatically' }).uncheck();
    await page.getByRole('combobox', { name: 'Appearance' }).selectOption('dark');
    await page.getByRole('button', { name: 'Sort by: Quantity', exact: true }).click();
    await page.getByRole('combobox', { name: 'Language' }).selectOption('pl');
    await page.reload();
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Wygląd' }).inputValue(), 'dark');
    assert.equal(
        await page.getByRole('button', { name: 'Sortuj według: Ilości', exact: true }).getAttribute('aria-pressed'),
        'true'
    );
    assert.equal(await page.getByRole('checkbox', { name: 'Odświeżaj automatycznie' }).isChecked(), false);
    assert.equal(await page.getByRole('combobox', { name: 'Sieć', exact: true }).inputValue(), gridA);
});

test('lost access clears resource data and manual retry recovers', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Iron Ingot/ }).click();
    options.status = 403;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByText('You no longer have access to this network.').waitFor();
    assert.equal(await page.getByRole('button', { name: /Iron Ingot/ }).count(), 0);
    assert.equal(await page.getByRole('heading', { name: 'Iron Ingot' }).count(), 0);
    options.status = 200;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
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
                    const tooltip = document.querySelector('[role="tooltip"]');
                    observer.disconnect();
                    resolve({ visible: tooltip.checkVisibility(), content: tooltip.textContent });
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
                            resolve(document.querySelector('[role="tooltip"]').checkVisibility());
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
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByRole('combobox', { name: 'Network' }).selectOption(gridA);
    await page.getByRole('button', { name: /Iron Ingot/ }).waitFor();
});

test('home network links keep keyboard focus when polling refreshes the list', async (t) => {
    const { page, options, base } = await fixture(t);
    await page.goto(base);
    const network = page.getByRole('link', { name: /Alpha/ });
    await network.focus();
    const refreshed = page.waitForResponse((response) => response.url().endsWith('/api/grids'));
    await (await refreshed).finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await network.evaluate((link) => link === document.activeElement), true);
    const secondNetwork = page.getByRole('link', { name: /Beta/ });
    await secondNetwork.focus();
    options.reverseGrids = true;
    await (await page.waitForResponse((response) => response.url().endsWith('/api/grids'))).finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await secondNetwork.evaluate((link) => link === document.activeElement), true);
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: /Gold Ingot/ }).waitFor();
});

test('terminal scrolls resources inside the viewport while search and navigation remain reachable', async (t) => {
    const { page, options, base } = await fixture(t);
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
        await page.getByRole('checkbox', { name: 'Refresh automatically' }).uncheck();
        const resources = page.getByRole('region', { name: 'Resources', exact: true });
        await resources.waitFor();
        const search = page.getByRole('searchbox', { name: 'Search resources' });
        const before = await search.boundingBox();
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
    assert.equal(await page.locator('img').count(), 0);
    await page.getByRole('button', { name: 'Next page' }).click();
    await page.getByRole('button', { name: /Resource 104/ }).waitFor();
    await page.getByRole('button', { name: 'Previous page' }).click();
    assert.equal(await item.getAttribute('aria-pressed'), 'true');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, 1);
});
