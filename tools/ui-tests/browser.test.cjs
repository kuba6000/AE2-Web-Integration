const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright');

const resources = path.resolve(__dirname, '../../src/main/resources');
const gridA = 'AAAAAAAAAAAAAAAAAAAAAA';
const gridB = 'BBBBBBBBBBBBBBBBBBBBBA';
const iron = { itemname: 'Iron Ingot', itemid: 'minecraft:iron_ingot', quantity: 128640, craftable: true, itemKey: 'iron' };
const quartz = { itemname: 'Certus Quartz Crystal', itemid: 'ae2:certus_quartz_crystal', quantity: 42, craftable: false, itemKey: 'quartz' };
const readyPlan = { isDone: true, isSimulating: false, bytesTotal: 2048, plan: [
    { itemid: 'minecraft:iron_ingot', itemname: 'Iron Ingot', stored: 4, requested: 12, missing: 0, steps: 3, usedPercent: 0 }
] };
const cpu = { name: 'Assembler', isBusy: false, availableStorage: 8192, usedStorage: 0, coProcessors: 1 };

async function fixture(t, mount = '') {
    const options = { delayA: 0, status: 200, requests: [], empty: false, reverseGrids: false, loggedOut: false, itemsA: [iron, quartz],
        plan: readyPlan, pendingReads: 0, cpus: { 'cpu-a': cpu, 'cpu-b': cpu }, planStatus: 'OK', submitStatus: 'OK' };
    const server = http.createServer(async (request, response) => {
        const url = new URL(request.url, 'http://localhost');
        if (!url.pathname.startsWith(mount + '/')) { response.writeHead(404).end(); return; }
        const resource = url.pathname.slice(mount.length);
        const body = [];
        for await (const chunk of request) body.push(chunk);
        options.requests.push({ path: resource, method: request.method, headers: request.headers,
            body: body.length ? JSON.parse(Buffer.concat(body).toString()) : null });
        if (resource.startsWith('/api/')) {
            response.setHeader('Content-Type', 'application/json');
            const mutation = request.method === 'DELETE' ? 'delete' : resource.endsWith('/submit') ? 'submit'
                : request.method === 'POST' && resource.endsWith('/crafting-plans') ? 'create' : null;
            if (mutation && options.fault?.operation === mutation) {
                if (options.fault.status === 'NETWORK_ERROR') { response.destroy(); return; }
                if (options.fault.status === 'INVALID_RESPONSE') { response.end('<html>Proxy error</html>'); return; }
                response.statusCode = options.fault.status === 'INTERNAL_ERROR' ? 500 : 503;
                response.end(JSON.stringify({status: options.fault.status, data: null})); return;
            }
            if (resource === '/api/auth/logout') {
                options.loggedOut = true;
                response.end(JSON.stringify({status: 'OK', data: null}));
            } else if (resource === '/api/grids') {
                if (options.gridError) { response.statusCode = 403; response.end(JSON.stringify({status: options.gridError, data: null})); return; }
                const grids = [
                    { key: gridA, owner: 'Alpha', cpuCount: 2, accessSources: {} },
                    { key: gridB, owner: 'Beta', cpuCount: 1, accessSources: {} }
                ];
                response.end(JSON.stringify({ status: 'OK', data: options.empty ? [] : options.reverseGrids ? grids.reverse() : grids }));
            } else if (resource.endsWith('/crafting-plans')) {
                response.statusCode = 202;
                response.end(JSON.stringify({ status: 'OK', data: { jobID: 7 } }));
            } else if (resource.endsWith('/submit')) {
                response.statusCode = options.submitStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.submitStatus, data: options.submitReason || null }));
            } else if (/\/crafting-plans\/\d+$/.test(resource)) {
                response.statusCode = options.planStatus === 'OK' ? 200 : 404;
                response.end(JSON.stringify({ status: options.planStatus, data: request.method === 'DELETE' ? null
                    : options.pendingReads-- > 0 ? { isDone: false, isSimulating: false, bytesTotal: 0, plan: null } : options.plan }));
            } else if (resource.endsWith('/cpus')) {
                if (options.cpuError) { response.statusCode = 403; response.end(JSON.stringify({status: options.cpuError, data: null})); return; }
                response.end(JSON.stringify({ status: 'OK', data: options.cpus }));
            } else if (resource.endsWith('/items')) {
                const first = resource.includes(gridA);
                const send = () => { response.statusCode = options.status; response.end(JSON.stringify(options.status === 200
                    ? { status: 'OK', data: first ? options.itemsA : [{ ...iron, itemname: 'Gold Ingot', itemid: 'minecraft:gold_ingot', quantity: 5 }] }
                    : { status: 'NO_PERMISSIONS', data: null })); };
                if (first && options.delayA) setTimeout(send, options.delayA); else send();
            } else { response.statusCode = 404; response.end(JSON.stringify({ status: 'NOT_FOUND', data: null })); }
            return;
        }
        const login = resource === '/' && (options.loggedOut || url.searchParams.has('INVALID_PASSWORD') || url.searchParams.has('confirmregistration'));
        const relative = resource === '/' ? login ? 'assets/login.html' : 'assets/web/index.html' : resource.slice(1);
        const file = path.resolve(resources, relative);
        if (!file.startsWith(resources + path.sep)) { response.writeHead(404).end(); return; }
        try {
            response.setHeader('Content-Type', file.endsWith('.mjs') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
            const content = await fs.readFile(file);
            response.end(login ? content.toString().replace('_REPLACE_ME_IS_PUBLIC_MODE', 'true') : content);
        } catch { response.writeHead(404).end('Missing asset'); }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    t.after(async () => {
        await browser?.close();
        await new Promise(resolve => server.close(resolve));
    });
    browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_CHANNEL ? { channel: process.env.BROWSER_CHANNEL } : {}) });
    const page = await browser.newPage({ locale: 'en-US' });
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    t.after(() => assert.deepEqual(errors, []));
    return { page, options, base: `http://127.0.0.1:${server.address().port}${mount}/?ui=next` };
}

