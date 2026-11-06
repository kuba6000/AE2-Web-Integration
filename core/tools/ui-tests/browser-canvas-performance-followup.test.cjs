const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, gridA, iron, pauseClock } = require('./fixtures.cjs');
const { iconColor, waitIconColor } = require('./icon-pixels.cjs');

for (const display of ['both', 'icons'])
    test(`icons stay aligned through small scrolls and large jumps in ${display} mode`, async (t) => {
        const { page, options, base } = await fixture(t, '', {
            deviceScaleFactor: 1.25,
            viewport: { width: 1100, height: 800 }
        });
        await atlasFixture(page, options);
        options.icons.pages.push({ digest: 'c'.repeat(64), width: 128, height: 64 });
        options.icons.animations = [
            {
                page: 1,
                frames: [
                    { x: 64, y: 0, durationMillis: 1000 },
                    { x: 64, y: 0, durationMillis: 1000 }
                ]
            }
        ];
        options.itemsA = Array.from({ length: 500 }, (_, index) => ({
            ...iron,
            itemKey: `resource-${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`,
            icon: { page: 0, x: 64, y: 0, ...(index % 2 ? { animation: 0 } : {}) }
        }));
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption(display);
        await page.goto(`${base}#/grids/${gridA}/items`);
        const viewport = page.locator('#item-scroll');
        const icons = page.locator('#items .item .resource-icon');
        await waitIconColor(page, icons.first(), 'green');
        assert.equal(await icons.count(), 500);

        const center = await viewport.evaluate((element) => {
            const bounds = element.getBoundingClientRect();
            const middle = bounds.top + bounds.height / 2;
            return [...element.querySelectorAll('#items .resource-icon')]
                .map((icon, index) => ({ index, box: icon.getBoundingClientRect() }))
                .filter(({ box }) => box.top >= bounds.top && box.bottom <= bounds.bottom)
                .sort((a, b) => Math.abs(a.box.top - middle) - Math.abs(b.box.top - middle))[0].index;
        });
        const retained = icons.nth(center);
        await waitIconColor(page, retained, 'green');
        await pauseClock(page);
        for (const top of [32, 64, 96, 128, 96, 64, 32, 0]) {
            await viewport.evaluate((element, value) => (element.scrollTop = value), top);
            assert.equal(
                await iconColor(page, retained),
                'green',
                'painted pixels follow their row before the next frame'
            );
            await page.clock.runFor(48);
            assert.equal(await iconColor(page, retained), 'green', 'the visible icon stays aligned after drawing');
        }
        await page.clock.resume();

        async function checkVisible() {
            const indices = await viewport.evaluate((element) => {
                const bounds = element.getBoundingClientRect();
                const visible = [...element.querySelectorAll('#items .resource-icon')]
                    .map((icon, index) => ({ index, box: icon.getBoundingClientRect() }))
                    .filter(
                        ({ box }) =>
                            box.left >= bounds.left &&
                            box.right <= bounds.right &&
                            box.top >= bounds.top &&
                            box.bottom <= bounds.bottom
                    );
                return [
                    visible[0]?.index,
                    visible[Math.floor(visible.length / 2)]?.index,
                    visible.at(-1)?.index
                ].filter((index) => index !== undefined);
            });
            assert.ok(indices.length, 'the viewport contains visible icons');
            for (const index of indices) await waitIconColor(page, icons.nth(index), 'green');
        }
        for (const fraction of [1, 0.45, 0.85, 0]) {
            await viewport.evaluate(
                (element, value) => (element.scrollTop = (element.scrollHeight - element.clientHeight) * value),
                fraction
            );
            await checkVisible();
        }
    });

