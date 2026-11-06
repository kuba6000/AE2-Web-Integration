const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const moduleUrl = pathToFileURL(
    path.resolve(__dirname, '../../build/generated/frontend/assets/web/themes/default/crafting-tree-export.js')
);
const style = {
    fontFamily: 'sans-serif',
    foreground: '#fff',
    background: '#111',
    tile: '#222',
    border: '#ccc',
    missing: '#600',
    missingBorder: '#f66',
    missingText: '#fff4f4',
    blocked: '#dd0',
    recipe: '#456',
    recipeBorder: '#789',
    textColors: {}
};
const settings = { scale: 1, name: true, icon: true, colors: true, background: 'theme' };
const scene = () => ({
    layout: {
        width: 4600,
        height: 4600,
        nodes: [
            { x: 16, y: 16, width: 240, height: 136, parent: null },
            { x: 4200, y: 4200, width: 240, height: 136, parent: 0 }
        ]
    },
    cards: [
        { title: 'Root', details: [], icon: null, missing: false, blocked: false, reference: false },
        {
            title: 'Offscreen material',
            details: ['9007199254740993'],
            icon: null,
            missing: true,
            blocked: false,
            reference: true
        }
    ],
    icons: null,
    display: 'both',
    title: 'Plan',
    scope: ['All expanded nodes', 'Partial capture']
});
function canvasEnvironment() {
    const texts = [],
        textDraws = [],
        images = [],
        cards = [],
        canvases = [];
    const context = {
        font: '',
        fillStyle: '',
        clearRect() {},
        fillRect() {},
        getImageData: () => ({ data: [0, 0, 0, 255] }),
        scale() {},
        translate() {},
        beginPath() {},
        moveTo() {},
        lineTo() {},
        stroke() {},
        setLineDash() {},
        drawImage(...args) {
            images.push(args);
        },
        strokeRect: (...args) => cards.push(args),
        measureText(text) {
            return {
                width: (text.length * Number(this.font.match(/([\d.]+)px/)?.[1] || 11)) / 2,
                actualBoundingBoxAscent: this.textBaseline === 'alphabetic' ? 8 : 0
            };
        },
        fillText(text, x, y) {
            texts.push(text);
            textDraws.push({ text, x, y, align: this.textAlign, baseline: this.textBaseline });
        }
    };
    global.Image = class {
        async decode() {}
    };
    global.document = {
        fonts: { load: async () => [] },
        createElement: () => {
            const canvas = {
                width: 0,
                height: 0,
                getContext: () => context,
                toBlob: (callback) => callback(new Blob(['png'], { type: 'image/png' }))
            };
            canvases.push(canvas);
            return canvas;
        }
    };
    return { texts, textDraws, images, cards, canvases };
}
test('PNG paints every node in the snapshot including offscreen nodes and exact quantities, then releases canvas/icons', async () => {
    const { renderTreePng } = await import(moduleUrl);
    const env = canvasEnvironment();
    let disposed = false;
    const result = await renderTreePng(
        scene(),
        settings,
        style,
        {
            resolve: async () => ({
                icons: new Map(),
                dispose() {
                    disposed = true;
                }
            })
        },
        new AbortController().signal
    );
    assert.equal(result.blob.type, 'image/png');
    assert.equal(env.cards.length, 2);
    assert.ok(env.cards.some(([x, y]) => x === 4200 && y === 4200));
    assert.ok(env.texts.includes('9007199254740993'));
    assert.ok(env.texts.includes('All expanded nodes'));
    assert.ok(env.texts.includes('Partial capture'));
    assert.ok(env.canvases.every((canvas) => canvas.width === 0 && canvas.height === 0));
    assert.equal(disposed, true);
});
test('PNG refuses excessive geometry before allocating a canvas', async () => {
    const { renderTreePng } = await import(moduleUrl);
    const env = canvasEnvironment();
    const huge = scene();
    huge.layout.width = 1000000;
    await assert.rejects(
        renderTreePng(
            huge,
            settings,
            style,
            {
                resolve() {
                    throw new Error('must not resolve');
                }
            },
            new AbortController().signal
        ),
        /too-large/
    );
    assert.equal(env.canvases.length, 0);
});
test('cancellation after icon resolution never produces a PNG and releases resolved bitmaps', async () => {
    const { renderTreePng } = await import(moduleUrl);
    const env = canvasEnvironment();
    const controller = new AbortController();
    let disposed = false;
    await assert.rejects(
        renderTreePng(
            scene(),
            settings,
            style,
            {
                resolve: async () => {
                    controller.abort();
                    return {
                        icons: new Map(),
                        dispose() {
                            disposed = true;
                        }
                    };
                }
            },
            controller.signal
        ),
        { name: 'AbortError' }
    );
    assert.equal(disposed, true);
    assert.equal(env.canvases.length, 0);
});

