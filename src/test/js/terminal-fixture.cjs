const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function terminal(page) {
    const elements = new Map();
    const submissions = [];
    const document = {
        cookie: '',
        body: { appendChild() {} },
        createElement(tagName) {
            return {
                tagName, children: [],
                appendChild(child) { this.children.push(child); },
                submit() {
                    submissions.push({ method: this.method, action: this.action,
                        fields: Object.fromEntries(this.children.map(child => [child.name, child.value])) });
                }
            };
        },
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, { innerHTML: '', style: {}, value: '', checked: false, setAttribute() {}, addEventListener() {} });
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
    $.ajax = options => {
        const request = { ...options };
        requests.push(request);
        return { fail(callback) { request.failure = callback; return this; } };
    };
    const context = vm.createContext({
        document, $, AbortController, console: { log() {} }, setTimeout() {},
        window: { prompt: () => '3', addEventListener() {} },
        localStorage: { getItem: () => null, setItem() {} }
    });
    const html = fs.readFileSync(path.join(__dirname, page), 'utf8');
    const script = html.match(/<script>([\s\S]*?)<\/script>/)[1]
        .replace(/<\?php[\s\S]*?\?>/g, 'false');
    vm.runInContext(script, context);
    requests.length = 0;
    context.selectedGrid = 123;
    return { context, requests, elements, submissions };
}

module.exports = { terminal };