test('real page browses API resources under a proxy prefix, with search and filters', async t => {
    const {page, options, base} = await fixture(t, '/ae2');
    await page.goto(base);
    await page.getByRole('combobox', {name: 'Network'}).selectOption(gridA);
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
    await page.getByRole('searchbox', {name: 'Search resources'}).fill('quartz');
    await page.getByRole('button', {name: /Certus Quartz Crystal/}).waitFor();
    assert.equal(await page.getByRole('button', {name: /Iron Ingot/}).count(), 0);
    await page.getByRole('searchbox', {name: 'Search resources'}).fill('');
    await page.getByRole('combobox', {name: 'Resources'}).selectOption('craftable');
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
    assert.equal(await page.getByRole('button', {name: /Certus Quartz Crystal/}).count(), 0);
    assert.equal(options.requests.filter(request => request.path.endsWith('/items')).length, 1,
        'Local search/filter changes reuse the loaded inventory');
});

// Public seam: browser controls plus emitted HTTP. A calculation must be explicit, keep its
// quantity/identity, finish without inventory auto-refresh, and submit the chosen stable CPU key.
test('crafting calculates a quantity, polls, preserves CPU identity and submits explicitly', async t => {
    const {page, options, base} = await fixture(t, '/ae2');
    options.pendingReads = 1;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('checkbox', {name: 'Refresh automatically'}).uncheck();
    await page.getByRole('button', {name: /Iron Ingot/}).click();
    await page.getByRole('spinbutton', {name: 'Craft quantity'}).fill('12');
    await page.getByRole('button', {name: 'Calculate plan', exact: true}).click();
    await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).waitFor({timeout: 5000});
    assert.match(page.url(), /\/plans\/7$/);
    await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).selectOption('cpu-b');
    options.cpus = { 'cpu-b': cpu, 'cpu-a': cpu };
    const refreshed = page.waitForResponse(response => response.url().endsWith('/cpus'));
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await (await refreshed).finished();
    assert.equal(await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).inputValue(), 'cpu-b');
    await page.getByRole('button', {name: 'Start crafting', exact: true}).click();
    await page.getByRole('status').filter({hasText: /submitted/i}).waitFor();
    const create = options.requests.filter(request => request.method === 'POST' && request.path.endsWith('/crafting-plans'));
    assert.equal(create.length, 1);
    assert.deepEqual(create[0].body, { itemKey: 'iron', quantity: 12 });
    assert.equal(create[0].headers['x-ae2-request'], 'true');
    assert.match(create[0].headers['content-type'], /application\/json/);
    assert.deepEqual(options.requests.filter(request => request.path.endsWith('/submit')).map(request => request.body), [{cpuKey: 'cpu-b'}]);
});

