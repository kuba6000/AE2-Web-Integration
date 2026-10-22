const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { fixture, gridA, readyPlan, atlasFixture } = require('./fixtures.cjs');

async function openExport(page, base, search) {
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    if (search) await page.getByRole('searchbox', { name: 'Search plan resources', exact: true }).fill(search);
    await page.getByRole('button', { name: 'Export PNG', exact: true }).click();
    return page.getByRole('dialog', { name: 'Export PNG', exact: true });
}
async function png(page, dialog) {
    const promise = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download PNG', exact: true }).click();
    const download = await promise;
    const bytes = await fs.readFile(await download.path());
    assert.equal(bytes.subarray(1, 4).toString(), 'PNG');
    const pixels = await page.evaluate(async (data) => {
        const blob = await (await fetch(`data:image/png;base64,${data}`)).blob();
        const bitmap = await createImageBitmap(blob);
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(bitmap, 0, 0);
        bitmap.close();
        const values = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        let opaque = 0,
            red = 0,
            green = 0;
        for (let i = 0; i < values.length; i += 4) {
            if (values[i + 3]) opaque++;
            if (values[i] === 255 && values[i + 1] === 0 && values[i + 2] === 0 && values[i + 3] === 255) red++;
            if (values[i] === 0 && values[i + 1] === 255 && values[i + 2] === 0 && values[i + 3] === 255) green++;
        }
        return { width: canvas.width, height: canvas.height, cornerAlpha: values[3], opaque, red, green };
    }, bytes.toString('base64'));
    return { bytes, pixels, download };
}

test('plan PNG exports every row independently of search, without icons, and remembers settings', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    options.plan = {
        ...readyPlan,
        plan: Array.from({ length: 7 }, (_, i) => ({ ...readyPlan.plan[0], displayName: `Resource ${i}` }))
    };
    const dialog = await openExport(page, base, 'Resource 0');
    assert.ok(await dialog.getByRole('checkbox', { name: 'Icons', exact: true }).isDisabled());
    await dialog.getByRole('combobox', { name: 'Layout', exact: true }).selectOption('columns');
    await dialog.getByRole('spinbutton', { name: 'Columns', exact: true }).fill('3');
    const tileWidth = await page
        .locator('.crafting-plan .item')
        .first()
        .evaluate((node) => Math.round(node.getBoundingClientRect().width));
    await dialog.getByRole('combobox', { name: 'Background', exact: true }).selectOption('transparent');
    const { pixels, bytes, download } = await png(page, dialog);
    assert.equal(pixels.width, tileWidth * 3);
    assert.equal(pixels.height, 332);
    assert.equal(pixels.cornerAlpha, 0);
    assert.ok(pixels.opaque >= 9 * tileWidth * 100);
    assert.match(download.suggestedFilename(), /^Iron Ingot-plan-\d{8}-\d{6}\.png$/);
    if (process.env.EXPORT_EVIDENCE_DIR) {
        await fs.mkdir(process.env.EXPORT_EVIDENCE_DIR, { recursive: true });
        await fs.writeFile(path.join(process.env.EXPORT_EVIDENCE_DIR, 'plan-no-icons.png'), bytes);
        await page.screenshot({ path: path.join(process.env.EXPORT_EVIDENCE_DIR, 'dialog-desktop.png') });
        await page.setViewportSize({ width: 390, height: 844 });
        await page.screenshot({ path: path.join(process.env.EXPORT_EVIDENCE_DIR, 'dialog-mobile.png') });
    }
    await page.setViewportSize({ width: 390, height: 844 });
    const downloadButton = dialog.getByRole('button', { name: 'Download PNG', exact: true });
    await downloadButton.scrollIntoViewIfNeeded();
    assert.ok(await downloadButton.isVisible());
    assert.ok(await dialog.evaluate((node) => node.scrollWidth <= node.clientWidth));
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.reload();
    await page.getByRole('button', { name: 'Export PNG', exact: true }).click();
    assert.equal(await dialog.getByRole('combobox', { name: 'Background', exact: true }).inputValue(), 'transparent');
    assert.equal(await dialog.getByRole('spinbutton', { name: 'Columns', exact: true }).inputValue(), '3');
    await dialog.getByRole('combobox', { name: 'Scale', exact: true }).selectOption('0.5');
    assert.equal(await dialog.getByRole('spinbutton', { name: 'Columns', exact: true }).inputValue(), '3');
    const smaller = await png(page, dialog);
    const mobileWidth = await page
        .locator('.crafting-plan .item')
        .first()
        .evaluate((node) => Math.round(node.getBoundingClientRect().width));
    assert.equal(smaller.pixels.width, Math.round(mobileWidth * 0.5) * 3);
    assert.equal(smaller.pixels.height, 166);
});

