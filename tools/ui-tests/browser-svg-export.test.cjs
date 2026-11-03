const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { fixture, gridA, readyPlan, atlasFixture } = require('./fixtures.cjs');

test('tree SVG is standalone with shared icons, safe styled text and bounds beyond the PNG budget', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    await atlasFixture(page, options);
    await page.goto(base);
    const result = await page.evaluate(async (metadata) => {
        const { renderTreeSvg } = await import('./assets/web/themes/default/crafting-tree-export.js');
        const { createIconLoader } = await import('./assets/web/app/icons.js');
        const loader = createIconLoader(
            new URL(location.href),
            () => {},
            async () => {}
        );
        loader.enabled(true);
        const icon = { page: 0, x: 0, y: 0 };
        const settings = { format: 'svg', scale: 1, name: true, icon: true, colors: true, background: 'transparent' };
        const style = {
            fontFamily: 'Monocraft, monospace',
            foreground: '#eeeeee',
            background: '#282a31',
            tile: '#45454e',
            border: '#94909f',
            missing: '#6b3030',
            textColors: { red: '#ff5555' },
            fonts: ['normal', 'bold'].map((weight) => ({
                family: 'Monocraft',
                weight,
                asset: new URL(
                    `assets/web/themes/default/fonts/monocraft/Monocraft-${weight === 'bold' ? 'Bold' : 'Regular'}.woff2`,
                    location.href
                ).href,
                license: new URL('assets/web/themes/default/fonts/monocraft/LICENSE', location.href).href
            }))
        };
        const scene = {
            layout: {
                width: 320,
                height: 260,
                nodes: [
                    { x: 16, y: 16, width: 240, height: 108, parent: null },
                    { x: 16, y: 152, width: 240, height: 108, parent: 0 }
                ]
            },
            display: 'both',
            icons: metadata,
            title: 'Title <&',
            scope: ['Expanded nodes'],
            cards: [
                {
                    title: '§c§lCard <&',
                    details: ['Stored: 2'],
                    icon,
                    hasResource: true,
                    symbol: 'storage',
                    count: '2',
                    missing: false,
                    reference: false
                },
                {
                    title: 'Card <&',
                    details: ['Missing: 1'],
                    icon,
                    hasResource: true,
                    symbol: 'missing',
                    count: '1',
                    missing: true,
                    reference: false
                }
            ]
        };
        try {
            const small = await renderTreeSvg(scene, settings, style, loader, new AbortController().signal);
            const xml = await small.blob.text();
            const parsed = new DOMParser().parseFromString(xml, 'image/svg+xml');
            const image = new Image();
            image.src = URL.createObjectURL(small.blob);
            await image.decode();
            const canvas = document.createElement('canvas');
            canvas.width = image.naturalWidth;
            canvas.height = image.naturalHeight;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(image, 0, 0);
            URL.revokeObjectURL(image.src);
            const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
            let red = 0;
            for (let i = 0; i < pixels.length; i += 4)
                if (pixels[i] === 255 && pixels[i + 1] === 0 && pixels[i + 2] === 0 && pixels[i + 3] === 255) red++;
            const huge = {
                ...scene,
                layout: {
                    ...scene.layout,
                    height: 100000,
                    nodes: [scene.layout.nodes[0], { ...scene.layout.nodes[1], y: 99800 }]
                }
            };
            const large = await renderTreeSvg(huge, settings, style, loader, new AbortController().signal);
            return {
                type: small.blob.type,
                errors: parsed.querySelectorAll('parsererror').length,
                text: parsed.documentElement.textContent,
                red,
                cornerAlpha: pixels[3],
                images: [...parsed.querySelectorAll('image')].map((item) => item.getAttribute('href')),
                unsafe: parsed.querySelectorAll('script, foreignObject').length,
                hasFonts: xml.includes('data:font/woff2;base64,'),
                hasLicense: xml.includes('SIL OPEN FONT LICENSE'),
                largeHeight: large.height,
                largeScale: large.scale
            };
        } finally {
            loader.dispose();
        }
    }, options.icons);
    assert.equal(result.type, 'image/svg+xml');
    assert.equal(result.errors, 0);
    assert.ok(result.text.includes('Title <&') && result.text.includes('Card <&'));
    assert.ok(result.red > 500, 'standalone SVG displays both embedded red item crops');
    assert.equal(result.cornerAlpha, 0);
    assert.equal(result.images.length, 1, 'repeated resource crops are embedded once');
    assert.ok(result.images.every((source) => source.startsWith('data:image/png;base64,')));
    assert.equal(result.unsafe, 0);
    assert.ok(result.hasFonts && result.hasLicense);
    assert.ok(result.largeHeight > 100000);
    assert.equal(result.largeScale, 1);
});