// Public seam: reopening a calculation reads its runtime identity; cancelling it is a single
// explicit DELETE and navigation never repeats either mutation.
test('direct plan entry only reads, and explicit cancellation stops calculation polling', async t => {
    const {page, options, base} = await fixture(t);
    options.pendingReads = 100;
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', {name: 'Delete calculation', exact: true}).click({timeout: 3000});
    await page.getByRole('status').filter({hasText: /calculation deleted/i}).waitFor();
    await page.getByRole('checkbox', {name: 'Refresh automatically'}).uncheck();
    const reads = options.requests.filter(request => request.path.endsWith('/crafting-plans/7') && request.method === 'GET').length;
    await page.waitForTimeout(1200);
    assert.equal(options.requests.filter(request => request.path.endsWith('/crafting-plans/7') && request.method === 'GET').length, reads);
    assert.deepEqual(options.requests.filter(request => request.method !== 'GET').map(request => request.method), ['DELETE']);
    await page.getByRole('link', {name: 'Back to resources'}).click();
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
    assert.equal(options.requests.filter(request => request.method !== 'GET').length, 1);
});

// Public seam: CPU option availability and selection across live data and full reload. Variant
// identity and capacity determine merging; a name or ordinal cannot identify a selected CPU.
test('busy CPU eligibility uses known output identity and missing selections require a new choice', async t => {
    const {page, options, base} = await fixture(t);
    options.cpus = {
        'cpu-a': cpu,
        'cpu-b': {...cpu, isBusy: true, usedStorage: 2048, finalOutput: {itemKey: 'iron'}},
        'other-output': {...cpu, isBusy: true, finalOutput: {itemKey: 'other-variant'}},
        'unknown-storage': {...cpu, isBusy: true, usedStorage: -1, finalOutput: {itemKey: 'iron'}}
    };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', {name: /Iron Ingot/}).click();
    await page.getByRole('button', {name: 'Calculate plan', exact: true}).click();
    const cpus = page.getByRole('combobox', {name: 'Crafting CPU', exact: true});
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('option', {name: /cpu-b/}).evaluate(option => option.disabled), false);
    assert.equal(await cpus.getByRole('option', {name: /other-output/}).evaluate(option => option.disabled), true);
    assert.equal(await cpus.getByRole('option', {name: /unknown-storage/}).evaluate(option => option.disabled), true);
    await cpus.selectOption('cpu-b');
    delete options.cpus['cpu-b'];
    const refreshed = page.waitForResponse(response => response.url().endsWith('/cpus'));
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await (await refreshed).finished();
    await cpus.getByRole('option', {name: /cpu-b/}).waitFor({state: 'detached'});
    assert.equal(await cpus.inputValue(), '');
    assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).isDisabled(), true);
    options.cpus['cpu-b'] = {...cpu, isBusy: true, finalOutput: {itemKey: 'iron'}};
    await page.reload();
    await cpus.waitFor();
    assert.equal(await cpus.getByRole('option', {name: /cpu-b/}).evaluate(option => option.disabled), true);
    await page.getByText(/Only idle CPUs/).waitFor();
    assert.equal(options.requests.filter(request => request.method === 'POST').length, 1);
});

