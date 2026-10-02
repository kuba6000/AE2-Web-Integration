const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const php = process.env.PHP_BINARY || 'php';
const proxyFile = path.resolve(__dirname, '../../../tools/php-proxy/index.php');

async function fixture(t) {
    const received = [];
    let reply = (request, response) => {
        response.setHeader('Content-Type', 'text/html');
        response.end('<html><body>Upstream login</body></html>');
    };
    const upstream = http.createServer(async (request, response) => {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        received.push({ url: request.url, method: request.method, headers: request.headers, body: Buffer.concat(chunks) });
        reply(request, response);
    });
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
    t.after(() => new Promise(resolve => upstream.close(resolve)));
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ae2-php-proxy-'));
    t.after(() => fs.rm(directory, { recursive: true, force: true }));
    const source = (await fs.readFile(proxyFile, 'utf8')).replace('http://localhost:2324/', `http://127.0.0.1:${upstream.address().port}/`);
    for (const mount of ['', 'ae2', 'trusted']) {
        await fs.mkdir(path.join(directory, mount), { recursive: true });
        await fs.writeFile(path.join(directory, mount, 'index.php'), mount === 'trusted'
            ? source.replace('$AE2_TRUSTED_PROXIES = [];', "$AE2_TRUSTED_PROXIES = ['127.0.0.1'];") : source);
    }
    // PHP's development server does not process .htaccess; reproduce its front-controller routing.
    const router = path.join(directory, 'router.php');
    await fs.writeFile(router, `<?php
        $path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
        $mount = preg_match('~^/(ae2|trusted)(/|$)~', $path, $match) ? '/' . $match[1] : '';
        $_SERVER['SCRIPT_NAME'] = $mount . '/index.php';
        require __DIR__ . $mount . '/index.php';
    `);
    const reservation = http.createServer();
    await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
    const port = reservation.address().port;
    await new Promise(resolve => reservation.close(resolve));
    const child = spawn(php, ['-S', `127.0.0.1:${port}`, '-t', directory, router], {
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
        const timer = setTimeout(() => reject(new Error('PHP server did not start')), 10000);
        child.stderr.on('data', data => {
            if (String(data).includes('Development Server')) { clearTimeout(timer); resolve(); }
        });
        child.once('error', error => { clearTimeout(timer); reject(error); });
    });
    const request = (resource, options = {}) => new Promise((resolve, reject) => {
        const call = http.request({ hostname: '127.0.0.1', port, path: resource, ...options }, response => {
            const chunks = [];
            response.on('data', chunk => chunks.push(chunk));
            response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks) }));
        });
        call.on('error', reject);
        call.end(options.body);
    });
    return { request, received, upstream, setReply(callback) { reply = callback; } };
}

test('PHP serves the upstream page at root and under a path prefix', async t => {
    const proxy = await fixture(t);
    for (const mount of ['/', '/ae2/']) {
        const response = await proxy.request(mount);
        assert.equal(response.status, 200);
        assert.equal(response.body.toString(), '<html><body>Upstream login</body></html>');
        assert.equal(proxy.received.pop().url, '/');
    }
});


test('PHP forwards client identity without trusting spoofed headers from visitors', async t => {
    const proxy = await fixture(t);
    const spoofed = { 'X-Forwarded-For': '127.0.0.99', 'X-Real-IP': '127.0.0.99',
        'X-Forwarded-Proto': 'https', Forwarded: 'for=127.0.0.99;proto=https', Host: 'public.example:8443' };
    assert.equal((await proxy.request('/ae2/api/grids', { headers: spoofed })).status, 200);
    let request = proxy.received.pop();
    assert.equal(request.headers['x-forwarded-for'], '127.0.0.1');
    assert.equal(request.headers['x-real-ip'], '127.0.0.1');
    assert.equal(request.headers['x-forwarded-proto'], 'http');
    assert.equal(request.headers.forwarded, undefined);
    assert.equal(request.headers.host, 'public.example:8443');
    assert.equal((await proxy.request('/trusted/api/grids', { headers: {
        ...spoofed, 'X-Forwarded-For': '127.0.0.1, 203.0.113.40'
    } })).status, 200);
    request = proxy.received.pop();
    assert.equal(request.headers['x-forwarded-for'], '203.0.113.40');
    assert.equal(request.headers['x-real-ip'], '203.0.113.40');
    assert.equal(request.headers['x-forwarded-proto'], 'https');
    for (const headers of [ { 'X-Forwarded-For': 'invalid' }, { 'X-Forwarded-Proto': 'https,http' } ]) {
        assert.equal((await proxy.request('/trusted/', { headers })).status, 400);
        assert.equal(proxy.received.length, 0);
    }
});