test('missing-only export clamps rows and columns to the selection and validates edited counts', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    options.plan = {
        ...readyPlan,
        plan: Array.from({ length: 40 }, (_, i) => ({
            ...readyPlan.plan[0],
            displayName: `Resource ${i}`,
            missing: i < 3 ? 1 : 0
        }))
    };
    const dialog = await openExport(page, base);
    const layout = dialog.getByRole('combobox', { name: 'Layout', exact: true });
    const onlyMissing = dialog.getByRole('checkbox', { name: 'Only missing resources', exact: true });
    const download = dialog.getByRole('button', { name: 'Download PNG', exact: true });
    for (const axis of ['columns', 'rows']) {
        await onlyMissing.uncheck();
        await layout.selectOption(axis);
        const count = dialog.getByRole('spinbutton');
        await count.fill('10');
        await onlyMissing.check();
        assert.equal(await count.inputValue(), '3');
        assert.equal(await count.getAttribute('max'), '3');
        assert.ok(await download.isEnabled());
        await png(page, dialog);
        for (const invalid of ['', '0', '1.5']) {
            await count.fill(invalid);
            assert.equal(await count.getAttribute('aria-invalid'), 'true');
            assert.equal(await download.isEnabled(), false);
        }
        await count.fill('2');
        assert.equal(await count.getAttribute('aria-invalid'), 'false');
        assert.ok(await download.isEnabled());
    }
});

test('closing a large PNG export interrupts drawing before the remaining resources and prevents a download', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    options.plan = {
        ...readyPlan,
        plan: Array.from({ length: 4000 }, (_, i) => ({
            ...readyPlan.plan[0],
            displayName: `Resource${String(i).padStart(5, '0')}`
        }))
    };
    const dialog = await openExport(page, base);
    let downloads = 0;
    page.on('download', () => downloads++);
    await dialog.evaluate((node) => {
        const fillText = CanvasRenderingContext2D.prototype.fillText;
        window.exportDrewLastResource = false;
        CanvasRenderingContext2D.prototype.fillText = function (text, ...args) {
            if (text === 'Resource00000') {
                setTimeout(() => node.querySelector('.close-dialog').click(), 0);
            }
            if (text === 'Resource03999') window.exportDrewLastResource = true;
            return fillText.call(this, text, ...args);
        };
    });
    await dialog.getByRole('button', { name: 'Download PNG', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    assert.equal(await page.evaluate(() => window.exportDrewLastResource), false);
    assert.equal(downloads, 0);
    await page.getByRole('button', { name: 'Export PNG', exact: true }).click();
    assert.ok(await dialog.getByRole('button', { name: 'Download PNG', exact: true }).isEnabled());
});

test('PNG header includes the output icon only when both header and icons are enabled', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    await atlasFixture(page, options);
    options.plan = {
        ...readyPlan,
        finalOutput: { ...readyPlan.finalOutput, icon: { page: 0, x: 64, y: 0 } },
        plan: [{ ...readyPlan.plan[0], icon: { page: 0, x: 0, y: 0 } }]
    };
    const dialog = await openExport(page, base);
    const withHeader = await png(page, dialog);
    assert.ok(withHeader.pixels.green > 100, 'output icon is drawn even when absent from plan rows');
    assert.ok(withHeader.pixels.red > 100, 'resource icon is still drawn');
    await dialog.getByRole('checkbox', { name: 'Icons', exact: true }).uncheck();
    assert.equal((await png(page, dialog)).pixels.green, 0);
    await dialog.getByRole('checkbox', { name: 'Icons', exact: true }).check();
    await dialog.getByRole('checkbox', { name: 'Include title', exact: true }).uncheck();
    const withoutHeader = await png(page, dialog);
    assert.equal(withoutHeader.pixels.green, 0);
    assert.ok(withoutHeader.pixels.red > 100);
});

test('PNG uses authenticated atlas crops and falls back without changing grid positions', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    await atlasFixture(page, options);
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], icon: { page: 0, x: 0, y: 0 } }] };
    const dialog = await openExport(page, base);
    await dialog.getByRole('combobox', { name: 'Scale', exact: true }).selectOption('2');
    // Simulate a browser rejecting large canvas allocation through the platform API.
    const tileWidth = await page
        .locator('.crafting-plan .item')
        .first()
        .evaluate((node) => Math.round(node.getBoundingClientRect().width));
    await page.evaluate((limit) => {
        const getContext = HTMLCanvasElement.prototype.getContext;
        HTMLCanvasElement.prototype.getContext = function (...args) {
            if (this.width > limit || this.height > 132) return null;
            return getContext.apply(this, args);
        };
    }, tileWidth);
    const { pixels } = await png(page, dialog);
    assert.equal(pixels.width, tileWidth);
    assert.equal(pixels.height, 132);
    assert.ok(pixels.red > 100, 'the exported crop contains the red atlas icon');
    await dialog
        .getByRole('status')
        .filter({ hasText: /reduced/ })
        .waitFor();
});

