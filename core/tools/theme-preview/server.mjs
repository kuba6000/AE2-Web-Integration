import http from 'node:http';
import { parseArgs } from 'node:util';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = path.resolve(fileURLToPath(new URL('./', import.meta.url)));
const project = fileURLToPath(new URL('../../', import.meta.url));
const gridKey = 'AAAAAAAAAAAAAAAAAAAAAA';
const grid = {
    key: gridKey,
    name: 'Example network',
    owner: 'PreviewPlayer',
    cpuCount: 0,
    isOwned: true,
    isTrackingEnabled: false,
    accessSources: {}
};
const resources = [
    {
        displayName: 'Iron Ingot',
        registryNamespace: 'minecraft',
        registryPath: 'iron_ingot',
        quantity: 128,
        craftable: true
    },
    {
        displayName: 'Gold Ingot',
        registryNamespace: 'minecraft',
        registryPath: 'gold_ingot',
        quantity: 32,
        craftable: false
    },
    {
        displayName: 'Crafting Table',
        registryNamespace: 'minecraft',
        registryPath: 'crafting_table',
        quantity: 0,
        craftable: true
    }
].map((item, index) => ({
    ...item,
    resourceType: 'ITEM',
    componentCount: 0,
    damage: 0,
    itemKey: `example-${index}`,
    icon: null
}));