test('PNG uses the contribution source identity and icon from the shared card content', async () => {
    const { renderTreePng } = await import(moduleUrl);
    const contentUrl = pathToFileURL(
        path.resolve(__dirname, '../../build/generated/frontend/assets/web/themes/default/crafting-tree-content.js')
    );
    const { craftingTreeContent } = await import(contentUrl);
    const icon = { page: 1, x: 0, y: 0 };
    const card = craftingTreeContent(
        {
            key: 'CONTRIBUTION:1',
            node: { kind: 'CONTRIBUTION', resourceId: 1, contribution: 'STORAGE' },
            shortage: 'NONE',
            depth: 0,
            reference: false,
            quantity: '2',
            relation: 'CONTRIBUTION'
        },
        { resources: { 0: { displayName: 'Requested' }, 1: { displayName: 'Substitute', icon } }, recipes: {} },
        { common: (key) => key },
        'en'
    );
    const snapshot = scene();
    snapshot.layout.nodes = [snapshot.layout.nodes[0]];
    snapshot.cards = [card];
    const env = canvasEnvironment();
    let resolved;
    await renderTreePng(
        snapshot,
        settings,
        style,
        {
            resolve: async (references) => {
                resolved = references;
                return { icons: new Map(), dispose() {} };
            }
        },
        new AbortController().signal
    );
    assert.deepEqual(resolved, [icon]);
    assert.ok(env.texts.includes('Substitute'));
    assert.ok(!env.texts.includes('Requested'));
});

test('compact PNG retains small node geometry, counts and source symbols', async () => {
    const { renderTreePng } = await import(moduleUrl);
    const snapshot = scene();
    snapshot.display = 'icons';
    snapshot.layout = {
        width: 192,
        height: 192,
        nodes: [
            { x: 16, y: 16, width: 64, height: 64, parent: null },
            { x: 112, y: 112, width: 64, height: 64, parent: 0 }
        ]
    };
    snapshot.cards = [
        {
            title: 'Recipe',
            details: ['Crafts: 3'],
            icon: null,
            hasResource: false,
            symbol: 'craft',
            count: '3',
            missing: false,
            blocked: false,
            reference: false
        },
        {
            title: 'Plate',
            details: ['Storage: 2'],
            icon: null,
            hasResource: true,
            symbol: 'storage',
            count: '2',
            missing: false,
            blocked: false,
            reference: false
        }
    ];
    const env = canvasEnvironment();
    await renderTreePng(
        snapshot,
        settings,
        style,
        { resolve: async () => ({ icons: new Map(), dispose() {} }) },
        new AbortController().signal
    );
    assert.equal(env.cards.filter(([, , width, height]) => width === 64 && height === 64).length, 2);
    assert.ok(env.texts.includes('3') && env.texts.includes('2'));
    assert.ok(env.images.length >= 2, 'craft and storage symbols are painted');
});

