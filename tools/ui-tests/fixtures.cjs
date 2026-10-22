const { after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright');

const resources = path.resolve(__dirname, '../../src/main/resources');
const compiledResources = path.resolve(__dirname, '../../build/generated/frontend');
const gridA = 'AAAAAAAAAAAAAAAAAAAAAA';
const gridB = 'BBBBBBBBBBBBBBBBBBBBBA';
const defaultSettingsLimits = {
    historyMaxRowsPerGrid: 250000,
    stockSampleIntervalSeconds: { min: 1, max: 86400, defaultValue: 300 },
    flowFlushIntervalSeconds: { min: 1, max: 86400, defaultValue: 60 },
    historyRetentionDays: { min: 1, max: 365, defaultValue: 7 }
};

const iron = {
    displayName: 'Iron Ingot',
    registryNamespace: 'minecraft',
    registryPath: 'iron_ingot',
    componentCount: 0,
    damage: 0,
    quantity: 128640,
    craftable: true,
    resourceType: 'ITEM',
    itemKey: 'iron',
    icon: null
};

const quartz = {
    displayName: 'Certus Quartz Crystal',
    registryNamespace: 'ae2',
    registryPath: 'certus_quartz_crystal',
    componentCount: 0,
    damage: 0,
    quantity: 42,
    craftable: false,
    resourceType: 'ITEM',
    itemKey: 'quartz',
    icon: null
};

const readyPlan = {
    finalOutput: { ...iron, quantity: 12 },
    options: { lightMode: false, ignoreMissing: false },
    autoStart: false,
    isDone: true,
    isSimulating: false,
    bytesTotal: 2048,
    plan: [
        {
            registryNamespace: 'minecraft',
            registryPath: 'iron_ingot',
            componentCount: 0,
            damage: 0,
            displayName: 'Iron Ingot',
            itemKey: null,
            icon: null,
            stored: 4,
            requested: 12,
            missing: 0,
            steps: 3,
            usedPercent: 0
        }
    ]
};

const cpu = {
    acceptsPlayerJobs: true,
    selectionMode: null,
    name: 'Assembler',
    isBusy: false,
    supportsPause: false,
    isPaused: false,
    availableStorage: 8192,
    usedStorage: 0,
    coProcessors: 1
};

const cpuWork = {
    selectionMode: null,
    size: 8192,
    isBusy: true,
    supportsPause: false,
    isPaused: false,
    finalOutput: { ...iron, quantity: 12 },
    hasTrackingInfo: true,
    timeStarted: 1700000000000,
    timeElapsed: 10000,
    items: [
        {
            registryNamespace: 'minecraft',
            registryPath: 'iron_ingot',
            componentCount: 0,
            damage: 0,
            displayName: 'Iron Ingot',
            itemKey: null,
            icon: null,
            active: 4,
            pending: 6,
            stored: 2,
            timeSpentCrafting: 5000,
            craftedTotal: 10,
            shareInCraftingTime: 0.4,
            shareInCraftingTimeCombined: 0.5,
            craftsPerSec: 2
        }
    ]
};

const historyEntry = {
    id: '1',
    finalOutput: { ...iron, quantity: 12 },
    timeStarted: 1700000000000,
    timeDone: 1700000010000,
    wasCancelled: true
};

const historyDetail = {
    ...historyEntry,
    items: [
        {
            displayName: 'Iron Ingot',
            itemKey: null,
            icon: null,
            registryNamespace: 'minecraft',
            registryPath: 'iron_ingot',
            componentCount: 0,
            damage: 0,
            timeSpentOn: 5000,
            craftedTotal: 10,
            craftsPerSec: 2,
            shareInCraftingTime: 0.4,
            shareInCraftingTimeCombined: 0.5,
            timings: [{ started: 1700000001000, ended: 1700000006000 }]
        }
    ],
    interfaceShare: [
        {
            name: 'Smelter',
            timingsCombined: 5000,
            location: [{ dimensionId: 'minecraft:overworld', x: 120, y: 64, z: -32 }],
            timings: [{ started: 1700000001000, ended: 1700000006000 }]
        }
    ]
};

let browserLaunch;

// One browser serves every test of this process; each test gets its own context. A lost browser is relaunched.
async function sharedBrowser() {
    const launch = (browserLaunch ??= chromium.launch({
        headless: true,
        ...(process.env.BROWSER_CHANNEL ? { channel: process.env.BROWSER_CHANNEL } : {})
    }));
    const browser = await launch.catch((error) => {
        if (browserLaunch === launch) browserLaunch = undefined;
        throw error;
    });
    if (browser.isConnected()) return browser;
    if (browserLaunch === launch) browserLaunch = undefined;
    return sharedBrowser();
}

after(async () => {
    const browser = await browserLaunch?.catch(() => null);
    await browser?.close();
});

// The default theme refreshes every 5 s while automatic updates are on; only tests of that behaviour enable it.
function refreshingFixture(t, mount, contextOptions, flags) {
    return fixture(t, mount, contextOptions, { ...flags, autoRefresh: true });
}

async function fixture(t, mount = '', contextOptions = {}, { mockClock = true, autoRefresh = false } = {}) {
    const options = {
        pins: [],
        delayA: 0,
        status: 200,
        requests: [],
        empty: false,
        reverseGrids: false,
        loggedOut: false,
        username: 'ExamplePlayer',
        isAdmin: false,
        serverConfiguration: require('./server-settings-fixture.cjs').settings(),
        serverConfigurationReadOnly: require('./server-settings-fixture.cjs').readOnly(),
        serverConfigurationRevision: 1,
        availableUpdate: {
            version: '10.0.0',
            tag: '1.7.10-10.0.0',
            releaseUrl: 'https://github.com/kuba6000/AE2-Web-Integration/releases/tag/1.7.10-10.0.0',
            skipped: false
        },
        skipUpdateStatus: 'OK',
        modVersion: '9.8.7-browser-fixture',
        publicMode: true,
        capabilities: { craftingLightMode: false },
        bootstrapStatus: 200,
        bootstrapDelay: 0,
        itemsA: [iron, quartz],
        plan: readyPlan,
        pendingReads: 0,
        cpus: { 'cpu-a': cpu, 'cpu-b': cpu },
        planStatus: 'OK',
        submitStatus: 'OK',
        cpuDetails: { 'cpu-a': cpuWork, 'cpu-b': cpuWork },
        cancelStatus: 'OK',
        pauseStatus: 'OK',
        history: [historyEntry],
        historyDetail,
        resourceHistory: { points: [], baseline: null, complete: true, truncated: false },
        settings: {
            [gridA]: {
                isTracked: false,
                trackMachineCrafting: false,
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300,
                flowFlushIntervalSeconds: 60,
                historyRetentionDays: 7,
                craftingHistoryRetentionDays: 7,
                craftingLimits: {
                    historyRetentionDays: { min: 1, max: 15, defaultValue: 7 },
                    historyMaxJobsPerGrid: 1000
                },
                limits: structuredClone(defaultSettingsLimits),
                name: ''
            },
            [gridB]: {
                isTracked: false,
                trackMachineCrafting: false,
                isStorageTracked: false,
                isFlowTracked: false,
                stockSampleIntervalSeconds: 300,
                flowFlushIntervalSeconds: 60,
                historyRetentionDays: 7,
                craftingHistoryRetentionDays: 7,
                craftingLimits: {
                    historyRetentionDays: { min: 1, max: 15, defaultValue: 7 },
                    historyMaxJobsPerGrid: 1000
                },
                limits: structuredClone(defaultSettingsLimits),
                name: ''
            }
        },
        accessSources: {}
    };
    const planRequests = new Map();
    const server = http.createServer(async (request, response) => {
        const url = new URL(request.url, 'http://localhost');
        if (!url.pathname.startsWith(mount + '/')) {
            response.writeHead(404).end();
            return;
        }
        const resource = url.pathname.slice(mount.length);
        const body = [];
        for await (const chunk of request) body.push(chunk);
        options.requests.push({
            path: resource,
            query: url.search,
            method: request.method,
            headers: request.headers,
            body: body.length ? JSON.parse(Buffer.concat(body).toString()) : null
        });
        if (resource === '/api/themes') {
            response.writeHead(200, { 'Content-Type': 'application/json' }).end(
                JSON.stringify({
                    status: 'OK',
                    data: options.themeCatalog || { defaultTheme: 'builtin:default', themes: [] }
                })
            );
            return;
        }
        if (resource.startsWith('/themes/')) {
            const file = options.themeFiles?.[resource];
            response
                .writeHead(file ? 200 : 404, {
                    'Content-Type':
                        { '.css': 'text/css', '.svg': 'image/svg+xml' }[path.extname(resource)] ?? 'text/javascript'
                })
                .end(file || '');
            return;
        }
        // Test-owned theme modules exercise the same bootstrap and HTTP services as production.
        if (options.themeEntry && resource === '/__theme-test.js') {
            response.writeHead(200, { 'Content-Type': 'text/javascript' }).end(options.themeEntry);
            return;
        }
        if (options.themeEntry && resource === '/') {
            response
                .writeHead(200, { 'Content-Type': 'text/html' })
                .end(
                    '<!doctype html><title>Theme fixture</title><main id="app"></main><script type="module" src="./__theme-test.js"></script>'
                );
            return;
        }
        if (resource.startsWith('/api/')) {
            if (resource.startsWith('/api/icon-packs/')) {
                options.activePages = (options.activePages || 0) + 1;
                options.maxActivePages = Math.max(options.maxActivePages || 0, options.activePages);
                response.once('close', () => options.activePages--);
                response.writeHead(options.pageStatus || 200, { 'Content-Type': 'image/png' });
                if (options.pageDelay) setTimeout(() => response.end(options.atlas), options.pageDelay);
                else response.end(options.atlas);
                return;
            }
            response.setHeader('Content-Type', 'application/json');
            const mutation =
                request.method === 'PATCH'
                    ? 'settings'
                    : request.method === 'DELETE'
                      ? 'delete'
                      : resource.endsWith('/pause')
                        ? 'pause'
                        : resource.endsWith('/cancel')
                          ? 'cancel'
                          : resource.endsWith('/submit')
                            ? 'submit'
                            : request.method === 'POST' && resource.endsWith('/crafting-plans')
                              ? 'create'
                              : null;
            if (mutation && options.fault?.operation === mutation) {
                if (options.fault.status === 'NETWORK_ERROR') {
                    response.destroy();
                    return;
                }
                if (options.fault.status === 'INVALID_RESPONSE') {
                    response.end('<html>Proxy error</html>');
                    return;
                }
                response.statusCode = options.fault.status === 'INTERNAL_ERROR' ? 500 : 503;
                response.end(JSON.stringify({ status: options.fault.status, data: null }));
                return;
            }
            if (resource === '/api/context') {
                if (options.bootstrapDelay) await new Promise((resolve) => setTimeout(resolve, options.bootstrapDelay));
                response.statusCode = options.bootstrapStatus;
                response.end(
                    JSON.stringify({
                        status: options.bootstrapStatus === 200 ? 'OK' : 'INTERNAL_ERROR',
                        data:
                            options.bootstrapStatus === 200
                                ? {
                                      publicMode: options.publicMode,
                                      capabilities: options.loggedOut ? {} : options.capabilities,
                                      modVersion: options.modVersion,
                                      isOutdated: options.isOutdated || false,
                                      availableUpdate:
                                          !options.loggedOut && options.isAdmin && options.isOutdated
                                              ? options.availableUpdate
                                              : null,
                                      user: options.loggedOut
                                          ? null
                                          : { username: options.username, isAdmin: options.isAdmin }
                                  }
                                : null
                    })
                );
            } else if (resource === '/api/server/settings') {
                let status = options.isAdmin ? 'OK' : 'NO_PERMISSIONS';
                if (status === 'OK' && request.method === 'POST') {
                    const input = JSON.parse(Buffer.concat(body).toString());
                    if (
                        'readOnly' in input ||
                        ['password', 'port', 'publicMode', 'allowNoPasswordOnLocalhost'].some(
                            (key) => key in input.settings.general
                        )
                    )
                        status = 'BAD_PARAM';
                    else if (input.revision !== String(options.serverConfigurationRevision)) status = 'CONFIG_CHANGED';
                    else {
                        options.serverConfiguration = input.settings;
                        options.serverConfigurationRevision++;
                    }
                }
                response.statusCode =
                    status === 'OK' ? 200 : status === 'CONFIG_CHANGED' ? 409 : status === 'BAD_PARAM' ? 400 : 403;
                response.end(
                    JSON.stringify({
                        status,
                        data:
                            status === 'OK'
                                ? {
                                      revision: String(options.serverConfigurationRevision),
                                      settings: options.serverConfiguration,
                                      readOnly: options.serverConfigurationReadOnly
                                  }
                                : null
                    })
                );
            } else if (resource === '/api/updates/skip') {
                const input = body.length ? JSON.parse(Buffer.concat(body).toString()) : {};
                let status = options.skipUpdateStatus;
                if (!options.isAdmin) status = 'FORBIDDEN';
                else if (!options.availableUpdate || input.tag !== options.availableUpdate.tag)
                    status = 'UPDATE_CHANGED';
                if (status === 'OK') options.availableUpdate.skipped = true;
                response.statusCode =
                    status === 'OK' ? 200 : status === 'FORBIDDEN' ? 403 : status === 'UPDATE_CHANGED' ? 409 : 500;
                response.end(JSON.stringify({ status, data: null }));
            } else if (resource === '/api/icon-pack') {
                if (options.packError) {
                    response.writeHead(503).end(JSON.stringify({ status: 'INTERNAL_ERROR', data: null }));
                    return;
                }
                const pack = options.pack || { available: false, packId: null, width: 0, height: 0 };
                if (options.packDelay) await new Promise((resolve) => setTimeout(resolve, options.packDelay));
                response.end(JSON.stringify({ status: 'OK', data: pack }));
            } else if (resource === '/api/auth/logout') {
                if (options.logoutError) {
                    response.writeHead(503).end(JSON.stringify({ status: options.logoutError, data: null }));
                    return;
                }
                options.loggedOut = true;
                response.end(JSON.stringify({ status: 'OK', data: null }));
            } else if (resource.includes('/resource-pins')) {
                const input = options.requests.at(-1).body;
                if (request.method === 'POST') {
                    const gridKey = resource.split('/')[3];
                    let pin = options.pins.find((pin) => pin.gridKey === gridKey && pin.itemKey === input.itemKey);
                    if (!pin) {
                        pin = {
                            pinKey: `pin-${options.pins.length}`,
                            available: true,
                            gridKey,
                            itemKey: input.itemKey,
                            gridName: '',
                            resource: options.itemsA.find((item) => item.itemKey === input.itemKey) ?? iron,
                            terminalOrder: null,
                            homeOrder: null,
                            homeConfig: {
                                showStock: true,
                                showFlow: true,
                                showRecentFlow: true,
                                flowRangeMillis: 86400000,
                                showChart: true,
                                stock: true,
                                input: true,
                                output: true,
                                net: false,
                                rangeMillis: 86400000
                            }
                        };
                        options.pins.push(pin);
                    }
                    if (pin[`${input.placement}Order`] === null)
                        pin[`${input.placement}Order`] = options.pins.filter(
                            (value) => value[`${input.placement}Order`] !== null
                        ).length;
                } else if (request.method === 'PATCH') {
                    const key = resource.split('/').at(-1);
                    const pin = options.pins.find((pin) => pin.pinKey === key);
                    for (const placement of ['home', 'terminal'])
                        if (input[placement] !== undefined)
                            pin[`${placement}Order`] = input[placement]
                                ? (pin[`${placement}Order`] ??
                                  options.pins.filter((value) => value[`${placement}Order`] !== null).length)
                                : null;
                    if (input.homeConfig) pin.homeConfig = input.homeConfig;
                    if (input.move) {
                        const property = `${input.move.placement}Order`;
                        const ordered = options.pins
                            .filter(
                                (value) =>
                                    value[property] !== null &&
                                    (input.move.placement === 'home' || value.gridKey === pin.gridKey)
                            )
                            .sort((a, b) => a[property] - b[property]);
                        const index = ordered.indexOf(pin),
                            other = ordered[index + (input.move.direction === 'earlier' ? -1 : 1)];
                        if (other) [pin[property], other[property]] = [other[property], pin[property]];
                    }
                    options.pins = options.pins.filter((pin) => pin.homeOrder !== null || pin.terminalOrder !== null);
                } else if (request.method === 'DELETE')
                    options.pins = options.pins.filter((pin) => pin.pinKey !== resource.split('/').at(-1));
                response.end(JSON.stringify({ status: 'OK', data: options.pins, icons: options.icons ?? null }));
            } else if (resource === '/api/grids') {
                if (options.gridDelay) await new Promise((resolve) => setTimeout(resolve, options.gridDelay));
                if (options.gridError === 'NETWORK_ERROR') {
                    response.destroy();
                    return;
                }
                if (options.gridError) {
                    response.statusCode = 403;
                    response.end(JSON.stringify({ status: options.gridError, data: null }));
                    return;
                }
                const grids = [
                    { key: gridA, owner: 'Alpha', cpuCount: 2, isOwned: false, accessSources: options.accessSources },
                    { key: gridB, owner: 'Beta', cpuCount: 1, accessSources: {} }
                ];
                response.end(
                    JSON.stringify({
                        status: 'OK',
                        data: (options.empty
                            ? []
                            : options.grids || (options.reverseGrids ? grids.reverse() : grids)
                        ).map((grid) => ({
                            ...grid,
                            name: options.settings[grid.key]?.name ?? grid.name ?? '',
                            isTrackingEnabled: options.settings[grid.key]?.isTracked ?? false
                        }))
                    })
                );
            } else if (resource.endsWith('/crafting-plans')) {
                const input = options.requests.at(-1).body;
                const item = options.itemsA.find((item) => item.itemKey === input.itemKey) || iron;
                planRequests.set(`${resource}/${options.nextPlanId ?? 7}`, {
                    finalOutput: { ...item, itemKey: input.itemKey, quantity: input.quantity },
                    options: { lightMode: input.lightMode === true, ignoreMissing: input.ignoreMissing === true },
                    autoStart: input.autoStart === true
                });
                response.statusCode = 202;
                response.end(JSON.stringify({ status: 'OK', data: { jobId: options.nextPlanId ?? 7 } }));
            } else if (resource.endsWith('/submit')) {
                if (options.submitDelay) await new Promise((resolve) => setTimeout(resolve, options.submitDelay));
                response.statusCode = options.submitStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.submitStatus, data: options.submitReason || null }));
            } else if (/\/crafting-plans\/\d+$/.test(resource)) {
                response.statusCode = options.planStatus === 'OK' ? 200 : 404;
                response.end(
                    JSON.stringify({
                        status: options.planStatus,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null,
                        data:
                            request.method === 'DELETE'
                                ? null
                                : options.pendingReads-- > 0
                                  ? {
                                        ...options.plan,
                                        ...planRequests.get(resource),
                                        isDone: false,
                                        isSimulating: false,
                                        bytesTotal: 0,
                                        plan: null
                                    }
                                  : { ...options.plan, ...planRequests.get(resource) }
                    })
                );
            } else if (resource.endsWith('/cpus')) {
                const gridKey = resource.split('/')[3];
                const cpuError = options.cpuErrors?.[gridKey] || options.cpuError;
                const result = JSON.stringify({
                    status: cpuError || 'OK',
                    data: cpuError ? null : options.cpusByGrid?.[gridKey] || options.cpus,
                    icons: url.searchParams.get('icons') === 'true' ? (options.cpuIcons ?? options.icons) : null
                });
                if (options.cpuDelay) await new Promise((resolve) => setTimeout(resolve, options.cpuDelay));
                response.statusCode = cpuError ? 403 : 200;
                response.end(result);
            } else if (resource.endsWith('/crafting-history')) {
                response.end(
                    JSON.stringify({
                        status: options.historyError || 'OK',
                        data: options.history,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null
                    })
                );
            } else if (/\/crafting-history\/\d+$/.test(resource)) {
                response.end(
                    JSON.stringify({
                        status: options.historyError || 'OK',
                        data: options.historyDetail,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null
                    })
                );
            } else if (resource.endsWith('/resource-history')) {
                const key = resource.includes(gridA) ? gridA : gridB;
                response.end(
                    JSON.stringify({
                        status: options.resourceHistoryError || 'OK',
                        data: {
                            ...options.resourceHistory,
                            points: options.resourceHistory.points.map((point) => ({
                                inputOperations: '0',
                                outputOperations: '0',
                                ...point
                            })),
                            enabled: !!options.settings[key].isStorageTracked || !!options.settings[key].isFlowTracked
                        },
                        icons: url.searchParams.get('icons') === 'true' ? (options.historyIcons ?? options.icons) : null
                    })
                );
            } else if (resource.endsWith('/settings')) {
                const key = resource.includes(gridA) ? gridA : gridB;
                if (request.method === 'PATCH' && !options.settingsError)
                    options.settings[key] = options.settingsReply || {
                        ...options.settings[key],
                        ...options.requests.at(-1).body
                    };
                if (typeof options.settings[key].name === 'string')
                    options.settings[key].name = options.settings[key].name.trim();
                response.end(JSON.stringify({ status: options.settingsError || 'OK', data: options.settings[key] }));
            } else if (resource.endsWith('/pause')) {
                if (options.pauseStatus === 'OK') {
                    const key = decodeURIComponent(resource.split('/').at(-2));
                    const isPaused = options.requests.at(-1).body.paused;
                    options.cpus[key] = { ...options.cpus[key], isPaused };
                    options.cpuDetails[key] = { ...options.cpuDetails[key], isPaused };
                }
                response.statusCode = options.pauseStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.pauseStatus, data: null }));
            } else if (resource.endsWith('/cancel')) {
                if (options.cancelStatus === 'OK') {
                    const key = decodeURIComponent(resource.split('/').at(-2));
                    options.cpus[key] = { ...options.cpus[key], isBusy: false, finalOutput: null, usedStorage: 0 };
                    options.cpuDetails[key] = {
                        size: 8192,
                        isBusy: false,
                        finalOutput: null,
                        items: null,
                        hasTrackingInfo: false,
                        timeStarted: 0,
                        timeElapsed: 0
                    };
                }
                response.statusCode = options.cancelStatus === 'OK' ? 200 : 409;
                response.end(JSON.stringify({ status: options.cancelStatus, data: null }));
            } else if (/\/cpus\/[^/]+\/events$/.test(resource) && request.method === 'POST') {
                response.end(JSON.stringify({ status: 'OK', data: null }));
            } else if (/\/cpus\/[^/]+\/events$/.test(resource)) {
                response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
                response.flushHeaders();
                response.write('event: ready\ndata: {}\n\n');
                options.cpuStreams ??= [];
                options.cpuStreams.push(response);
                response.on('close', () => {
                    options.cpuStreams = options.cpuStreams.filter((stream) => stream !== response);
                });
            } else if (/\/cpus\/[^/]+$/.test(resource)) {
                const key = decodeURIComponent(resource.split('/').at(-1));
                const status = options.detailError || (options.cpuDetails[key] ? 'OK' : 'CPU_NOT_FOUND');
                response.statusCode = status === 'OK' ? 200 : 404;
                response.end(
                    JSON.stringify({
                        status,
                        data: status === 'OK' ? options.cpuDetails[key] : null,
                        icons: url.searchParams.get('icons') === 'true' ? options.icons : null,
                        ...(status === 'OK' && options.cpuLiveView && url.searchParams.get('live') === 'true'
                            ? {
                                  view: options.cpuLiveView,
                                  subscription: `cpu-subscription-${options.cpuLiveView.revision}`
                              }
                            : {})
                    })
                );
            } else if (resource.endsWith('/items/events') && request.method === 'POST') {
                response.statusCode = options.renewalStatus || 200;
                const send = () =>
                    response.end(
                        JSON.stringify({ status: response.statusCode === 200 ? 'OK' : 'BAD_PARAM', data: null })
                    );
                if (options.renewalDelay) setTimeout(send, options.renewalDelay);
                else send();
            } else if (resource.endsWith('/items/events')) {
                response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
                response.flushHeaders();
                if (!options.delayStreamReady) response.write('event: ready\ndata: {}\n\n');
                options.itemStreams ??= [];
                options.itemStreams.push(response);
                response.on('close', () => {
                    options.itemStreams = options.itemStreams.filter((stream) => stream !== response);
                });
            } else if (resource.endsWith('/items')) {
                const first = resource.includes(gridA);
                const send = () => {
                    response.statusCode = options.status;
                    response.end(
                        JSON.stringify(
                            options.status === 200
                                ? {
                                      status: 'OK',
                                      recent: options.recent,
                                      ...(options.liveView
                                          ? {
                                                view: options.liveView,
                                                subscription: `subscription-${options.liveView.revision}`
                                            }
                                          : {}),
                                      icons: url.searchParams.get('icons') === 'true' ? options.icons : null,
                                      data: first
                                          ? options.itemsA
                                          : [
                                                {
                                                    ...iron,
                                                    displayName: 'Gold Ingot',
                                                    registryNamespace: 'minecraft',
                                                    registryPath: 'gold_ingot',
                                                    quantity: 5
                                                }
                                            ]
                                  }
                                : { status: 'NO_PERMISSIONS', data: null }
                        )
                    );
                };
                if (first && options.delayA) setTimeout(send, options.delayA);
                else send();
            } else {
                response.statusCode = 404;
                response.end(JSON.stringify({ status: 'NOT_FOUND', data: null }));
            }
            return;
        }
        const login =
            resource === '/' &&
            (options.loggedOut ||
                url.searchParams.has('INVALID_PASSWORD') ||
                url.searchParams.has('confirmregistration'));
        const relative = resource === '/' ? (login ? 'assets/login.html' : 'assets/web/index.html') : resource.slice(1);
        const resourceRoot =
            relative.endsWith('.js') || relative.endsWith('/uplot.css') || relative.endsWith('/uplot-LICENSE.txt')
                ? compiledResources
                : resources;
        const file = path.resolve(resourceRoot, relative);
        if (!file.startsWith(resourceRoot + path.sep)) {
            response.writeHead(404).end();
            return;
        }
        try {
            response.setHeader(
                'Content-Type',
                file.endsWith('.js') || file.endsWith('.mjs')
                    ? 'text/javascript'
                    : file.endsWith('.svg')
                      ? 'image/svg+xml'
                      : file.endsWith('.css')
                        ? 'text/css'
                        : file.endsWith('.woff2')
                          ? 'font/woff2'
                          : 'text/html'
            );
            const content = await fs.readFile(file);
            response.end(content);
        } catch {
            response.writeHead(404).end('Missing asset');
        }
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const closeServer = () => new Promise((resolve) => server.close(resolve));
    const context = await sharedBrowser()
        .then((browser) => browser.newContext({ locale: 'en-US', ...contextOptions }))
        .catch(async (error) => {
            await closeServer();
            throw error;
        });
    t.after(async () => {
        await context.close();
        await closeServer();
    });
    const page = await context.newPage();
    page.on('dialog', (dialog) => dialog.accept());
    await page.addInitScript(
        ({ key, autoRefresh }) => {
            if (location.protocol === 'http:' && localStorage.getItem(key) === null)
                localStorage.setItem(key, JSON.stringify({ autoRefresh }));
        },
        { key: `ae2web:${mount}/:ui`, autoRefresh }
    );
    if (mockClock) await page.clock.install();
    const errors = [];
    page.on('pageerror', (error) => errors.push(String(error)));
    t.after(() => assert.deepEqual(errors, []));
    return { page, options, base: `http://127.0.0.1:${server.address().port}${mount}/` };
}

