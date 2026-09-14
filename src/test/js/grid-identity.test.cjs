const test = require('node:test');
const assert = require('node:assert/strict');
const { terminal } = require('./terminal-fixture.cjs');

for (const page of ['../../main/resources/assets/webpage.html', '../../../example_website/index.php']) {
    test(page + ': selecting a grid preserves its opaque key in requests', () => {
        const { context, requests } = terminal(page);
        const key = 'EjRWeJCrze8BI0VniavN7w';
        context.selectedGridChanged({ value: key });
        assert.equal(context.selectedGrid, key);
        const targeted = requests.map(request => new URL(request.url, 'http://local/'))
            .filter(url => url.searchParams.has('grid'));
        assert.ok(targeted.length > 0);
        assert.ok(targeted.every(url => url.searchParams.get('grid') === key));
    });

    test(page + ': restoring the default grid preserves its opaque key', () => {
        const { context } = terminal(page);
        const key = 'EjRWeJCrze8BI0VniavN7w';
        context.document.cookie = 'defaultGrid=' + key;
        context.initSettings();
        assert.equal(context.settings.defaultGrid, key);
    });
}