test('PNG anchors source badges to the same card corner in both icon modes', async () => {
    const { renderTreePng } = await import(moduleUrl);
    const icon = { page: 0, x: 0, y: 0 };
    const bitmap = {};
    const failures = [];
    for (const display of ['icons', 'both', 'names']) {
        const compact = display === 'icons';
        const width = compact ? 64 : 240;
        const height = compact ? 64 : 108;
        const snapshot = scene();
        snapshot.display = display;
        snapshot.layout = {
            width: 640,
            height: 400,
            nodes: [
                { x: 16, y: 16, width, height, parent: null },
                { x: 320, y: 200, width, height, parent: null }
            ]
        };
        snapshot.cards = [
            {
                title: 'Recipe',
                details: [],
                icon: null,
                hasResource: false,
                symbol: 'craft',
                missing: false,
                blocked: false,
                reference: false
            },
            {
                title: 'Plate',
                details: [],
                icon,
                hasResource: true,
                symbol: 'storage',
                missing: false,
                blocked: false,
                reference: false
            }
        ];
        const env = canvasEnvironment();
        await renderTreePng(
            snapshot,
            settings,
            style,
            {
                resolve: async () => ({
                    icons: new Map([[icon, { image: bitmap, x: 0, y: 0, width: 64, height: 64 }]]),
                    dispose() {}
                })
            },
            new AbortController().signal
        );
        const checkCenter = (image, tile) => {
            const [x, y, imageWidth, imageHeight] = image.slice(-4);
            const expectedSize = 32;
            if (
                imageWidth !== expectedSize ||
                imageHeight !== expectedSize ||
                y + imageHeight / 2 !== tile.y + tile.height / 2 ||
                (compact && x + imageWidth / 2 !== tile.x + tile.width / 2)
            )
                failures.push(`${display} artwork bounds: ${image.slice(-4)}`);
        };
        checkCenter(env.images[0], snapshot.layout.nodes[0]);
        if (display === 'names') checkCenter(env.images[1], snapshot.layout.nodes[1]);
        else {
            const itemIndex = env.images.findIndex(([image]) => image === bitmap);
            checkCenter(env.images[itemIndex], snapshot.layout.nodes[1]);
            const [x, y, size] = env.images.at(-1).slice(-4);
            const tile = snapshot.layout.nodes[1];
            assert.deepEqual(
                [x - tile.x, y - tile.y, size],
                [4, 5, 16],
                'both icon modes anchor the same-sized badge to the card, independently of item art'
            );
        }
    }
    assert.deepEqual(failures, []);
});

test('PNG centers source glyphs with and without resource artwork', async () => {
    const { renderTreePng } = await import(moduleUrl);
    const contentUrl = pathToFileURL(
        path.resolve(__dirname, '../../build/generated/frontend/assets/web/themes/default/crafting-tree-content.js')
    );
    const { craftingTreeContent } = await import(contentUrl);
    const source = (resourceId, contribution) =>
        craftingTreeContent(
            {
                key: contribution,
                node: { kind: 'CONTRIBUTION', resourceId, contribution },
                shortage: contribution === 'MISSING' ? 'MISSING' : 'NONE',
                depth: 0,
                reference: false,
                quantity: '1',
                relation: 'CONTRIBUTION'
            },
            { resources: { 1: { displayName: 'Missing', icon: null } }, recipes: {} },
            { common: (key) => key },
            'en'
        );
    const snapshot = scene();
    snapshot.display = 'icons';
    snapshot.layout = {
        width: 192,
        height: 140,
        nodes: [
            { x: 16, y: 16, width: 64, height: 64, parent: null },
            { x: 112, y: 16, width: 64, height: 64, parent: null }
        ]
    };
    snapshot.cards = [source(1, 'MISSING'), source(null, 'UNKNOWN')];
    const glyphs = [];
    for (const [display, showIcons] of [
        ['icons', true],
        ['icons', false],
        ['both', true]
    ]) {
        snapshot.display = display;
        for (const node of snapshot.layout.nodes) node.height = display === 'icons' ? 64 : 108;
        const env = canvasEnvironment();
        await renderTreePng(
            snapshot,
            { ...settings, icon: showIcons },
            style,
            { resolve: async () => ({ icons: new Map(), dispose() {} }) },
            new AbortController().signal
        );
        glyphs.push(env.textDraws.filter(({ text }) => text === '?' || text === '!'));
    }
    assert.deepEqual(glyphs, [
        [
            { text: '?', x: 48, y: 48, align: 'center', baseline: 'middle' },
            { text: '!', x: 28, y: 29, align: 'center', baseline: 'middle' },
            { text: '?', x: 144, y: 48, align: 'center', baseline: 'middle' }
        ],
        [
            { text: '!', x: 48, y: 48, align: 'center', baseline: 'middle' },
            { text: '?', x: 144, y: 48, align: 'center', baseline: 'middle' }
        ],
        [
            { text: '!', x: 44, y: 70, align: 'center', baseline: 'middle' },
            { text: '?', x: 140, y: 70, align: 'center', baseline: 'middle' }
        ]
    ]);
});