for (const display of ['both', 'icons'])
    test(`preloaded icons appear at the scrolling edge before the next frame in ${display} mode`, async (t) => {
        const { page, options, base } = await fixture(t, '', {
            deviceScaleFactor: 1.25,
            viewport: { width: 1100, height: 800 }
        });
        await atlasFixture(page, options);
        options.itemsA = Array.from({ length: 500 }, (_, index) => ({
            ...iron,
            itemKey: `resource-${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`,
            icon: { page: 0, x: 64, y: 0 }
        }));
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption(display);
        await page.goto(`${base}#/grids/${gridA}/items`);
        const viewport = page.locator('#item-scroll');
        const icons = page.locator('#items .item .resource-icon');
        await waitIconColor(page, icons.first(), 'green');
        await page.evaluate(
            () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
        );
        await pauseClock(page);
        for (const direction of [1, -1]) {
            let checked = 0;
            for (let step = 0; step < 24; step++) {
                const index = await viewport.evaluate((element, direction) => {
                    const bounds = element.getBoundingClientRect();
                    const top = bounds.top + element.clientTop;
                    const bottom = top + element.clientHeight;
                    return [...element.querySelectorAll('#items .resource-icon')].findIndex((icon) => {
                        const box = icon.getBoundingClientRect();
                        const center = box.top + box.height / 2;
                        return direction > 0
                            ? center > bottom && center <= bottom + 32
                            : center < top && center >= top - 32;
                    });
                }, direction);
                await viewport.evaluate((element, direction) => (element.scrollTop += direction * 32), direction);
                if (index >= 0) {
                    checked++;
                    assert.equal(
                        await iconColor(page, icons.nth(index)),
                        'green',
                        `a preloaded icon entering the viewport stays painted before the next frame (step ${step}, direction ${direction})`
                    );
                }
                await page.clock.runFor(48);
            }
            assert.ok(checked >= 5, 'the scroll crosses multiple preloaded icon rows');
        }
    });

test('visible animations stay aligned as display density and viewport dimensions change', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 1100, height: 800 } });
    await atlasFixture(page, options);
    options.icons.pages.push({ digest: 'c'.repeat(64), width: 128, height: 64 });
    options.icons.animations = [
        {
            page: 1,
            frames: [
                { x: 64, y: 0, durationMillis: 5000 },
                { x: 0, y: 0, durationMillis: 5000 }
            ]
        }
    ];
    options.itemsA = Array.from({ length: 120 }, (_, index) => ({
        ...iron,
        itemKey: `resource-${index}`,
        displayName: `Resource ${String(index).padStart(3, '0')}`,
        icon: { page: 0, x: 0, y: 0, animation: 0 }
    }));
    await page.goto(`${base}#/grids/${gridA}/items`);
    const icon = page.getByRole('button', { name: /Resource 000/ }).locator('.resource-icon');
    await waitIconColor(page, icon, 'green');
    await pauseClock(page);
    const client = await page.context().newCDPSession(page);
    async function currentColor() {
        const point = await icon.evaluate((node) => {
            const box = node.getBoundingClientRect();
            return { x: Math.floor(box.left + box.width / 2), y: Math.floor(box.top + box.height / 2) };
        });
        // Playwright's screenshot helper can restore its context's original device metrics.
        const { data } = await client.send('Page.captureScreenshot', {
            format: 'png',
            clip: { ...point, width: 1, height: 1, scale: 1 }
        });
        return page.evaluate(async (encoded) => {
            const bitmap = await createImageBitmap(
                new Blob([Uint8Array.from(atob(encoded), (character) => character.charCodeAt(0))], {
                    type: 'image/png'
                })
            );
            const sample = document.createElement('canvas');
            sample.width = sample.height = 1;
            const context = sample.getContext('2d');
            context.drawImage(bitmap, 0, 0, 1, 1);
            bitmap.close();
            const [red, green, blue] = context.getImageData(0, 0, 1, 1).data;
            if (red > 240 && green < 15 && blue < 15) return 'red';
            if (green > 240 && red < 15 && blue < 15) return 'green';
            return `${red},${green},${blue}`;
        }, data);
    }
    let previous = await currentColor();
    let aligned = false;
    for (let step = 0; step < 22; step++) {
        await page.clock.runFor(500);
        const current = await currentColor();
        if (previous === 'red' && current === 'green') {
            aligned = true;
            break;
        }
        previous = current;
    }
    assert.ok(aligned, 'the animation reaches a fresh green frame');
    for (const [width, height, density, expected] of [
        [830, 650, 1.25, 'green'],
        [1100, 800, 2, 'red']
    ]) {
        await client.send('Emulation.setDeviceMetricsOverride', {
            width,
            height,
            deviceScaleFactor: density,
            mobile: false
        });
        await page.clock.runFor(96);
        assert.equal(await page.evaluate(() => devicePixelRatio), density);
        assert.equal(await currentColor(), expected, 'the visible animation remains aligned after a display change');
        await page.clock.runFor(5100);
        assert.equal(await currentColor(), expected === 'green' ? 'red' : 'green', 'the animation keeps advancing');
        assert.equal(await page.evaluate(() => devicePixelRatio), density, 'the simulated density remains active');
    }
});
