const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, pauseClock, gridA, iron } = require('./fixtures.cjs');
const { iconColor } = require('./icon-pixels.cjs');

async function pixels(page, icon) {
    const box = await icon.boundingBox();
    const screenshot = await page.screenshot({
        clip: {
            x: Math.floor(box.x) - 2,
            y: Math.floor(box.y) - 2,
            width: Math.ceil(box.width) + 5,
            height: Math.ceil(box.height) + 5
        }
    });
    return page.evaluate(async (encoded) => {
        const bitmap = await createImageBitmap(
            new Blob([Uint8Array.from(atob(encoded), (c) => c.charCodeAt(0))], { type: 'image/png' })
        );
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const context = canvas.getContext('2d');
        context.drawImage(bitmap, 0, 0);
        bitmap.close();
        return [...context.getImageData(0, 0, canvas.width, canvas.height).data];
    }, screenshot.toString('base64'));
}

for (const deviceScaleFactor of [1.25])
    test(`transparent animation frames leave no edge remnants at density ${deviceScaleFactor}`, async (t) => {
        const { page, options, base } = await fixture(t, '', {
            deviceScaleFactor,
            viewport: { width: 1167, height: 900 }
        });
        await atlasFixture(page, options);
        options.atlas = Buffer.from(
            await page.evaluate(() => {
                const canvas = document.createElement('canvas');
                canvas.width = 192;
                canvas.height = 64;
                const context = canvas.getContext('2d');
                context.fillStyle = '#ff0000';
                context.fillRect(64, 0, 64, 64);
                context.fillStyle = '#00ff00';
                context.fillRect(128, 0, 64, 64);
                return canvas.toDataURL().split(',')[1];
            }),
            'base64'
        );
        options.icons.pages = [
            { digest: 'b'.repeat(64), width: 192, height: 64 },
            { digest: 'c'.repeat(64), width: 192, height: 64 }
        ];
        options.icons.animations = [
            {
                page: 1,
                frames: [
                    { x: 0, y: 0, durationMillis: 2000 },
                    { x: 64, y: 0, durationMillis: 1000 }
                ]
            }
        ];
        options.itemsA = [
            { ...iron, itemKey: 'neighbor', displayName: 'A neighbor', icon: { page: 1, x: 128, y: 0 } },
            { ...iron, itemKey: 'moving', displayName: 'B animation', icon: { page: 0, x: 0, y: 0, animation: 0 } }
        ];
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('slider', { name: 'Animation update interval', exact: true }).fill('0');
        await page.getByRole('combobox', { name: 'Terminal display', exact: true }).selectOption('icons');
        await pauseClock(page);
        const loaded = page.waitForResponse((response) => response.url().endsWith('/pages/' + 'c'.repeat(64)));
        await page.goto(`${base}#/grids/${gridA}/items`);
        await (await loaded).finished();
        const moving = page.getByRole('button', { name: /B animation/ }).locator('.resource-icon');
        const neighbor = page.getByRole('button', { name: /A neighbor/ }).locator('.resource-icon');
        // Loading may take arbitrary wall time, but setup cannot consume the initial transparent phase.
        // The neighbor shares the animation atlas and proves it has decoded and been painted.
        let neighborColor;
        for (let attempt = 0; attempt < 16; attempt++) {
            await page.clock.runFor(32);
            neighborColor = await iconColor(page, neighbor);
            if (neighborColor === 'green') break;
        }
        assert.equal(neighborColor, 'green', 'the shared atlas is visibly ready');
        await page.clock.runFor(32);
        const clean = await pixels(page, moving);
        const neighborBefore = await pixels(page, neighbor);
        await page.clock.runFor(2100);
        assert.equal(await iconColor(page, moving), 'red');
        await page.clock.runFor(1000);
        assert.deepEqual(
            await pixels(page, moving),
            clean,
            'a transparent frame must restore the complete icon area, including its antialiased edges'
        );
        for (let cycle = 0; cycle < 4; cycle++) await page.clock.runFor(3000);
        assert.deepEqual(await pixels(page, moving), clean, 'repeated cycles must not accumulate edge color');
        assert.deepEqual(
            await pixels(page, neighbor),
            neighborBefore,
            'clearing one icon must preserve its neighbor, including its edges'
        );
    });
