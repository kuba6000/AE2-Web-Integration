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

async function fixture(t, mount = '') {
    const options = { delayA: 0, status: 200, requests: [], empty: false, reverseGrids: false, loggedOut: false, itemsA: [iron, quartz] };
    const server = http.createServer(async (request, response) => {
        const url = new URL(request.url, 'http://localhost');
        if (!url.pathname.startsWith(mount + '/')) { response.writeHead(404).end(); return; }
        const resource = url.pathname.slice(mount.length);
        options.requests.push({ path: resource, method: request.method, headers: request.headers });
        if (resource.startsWith('/api/')) {
            response.setHeader('Content-Type', 'application/json');
            if (resource === '/api/auth/logout') {
                options.loggedOut = true;
                response.end(JSON.stringify({status: 'OK', data: null}));
            } else if (resource === '/api/grids') {
                const grids = [
                    { key: gridA, owner: 'Alpha', cpuCount: 2, accessSources: {} },
                    { key: gridB, owner: 'Beta', cpuCount: 1, accessSources: {} }
                ];
                response.end(JSON.stringify({ status: 'OK', data: options.empty ? [] : options.reverseGrids ? grids.reverse() : grids }));
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
