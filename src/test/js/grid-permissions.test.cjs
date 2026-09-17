const test = require('node:test');
const assert = require('node:assert/strict');
const { terminal } = require('./terminal-fixture.cjs');

for (const page of ['../../main/resources/assets/webpage.html', '../../../example_website/index.php']) {
    test(`${page}: grouped permission sources retain every player's access explanation`, () => {
        const { context } = terminal(page);
        function element(tag) {
            return { tag, children: [], textContent: '',
                appendChild(child) { this.children.push(child); },
                replaceChildren() { this.children = []; } };
        }
        context.document.createElement = element;
        const container = context.document.getElementById('gridaccessdetails');
        Object.assign(container, element('div'));
        const source = (uuid, name, kind) => ({player: {uuid, name}, kind,
            position: {dimid: 'world', x: 1, y: 2, z: 3}, reason: 'node_owner'});
        context.showGridAccess({
            'anna-id': [source('anna-id', 'Anna', 'controller'), source('anna-id', 'Anna', 'terminal')],
            'piotr-id': [source('piotr-id', 'Piotr', 'wireless_access_point')]
        });
        function descendants(node) {
            return [node, ...node.children.flatMap(descendants)];
        }
        const rendered = descendants(container);
        for (const value of ['Anna', 'Piotr', 'anna-id', 'piotr-id']) {
            assert.ok(rendered.some(node => String(node.textContent).includes(value)));
        }
        assert.equal(rendered.filter(node => node.tag === 'li').length, 3);
        context.showGridAccess({});
        assert.equal(container.children.length, 0);
    });
}
