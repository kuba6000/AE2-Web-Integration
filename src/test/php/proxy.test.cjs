const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');

const php = process.env.PHP_BINARY || 'php';
const available = spawnSync(php, ['-v'], { windowsHide: true }).status === 0;

test('PHP proxy preserves explicit credentials and guards cookie mutations', { skip: !available }, async t => {
    const received = [];
    let authFailure;
    const upstream = http.createServer((request, response) => {
        received.push(request.headers.authorization);
        response.setHeader('Content-Type', 'application/json');
        if (authFailure) {
            response.statusCode = authFailure === 'NOT_ONLINE' ? 409 : 401;
            response.end(JSON.stringify({ status: authFailure, data: null }));
            return;
        }
        response.end(JSON.stringify({ status: 'OK', data: null }));
    });
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
    t.after(() => new Promise(resolve => upstream.close(resolve)));
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ae2-php-proxy-'));
    t.after(() => fs.rm(directory, { recursive: true, force: true }));
    // Apply the documented deployment configuration to a disposable website copy.
    const website = await fs.readFile(path.resolve(__dirname, '../../../example_website/index.php'), 'utf8');
    await fs.writeFile(path.join(directory, 'index.php'), website.replace(
        'http://localhost:2324/', `http://127.0.0.1:${upstream.address().port}/`), 'utf8');
    const reservation = http.createServer();
    await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
    const port = reservation.address().port;
    await new Promise(resolve => reservation.close(resolve));
    const child = spawn(php, ['-S', `127.0.0.1:${port}`, '-t', directory], {
        windowsHide: true, stdio: ['ignore', 'ignore', 'pipe']
    });
    t.after(async () => {
        if (child.exitCode === null) {
            const stopped = new Promise(resolve => child.once('exit', resolve));
            child.kill();
            await stopped;
        }
    });
    await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('PHP server did not start')), 10000);
        child.stderr.on('data', data => {
            if (String(data).includes('Development Server')) { clearTimeout(timeout); resolve(); }
        });
        child.once('error', error => { clearTimeout(timeout); reject(error); });
    });
    const url = `http://127.0.0.1:${port}/index.php?API=api/grids`;
    for (const authorization of ['Basic invalid', 'Bearer', 'invalid']) {
        const response = await fetch(url, { headers: {
            Authorization: authorization, Cookie: 'authenticationToken=cookie-token'
        } });
        assert.equal(response.status, 401, authorization);
        assert.equal((await response.json()).status, 'UNAUTHORIZED');
    }
    assert.equal(received.length, 0);
    assert.equal((await fetch(url, { headers: {
        Authorization: 'Bearer explicit-token', Cookie: 'authenticationToken=cookie-token'
    } })).status, 200);
    assert.equal(received.pop(), 'Bearer explicit-token');
    assert.equal((await fetch(url, { headers: { Cookie: 'authenticationToken=cookie-token' } })).status, 200);
    assert.equal(received.pop(), 'Bearer cookie-token');
    const mutation = url.replace('api/grids', 'api/grids/grid-key/settings');
    assert.equal((await fetch(mutation, { method: 'PATCH', headers: {
        Cookie: 'authenticationToken=cookie-token', 'Content-Type': 'application/json'
    }, body: '{}' })).status, 403);
    assert.equal(received.length, 0);
    assert.equal((await fetch(mutation, { method: 'PATCH', headers: {
        Cookie: 'authenticationToken=cookie-token', 'Content-Type': 'application/json', 'X-AE2-Request': 'true'
    }, body: '{}' })).status, 200);
    assert.equal(received.pop(), 'Bearer cookie-token');
    for (const status of ['INVALID_USER', 'INVALID_PASSWORD', 'NOT_ONLINE']) {
        authFailure = status;
        const response = await fetch(`http://127.0.0.1:${port}/index.php`, {
            method: 'POST', redirect: 'manual',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: status === 'NOT_ONLINE' ? 'register=ExamplePlayer&password=test' : 'username=ExamplePlayer&password=test'
        });
        assert.equal(response.status, 302);
        assert.equal(response.headers.get('location'), `?${status}`);
    }
});
