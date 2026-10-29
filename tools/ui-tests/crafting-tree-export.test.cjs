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
    title: 'Plan',
    scope: ['Loaded expanded window only', 'Partial capture']
});
function canvasEnvironment() {
    const texts = [],
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
        drawImage() {},
        strokeRect: (...args) => cards.push(args),
        measureText(text) {
            return { width: (text.length * Number(this.font.match(/([\d.]+)px/)?.[1] || 11)) / 2 };
        },
        fillText: (text) => texts.push(text)
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
    return { texts, cards, canvases };
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
    assert.ok(env.texts.includes('Loaded expanded window only'));
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
