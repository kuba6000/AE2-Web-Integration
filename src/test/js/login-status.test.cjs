const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

for (const page of ['../../main/resources/assets/login.html', '../../../example_website/login.html']) {
    for (const status of ['INVALID_USER', 'INVALID_PASSWORD', 'NOT_ONLINE']) {
        test(`${page}: displays ${status} and clears the error URL`, () => {
            const elements = new Map();
            const navigations = [];
            const document = {
                cookie: '', title: 'Login',
                getElementById(id) {
                    if (!elements.has(id)) elements.set(id, { innerHTML: '', style: {} });
                    return elements.get(id);
                }
            };
            const context = vm.createContext({ document, URL, window: {
                location: { href: `https://example.com/ae2/?${status}`, pathname: '/ae2/' },
                history: { replaceState(state, title, url) { navigations.push(url); } }
            } });
            const html = fs.readFileSync(path.join(__dirname, page), 'utf8');
            const script = html.match(/<script>([\s\S]*?)<\/script>/)[1]
                .replace('_REPLACE_ME_IS_PUBLIC_MODE', 'true').replace(/<\?php[\s\S]*?\?>/g, 'true');
            vm.runInContext(script, context);
            assert.ok(document.getElementById('maininfo').innerHTML.length > 0);
            assert.deepEqual(navigations, ['/ae2/']);
        });
    }
}
