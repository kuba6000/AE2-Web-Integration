const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function terminal(page) {
    const elements = new Map();
    const document = {
        cookie: '',
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, { innerHTML: '', style: {}, value: '', checked: false });
            return elements.get(id);
        },
        getElementsByClassName() { return []; }
    };
    const requests = [];
    const $ = () => ({ height() { return 0; } });
    $.getJSON = (url, success) => {
        const request = { url, success };
        requests.push(request);
        return { fail(callback) { request.failure = callback; return this; } };
    };
    const context = vm.createContext({
        document, $, console: { log() {} }, setTimeout() {},
        window: { prompt: () => '3' },
        localStorage: { getItem: () => null, setItem() {} }
    });
    const html = fs.readFileSync(path.join(__dirname, page), 'utf8');
    const script = html.match(/<script>([\s\S]*?)<\/script>/)[1]
        .replace('_REPLACE_ME_IS_ADMIN', 'false').replace('_REPLACE_ME_VERSION_OUTDATED', 'false')
        .replace(/<\?php[\s\S]*?\?>/g, 'false');
    vm.runInContext(script, context);
    requests.length = 0;
    context.selectedGrid = 123;
    return { context, requests, elements };
}

for (const page of ['../../main/resources/assets/webpage.html', '../../../example_website/index.php']) {
test(page + ': ordering sends the stable resource key', () => {
    const { context, requests } = terminal(page);
    const key = 'AAAAAAAAAAAAAAAAAAAAAA';
    context.beginOrderingItem(key);
    assert.equal(requests.length, 1);
    const query = new URL(requests[0].url, 'http://local/').searchParams;
    assert.equal(query.get('itemKey'), key);
    assert.equal(query.has('item'), false);
    assert.equal(query.get('quantity'), '3');
});

test(page + ': CPU merging requires two present matching resource keys', () => {
    const { context } = terminal(page);
    context.currentJob.bytesTotal = 16;
    const cluster = { isBusy: true, availableStorage: 64, usedStorage: 16, finalOutput: {} };
    assert.equal(context.isValidCPUForOrder(cluster), false);
    context.currentJob.itemKey = 'AAAAAAAAAAAAAAAAAAAAAA';
    cluster.finalOutput.itemKey = context.currentJob.itemKey;
    assert.equal(context.isValidCPUForOrder(cluster), true);
    cluster.finalOutput.itemKey = 'BBBBBBBBBBBBBBBBBBBBBA';
    assert.equal(context.isValidCPUForOrder(cluster), false);
    context.currentJob.itemKey = 'ik1:AAAAAAAAAAAAAAAAAAAAAA';
    cluster.finalOutput.itemKey = context.currentJob.itemKey;
    assert.equal(context.isValidCPUForOrder(cluster), false);
    cluster.isBusy = false;
    assert.equal(context.isValidCPUForOrder(cluster), true);
});

test(page + ': only craftable rows with a usable identity offer ordering', () => {
    for (const icons of [false, true]) {
        const { context, requests, elements } = terminal(page);
        const key = 'AAAAAAAAAAAAAAAAAAAAAA';
        context.settings.showItemIcon = icons;
        context.globalItemList = [
            { itemid: 'minecraft:stone', itemname: 'Stone', quantity: 4, craftable: true, itemKey: key },
            { itemid: 'minecraft:dirt', itemname: 'Dirt', quantity: 2, craftable: true, identityStatus: 'UNAVAILABLE' },
            { itemid: 'minecraft:sand', itemname: 'Sand', quantity: 8, craftable: false, itemKey: key }
        ];
        context.displayItemList();
        const rendered = elements.get('terminalcontent').innerHTML;
        const buttons = [...rendered.matchAll(/<button[^>]*onclick="([^"]+)"[^>]*>/g)];
        assert.equal(buttons.length, 1);
        vm.runInContext(buttons[0][1], context);
        assert.equal(new URL(requests[0].url, 'http://local/').searchParams.get('itemKey'), key);
    }
});

test(page + ': unavailable identities cannot start an order', () => {
    const { context, requests } = terminal(page);
    let prompts = 0;
    context.window.prompt = () => { prompts++; return '3'; };
    context.beginOrderingItem(undefined);
    assert.equal(requests.length, 0);
    assert.equal(prompts, 0);
});

test(page + ': obsolete or noncanonical resource keys cannot start an order', () => {
    const { context, requests } = terminal(page);
    let prompts = 0;
    context.window.prompt = () => { prompts++; return '3'; };
    for (const invalid of [17, '17', 'ik1:AAAAAAAAAAAAAAAAAAAAAA', 'AAAAAAAAAAAAAAAAAAAAAB', 'AAAAAAAAAAAAAAAAAAAAAA==', 'AAAAAAAAAAAAAAAAAAAAAA\n']) {
        context.beginOrderingItem(invalid);
    }
    assert.equal(requests.length, 0);
    assert.equal(prompts, 0);
});

test(page + ': icons render from cache and missing icons round-trip using resource keys', () => {
    const { context, requests, elements } = terminal(page);
    const cached = 'AAAAAAAAAAAAAAAAAAAAAA';
    const missing = 'qTcTDu8-ZBplmiM8QEpOSQ';
    const cache = new Map([['itemIcon' + cached, 'Y2FjaGVk']]);
    context.localStorage.getItem = key => cache.get(key) ?? null;
    context.localStorage.setItem = (key, value) => cache.set(key, value);
    context.settings.showItemIcon = true;
    context.globalItemList = [
        { itemid: 'minecraft:stone', itemname: 'Stone', quantity: 4, itemKey: cached },
        { itemid: 'minecraft:dirt', itemname: 'Dirt', quantity: 2, itemKey: missing },
        { itemid: 'minecraft:sand', itemname: 'Sand', quantity: 1 }
    ];
    context.displayItemList(true);
    assert.ok(elements.get('terminalcontent').innerHTML.includes('data:image/png;base64,Y2FjaGVk'));
    assert.equal(requests.length, 1);
    const query = new URL(requests[0].url, 'http://local/').searchParams;
    assert.deepEqual(query.get('items').split(',').filter(Boolean), [missing]);
    requests[0].success({ status: 'OK', data: [{ itemKey: missing, pngData: 'bmV3' }] });
    assert.equal(cache.get('itemIcon' + missing), 'bmV3');
    assert.ok(elements.get('terminalcontent').innerHTML.includes('data:image/png;base64,bmV3'));
    assert.equal(requests.length, 1);
});

test(page + ': icon preference persists and controls image rendering', () => {
    const { context, elements } = terminal(page);
    context.globalItemList = [{ itemid: 'minecraft:stone', itemname: 'Stone', quantity: 4, itemKey: 'AAAAAAAAAAAAAAAAAAAAAA' }];
    context.changeShowItemIcon({ checked: true });
    assert.ok(elements.get('terminalcontent').innerHTML.includes('<img'));
    context.settings.showItemIcon = false;
    context.initSettings();
    assert.equal(elements.get('showitemicon').checked, true);
    context.changeShowItemIcon({ checked: false });
    assert.equal(elements.get('terminalcontent').innerHTML.includes('<img'), false);
});

test(page + ': uncertain transport failure clears pending UI without replaying the order', () => {
    const { context, requests } = terminal(page);
    context.beginOrderingItem('AAAAAAAAAAAAAAAAAAAAAA');
    assert.equal(typeof requests[0].failure, 'function');
    requests[0].failure();
    assert.equal(requests.length, 1);
    assert.equal(context.loadingMessages.length, 0);
    assert.equal(context.currentJob.id, -1);
});

}
