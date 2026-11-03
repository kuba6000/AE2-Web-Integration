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
        { title: 'Root', details: [], icon: null, missing: false, reference: false },
        { title: 'Offscreen material', details: ['9007199254740993'], icon: null, missing: true, reference: true }
    ],
    icons: null,
    display: 'both',
    title: 'Plan',
    scope: ['All expanded nodes', 'Partial capture']
});
function canvasEnvironment() {
    const texts = [],
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
            return { width: (text.length * Number(this.font.match(/([\d.]+)px/)?.[1] || 11)) / 2 };
        },
        fillText: (text) => texts.push(text)
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
    return { texts, images, cards, canvases };
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
            symbol: 'craft',
            count: '3',
            missing: false,
            reference: false
        },
        {
            title: 'Plate',
            details: ['Storage: 2'],
            icon: null,
            symbol: 'storage',
            count: '2',
            missing: false,
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
    assert.ok(env.cards.every(([, , width, height]) => width === 64 && height === 64));
    assert.ok(env.texts.includes('3') && env.texts.includes('2'));
    assert.ok(env.images.length >= 2, 'craft and storage symbols are painted');
});
