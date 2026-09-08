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

module.exports = { terminal };