test('restoring a saved ratio chooses columns once rather than changing them behind the control', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    options.plan = { ...readyPlan, plan: Array.from({ length: 4 }, () => ({ ...readyPlan.plan[0] })) };
    const dialog = await openExport(page, base);
    await dialog.getByRole('combobox', { name: 'Layout', exact: true }).selectOption('columns');
    await dialog.getByRole('spinbutton', { name: 'Columns', exact: true }).fill('2');
    await dialog.getByRole('combobox', { name: 'Scale', exact: true }).selectOption('2');
    const original = await png(page, dialog);
    const tileWidth = await page
        .locator('.crafting-plan .item')
        .first()
        .evaluate((node) => Math.round(node.getBoundingClientRect().width));
    assert.deepEqual([original.pixels.width, original.pixels.height], [tileWidth * 4, 464]);
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('button', { name: 'Export PNG', exact: true }).click();
    await dialog.getByRole('combobox', { name: 'Scale', exact: true }).selectOption('0.5');
    assert.equal(await dialog.getByRole('spinbutton', { name: 'Columns', exact: true }).inputValue(), '2');
    const smaller = await png(page, dialog);
    assert.deepEqual([smaller.pixels.width, smaller.pixels.height], [Math.round(tileWidth * 0.5) * 2, 116]);
});

