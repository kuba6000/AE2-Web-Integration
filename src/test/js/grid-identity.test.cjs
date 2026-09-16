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
            .filter(url => url.pathname.startsWith('/api/grids/'));
        assert.ok(targeted.length > 0);
        assert.ok(targeted.every(url => url.pathname.split('/')[3] === key));
    });

    test(page + ': restoring the default grid preserves its opaque key', () => {
        const { context } = terminal(page);
        const key = 'EjRWeJCrze8BI0VniavN7w';
        context.document.cookie = 'defaultGrid=' + key;
        context.initSettings();
        assert.equal(context.settings.defaultGrid, key);
    });
}

for (const page of ['../../main/resources/assets/webpage.html', '../../../example_website/index.php']) {
    test(page + ': grid listing uses the API route and reports HTTP errors', () => {
        const { context, requests } = terminal(page);
        const alerts = [];
        context.showAlert = message => alerts.push(message);
        context.updateGridList();
        const request = requests.find(entry => entry.url === 'api/grids');
        assert.ok(request);
        assert.equal(typeof request.failure, 'function');
        request.failure({status: 503, responseJSON: {status: 'SERVER_BUSY', data: null}});
        assert.ok(alerts.some(message => message.includes('SERVER_BUSY')));
    });
}