test('compact counts align to the right card edge in PNG and SVG', async () => {
    const { renderTreePng, renderTreeSvg } = await import(moduleUrl);
    const snapshot = scene();
    snapshot.display = 'icons';
    snapshot.layout = { width: 96, height: 96, nodes: [{ x: 16, y: 16, width: 64, height: 64, parent: null }] };
    snapshot.cards = [{ title: 'Plate', details: [], count: '120', symbol: null, icon: null }];
    const env = canvasEnvironment();
    const icons = { resolve: async () => ({ icons: new Map(), dispose() {} }) };
    await renderTreePng(snapshot, { ...settings, icon: false }, style, icons, new AbortController().signal);
    const count = env.textDraws.find((entry) => entry.text === '120');
    assert.equal(count.align, 'right');
    assert.ok(count.x > 70 && count.x < 80);
    const result = await renderTreeSvg(
        snapshot,
        { ...settings, icon: false },
        style,
        icons,
        new AbortController().signal
    );
    assert.match(await result.blob.text(), /<text[^>]*text-anchor="end"[^>]*>120<\/text>/);
});

test('truncated compact counts keep their ellipsis inside the right edge in both formats', async () => {
    const { renderTreePng, renderTreeSvg } = await import(moduleUrl);
    const snapshot = scene();
    snapshot.display = 'icons';
    snapshot.layout = { width: 96, height: 96, nodes: [{ x: 16, y: 16, width: 64, height: 64, parent: null }] };
    snapshot.cards = [
        { title: 'Plate', details: [], count: '123456789012345678901234567890', symbol: null, icon: null }
    ];
    const env = canvasEnvironment();
    const icons = { resolve: async () => ({ icons: new Map(), dispose() {} }) };
    await renderTreePng(snapshot, { ...settings, icon: false }, style, icons, new AbortController().signal);
    const runs = env.textDraws.filter((entry) => entry.align === 'right');
    assert.ok(runs.some((run) => run.text === '…'));
    assert.ok(runs.every((run) => run.x <= 76));
    const result = await renderTreeSvg(
        snapshot,
        { ...settings, icon: false },
        style,
        icons,
        new AbortController().signal
    );
    const text = await result.blob.text();
    const svgRuns = [...text.matchAll(/<text x="([^"]+)"[^>]*text-anchor="end"[^>]*>([^<]*)<\/text>/g)];
    assert.ok(svgRuns.some((run) => run[2] === '…'));
    assert.ok(svgRuns.every((run) => Number(run[1]) <= 76));
});

test('text card exports center the title and detail block vertically', async () => {
    const { renderTreePng, renderTreeSvg } = await import(moduleUrl);
    const snapshot = scene();
    snapshot.display = 'both';
    snapshot.layout = { width: 192, height: 104, nodes: [{ x: 16, y: 16, width: 160, height: 72, parent: null }] };
    snapshot.cards = [{ title: 'Recipe', details: ['Crafts: 12'], icon: null, symbol: null }];
    const env = canvasEnvironment();
    const icons = { resolve: async () => ({ icons: new Map(), dispose() {} }) };
    await renderTreePng(snapshot, settings, style, icons, new AbortController().signal);
    assert.equal(env.textDraws.find((run) => run.text === 'Recipe').y, 35);
    assert.equal(env.textDraws.find((run) => run.text === 'Crafts: 12').y, 55);
    const result = await renderTreeSvg(snapshot, settings, style, icons, new AbortController().signal);
    const svg = await result.blob.text();
    // SVG alphabetic baselines include the fixture font's eight-pixel ascent.
    assert.match(svg, /<text[^>]*y="43"[^>]*>Recipe<\/text>/);
    assert.match(svg, /<text[^>]*y="63"[^>]*>Crafts: 12<\/text>/);
});

test('small recipe exports keep details readable instead of overlapping extra lines', async () => {
    const { renderTreePng, renderTreeSvg } = await import(moduleUrl);
    const snapshot = scene();
    snapshot.display = 'both';
    snapshot.layout = { width: 192, height: 100, nodes: [{ x: 16, y: 16, width: 160, height: 56, parent: null }] };
    snapshot.cards = [
        {
            title: 'Recipe',
            details: ['Craft count: 12', 'Shared reference', 'Partial recipe'],
            icon: null,
            symbol: null
        }
    ];
    const env = canvasEnvironment();
    const icons = { resolve: async () => ({ icons: new Map(), dispose() {} }) };
    await renderTreePng(snapshot, { ...settings, icon: false }, style, icons, new AbortController().signal);
    assert.ok(env.texts.includes('Craft count: 12'));
    assert.ok(!env.texts.includes('Shared reference'));
    const result = await renderTreeSvg(
        snapshot,
        { ...settings, icon: false },
        style,
        icons,
        new AbortController().signal
    );
    const text = await result.blob.text();
    assert.match(text, /Craft count: 12/);
    assert.doesNotMatch(text, /Shared reference|Partial recipe/);
});