test('rectangular export keeps all metric rows and full quantities inside each cell on mobile', async (t) => {
    const { page, options, base } = await fixture(
        t,
        '',
        { viewport: { width: 390, height: 844 } },
        { mockClock: false }
    );
    await atlasFixture(page, options);
    options.capabilities.craftingPlanSteps = true;
    options.plan = {
        ...readyPlan,
        plan: [
            {
                ...readyPlan.plan[0],
                displayName: 'Four metrics',
                missing: 5,
                requested: 48,
                steps: 6,
                stored: Number.MAX_SAFE_INTEGER,
                usedPercent: 0.95,
                icon: { page: 0, x: 0, y: 0 }
            },
            {
                ...readyPlan.plan[0],
                displayName: 'Requested quartz',
                requested: 64,
                stored: 1024,
                usedPercent: 0.1,
                icon: { page: 0, x: 64, y: 0 }
            },
            {
                ...readyPlan.plan[0],
                displayName: 'Stored silicon',
                requested: 0,
                stored: 4096,
                steps: 0,
                usedPercent: 0.4,
                icon: { page: 0, x: 0, y: 0 }
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.locator('.crafting-plan .resource-icon-ready').first().waitFor();
    if (process.env.EXPORT_EVIDENCE_DIR)
        await page.screenshot({ path: path.join(process.env.EXPORT_EVIDENCE_DIR, 'plan-source-mobile.png') });
    await page.getByRole('searchbox', { name: 'Search plan resources', exact: true }).fill('Requested quartz');
    await page.getByRole('button', { name: 'Export PNG', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export PNG', exact: true });
    await dialog.getByRole('combobox', { name: 'Layout', exact: true }).selectOption('columns');
    await dialog.getByRole('spinbutton', { name: 'Columns', exact: true }).fill('2');
    await dialog.getByRole('combobox', { name: 'Number format', exact: true }).selectOption('full');
    await page.evaluate(() => {
        window.exportNumbers = [];
        const fillText = CanvasRenderingContext2D.prototype.fillText;
        CanvasRenderingContext2D.prototype.fillText = function (text, x, y, ...rest) {
            if (text.includes('9,007,199,254,740,991') || text.includes('(95%)'))
                window.exportNumbers.push({ text, x, right: x + this.measureText(text).width });
            return fillText.call(this, text, x, y, ...rest);
        };
    });
    const result = await png(page, dialog);
    assert.equal(result.pixels.height, 272, 'unfiltered four-metric row determines 120px cells plus header');
    const values = await page.evaluate(() => window.exportNumbers);
    assert.ok(
        values.some((call) => call.text === '9,007,199,254,740,991'),
        'full quantity is not replaced by an ellipsis'
    );
    assert.ok(values.some((call) => call.text === ' (95%)'));
    assert.ok(
        values.every((call) => call.x >= 0 && call.right <= result.pixels.width / 2),
        'full quantity and suffix stay inside their cell'
    );
    if (process.env.EXPORT_EVIDENCE_DIR)
        await fs.writeFile(path.join(process.env.EXPORT_EVIDENCE_DIR, 'plan-icons-full.png'), result.bytes);
    await dialog.getByRole('checkbox', { name: 'Include title', exact: true }).uncheck();
    await dialog.getByRole('checkbox', { name: 'Name', exact: true }).uncheck();
    const compact = await png(page, dialog);
    assert.equal(compact.pixels.height, 200, 'hiding names uses compact four-metric cells');
});

test('closing export while fonts load cancels the download and permits reopening', async (t) => {
    const { page, base } = await fixture(t, '', {}, { mockClock: false });
    const dialog = await openExport(page, base);
    let downloads = 0;
    page.on('download', () => downloads++);
    await page.evaluate(() => {
        const load = document.fonts.load.bind(document.fonts);
        window.releaseExportFonts = () => {};
        const gate = new Promise((resolve) => {
            window.releaseExportFonts = resolve;
        });
        document.fonts.load = (...args) => gate.then(() => load(...args));
    });
    await dialog.getByRole('button', { name: 'Download PNG', exact: true }).click();
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('button', { name: 'Export PNG', exact: true }).click();
    await dialog.getByRole('checkbox', { name: 'Include title', exact: true }).uncheck();
    await page.evaluate(() => window.releaseExportFonts());
    await png(page, dialog);
    assert.equal(downloads, 1);
});

test('drawable icon resolution omits missing icons and settles when disabled between atlas pages', async (t) => {
    const { page, base } = await fixture(t, '', {}, { mockClock: false });
    await page.goto(base);
    const result = await page.evaluate(async () => {
        const { createIconLoader } = await import('./assets/web/app/icons.js');
        const loader = createIconLoader(
            new URL(location.href),
            () => {},
            async () => {}
        );
        const metadata = {
            packId: 'a'.repeat(64),
            width: 64,
            height: 64,
            pages: [
                { digest: 'b'.repeat(64), width: 64, height: 64 },
                { digest: 'c'.repeat(64), width: 64, height: 64 }
            ]
        };
        loader.enabled(true);
        const promise = loader.resolve(
            [
                { page: 0, x: 0, y: 0 },
                { page: 1, x: 0, y: 0 }
            ],
            metadata,
            new AbortController().signal
        );
        loader.enabled(false);
        const disabled = await promise;
        loader.enabled(true);
        const missing = await loader.resolve(
            [
                { page: 99, x: 0, y: 0 },
                { page: 0, x: 0, y: 0 }
            ],
            metadata,
            new AbortController().signal
        );
        const root = document.createElement('div');
        const target = document.createElement('div');
        target.style.cssText = 'width:64px;height:64px';
        root.append(target);
        document.body.append(root);
        const observer = loader.observe(root, () => {});
        observer.update([{ element: target, icon: { page: 0, x: 0, y: 0 } }], metadata);
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const counts = [disabled.icons.size, missing.icons.size];
        disabled.dispose();
        missing.dispose();
        loader.dispose();
        observer.dispose();
        root.remove();
        return counts;
    });
    assert.deepEqual(result, [0, 0]);
});

test('resolved sprites retain only their crop and remain drawable until explicitly released', async (t) => {
    const { page, options, base } = await fixture(t, '', {}, { mockClock: false });
    await atlasFixture(page, options);
    await page.goto(base);
    const result = await page.evaluate(async (metadata) => {
        const { createIconLoader } = await import('./assets/web/app/icons.js');
        const loader = createIconLoader(
            new URL(location.href),
            () => {},
            async () => {}
        );
        loader.enabled(true);
        const red = { page: 0, x: 0, y: 0 },
            green = { page: 0, x: 64, y: 0 };
        const resolved = await loader.resolve([red, green], metadata, new AbortController().signal);
        loader.enabled(false);
        const samples = [];
        for (const reference of [red, green]) {
            const icon = resolved.icons.get(reference);
            const canvas = document.createElement('canvas');
            canvas.width = 64;
            canvas.height = 64;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(icon.image, icon.x, icon.y, icon.width, icon.height, 0, 0, 64, 64);
            samples.push({
                source: [icon.image.width, icon.image.height],
                pixel: [...ctx.getImageData(32, 32, 1, 1).data]
            });
        }
        const images = [...resolved.icons.values()].map((icon) => icon.image);
        resolved.dispose();
        loader.dispose();
        return { samples, released: images.every((image) => image.width === 0) };
    }, options.icons);
    assert.deepEqual(result.samples, [
        { source: [64, 64], pixel: [255, 0, 0, 255] },
        { source: [64, 64], pixel: [0, 255, 0, 255] }
    ]);
    assert.equal(result.released, true);
});
