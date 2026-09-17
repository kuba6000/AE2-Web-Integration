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
        response.end(JSON.stringify({ status: 'OK', data: {
            token: 'session-token', username: 'ExamplePlayer', isAdmin: false, isOutdated: false
        } }));
    });
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
    t.after(() => new Promise(resolve => upstream.close(resolve)));
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ae2-php-proxy-'));
    t.after(() => fs.rm(directory, { recursive: true, force: true }));
    // Apply the documented deployment configuration to a disposable website copy.
    const website = await fs.readFile(path.resolve(__dirname, '../../../example_website/index.php'), 'utf8');
    await fs.writeFile(path.join(directory, 'index.php'), website.replace(
        'http://localhost:2324/', `http://127.0.0.1:${upstream.address().port}/`), 'utf8');
    await fs.mkdir(path.join(directory, 'ae2'));
    await fs.copyFile(path.join(directory, 'index.php'), path.join(directory, 'ae2/index.php'));
    await fs.mkdir(path.join(directory, 'untrusted'));
    await fs.writeFile(path.join(directory, 'untrusted/index.php'), website.replace(
        'http://localhost:2324/', `http://127.0.0.1:${upstream.address().port}/`).replace(
        "$AE2_TRUSTED_PROXIES = ['127.0.0.1', '::1'];", '$AE2_TRUSTED_PROXIES = [];'), 'utf8');
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
    authFailure = null;
    await t.test('mounted login and logout leave cookie scope to the browser', async () => {
        const login = await fetch(`http://127.0.0.1:${port}/ae2/index.php`, {
            method: 'POST', redirect: 'manual',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'username=ExamplePlayer&password=test'
        });
        assert.equal(login.status, 302);
        assert.equal(login.headers.get('location'), '.');
        const cookies = login.headers.getSetCookie();
        assert.equal(cookies.length, 4);
        for (const cookie of cookies) assert.doesNotMatch(cookie, /;\s*path=/i);
        const logout = await fetch(`http://127.0.0.1:${port}/ae2/index.php?API=api/auth/logout`, {
            method: 'POST', headers: {
                Cookie: cookies.map(cookie => cookie.split(';')[0]).join('; '),
                'X-AE2-Request': 'true'
            }
        });
        assert.equal(logout.status, 200);
        const expired = logout.headers.getSetCookie();
        assert.equal(expired.length, 4);
        for (const cookie of expired) {
            assert.doesNotMatch(cookie, /;\s*path=/i);
            assert.match(cookie, /;\s*max-age=0(?:;|$)/i);
        }
    });
    await t.test('HTTPS form origin uses the public host and trusted proxy protocol', async () => {
        const form = (headers, pathname = '/ae2/index.php') => new Promise((resolve, reject) => {
            const request = http.request({ hostname: '127.0.0.1', port, path: pathname, method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...headers }
            }, response => {
                response.resume();
                response.on('end', () => resolve(response.statusCode));
            });
            request.on('error', reject);
            request.end('username=ExamplePlayer&password=test');
        });
        assert.equal(await form({ Host: 'ae.example', Origin: 'https://ae.example',
            'X-Forwarded-Proto': 'https' }), 302);
        assert.equal(await form({ Host: 'ae.example:8443', Origin: 'https://ae.example:8443',
            'X-Forwarded-Proto': 'https' }), 302);
        assert.equal(await form({ Host: 'ae.example', Origin: 'http://ae.example' }), 302);
        assert.equal(await form({ Host: 'ae.example', Origin: 'https://ae.example:443',
            'X-Forwarded-Proto': 'https' }), 302);
        for (const origin of ['https://other.example', 'https://ae.example:8443', 'http://ae.example',
            'null', 'https://user@ae.example', 'https://ae.example/path', 'https://ae.example?query',
            'https://ae.example#fragment', 'https://ae.example https://other.example']) {
            assert.equal(await form({ Host: 'ae.example', Origin: origin,
                'X-Forwarded-Proto': 'https' }), 403, origin);
        }
        for (const protocol of ['https,http', 'https, https', 'ftp', '']) {
            assert.equal(await form({ Host: 'ae.example', Origin: 'http://ae.example',
                'X-Forwarded-Proto': protocol }), 403, protocol);
        }
        assert.equal(await form({ Host: 'ae.example', Origin: 'https://ae.example',
            'X-Forwarded-Proto': 'https' }, '/untrusted/index.php'), 403);
        assert.equal(await form({ Host: 'ae.example', Origin: 'http://ae.example',
            'X-Forwarded-Proto': 'https' }, '/untrusted/index.php'), 302);
        assert.equal(await form({ Host: 'ae.example', Origin: 'https://ae.example',
            'X-Forwarded-Proto': 'https', 'Sec-Fetch-Site': 'cross-site' }), 403);
    });
});