async function clickTab(page, name) {
    const link = page.getByRole('link', { name, exact: true });
    if (page.viewportSize().width <= 600 && !(await link.isVisible()))
        await page.getByRole('button', { name: /^(Navigation|Nawigacja)$/ }).click();
    await link.click();
}

async function openMobilePane(page, name) {
    if (page.viewportSize().width <= 600) await page.getByRole('button', { name, exact: true }).click();
}

async function closeMobilePane(page) {
    const close = page.getByRole('button', { name: 'Close panel', exact: true });
    if (await close.isVisible()) await close.click();
}

async function settleFrames(page) {
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function settleResponse(page, request) {
    const response = await request.response();
    if (response) await response.finished();
    await settleFrames(page);
}

// Advance the browser's polling timers until the next refresh request arrives. A refresh that is still in flight
// has not re-armed its timer yet, so the clock is advanced again instead of waiting for real time.
async function poll(page) {
    const home = ['', '#', '#/'].includes(new URL(page.url()).hash);
    const endpoint = home ? '/api/resource-pins' : '/api/grids';
    let arrived;
    const requested = page
        .waitForRequest((request) => new URL(request.url()).pathname.endsWith(endpoint))
        .then(
            (request) => {
                arrived = request;
            },
            () => {}
        );
    const deadline = Date.now() + 10000;
    while (!arrived) {
        assert.ok(Date.now() < deadline, `No ${endpoint} refresh was requested`);
        await page.clock.fastForward(6000);
        await Promise.race([requested, new Promise((resolve) => setTimeout(resolve, 25))]);
    }
    return arrived;
}

// Wait for simulated server state changed by a UI request; the click resolves before the request lands.
async function serverState(read, expected) {
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
        if (read() === expected) return;
        await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(read(), expected);
}

async function seedTerminalPageSize(page, base, size) {
    await page.addInitScript(
        ({ key, size }) => {
            const saved = JSON.parse(localStorage.getItem(key) || '{}');
            if (saved.terminalPageSize === undefined) {
                saved.terminalPageSize = size;
                localStorage.setItem(key, JSON.stringify(saved));
            }
        },
        { key: `ae2web:${new URL(base).pathname}:theme:default`, size }
    );
}

async function atlasFixture(page, options) {
    options.atlas = await page.evaluate(() => {
        const canvas = document.createElement('canvas');
        canvas.width = 128;
        canvas.height = 64;
        const context = canvas.getContext('2d');
        context.fillStyle = '#ff0000';
        context.fillRect(0, 0, 64, 64);
        context.fillStyle = '#00ff00';
        context.fillRect(64, 0, 64, 64);
        return canvas.toDataURL().split(',')[1];
    });
    options.atlas = Buffer.from(options.atlas, 'base64');
    options.icons = {
        packId: 'a'.repeat(64),
        width: 64,
        height: 64,
        pages: [{ digest: 'b'.repeat(64), width: 128, height: 64 }]
    };
    options.pack = { available: true, packId: options.icons.packId, width: 64, height: 64 };
}

// Public seam: API names become readable, safely styled browser content; section codes
// follow Minecraft's stable classic formatting rules rather than leaking into labels.
async function textStyle(locator, text) {
    return locator.evaluate((element, text) => {
        const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
            if (!walker.currentNode.textContent.includes(text)) continue;
            const style = getComputedStyle(walker.currentNode.parentElement);
            return {
                color: style.color,
                weight: style.fontWeight,
                italic: style.fontStyle,
                decoration: style.textDecorationLine
            };
        }
        throw new Error(`Visible text not found: ${text}`);
    }, text);
}

module.exports = {
    atlasFixture,
    clickTab,
    closeMobilePane,
    cpu,
    cpuWork,
    defaultSettingsLimits,
    fixture,
    gridA,
    gridB,
    historyDetail,
    historyEntry,
    iron,
    openMobilePane,
    poll,
    quartz,
    readyPlan,
    refreshingFixture,
    seedTerminalPageSize,
    serverState,
    settleFrames,
    settleResponse,
    textStyle
};