test('PHP preserves raw API requests, credentials and end-to-end HTTP metadata', async t => {
    const proxy = await fixture(t);
    const payload = Buffer.from([0, 255, 13, 10, 38, 61, 43]);
    proxy.setReply((request, response) => {
        response.writeHead(422, { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'private, no-store',
            'WWW-Authenticate': 'Bearer', Allow: 'GET, PATCH', Connection: 'close, X-Internal',
            'X-Internal': 'hidden', ETag: '"sample"' });
        response.end(payload);
    });
    for (const mount of ['/', '/ae2/']) {
        const response = await proxy.request(mount + 'api/grids/key/settings?x=1&x=2&name=a%2Bb+z&API=http://unrelated.invalid/', {
            method: 'PATCH', body: payload, headers: {
                Authorization: 'Basic invalid', Cookie: 'authenticationToken=cookie-token; preference=light',
                'Content-Type': 'application/octet-stream', 'X-AE2-Request': 'true',
                Origin: 'https://public.example', 'Sec-Fetch-Site': 'same-origin',
                Connection: 'close, X-Private', 'X-Private': 'hidden'
            }
        });
        assert.equal(response.status, 422);
        assert.deepEqual(response.body, payload);
        assert.equal(response.headers['content-type'], 'application/octet-stream');
        assert.equal(response.headers['cache-control'], 'private, no-store');
        assert.equal(response.headers['www-authenticate'], 'Bearer');
        assert.equal(response.headers.allow, 'GET, PATCH');
        assert.equal(response.headers.etag, '"sample"');
        assert.equal(response.headers['x-internal'], undefined);
        const request = proxy.received.pop();
        assert.equal(request.url, '/api/grids/key/settings?x=1&x=2&name=a%2Bb+z&API=http://unrelated.invalid/');
        assert.equal(request.method, 'PATCH');
        assert.deepEqual(request.body, payload);
        assert.equal(request.headers.authorization, 'Basic invalid');
        assert.equal(request.headers.cookie, 'authenticationToken=cookie-token; preference=light');
        assert.equal(request.headers['x-ae2-request'], 'true');
        assert.equal(request.headers.origin, 'https://public.example');
        assert.equal(request.headers['sec-fetch-site'], 'same-origin');
        assert.equal(request.headers['x-private'], undefined);
    }
});

test('PHP mounts redirects and cookie paths while preserving separate cookies', async t => {
    const proxy = await fixture(t);
    for (const mount of ['/', '/ae2/']) {
        for (const [location, expected] of [
            ['?INVALID_USER', '?INVALID_USER'], ['.', '.'], ['/api/result?q=1', mount + 'api/result?q=1'],
            [`http://127.0.0.1:${proxy.upstream.address().port}/?confirmregistration&token=abc`, mount + '?confirmregistration&token=abc'],
            ['https://external.example/', 'https://external.example/']
        ]) {
            proxy.setReply((request, response) => {
                response.writeHead(302, { Location: location, 'Set-Cookie': [
                    'authenticationToken=token; HttpOnly; SameSite=Lax',
                    'username=Example; Path=/; SameSite=Lax',
                    'expired=; Path=/api; Max-Age=0'
                ] });
                response.end();
            });
            const response = await proxy.request(mount, { method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'username=Example&password=a%2Bb' });
            assert.equal(response.status, 302);
            assert.equal(response.headers.location, expected);
            assert.deepEqual(response.headers['set-cookie'], [
                'authenticationToken=token; HttpOnly; SameSite=Lax',
                `username=Example; Path=${mount}; SameSite=Lax`,
                `expired=; Path=${mount}api; Max-Age=0`
            ]);
            assert.equal(proxy.received.pop().body.toString(), 'username=Example&password=a%2Bb');
            assert.equal(proxy.received.length, 0, 'redirect must be returned, not followed');
        }
    }
});

