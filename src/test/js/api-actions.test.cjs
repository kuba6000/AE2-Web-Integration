const test = require('node:test');
const assert = require('node:assert/strict');
const { terminal } = require('./terminal-fixture.cjs');

for (const page of ['../../main/resources/assets/webpage.html', '../../../example_website/index.php']) {
    test(`${page}: tracking changes send a JSON patch and display rejected changes`, () => {
        const { context, requests } = terminal(page);
        const alerts = [];
        context.showAlert = value => alerts.push(value);
        context.onTrackThisGridChange({ checked: true });
        const request = requests.shift();
        assert.equal(request.url, 'api/grids/123/settings');
        assert.equal(request.method, 'PATCH');
        assert.equal(request.headers['X-AE2-Request'], 'true');
        assert.deepEqual(JSON.parse(request.data), { isTracked: true });
        request.failure({ status: 403, responseJSON: { status: 'ACCESS_DENIED', data: null } });
        assert.ok(alerts.some(value => value.includes('ACCESS_DENIED')));
    });

    test(`${page}: history failures release the loading UI and preserve the server status`, () => {
        const { context, requests } = terminal(page);
        const alerts = [];
        context.showAlert = value => alerts.push(value);
        for (const [action, url] of [
            [() => context.getCraftingHistory(), 'api/grids/123/crafting-history'],
            [() => context.openTrackingData(7), 'api/grids/123/crafting-history/7']
        ]) {
            action();
            const request = requests.shift();
            assert.equal(request.url, url);
            assert.equal(request.method, 'GET');
            assert.equal(request.headers, undefined);
            request.failure({ status: 404, responseJSON: { status: 'NOT_FOUND', data: null } });
            assert.equal(context.loadingMessages.length, 0);
            assert.ok(alerts.pop().includes('NOT_FOUND'));
        }
    });

    test(`${page}: cancelling a plan and a CPU use explicit mutations`, () => {
        const { context, requests } = terminal(page);
        context.currentWindow = 2;
        context.currentJob.id = 17;
        context.cancelCurrentJob();
        const deletion = requests.find(request => request.method === 'DELETE');
        assert.ok(deletion);
        assert.equal(deletion.url, 'api/grids/123/crafting-plans/17');
        assert.equal(deletion.headers['X-AE2-Request'], 'true');
        context.globalCPUList = { 'a-cpu': { name: 'Main' } };
        context.cancelJobOnCPU('a-cpu');
        const cancellation = requests.find(request => request.method === 'POST');
        assert.ok(cancellation);
        assert.equal(cancellation.url, 'api/grids/123/cpus/a-cpu/cancel');
        assert.equal(cancellation.headers['X-AE2-Request'], 'true');
    });

    test(`${page}: logout posts before returning to the mounted page, including an expired session`, () => {
        for (const response of [null, { status: 401, responseJSON: { status: 'UNAUTHORIZED', data: null } }, { status: 401 }]) {
            const { context, requests } = terminal(page);
            const navigations = [];
            context.document.location = { set href(value) { navigations.push(value); } };
            context.logout();
            const request = requests.shift();
            assert.equal(request.url, 'api/auth/logout');
            assert.equal(request.method, 'POST');
            assert.equal(request.headers['X-AE2-Request'], 'true');
            assert.equal(navigations.length, 0);
            if (response) request.failure(response);
            else request.success({ status: 'OK', data: null });
            assert.equal(navigations.length, 1);
            assert.equal(new URL(navigations[0], 'https://example.com/ae2/').pathname, '/ae2/');
        }
    });
}