// Known native failures retain the computed resource details and safely display opaque text.
test('known submission rejection retains the plan and permits deliberate correction', async t => {
    const {page, options, base} = await fixture(t);
    options.submitStatus = 'FAIL';
    options.submitReason = '<img src=x onerror=alert(1)> Native ingredients disappeared';
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', {name: 'Start crafting', exact: true}).click();
    await page.getByText(options.submitReason, {exact: true}).waitFor({timeout: 3000});
    assert.equal(await page.getByRole('cell', {name: /Iron Ingot/}).count(), 1);
    assert.equal(await page.locator('img').count(), 0);
    assert.equal(options.requests.filter(request => request.path.endsWith('/submit')).length, 1);
    options.submitStatus = 'CPU_NOT_FOUND';
    await page.getByRole('button', {name: 'Start crafting', exact: true}).click();
    await page.getByRole('status').filter({hasText: /CPU.*available/i}).waitFor();
    assert.equal(await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).inputValue(), '');
    assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).isDisabled(), true);
    await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).selectOption('cpu-b');
    options.submitStatus = 'OK';
    await page.getByRole('button', {name: 'Start crafting', exact: true}).click();
    await page.getByRole('status').filter({hasText: /submitted/i}).waitFor();
    assert.equal(options.requests.filter(request => request.path.endsWith('/submit')).length, 3);
});

// Public seam: unusable plans and access failures must remove mutation authority, not merely
// add an error next to stale actionable private data.
test('unavailable and incomplete plans cannot submit, and denied refresh clears private plan data', async t => {
    const {page, options, base} = await fixture(t);
    options.planStatus = 'INVALID_ID';
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('status').filter({hasText: /plan.*unavailable/i}).waitFor({timeout: 3000});
    assert.equal(await page.getByRole('button', {name: 'Delete calculation', exact: true}).count(), 0);
    assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).count(), 0);
    options.planStatus = 'OK'; options.plan = {...readyPlan, isSimulating: true};
    await page.reload();
    await page.getByRole('status').filter({hasText: /missing resources/i}).waitFor();
    assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).isDisabled(), true);
    options.plan = {...readyPlan, plan: [{...readyPlan.plan[0], missing: 8}]};
    await page.reload();
    await page.getByRole('cell', {name: '8', exact: true}).waitFor();
    assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).isDisabled(), true);
    options.plan = readyPlan;
    await page.reload();
    await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).waitFor();
    options.cpuError = 'NO_PERMISSIONS';
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await page.getByRole('status').filter({hasText: /no longer have access/i}).waitFor();
    assert.equal(await page.getByRole('cell', {name: /Iron Ingot/}).count(), 0);
    assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).count(), 0);
    options.cpuError = null;
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await page.getByRole('cell', {name: /Iron Ingot/}).waitFor();
    options.gridError = 'NO_PERMISSIONS';
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await page.getByRole('cell', {name: /Iron Ingot/}).waitFor({state: 'hidden'});
    assert.equal(options.requests.filter(request => request.method !== 'GET').length, 0);
});