test('PHP forwards binary assets, conditional requests and HEAD without decoding bodies', async t => {
    const proxy = await fixture(t);
    const asset = Buffer.from([137, 80, 78, 71, 0, 13, 10, 255]);
    proxy.setReply((request, response) => {
        if (request.headers['if-none-match'] === '"asset-v1"') { response.writeHead(304); response.end(); return; }
        response.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': asset.length,
            ETag: '"asset-v1"', 'Cache-Control': 'public, max-age=3600' });
        response.end(request.method === 'HEAD' ? undefined : asset);
    });
    for (const mount of ['/', '/ae2/']) {
        const response = await proxy.request(mount + 'themes/quartz/image%20one.png');
        assert.equal(response.status, 200);
        assert.deepEqual(response.body, asset);
        assert.equal(response.headers['cache-control'], 'public, max-age=3600');
        assert.equal(proxy.received.pop().url, '/themes/quartz/image%20one.png');
        const head = await proxy.request(mount + 'favicon.ico', { method: 'HEAD' });
        assert.equal(head.status, 200);
        assert.equal(head.body.length, 0);
        assert.equal(head.headers['content-length'], String(asset.length));
        assert.equal(proxy.received.pop().method, 'HEAD');
        const cached = await proxy.request(mount + 'favicon.ico', { headers: { 'If-None-Match': '"asset-v1"' } });
        assert.equal(cached.status, 304);
        assert.equal(cached.body.length, 0);
    }
});

test('PHP leaves API authorization and cookie mutation decisions to the backend', async t => {
    const proxy = await fixture(t);
    proxy.setReply((request, response) => {
        const bearer = request.headers.authorization === 'Bearer explicit-token';
        const cookie = request.headers.cookie === 'authenticationToken=cookie-token';
        response.statusCode = bearer || cookie && request.headers['x-ae2-request'] === 'true' ? 204 : 403;
        response.end();
    });
    for (const mount of ['/', '/ae2/']) {
        for (const [headers, status] of [
            [{ Cookie: 'authenticationToken=cookie-token' }, 403],
            [{ Cookie: 'authenticationToken=cookie-token', 'X-AE2-Request': 'true' }, 204],
            [{ Authorization: 'Bearer explicit-token' }, 204]
        ]) {
            assert.equal((await proxy.request(mount + 'api/auth/logout', { method: 'POST', headers })).status, status);
        }
    }
});

test('PHP reports unavailable upstream and keeps upstream errors unchanged', async t => {
    const proxy = await fixture(t);
    for (const status of [401, 403, 404, 409, 500, 503]) {
        proxy.setReply((request, response) => {
            response.writeHead(status, { 'Content-Type': 'application/json' });
            response.end('{"status":"BACKEND_ERROR","data":null}');
        });
        const response = await proxy.request('/ae2/api/grids');
        assert.equal(response.status, status);
        assert.equal(JSON.parse(response.body).status, 'BACKEND_ERROR');
    }
    proxy.setReply(request => request.socket.destroy());
    const response = await proxy.request('/ae2/api/grids');
    assert.equal(response.status, 502);
    assert.equal(JSON.parse(response.body).status, 'UPSTREAM_UNAVAILABLE');
    assert.equal(response.headers['cache-control'], 'no-store');
});


test('PHP preserves compressed asset bytes and their length', async t => {
    const proxy = await fixture(t);
    const compressed = require('node:zlib').gzipSync('body { color: white; }');
    proxy.setReply((request, response) => {
        response.writeHead(200, { 'Content-Type': 'text/css', 'Content-Encoding': 'gzip', 'Content-Length': compressed.length });
        response.end(compressed);
    });
    const response = await proxy.request('/ae2/themes/quartz/style.css', { headers: { 'Accept-Encoding': 'gzip' } });
    assert.equal(response.status, 200);
    assert.equal(response.headers['content-encoding'], 'gzip');
    assert.equal(Number(response.headers['content-length']), compressed.length);
    assert.deepEqual(response.body, compressed);
});