/** Loopback-only author preview. Uses the real browser application against a tiny simulated HTTP API. */
export function startServer({ port = 8770, themeDirectory = path.join(project, 'examples/theme-starter/dist') } = {}) {
    themeDirectory = path.resolve(themeDirectory);
    let loggedOut = false;
    let pins = [];
    let nextPin = 0;
    const server = http.createServer(async (request, response) => {
        try {
            const url = new URL(request.url, 'http://localhost');
            const json = (data, status = 'OK', code = 200) => {
                response.writeHead(code, { 'Content-Type': 'application/json' });
                response.end(JSON.stringify({ status, data }));
            };
            if (url.pathname === '/api/auth/logout' && request.method === 'POST') {
                loggedOut = true;
                json(null);
                return;
            }
            if (url.pathname.startsWith('/api/') && url.pathname.includes('/resource-pins')) {
                if (loggedOut) {
                    json(null, 'UNAUTHORIZED', 401);
                    return;
                }
                const chunks = [];
                for await (const chunk of request) chunks.push(chunk);
                const input = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
                if (request.method === 'POST') {
                    const item = resources.find((resource) => resource.itemKey === input.itemKey);
                    if (!item || !['home', 'terminal'].includes(input.placement)) {
                        json(null, 'BAD_PARAM', 400);
                        return;
                    }
                    let pin = pins.find((value) => value.itemKey === item.itemKey);
                    if (!pin) {
                        pin = {
                            pinKey: String(++nextPin),
                            recent: null,
                            available: true,
                            gridKey,
                            itemKey: item.itemKey,
                            gridName: grid.name,
                            resource: {
                                displayName: item.displayName,
                                registryNamespace: item.registryNamespace,
                                registryPath: item.registryPath,
                                resourceType: item.resourceType,
                                itemKey: item.itemKey,
                                icon: null,
                                componentCount: item.componentCount,
                                damage: item.damage
                            },
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
                        pins.push(pin);
                    }
                    pin[`${input.placement}Order`] ??= pins.filter(
                        (value) => value[`${input.placement}Order`] !== null
                    ).length;
                } else if (request.method === 'PATCH') {
                    const pin = pins.find((value) => value.pinKey === url.pathname.split('/').at(-1));
                    if (!pin) {
                        json(null, 'NOT_FOUND', 404);
                        return;
                    }
                    for (const placement of ['terminal', 'home'])
                        if (input[placement] !== undefined)
                            pin[`${placement}Order`] = input[placement]
                                ? (pin[`${placement}Order`] ??
                                  pins.filter((value) => value[`${placement}Order`] !== null).length)
                                : null;
                    if (input.homeConfig) pin.homeConfig = input.homeConfig;
                    if (input.move) {
                        const property = `${input.move.placement}Order`,
                            ordered = pins
                                .filter((value) => value[property] !== null)
                                .sort((a, b) => a[property] - b[property]);
                        const neighbor = ordered[ordered.indexOf(pin) + (input.move.direction === 'earlier' ? -1 : 1)];
                        if (neighbor) [neighbor[property], pin[property]] = [pin[property], neighbor[property]];
                    }
                    pins = pins.filter((value) => value.homeOrder !== null || value.terminalOrder !== null);
                } else if (request.method === 'DELETE')
                    pins = pins.filter((value) => value.pinKey !== url.pathname.split('/').at(-1));
                json(pins);
                return;
            }
            if (url.pathname.startsWith('/api/')) {
                if (request.method !== 'GET') {
                    json(null, 'NOT_FOUND', 404);
                    return;
                }
                if (url.pathname === '/api/context')
                    json({
                        user: loggedOut ? null : { username: 'PreviewPlayer', isAdmin: false },
                        publicMode: true,
                        modVersion: '1.2.0-preview',
                        capabilities: {},
                        isOutdated: false,
                        availableUpdate: null
                    });
                else if (loggedOut) json(null, 'UNAUTHORIZED', 401);
                else if (url.pathname === '/api/themes') json({ defaultTheme: 'builtin:default', themes: [] });
                else if (url.pathname === '/api/icon-pack')
                    json({ available: false, packId: null, width: 0, height: 0 });
                else if (url.pathname === '/api/grids') json([grid]);
                else if (url.pathname === `/api/grids/${gridKey}/items`) json(resources);
                else if (url.pathname === `/api/grids/${gridKey}/cpus`) json({});
                else if (url.pathname === `/api/grids/${gridKey}/resource-history`)
                    json({ points: [], baseline: null, enabled: false, complete: true, truncated: false });
                else json(null, 'NOT_FOUND', 404);
                return;
            }
            if (url.pathname === '/reset') {
                loggedOut = false;
                response.writeHead(303, { Location: '/' }).end();
                return;
            }
            if (url.pathname === '/' && loggedOut) {
                response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                response.end(
                    '<!doctype html><html lang="en"><title>Preview signed out</title><p>Signed out of the local preview.</p><a href="./reset">Restart preview</a></html>'
                );
                return;
            }
            let root;
            let relative;
            if (url.pathname === '/') {
                root = directory;
                relative = 'index.html';
            } else if (url.pathname.startsWith('/theme/')) {
                root = themeDirectory;
                relative = url.pathname.slice(7);
            } else if (url.pathname.startsWith('/runtime/')) {
                root = path.join(project, 'build/theme-sdk/runtime');
                relative = url.pathname.slice(9);
            } else if (url.pathname.startsWith('/dev/')) {
                root = directory;
                relative = url.pathname.slice(5);
            } else {
                response.writeHead(404).end();
                return;
            }
            const file = path.resolve(root, relative);
            if (!file.startsWith(root + path.sep)) {
                response.writeHead(404).end();
                return;
            }
            const mime = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html; charset=utf-8' }[
                path.extname(file)
            ];
            if (!mime) {
                response.writeHead(404).end();
                return;
            }
            const body = await readFile(file);
            response.writeHead(200, { 'Content-Type': mime, 'Cache-Control': 'no-store' });
            response.end(body);
        } catch {
            response.writeHead(404).end('Build the theme and SDK before starting the preview.');
        }
    });
    server.listen(port, '127.0.0.1', () => console.log(`Theme preview: http://127.0.0.1:${server.address().port}/`));
    return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const { values } = parseArgs({
        options: {
            theme: { type: 'string' },
            port: { type: 'string', default: '8770' }
        }
    });
    const port = Number(values.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535)
        throw new Error('Port must be an integer between 1 and 65535.');
    startServer({ port, themeDirectory: values.theme });
}