// The API has no idempotency receipt. Transport failures must never trigger mutation replay,
// including explicit refresh and ordinary navigation back to the same runtime plan.
test('uncertain submission outcomes cannot be retried by polling, refresh or route reentry', async t => {
    const {page, options, base} = await fixture(t);
    let planId = 7;
    for (const status of ['TIMEOUT', 'INTERNAL_ERROR', 'INVALID_RESPONSE', 'NETWORK_ERROR']) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId++}`);
        options.fault = {operation: 'submit', status};
        await page.getByRole('button', {name: 'Start crafting', exact: true}).click();
        await page.getByRole('status').filter({hasText: /outcome.*unknown/i}).waitFor({timeout: 3000});
        const count = options.requests.filter(request => request.path.endsWith('/submit')).length;
        await page.getByRole('button', {name: 'Refresh', exact: true}).click();
        await page.getByRole('link', {name: 'Back to resources'}).click();
        await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
        await page.goBack();
        await page.getByRole('status').filter({hasText: /outcome.*unknown/i}).waitFor();
        assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).isDisabled(), true);
        assert.equal(options.requests.filter(request => request.path.endsWith('/submit')).length, count);
    }
});

test('uncertain creation and deletion remain explicit and are never replayed', async t => {
    const {page, options, base} = await fixture(t);
    options.fault = {operation: 'create', status: 'TIMEOUT'};
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', {name: /Iron Ingot/}).click();
    await page.getByRole('button', {name: 'Calculate plan', exact: true}).click();
    await page.getByRole('status').filter({hasText: /outcome.*unknown/i}).waitFor({timeout: 3000});
    assert.equal(await page.getByRole('button', {name: 'Calculate plan', exact: true}).isDisabled(), true);
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await page.getByRole('combobox', {name: 'Network', exact: true}).selectOption(gridB);
    await page.getByRole('button', {name: /Gold Ingot/}).waitFor();
    await page.goBack();
    await page.getByRole('button', {name: /Iron Ingot/}).click();
    assert.equal(await page.getByRole('button', {name: 'Calculate plan', exact: true}).isDisabled(), true);
    options.fault = {operation: 'delete', status: 'TIMEOUT'};
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', {name: 'Delete calculation', exact: true}).click();
    await page.getByRole('status').filter({hasText: /outcome.*unknown/i}).waitFor();
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    assert.equal(await page.getByRole('button', {name: 'Delete calculation', exact: true}).isDisabled(), true);
    assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).isDisabled(), true);
    assert.deepEqual(options.requests.filter(request => request.method !== 'GET').map(request => request.method), ['POST', 'DELETE']);
});

test('quantity editing survives refresh and only positive safe integer quantities reach HTTP', async t => {
    const {page, options, base} = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', {name: /Iron Ingot/}).click();
    const quantity = page.getByRole('spinbutton', {name: 'Craft quantity'});
    await quantity.fill('37');
    const refreshed = page.waitForResponse(response => response.url().endsWith('/items'));
    await page.getByRole('button', {name: 'Refresh', exact: true}).evaluate(button => button.click());
    await (await refreshed).finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await quantity.evaluate(input => input === document.activeElement), true);
    assert.equal(await quantity.inputValue(), '37');
    for (const invalid of ['0', '-1', '1.5', '9007199254740992']) {
        await quantity.fill(invalid);
        await page.getByRole('button', {name: 'Calculate plan', exact: true}).click();
    }
    assert.equal(options.requests.filter(request => request.method === 'POST').length, 0);
    await quantity.fill('9007199254740991');
    await page.getByRole('button', {name: 'Calculate plan', exact: true}).click();
    await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).waitFor();
    assert.equal(options.requests.find(request => request.path.endsWith('/crafting-plans')).body.quantity, 9007199254740991);
});

test('deletion rejection makes unavailable or denied plans non-actionable', async t => {
    const {page, options, base} = await fixture(t);
    let planId = 20;
    for (const status of ['NO_PERMISSIONS', 'GRID_NOT_FOUND', 'INVALID_ID']) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId++}`);
        await page.getByRole('cell', {name: /Iron Ingot/}).waitFor();
        options.fault = {operation: 'delete', status};
        await page.getByRole('button', {name: 'Delete calculation', exact: true}).click();
        await page.getByRole('cell', {name: /Iron Ingot/}).waitFor({state: 'hidden', timeout: 3000});
        assert.equal(await page.getByRole('button', {name: 'Start crafting', exact: true}).count(), 0);
    }
});

