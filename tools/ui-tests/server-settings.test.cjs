const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const moduleUrl = pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/terminal.js'));
const { settings, readOnly } = require('./server-settings-fixture.cjs');

test('file-only metadata stays separate from edits and saves, then clears when access is denied', async (t) => {
    const metadata = { port: 2324, publicMode: true, allowNoPasswordOnLocalhost: false, adminPasswordConfigured: true };
    let reject = false;
    let submitted;
    const terminal = await application(t, true, {
        serverSettings: async () => ({ revision: 'first', settings: settings(), readOnly: metadata }),
        saveServerSettings: async (body) => {
            submitted = body;
            if (reject) throw Object.assign(new Error(), { status: 'NO_PERMISSIONS' });
            return {
                ...body,
                revision: 'second',
                readOnly: { ...metadata, port: 2424, adminPasswordConfigured: false }
            };
        }
    });
    assert.equal(terminal.state.serverSettings.readOnly, null);
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    assert.deepEqual(terminal.state.serverSettings.readOnly, metadata);
    terminal.serverSettings.edit('general', 'checkForUpdates', false);
    assert.deepEqual(terminal.state.serverSettings.readOnly, metadata);
    await terminal.serverSettings.save();
    assert.deepEqual(Object.keys(submitted).sort(), ['revision', 'settings']);
    for (const field of ['port', 'password', 'publicMode', 'allowNoPasswordOnLocalhost', 'adminPasswordConfigured'])
        assert.equal(field in submitted.settings.general, false);
    assert.equal(terminal.state.serverSettings.readOnly.port, 2424);
    assert.equal(terminal.state.serverSettings.readOnly.adminPasswordConfigured, false);
    reject = true;
    terminal.serverSettings.edit('general', 'checkForUpdates', true);
    await terminal.serverSettings.save();
    assert.equal(terminal.state.serverSettings.readOnly, null);
});
async function application(t, isAdmin, overrides = {}) {
    const before = global.document;
    global.document = new EventTarget();
    const { createTerminal } = await import(moduleUrl);
    const terminal = createTerminal(
        {
            grids: async () => [],
            iconPack: async () => ({ available: false, packId: null }),
            resourcePins: async () => ({ data: [], icons: null }),
            serverSettings: async () => ({ revision: 'first', settings: settings(), readOnly: readOnly() }),
            ...overrides
        },
        {
            values: { autoRefresh: false },
            set(name, value) {
                this.values[name] = value;
            }
        },
        { enabled() {}, dispose() {} },
        {},
        isAdmin
    );
    t.after(() => {
        terminal.dispose();
        global.document = before;
    });
    return terminal;
}