test('ordinary plan offers PNG and SVG, remembers the format, and downloads independently rendered icons', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    await atlasFixture(page, options);
    options.plan = {
        ...readyPlan,
        plan: [
            { ...readyPlan.plan[0], displayName: '§c§lPlan <&', icon: { page: 0, x: 0, y: 0 }, stored: 1234 },
            { ...readyPlan.plan[0], displayName: 'Second', icon: { page: 0, x: 0, y: 0 }, missing: 2 }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Export', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export', exact: true });
    await dialog.getByRole('combobox', { name: 'Format', exact: true }).selectOption('svg');
    const pending = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download SVG', exact: true }).click();
    const download = await pending;
    const xml = await fs.readFile(await download.path(), 'utf8');
    assert.match(download.suggestedFilename(), /\.svg$/);
    const check = await page.evaluate(async (svg) => {
        const document = new DOMParser().parseFromString(svg, 'image/svg+xml');
        const image = new Image();
        image.src = `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`;
        await image.decode();
        return {
            errors: document.querySelectorAll('parsererror').length,
            width: image.naturalWidth,
            text: document.documentElement.textContent,
            images: document.querySelectorAll('image').length
        };
    }, xml);
    assert.equal(check.errors, 0);
    assert.ok(check.width > 0 && check.text.includes('Plan <&') && check.text.includes('Second'));
    assert.equal(check.images, 1);
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.reload();
    await page.getByRole('button', { name: 'Export', exact: true }).click();
    assert.equal(await dialog.getByRole('combobox', { name: 'Format', exact: true }).inputValue(), 'svg');
    await dialog.getByRole('combobox', { name: 'Format', exact: true }).selectOption('png');
    const pngPending = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download PNG', exact: true }).click();
    const png = await fs.readFile(await (await pngPending).path());
    assert.equal(png.subarray(1, 4).toString(), 'PNG');
});

test('closing SVG export during asset loading cancels download and allows a new export', async (t) => {
    const { page, base } = await fixture(t, '', {}, { mockClock: false });
    await page.addInitScript(() => localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ font: 'lato' })));
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Export', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export', exact: true });
    await dialog.getByRole('combobox', { name: 'Format', exact: true }).selectOption('svg');
    await page.evaluate(() => document.fonts.ready);
    let release;
    const started = new Promise((resolve) => {
        release = resolve;
    });
    const pattern = '**/fonts/lato/Lato-Regular.woff2';
    await page.route(pattern, (route) => {
        release(route);
    });
    let downloads = 0;
    page.on('download', () => downloads++);
    await dialog.getByRole('button', { name: 'Download SVG', exact: true }).click();
    const route = await started;
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await route.abort().catch(() => {});
    await page.unroute(pattern);
    await dialog.waitFor({ state: 'hidden' });
    assert.equal(downloads, 0);
    await page.getByRole('button', { name: 'Export', exact: true }).click();
    const pending = page.waitForEvent('download');
    await dialog.getByRole('combobox', { name: 'Format', exact: true }).selectOption('svg');
    await dialog.getByRole('button', { name: 'Download SVG', exact: true }).click();
    const svg = await fs.readFile(await (await pending).path(), 'utf8');
    assert.ok(svg.includes('data:font/woff2;base64,'));
    assert.ok(svg.includes('Lato'));
    assert.equal(downloads, 1);
});