test('pending submission resolves after route reentry and older CPU reads cannot overwrite its result', async t => {
    const {page, base} = await fixture(t);
    let completeSubmit;
    await page.route('**/submit', route => { completeSubmit = status => route.fulfill({status: status === 'OK' ? 200 : 503,
        contentType: 'application/json', body: JSON.stringify({status, data: null})}); });
    for (const [planId, status] of [[30, 'OK'], [31, 'TIMEOUT']]) {
        await page.goto(`${base}#/grids/${gridA}/plans/${planId}`);
        await page.getByRole('button', {name: 'Start crafting', exact: true}).click();
        await page.getByRole('link', {name: 'Back to resources'}).click();
        await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
        await page.goBack();
        await page.getByRole('heading', {name: 'Crafting plan', exact: true}).waitFor();
        await completeSubmit(status);
        await page.getByRole('status').filter({hasText: status === 'OK' ? /submitted/i : /outcome.*unknown/i}).waitFor();
        await page.getByRole('button', {name: 'Refresh', exact: true}).click();
        await page.getByRole('status').filter({hasText: status === 'OK' ? /submitted/i : /outcome.*unknown/i}).waitFor();
    }
    await page.unroute('**/submit');
    await page.goto(`${base}#/grids/${gridA}/plans/32`);
    await page.getByRole('combobox', {name: 'Crafting CPU', exact: true}).waitFor();
    let releaseCpus;
    const captured = new Promise(resolve => { releaseCpus = resolve; });
    await page.route('**/cpus', route => releaseCpus(route));
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    const cpuRequest = await captured;
    await page.getByRole('button', {name: 'Start crafting', exact: true}).click();
    await page.getByRole('status').filter({hasText: /submitted/i}).waitFor();
    await cpuRequest.fulfill({status: 403, contentType: 'application/json', body: JSON.stringify({status: 'NO_PERMISSIONS', data: null})});
    await page.waitForTimeout(100);
    await page.getByRole('status').filter({hasText: /submitted/i}).waitFor();
});

test('login errors keep the new UI selector and destination without loading a theme', async t => {
    const {page, options, base} = await fixture(t, '/ae2');
    await page.goto(`${base}&INVALID_PASSWORD#/grids/${gridA}/items`);
    await page.waitForFunction(() => !location.search.includes('INVALID_PASSWORD'));
    assert.equal(new URL(page.url()).searchParams.get('ui'), 'next');
    assert.equal(new URL(page.url()).hash, `#/grids/${gridA}/items`);
    assert.equal(options.requests.some(request => request.path.includes('/assets/web/')), false);
});

test('logout returns to the login document even when its URL matches the current route', async t => {
    const {page, base} = await fixture(t, '/ae2');
    await page.goto(`${base}#/`);
    await page.getByRole('button', {name: 'Log out', exact: true}).click();
    await page.getByPlaceholder('Enter password', {exact: true}).first().waitFor({timeout: 5000});
    assert.equal(new URL(page.url()).searchParams.get('ui'), 'next');
    assert.equal(new URL(page.url()).hash, '#/');
});

test('changing networks cannot publish a delayed response from the previous network', async t => {
    const {page, options, base} = await fixture(t);
    options.delayA = 400;
    await page.goto(base);
    const requested = page.waitForRequest(request => request.url().includes(gridA + '/items'));
    await page.getByRole('combobox', {name: 'Network'}).selectOption(gridA);
    await requested;
    await page.getByRole('combobox', {name: 'Network'}).selectOption(gridB);
    await page.getByRole('button', {name: /Gold Ingot/}).waitFor();
    // Let the deliberately delayed old response reach the browser before observing the final view.
    await page.waitForTimeout(550);
    assert.equal(await page.getByRole('button', {name: /Iron Ingot/}).count(), 0);
    assert.match(page.url(), new RegExp(gridB));
    await page.goBack();
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
    assert.equal(await page.getByRole('combobox', {name: 'Network'}).inputValue(), gridA);
});