test('player cannot load or mutate server configuration through a direct route', async (t) => {
    let requests = 0;
    const terminal = await application(t, false, {
        serverSettings: async () => {
            requests++;
        },
        saveServerSettings: async () => {
            requests++;
        }
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    assert.equal(terminal.state.serverSettings.error, 'NO_PERMISSIONS');
    await terminal.serverSettings.reload();
    await terminal.serverSettings.save();
    assert.equal(requests, 0);
    assert.equal(terminal.state.serverSettings.draft, null);
    assert.equal(terminal.state.serverSettings.readOnly, null);
});

test('admin saves an edited full configuration with its revision and preserves drafts on unrelated updates', async (t) => {
    let reads = 0;
    let submitted;
    const terminal = await application(t, true, {
        serverSettings: async () => {
            reads++;
            return { revision: 'original', settings: settings(), readOnly: readOnly() };
        },
        saveServerSettings: async (body) => {
            submitted = body;
            return { ...body, revision: 'saved', readOnly: readOnly() };
        }
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    terminal.serverSettings.edit('general', 'checkForUpdates', false);
    terminal.serverSettings.edit('ntfy', 'password', 'notification-secret');
    terminal.preference('language', 'pl');
    await terminal.refresh();
    terminal.route({ view: 'about', gridKey: null });
    terminal.route({ view: 'server-settings', gridKey: null });
    assert.equal(reads, 1);
    assert.equal(terminal.state.serverSettings.draft.general.checkForUpdates, false);
    assert.equal(terminal.state.serverSettings.current.general.checkForUpdates, true);
    await terminal.serverSettings.save();
    assert.equal(submitted.revision, 'original');
    assert.equal(submitted.settings.ntfy.password, 'notification-secret');
    assert.equal('password' in submitted.settings.general, false);
    assert.equal('port' in submitted.settings.general, false);
    assert.equal('readOnly' in submitted, false);
    assert.equal(terminal.state.serverSettings.dirty, false);
    assert.equal(terminal.state.serverSettings.saved, true);
    assert.equal(terminal.state.serverSettings.current.general.checkForUpdates, false);
});

test('configuration conflict retains edits until explicit reload and uses the new revision afterwards', async (t) => {
    let revision = 'old';
    let writes = 0;
    const terminal = await application(t, true, {
        serverSettings: async () => ({ revision, settings: settings(), readOnly: readOnly() }),
        saveServerSettings: async (body) => {
            writes++;
            if (body.revision !== revision) throw Object.assign(new Error(), { status: 'CONFIG_CHANGED' });
            return { ...body, readOnly: readOnly() };
        }
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    terminal.serverSettings.edit('general', 'checkForUpdates', false);
    revision = 'edited-on-disk';
    await terminal.serverSettings.save();
    assert.equal(terminal.state.serverSettings.error, 'CONFIG_CHANGED');
    assert.equal(terminal.state.serverSettings.draft.general.checkForUpdates, false);
    await terminal.serverSettings.save();
    assert.equal(writes, 1);
    await terminal.serverSettings.reload();
    assert.equal(terminal.state.serverSettings.dirty, false);
    assert.equal(terminal.state.serverSettings.draft.general.checkForUpdates, true);
    terminal.serverSettings.edit('general', 'checkForUpdates', false);
    await terminal.serverSettings.save();
    assert.equal(writes, 2);
    assert.equal(terminal.state.serverSettings.saved, true);
});

test('uncertain saves keep drafts and cannot be repeated before checking server state', async (t) => {
    let writes = 0;
    const terminal = await application(t, true, {
        saveServerSettings: async () => {
            writes++;
            throw Object.assign(new Error(), { status: 'NETWORK_ERROR' });
        }
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    terminal.serverSettings.edit('resourceHistory', 'historyRetentionDays', { min: 1, defaultValue: 8, max: 15 });
    await terminal.serverSettings.save();
    assert.equal(terminal.state.serverSettings.uncertain, true);
    assert.equal(terminal.state.serverSettings.draft.resourceHistory.historyRetentionDays.defaultValue, 8);
    await terminal.serverSettings.save();
    assert.equal(writes, 1);
    await terminal.serverSettings.reload();
    assert.equal(terminal.state.serverSettings.uncertain, false);
    assert.equal(terminal.state.serverSettings.dirty, false);
});

test('a denied save clears configuration credentials and a failed load can be retried', async (t) => {
    let failRead = true;
    const terminal = await application(t, true, {
        serverSettings: async () => {
            if (failRead) throw Object.assign(new Error(), { status: 'NETWORK_ERROR' });
            return { revision: 'first', settings: settings(), readOnly: readOnly() };
        },
        saveServerSettings: async () => {
            throw Object.assign(new Error(), { status: 'NO_PERMISSIONS' });
        }
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    assert.equal(terminal.state.serverSettings.status, 'error');
    failRead = false;
    await terminal.serverSettings.reload();
    terminal.serverSettings.edit('ntfy', 'password', 'secret');
    await terminal.serverSettings.save();
    assert.equal(terminal.state.serverSettings.error, 'NO_PERMISSIONS');
    assert.equal(terminal.state.serverSettings.current, null);
    assert.equal(terminal.state.serverSettings.readOnly, null);
    assert.equal(terminal.state.serverSettings.draft, null);
});

test('navigation and disposal discard obsolete reads, including transports that complete after abort', async (t) => {
    const reads = [];
    const terminal = await application(t, true, {
        serverSettings: (signal) =>
            new Promise((resolve) => {
                reads.push({ signal, resolve });
            })
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    terminal.route({ view: 'about', gridKey: null });
    assert.equal(reads[0].signal.aborted, true);
    terminal.route({ view: 'server-settings', gridKey: null });
    const pending = terminal.refresh();
    reads[1].resolve({
        revision: 'new',
        settings: { ...settings(), discord: { webhook: 'new', roleId: '' } },
        readOnly: readOnly()
    });
    await pending;
    reads[0].resolve({
        revision: 'old',
        settings: { ...settings(), discord: { webhook: 'old', roleId: '' } },
        readOnly: readOnly()
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(terminal.state.serverSettings.draft.discord.webhook, 'new');
    const reload = terminal.serverSettings.reload();
    terminal.dispose();
    reads[2].resolve({ revision: 'disposed', settings: settings(), readOnly: readOnly() });
    await reload;
    assert.equal(reads[2].signal.aborted, true);
    assert.equal(terminal.state.serverSettings.current, null);
    assert.equal(terminal.state.serverSettings.readOnly, null);
});

test('saving is single-flight and cannot race an explicit reload', async (t) => {
    let write;
    let reads = 0;
    const writes = [];
    const terminal = await application(t, true, {
        serverSettings: async () => {
            reads++;
            return { revision: 'one', settings: settings(), readOnly: readOnly() };
        },
        saveServerSettings: (body) => {
            writes.push(body);
            return new Promise((resolve) => {
                write = resolve;
            });
        }
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    terminal.serverSettings.edit('general', 'checkForUpdates', false);
    const pending = terminal.serverSettings.save();
    await terminal.serverSettings.save();
    await terminal.serverSettings.reload();
    terminal.serverSettings.edit('general', 'checkForUpdates', true);
    assert.equal(writes.length, 1);
    assert.equal(reads, 1);
    assert.equal(terminal.state.serverSettings.draft.general.checkForUpdates, false);
    terminal.route({ view: 'about', gridKey: null });
    write({ revision: 'two', settings: writes[0].settings, readOnly: readOnly() });
    await pending;
    terminal.route({ view: 'server-settings', gridKey: null });
    assert.equal(terminal.state.serverSettings.saved, true);
    assert.equal(reads, 1);
});

test('an explicit discard reload blocks saving the outgoing draft while the new revision loads', async (t) => {
    let loading = false;
    let resolveRead;
    let writes = 0;
    const terminal = await application(t, true, {
        serverSettings: () =>
            loading
                ? new Promise((resolve) => {
                      resolveRead = resolve;
                  })
                : Promise.resolve({ revision: 'old', settings: settings(), readOnly: readOnly() }),
        saveServerSettings: async (body) => {
            writes++;
            return { ...body, readOnly: readOnly() };
        }
    });
    terminal.route({ view: 'server-settings', gridKey: null });
    await terminal.refresh();
    terminal.serverSettings.edit('general', 'checkForUpdates', false);
    loading = true;
    const reload = terminal.serverSettings.reload();
    await terminal.serverSettings.save();
    resolveRead({ revision: 'new', settings: settings(), readOnly: readOnly() });
    await reload;
    assert.equal(writes, 0);
    assert.equal(terminal.state.serverSettings.draft.general.checkForUpdates, true);
});

test('HTTP server settings transport sends revisioned JSON with CSRF header and retains conflict status', async (t) => {
    const http = require('node:http');
    const requests = [];
    const server = http.createServer(async (request, response) => {
        let body = '';
        for await (const chunk of request) body += chunk;
        requests.push({ method: request.method, url: request.url, headers: request.headers, body });
        response.setHeader('content-type', 'application/json');
        if (request.method === 'GET')
            response.end(
                JSON.stringify({
                    status: 'OK',
                    data: { revision: 'file-hash', settings: settings(), readOnly: readOnly() }
                })
            );
        else {
            response.statusCode = 409;
            response.end(JSON.stringify({ status: 'CONFIG_CHANGED', data: null }));
        }
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    t.after(() => new Promise((resolve) => server.close(resolve)));
    const apiUrl = pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/api.js'));
    const { createApi } = await import(apiUrl);
    const api = createApi(new URL(`http://127.0.0.1:${server.address().port}/panel/`), () =>
        assert.fail('Unexpected authentication redirect')
    );
    const result = await api.serverSettings();
    await assert.rejects(
        api.saveServerSettings({ revision: result.revision, settings: result.settings }),
        (error) => error.status === 'CONFIG_CHANGED'
    );
    assert.equal(requests[0].url, '/panel/api/server/settings');
    assert.equal(requests[1].method, 'POST');
    assert.equal(requests[1].headers['x-ae2-request'], 'true');
    assert.deepEqual(JSON.parse(requests[1].body), { revision: result.revision, settings: result.settings });
    assert.deepEqual(result.readOnly, readOnly());
});