test('appearance, language and terminal preferences survive reload and direct links', async t => {
    const {page, base} = await fixture(t, '/ae2');
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
    await page.getByRole('checkbox', {name: 'Refresh automatically'}).uncheck();
    await page.getByRole('combobox', {name: 'Appearance'}).selectOption('dark');
    await page.getByRole('combobox', {name: 'Sort by'}).selectOption('quantity');
    await page.getByRole('combobox', {name: 'Language'}).selectOption('pl');
    await page.reload();
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
    assert.equal(await page.getByRole('combobox', {name: 'Wygląd'}).inputValue(), 'dark');
    assert.equal(await page.getByRole('combobox', {name: 'Sortuj według'}).inputValue(), 'quantity');
    assert.equal(await page.getByRole('checkbox', {name: 'Odświeżaj automatycznie'}).isChecked(), false);
    assert.equal(await page.getByRole('combobox', {name: 'Sieć', exact: true}).inputValue(), gridA);
});

test('lost access clears resource data and manual retry recovers', async t => {
    const {page, options, base} = await fixture(t);
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', {name: /Iron Ingot/}).click();
    options.status = 403;
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await page.getByText('You no longer have access to this network.').waitFor();
    assert.equal(await page.getByRole('button', {name: /Iron Ingot/}).count(), 0);
    assert.equal(await page.getByRole('heading', {name: 'Iron Ingot'}).count(), 0);
    options.status = 200;
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
});

test('empty discovery and unavailable bookmarked networks stay usable', async t => {
    const {page, options, base} = await fixture(t);
    options.empty = true;
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByText('This network is not available.').waitFor();
    assert.equal(await page.getByRole('button', {name: /Iron Ingot/}).count(), 0);
    await page.getByRole('link', {name: 'Home', exact: true}).click();
    await page.getByText(/No accessible networks/).waitFor();
    options.empty = false;
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await page.getByRole('combobox', {name: 'Network'}).selectOption(gridA);
    await page.getByRole('button', {name: /Iron Ingot/}).waitFor();
});

test('home network links keep keyboard focus when polling refreshes the list', async t => {
    const {page, options, base} = await fixture(t);
    await page.goto(base);
    const network = page.getByRole('link', {name: /Alpha/});
    await network.focus();
    const refreshed = page.waitForResponse(response => response.url().endsWith('/api/grids'));
    await (await refreshed).finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await network.evaluate(link => link === document.activeElement), true);
    const secondNetwork = page.getByRole('link', {name: /Beta/});
    await secondNetwork.focus();
    options.reverseGrids = true;
    await (await page.waitForResponse(response => response.url().endsWith('/api/grids'))).finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await secondNetwork.evaluate(link => link === document.activeElement), true);
    await page.keyboard.press('Enter');
    await page.getByRole('button', {name: /Gold Ingot/}).waitFor();
});

test('resource details are text, keyboard accessible and paging does not require new requests', async t => {
    const {page, options, base} = await fixture(t);
    const untrustedName = '<img src=x onerror=alert(1)> Quartz';
    options.itemsA = [{...quartz, itemname: untrustedName}, ...Array.from({length: 105}, (_, index) => ({...iron,
        itemname: `Resource ${String(index).padStart(3, '0')}`, itemKey: `key-${index}`}))];
    await page.goto(`${base}#/grids/${gridA}/items`);
    const item = page.getByRole('button', {name: untrustedName + ' 42'});
    await item.focus();
    await page.getByRole('tooltip').waitFor({state:'visible'});
    await page.keyboard.press('Escape');
    await page.getByRole('tooltip').waitFor({state:'hidden'});
    await item.click();
    await page.getByRole('heading', {name: untrustedName, exact:true}).waitFor();
    assert.equal(await page.locator('img').count(), 0);
    await page.getByRole('button', {name:'Next page'}).click();
    await page.getByRole('button', {name:/Resource 104/}).waitFor();
    await page.getByRole('button', {name:'Previous page'}).click();
    assert.equal(await item.getAttribute('aria-pressed'), 'true');
    assert.equal(options.requests.filter(request=>request.path.endsWith('/items')).length,1);
});
