const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
    gridA,
    gridB,
    iron,
    quartz,
    cpu,
    cpuWork,
    refreshingFixture,
    fixture,
    clickTab,
    settleFrames,
    settleResponse,
    poll,
    serverState,
    atlasFixture
} = require('./fixtures.cjs');

test('history GAPs retain partial counters while stock recovers to zero', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.UTC(2026, 0, 1, 12, 0, 0);
    await page.clock.setFixedTime(now);
    const point = (age, kind, fields = {}) => ({
        flowFrom: kind === 'FLOW' || kind === 'FLOW_OBSERVATION' ? now - age - 5000 : null,
        observedAt: now - age,
        generation: '1',
        kind,
        ...fields
    });
    options.resourceHistory = {
        baseline: point(1000000, 'STOCK', { resource: { ...iron, quantity: '20' } }),
        complete: false,
        truncated: false,
        points: [
            point(451000, 'GAP'),
            point(451000, 'FLOW', { input: '9223372036854775807', output: '4' }),
            point(450000, 'STOCK', { resource: { ...iron, quantity: '0' } }),
            point(446000, 'FLOW', { input: '3', output: '9' }),
            point(441000, 'FLOW_OBSERVATION')
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    await page.evaluate(() => document.fonts.ready);
    const totals = await page.locator('.tracking-view .tracking-count-full').allTextContents();
    assert.equal(
        totals[0].replace(/,/g, ''),
        '9223372036854775810',
        'Measured partial counters remain included without rounding'
    );
    assert.equal(totals[1].replace(/,/g, ''), '13');
    const area = page.locator('.tracking-view .u-over');
    await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
    let bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.48, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.52, bounds.y + 40);
    await page.mouse.up();
    bounds = await area.boundingBox();
    for (const [position, net, stored] of [
        [0.45, '9223372036854775803', '20'],
        [0.51, '-6', '—'],
        [0.6, '-6', '0'],
        [0.72, '0', '0'],
        [0.9, '—', '0']
    ]) {
        await page.mouse.move(bounds.x + bounds.width * position, bounds.y + 40);
        const value = (name) =>
            page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('.u-value');
        assert.equal(
            (await value('Net').innerText()).replace(/,/g, ''),
            net,
            `${position}: ${await value('Time').innerText()}`
        );
        if (position === 0.45) assert.match(await value('Time').innerText(), /partial/i);
        const actualStock = await value('Stored').innerText();
        assert.ok(actualStock.startsWith(stored), `${position}: expected ${stored}, saw ${actualStock}`);
    }
});

test('sparse stock remains held between real measurements and Net keeps exact interval differences', async (t) => {
    const { page, options, base } = await fixture(t);
    // Hover fractions below address clock-aligned five-second bins in the zoomed window.
    const now = Date.UTC(2026, 0, 1, 12, 0, 0);
    await page.clock.setFixedTime(now);
    const point = (age, kind, fields = {}) => ({
        flowFrom: kind === 'FLOW' || kind === 'FLOW_OBSERVATION' ? now - age - 5000 : null,
        observedAt: now - age,
        generation: '1',
        kind,
        ...fields
    });
    options.resourceHistory = {
        baseline: point(1000000, 'STOCK', { resource: { ...iron, quantity: '20' } }),
        complete: true,
        truncated: false,
        points: [
            point(452500, 'OBSERVATION'),
            point(451000, 'FLOW', { input: '9223372036854775807', output: '9223372036854775804' }),
            point(450000, 'STOCK', { resource: { ...iron, quantity: '80' } }),
            point(446000, 'FLOW', { input: '3', output: '9' }),
            point(441000, 'FLOW_OBSERVATION')
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.waitForFunction(
        () =>
            document.querySelector('.tracking-view .uplot') &&
            document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false'
    );
    await page.evaluate(() => document.fonts.ready);
    const area = page.locator('.tracking-view .u-over');
    await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
    let bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.48, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.52, bounds.y + 40);
    await page.mouse.up();
    for (const style of ['pixel', 'smooth']) {
        await page.locator('.tracking-view select').nth(1).selectOption(style);
        bounds = await area.boundingBox();
        for (const [position, net, stored] of [
            [0.45, '3', '20'],
            [0.6, '-6', '80'],
            [0.72, '0', '80'],
            [0.9, '—', '80']
        ]) {
            await page.mouse.move(bounds.x + bounds.width * position, bounds.y + 40);
            const value = (name) =>
                page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('.u-value');
            assert.equal(await value('Net').innerText(), net);
            assert.ok((await value('Stored').innerText()).startsWith(stored));
        }
        const raster = await page.locator('.tracking-view .uplot').evaluate((root) => {
            const canvas = root.querySelector('canvas'),
                box = canvas.getBoundingClientRect(),
                plot = root.querySelector('.u-over').getBoundingClientRect();
            const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
            const scale = canvas.width / box.width;
            const goldRows = (fraction) => {
                const x = Math.round((plot.left - box.left + plot.width * fraction) * scale),
                    rows = [];
                for (let y = Math.ceil((plot.top - box.top) * scale); y < (plot.bottom - box.top) * scale; y++) {
                    const i = (y * canvas.width + x) * 4;
                    if (pixels[i] === 214 && pixels[i + 1] === 155 && pixels[i + 2] === 40 && pixels[i + 3] > 100)
                        rows.push(y);
                }
                return rows;
            };
            let markerHeight = 0;
            for (let fraction = 0.39; fraction < 0.46; fraction += 1 / plot.width)
                markerHeight = Math.max(markerHeight, goldRows(fraction).length);
            return { before: goldRows(0.3), held: goldRows(0.47), markerHeight };
        });
        assert.ok(raster.before.length > 0 && raster.held.length > 0, `${style}: ${JSON.stringify(raster)}`);
        assert.ok(Math.abs(raster.before[0] - raster.held[0]) <= 2, 'Stored stays level until the next measurement');
        assert.ok(
            raster.markerHeight > raster.before.length,
            `An unchanged real observation has a marker; synthetic carry points do not: ${JSON.stringify(raster)}`
        );
    }
});

test('Net rendering leaves unobserved periods blank in pixel and smooth modes', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.UTC(2026, 0, 1, 12, 0, 0);
    await page.clock.setFixedTime(now);
    options.resourceHistory = {
        baseline: null,
        complete: false,
        truncated: false,
        points: [
            [700000, 640000, 100],
            [640000, 580000, 200],
            [340000, 280000, 100],
            [280000, 220000, 200]
        ].map(([from, until, input]) => ({
            observedAt: now - until,
            flowFrom: now - from,
            generation: '1',
            kind: 'FLOW',
            input: String(input),
            output: '0'
        }))
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    await page.evaluate(() => document.fonts.ready);
    for (const name of ['Input', 'Output'])
        await page.locator('.tracking-view .u-legend tr').filter({ hasText: name }).locator('th').click();
    for (const mode of ['pixel', 'smooth']) {
        await page.locator('.tracking-view select').nth(1).selectOption(mode);
        const raster = await page.locator('.tracking-view .uplot').evaluate((root) => {
            const canvas = root.querySelector('canvas');
            const box = canvas.getBoundingClientRect();
            const plot = root.querySelector('.u-over').getBoundingClientRect();
            const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
            const center = ((plot.left - box.left + plot.width * 0.5) * canvas.width) / box.width;
            let blue = 0,
                inGap = 0;
            for (let y = 0; y < canvas.height; y++)
                for (let x = 0; x < canvas.width; x++) {
                    const i = (y * canvas.width + x) * 4;
                    if (
                        Math.abs(data[i] - 128) <= 2 &&
                        Math.abs(data[i + 1] - 201) <= 2 &&
                        Math.abs(data[i + 2] - 237) <= 2 &&
                        data[i + 3] > 0
                    ) {
                        blue++;
                        if (Math.abs(x - center) < 10) inGap++;
                    }
                }
            return { blue, inGap };
        });
        assert.ok(raster.blue > 20, `${mode} renders measured Net segments`);
        assert.equal(raster.inGap, 0, `${mode} must not connect across missing flow coverage`);
    }
});

test('sparse minute and short flow intervals retain their actual spans through zoom', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Date.UTC(2026, 0, 1, 12, 0, 0);
    await page.clock.setFixedTime(now);
    const flow = (from, until, input) => ({
        observedAt: now - until,
        flowFrom: now - from,
        generation: '1',
        kind: 'FLOW',
        input: String(input),
        output: '0',
        resource: { ...iron, quantity: '0' }
    });
    options.resourceHistory = {
        baseline: null,
        complete: true,
        truncated: false,
        points: [
            flow(483000, 423000, 120),
            flow(423000, 418000, 7),
            { observedAt: now - 358000, flowFrom: now - 418000, generation: '1', kind: 'FLOW_OBSERVATION' }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
    const area = page.locator('.tracking-view .u-over');
    let bounds = await area.boundingBox();
    const input = page.locator('.tracking-view .u-legend tr').filter({ hasText: 'Input' }).locator('.u-value');
    for (const [position, expected] of [
        [0.47, '120'],
        [0.52, '120'],
        [0.533, '7'],
        [0.55, '0'],
        [0.61, '—']
    ]) {
        await page.mouse.move(bounds.x + bounds.width * position, bounds.y + 40);
        assert.equal(await input.innerText(), expected);
    }
    await page.mouse.move(bounds.x + bounds.width * 0.48, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.52, bounds.y + 40);
    await page.mouse.up();
    bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.5, bounds.y + 40);
    assert.equal(await input.innerText(), '120', 'Cropping inside a minute retains its whole measured amount');
});

test('flow bars and hover span the entire aggregation interval independently of stock samples', async (t) => {
    const { page, options, base } = await fixture(t);
    const now = Math.floor(Date.now() / 30000) * 30000;
    await page.clock.setFixedTime(now);
    const stock = (age, quantity) => ({
        flowFrom: null,
        observedAt: now - age,
        generation: '1',
        kind: 'STOCK',
        resource: { ...iron, quantity: String(quantity) }
    });
    options.resourceHistory = {
        baseline: stock(1000000, 10),
        complete: true,
        truncated: false,
        points: [
            stock(454900, 20),
            stock(454000, 40),
            stock(452500, 15),
            stock(450100, 60),
            {
                flowFrom: now - 450001 - 5000,
                observedAt: now - 450001,
                generation: '1',
                kind: 'FLOW',
                input: '123',
                output: '45'
            }
        ]
    };
    await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
    await page.locator('.tracking-view canvas').waitFor();
    const area = page.locator('.tracking-view .u-over');
    await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
    let bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.48, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.52, bounds.y + 40);
    await page.mouse.up();
    bounds = await area.boundingBox();
    for (const position of [0.38, 0.49]) {
        await page.mouse.move(bounds.x + bounds.width * position, bounds.y + 40);
        await page.waitForFunction(
            () =>
                [...document.querySelectorAll('.tracking-view .u-legend tr')]
                    .find((row) => row.querySelector('th')?.textContent.includes('Input'))
                    ?.querySelector('.u-value')?.textContent === '123'
        );
        assert.equal(
            await page
                .locator('.tracking-view .u-legend tr')
                .filter({ hasText: 'Output' })
                .locator('.u-value')
                .innerText(),
            '45'
        );
    }
    const filled = await page.locator('.tracking-view .uplot').evaluate((root) => {
        const canvas = root.querySelector('canvas'),
            box = canvas.getBoundingClientRect(),
            plot = root.querySelector('.u-over').getBoundingClientRect();
        const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        return [0.38, 0.49].map((fraction) => {
            const x = Math.round(((plot.left - box.left + plot.width * fraction) * canvas.width) / box.width);
            let green = 0;
            for (let y = 0; y < canvas.height; y++) {
                const i = (y * canvas.width + x) * 4;
                if (
                    data[i] >= 80 &&
                    data[i] <= 100 &&
                    data[i + 1] >= 155 &&
                    data[i + 1] <= 175 &&
                    data[i + 2] >= 100 &&
                    data[i + 2] <= 115 &&
                    data[i + 3] > 30
                )
                    green++;
            }
            return green;
        });
    });
    assert.ok(
        filled.every((pixels) => pixels > 10),
        'The bar fills both sides of its time interval: ' + JSON.stringify(filled)
    );
    // A missing neighboring interval must never borrow the previous bucket's counters.
    await page.mouse.move(bounds.x + bounds.width * 0.7, bounds.y + 40);
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('.tracking-view .u-legend tr')]
                .find((row) => row.querySelector('th')?.textContent.includes('Input'))
                ?.querySelector('.u-value')?.textContent === '—'
    );
    await page.mouse.move(bounds.x + bounds.width * 0.38, bounds.y + 40);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * 0.49, bounds.y + 40);
    await page.mouse.up();
    bounds = await area.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * 0.5, bounds.y + 40);
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('.tracking-view .u-legend tr')]
                .find((row) => row.querySelector('th')?.textContent.includes('Input'))
                ?.querySelector('.u-value')?.textContent === '123'
    );
});

for (const touch of [false, true]) {
    test(`tracking cursor pins, moves and releases without confusing a drag with a click (${touch ? 'touch' : 'mouse'})`, async (t) => {
        const { page, options, base } = await fixture(
            t,
            '',
            touch ? { viewport: { width: 390, height: 844 }, hasTouch: true } : {}
        );
        const now = Math.floor(Date.now() / 30000) * 30000;
        await page.clock.setFixedTime(now);
        options.resourceHistory = {
            baseline: {
                flowFrom: null,
                observedAt: now - 1000000,
                generation: '1',
                kind: 'STOCK',
                resource: { ...iron, quantity: '10' }
            },
            complete: true,
            truncated: false,
            points: [675001, 225001].map((age, index) => ({
                flowFrom: now - age - 5000,
                observedAt: now - age,
                generation: '1',
                kind: 'FLOW',
                input: String(index ? 222 : 111),
                output: '0'
            }))
        };
        await page.goto(`${base}#/grids/${gridA}/tracking/iron`);
        const area = page.locator('.tracking-view .u-over');
        await page.waitForFunction(
            () =>
                document.querySelector('.tracking-view .uplot') &&
                document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false'
        );
        await page.evaluate(() => document.fonts.ready);
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        let bounds = await area.boundingBox();
        const choose = async (fraction) => {
            await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
            bounds = await area.boundingBox();
            const x = bounds.x + bounds.width * fraction,
                y = bounds.y + 40;
            if (touch) await page.touchscreen.tap(x, y);
            else await page.mouse.click(x, y);
        };
        const input = page.locator('.tracking-view .u-legend tr').filter({ hasText: 'Input' }).locator('.u-value');
        await choose(0.247);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '111', 'Selection remains after the pointer/finger leaves');
        await choose(0.747);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '222', 'A new click moves the pinned timestamp');
        await page.locator('.tracking-view select').nth(1).selectOption('smooth');
        assert.equal(await input.innerText(), '222', 'Changing rendering style preserves the pinned timestamp');
        await page.locator('#refresh').click();
        await page.waitForFunction(() => document.querySelector('#refresh')?.getAttribute('aria-busy') === 'false');
        assert.equal(await input.innerText(), '222', 'Refreshing data preserves the pinned timestamp');
        const originalViewport = page.viewportSize();
        const time = page.locator('.tracking-view .u-legend tr').filter({ hasText: 'Time' }).locator('.u-value');
        const pinnedTime = await time.innerText();
        await page.setViewportSize({ ...originalViewport, width: originalViewport.width + 40 });
        await settleFrames(page);
        assert.equal(await input.innerText(), '222');
        assert.equal(
            await time.innerText(),
            pinnedTime,
            'Resizing preserves the selected time, not its old pixel position'
        );
        await page.setViewportSize(originalViewport);
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        bounds = await area.boundingBox();
        await choose(0.747);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '—', 'Clicking the same point releases selection');
        await choose(0.247);
        await page.keyboard.press('Escape');
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '—');
        await choose(0.247);
        await page.locator('.tracking-view').getByRole('button', { name: 'Reset zoom' }).click();
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '—', 'Reset zoom also clears the pinned timestamp');
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        bounds = await area.boundingBox();
        await choose(0.747);
        const y = bounds.y + 40;
        if (touch) {
            const client = await page.context().newCDPSession(page);
            await client.send('Input.dispatchTouchEvent', {
                type: 'touchStart',
                touchPoints: [{ x: bounds.x + bounds.width * 0.2, y }]
            });
            await client.send('Input.dispatchTouchEvent', {
                type: 'touchMove',
                touchPoints: [{ x: bounds.x + bounds.width * 0.25, y }]
            });
            await client.send('Input.dispatchTouchEvent', {
                type: 'touchMove',
                touchPoints: [{ x: bounds.x + bounds.width * 0.3, y }]
            });
            await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
            await client.detach();
        } else {
            await page.mouse.move(bounds.x + bounds.width * 0.2, y);
            await page.mouse.down();
            await page.mouse.move(bounds.x + bounds.width * 0.3, y, { steps: 4 });
            await page.mouse.up();
        }
        await choose(0.47);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '111', 'Dragging while pinned zooms instead of clicking another point');
        if (!touch) {
            await page.mouse.dblclick(bounds.x + bounds.width * 0.5, bounds.y + 40);
            await page.mouse.move(1, 1);
            assert.equal(await input.innerText(), '—');
        } else await page.locator('.tracking-view').getByRole('button', { name: 'Reset zoom' }).click();
        await page.locator('.tracking-view .tracking-chart').scrollIntoViewIfNeeded();
        bounds = await area.boundingBox();
        await choose(0.747);
        await page.mouse.move(1, 1);
        assert.equal(await input.innerText(), '222', 'Reset restores the original time range');
        if (!touch) {
            await page.mouse.move(bounds.x + bounds.width * 0.2, y);
            await page.mouse.down();
            await page.mouse.move(bounds.x + bounds.width * 0.3, y);
            await page.mouse.move(bounds.x + bounds.width * 0.2, y);
            await page.mouse.up();
            await page.mouse.move(1, 1);
            assert.equal(await input.innerText(), '—', 'A drag returning to its origin must not become a click');
        }
    });
}

test('recent tracking shows independent measured periods and does not advance stock time with flow updates', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const now = Date.now();
    options.liveView = { generation: '1', revision: '1' };
    options.recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: now - 60000,
        flowSince: now - 30000,
        stockSince: now - 360000,
        flowComplete: true,
        stockComplete: true,
        resources: { iron: { input: '100', output: '10', stockChange: '42' } }
    };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    const item = page.locator('#items button').filter({ hasText: 'Iron' }).first();
    await item.hover();
    const tooltip = page.locator('#resource-tooltip');
    await tooltip.locator('.recent-flow').waitFor();
    assert.match(await tooltip.locator('.recent-flow').innerText(), /\(2 min\)/);
    assert.match(await tooltip.locator('.recent-stock').innerText(), /\(1 h\)/);
    assert.match(await tooltip.locator('.recent-stock-period').innerText(), /5 min/);
    assert.match(await tooltip.locator('.recent-flow-period').innerText(), /30 s/);
    const sampledAt = new Date(now - 60000).toISOString();
    assert.equal(await tooltip.locator('time').getAttribute('datetime'), sampledAt);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [], recent: { ...options.recent, observedAt: now + 30000, flowObservedAt: now + 30000, resources: { iron: { input: '200', output: '10', stockChange: '42' } } } })}\n\n`
    );
    await tooltip.locator('[aria-label="+200"]').waitFor();
    assert.equal(await tooltip.locator('time').getAttribute('datetime'), sampledAt);
    assert.match(await tooltip.locator('.recent-stock-period').innerText(), /5 min/);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 0);
    await item.click();
    const details = page.locator('#details');
    await details.locator('.recent-stock').waitFor();
    assert.match(await details.locator('.recent-stock').innerText(), /\(1 h\)/);
    assert.equal(await details.locator('time').getAttribute('datetime'), sampledAt);
});

test('live quantities update immediately while terminal order uses a fixed thirty-second window and snapshots sort immediately', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    await page.addInitScript(() => {
        window.deltaDurations = [];
        const add = EventSource.prototype.addEventListener;
        EventSource.prototype.addEventListener = function (name, listener, options) {
            if (name !== 'delta') return add.call(this, name, listener, options);
            return add.call(
                this,
                name,
                (event) => {
                    const start = performance.now();
                    listener(event);
                    window.deltaDurations.push(performance.now() - start);
                },
                options
            );
        };
    });
    options.liveView = { generation: '1', revision: '1' };
    options.itemsA = Array.from({ length: 500 }, (_, index) => ({
        ...iron,
        itemKey: `resource-${index}`,
        displayName: `Resource ${String(index).padStart(3, '0')}`,
        quantity: 20000
    }));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#refresh .live-icon').waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    const target = page.locator('#items button').first();
    await target.focus();
    await page.getByRole('tooltip').waitFor();
    const before = await page.locator('#items strong').allTextContents();
    await page.evaluate(() => {
        window.resourceContentChanges = [];
        new MutationObserver((records) => {
            for (const record of records) {
                if (record.type === 'childList' || record.type === 'characterData')
                    window.resourceContentChanges.push(record.target.textContent);
            }
        }).observe(document.querySelector('#items'), { subtree: true, childList: true, characterData: true });
    });
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '1', revision: '2' },
            updates: [{ itemKey: 'resource-0', quantity: 20001, craftable: true }],
            definitions: [],
            removed: []
        })}\n\n`
    );
    await page.getByRole('tooltip').getByText('Stored: 20,001', { exact: true }).waitFor();
    assert.deepEqual(
        await page.locator('#items strong').allTextContents(),
        before,
        'Live quantities do not immediately move resources'
    );
    await page.clock.fastForward(20000);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '1', revision: '3' },
            updates: [{ itemKey: 'resource-0', quantity: 20002, craftable: true }],
            definitions: [],
            removed: []
        })}\n\n`
    );
    await page.getByRole('tooltip').getByText('Stored: 20,002', { exact: true }).waitFor();
    assert.deepEqual(await page.locator('#items strong').allTextContents(), before);
    await page.clock.fastForward(15000);
    await page.waitForFunction(() => document.querySelector('#items strong')?.textContent === 'Resource 001');
    assert.deepEqual(await page.locator('#items strong').allTextContents(), [...before.slice(1), before[0]]);
    assert.equal(await page.evaluate(() => document.activeElement?.textContent.includes('Resource 000')), true);
    await page.getByRole('tooltip').getByText('Resource 000', { exact: true }).waitFor();
    await page.getByRole('tooltip').getByText('Stored: 20,002', { exact: true }).waitFor();
    const mutations = await page.evaluate(() => window.resourceContentChanges.length);
    t.diagnostic(`Resource content mutations for one quantity update among 500 rows: ${mutations}`);
    t.diagnostic(`SSE callback duration (ms): ${await page.evaluate(() => window.deltaDurations.join(', '))}`);
    assert.ok(mutations < 10, 'A delta must not replace the unchanged names and quantities of the entire inventory');
    options.itemsA[0] = { ...options.itemsA[0], quantity: 1 };
    options.liveView = { generation: '1', revision: '4' };
    const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/items'));
    await page.locator('#refresh').click();
    await refreshed;
    await page.waitForFunction(() => document.querySelector('#items strong')?.textContent === 'Resource 000');
    await page.locator('#items button').first().getByText('1', { exact: true }).waitFor();
});

test('live additions append and removed resources remain inert zero slots until reordering or return', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    options.itemsA = ['Alpha', 'Bravo', 'Charlie'].map((displayName, index) => ({
        ...iron,
        itemKey: displayName,
        displayName,
        quantity: (index + 1) * 10
    }));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#refresh .live-icon').waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    const names = () => page.locator('#items strong').allTextContents();
    const bravo = page.locator('#items button').filter({ hasText: 'Bravo' });
    await bravo.evaluate((button) => {
        window.originalBravo = button;
    });
    await bravo.click({ button: 'right' });
    await page.getByRole('menu').waitFor();
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '1', revision: '2' },
            updates: [{ itemKey: 'Alpha', quantity: 40, craftable: true }],
            definitions: [{ ...iron, itemKey: 'Aaron', displayName: 'Aaron', quantity: 5 }],
            removed: ['Bravo']
        })}\n\n`
    );
    await page.locator('#items button').filter({ hasText: 'Aaron' }).waitFor();
    assert.deepEqual(await names(), ['Alpha', 'Bravo', 'Charlie', 'Aaron']);
    await bravo.getByText('0', { exact: true }).waitFor();
    assert.equal(
        await page.getByRole('menuitem', { name: 'Craft', exact: true }).isDisabled(),
        true,
        'A removed resource cannot retain a stale craft action'
    );
    assert.equal(await page.getByRole('menuitem', { name: 'View tracking', exact: true }).isEnabled(), true);
    await page.keyboard.press('Escape');
    const historyReads = options.requests.filter((request) => request.path.endsWith('/resource-history')).length;
    await bravo.click();
    assert.equal(await bravo.getAttribute('aria-pressed'), 'false');
    assert.equal(
        options.requests.filter((request) => request.path.endsWith('/resource-history')).length,
        historyReads,
        'Ghost clicks do not publish synthetic selections'
    );
    await bravo.focus();
    await page.getByRole('tooltip').getByText('Stored: 0', { exact: true }).waitFor();
    await page.clock.fastForward(10000);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '1', revision: '3' },
            updates: [],
            definitions: [{ ...options.itemsA[1], quantity: 25 }],
            removed: ['Charlie']
        })}\n\n`
    );
    await bravo.getByText('25', { exact: true }).waitFor();
    assert.equal(await bravo.evaluate((button) => button === window.originalBravo), true);
    assert.deepEqual(await names(), ['Alpha', 'Bravo', 'Charlie', 'Aaron']);
    await page.clock.fastForward(25000);
    await page.waitForFunction(() => document.querySelectorAll('#items button').length === 3);
    assert.deepEqual(await names(), ['Aaron', 'Bravo', 'Alpha']);
});

test('explicit null unkeyed payloads retain initial anonymous rows through keyed recent and ordering updates', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    options.itemsA = [
        ...Array.from({ length: 600 }, (_, index) => ({
            ...iron,
            itemKey: `resource-${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`,
            quantity: index + 100
        })),
        { ...iron, itemKey: null, displayName: 'Anonymous resource', quantity: 1, craftable: false }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#refresh .live-icon').waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    const anonymous = page.locator('#items button').filter({ hasText: 'Anonymous resource' });
    await anonymous.evaluate((button) => {
        window.initialAnonymous = button;
    });
    await anonymous.focus();
    const send = (revision, data) =>
        options.itemStreams[0].write(
            `event: delta\ndata: ${JSON.stringify({
                view: { generation: '1', revision },
                definitions: [],
                removed: [],
                updates: [],
                unkeyed: null,
                ...data
            })}\n\n`
        );
    send('2', { updates: [{ itemKey: 'resource-0', quantity: 900, craftable: true }] });
    await page.locator('#items button').filter({ hasText: 'Resource 000' }).getByText('900', { exact: true }).waitFor();
    assert.equal(
        await anonymous.evaluate((button) => button === window.initialAnonymous && button === document.activeElement),
        true
    );
    await page.locator('#refresh').focus();
    const previousEvent = await page.getByRole('tooltip').locator('time').getAttribute('datetime');
    await page.clock.fastForward(1000);
    send('3', {
        recent: {
            flowWindowMillis: 120000,
            stockWindowMillis: 3600000,
            bucketMillis: 5000,
            observedAt: Date.now(),
            flowObservedAt: Date.now(),
            stockObservedAt: null,
            flowSince: Date.now() - 120000,
            stockSince: null,
            flowComplete: true,
            stockComplete: false,
            resources: {}
        }
    });
    await page.waitForFunction(
        (previous) => document.querySelector('#resource-tooltip time')?.dateTime !== previous,
        previousEvent
    );
    assert.equal(await anonymous.evaluate((button) => button === window.initialAnonymous), true);
    assert.equal(await page.locator('#items button').count(), 500);
    await page.clock.fastForward(30000);
    assert.equal(await anonymous.evaluate((button) => button === window.initialAnonymous), true);
    assert.equal(await page.locator('#items strong').first().textContent(), 'Anonymous resource');
    send('4', { unkeyed: [] });
    await anonymous.waitFor({ state: 'detached' });
    assert.equal(await page.locator('#items button').count(), 500);
});

test('replacement unkeyed payloads keep occupied anonymous positions on a paginated live inventory', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    const unknown = (displayName, quantity) => ({ ...iron, itemKey: null, displayName, quantity, craftable: false });
    options.itemsA = [
        ...Array.from({ length: 600 }, (_, index) => ({
            ...iron,
            itemKey: `resource-${index}`,
            displayName: `Resource ${String(index).padStart(3, '0')}`,
            quantity: index + 100
        })),
        unknown('Unknown first', 99),
        unknown('Unknown middle', 350)
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#refresh .live-icon').waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    const names = () => page.locator('#items strong').allTextContents();
    const original = await names();
    assert.equal(original.length, 500);
    const firstSlot = original.indexOf('Unknown first');
    const middleSlot = original.indexOf('Unknown middle');
    assert.ok(firstSlot >= 0 && middleSlot > firstSlot);
    const oldUnknown = page.locator('#items button').filter({ hasText: 'Unknown first' });
    await oldUnknown.evaluate((button) => {
        window.previousAnonymous = button;
    });
    await oldUnknown.focus();
    const send = (revision, data) =>
        options.itemStreams[0].write(
            `event: delta\ndata: ${JSON.stringify({
                view: { generation: '1', revision },
                definitions: [],
                removed: [],
                updates: [],
                ...data
            })}\n\n`
        );
    send('2', {
        updates: [{ itemKey: 'resource-0', quantity: 777, craftable: true }],
        unkeyed: [unknown('Replacement first', 900), unknown('Replacement middle', 901)]
    });
    await page.locator('#items button').filter({ hasText: 'Resource 000' }).getByText('777', { exact: true }).waitFor();
    const replaced = await names();
    assert.equal(
        replaced[firstSlot],
        'Replacement first',
        'A fresh anonymous payload must not move visible unknown resources beyond the current page'
    );
    assert.equal(replaced[middleSlot], 'Replacement middle');
    assert.deepEqual(
        replaced.filter((name) => name.startsWith('Resource ')),
        original.filter((name) => name.startsWith('Resource '))
    );
    assert.equal(
        await page.evaluate(() => window.previousAnonymous.isConnected),
        false,
        'A replacement is a fresh resource, not an invented identity match'
    );
    assert.equal(await page.getByRole('tooltip').isVisible(), false);
    send('3', { updates: [{ itemKey: 'resource-1', quantity: 778, craftable: true }] });
    await page.locator('#items button').filter({ hasText: 'Resource 001' }).getByText('778', { exact: true }).waitFor();
    assert.deepEqual(await names(), replaced);
    await page.locator('#refresh').focus();
    const eventTime = await page.getByRole('tooltip').locator('time').getAttribute('datetime');
    await page.clock.fastForward(1000);
    send('4', {
        recent: {
            flowWindowMillis: 120000,
            stockWindowMillis: 3600000,
            bucketMillis: 5000,
            observedAt: Date.now(),
            flowObservedAt: Date.now(),
            stockObservedAt: null,
            flowSince: Date.now() - 120000,
            stockSince: null,
            flowComplete: true,
            stockComplete: false,
            resources: {}
        }
    });
    await page.waitForFunction(
        (previous) => document.querySelector('#resource-tooltip time')?.dateTime !== previous,
        eventTime
    );
    assert.deepEqual(await names(), replaced, 'A recent-only event keeps anonymous resources on the same page');
    send('5', { unkeyed: [unknown('Only unknown', 902)] });
    await page.locator('#items button').filter({ hasText: 'Only unknown' }).waitFor();
    assert.equal((await names())[firstSlot], 'Only unknown');
    assert.equal(
        await page
            .locator('#items strong')
            .filter({ hasText: /Replacement/ })
            .count(),
        0
    );
    send('6', { unkeyed: [unknown('Unknown one', 903), unknown('Unknown two', 904), unknown('Unknown three', 905)] });
    await page.locator('#items button').filter({ hasText: 'Unknown one' }).waitFor();
    assert.equal((await names())[firstSlot], 'Unknown one');
    assert.equal(
        await page
            .locator('#items strong')
            .filter({ hasText: /Unknown (two|three)/ })
            .count(),
        0
    );
    await page.getByRole('button', { name: 'Next page', exact: true }).click();
    assert.deepEqual(
        (await names()).slice(-2),
        ['Unknown two', 'Unknown three'],
        'Only surplus anonymous resources append to the held list'
    );
    send('7', { unkeyed: [] });
    await page.locator('#items strong').filter({ hasText: 'Unknown two' }).waitFor({ state: 'detached' });
    assert.equal(await page.locator('#items button').count(), 100);
    await page.getByRole('button', { name: 'Previous page', exact: true }).click();
    assert.equal(
        await page
            .locator('#items strong')
            .filter({ hasText: /Unknown/ })
            .count(),
        0
    );
});

for (const { sort, direction, expected } of [
    { sort: 'quantity', direction: 'ascending', expected: ['Y lowest', 'Z low', 'M middle', 'A high', 'B highest'] },
    { sort: 'quantity', direction: 'descending', expected: ['B highest', 'A high', 'M middle', 'Z low', 'Y lowest'] },
    { sort: 'name', direction: 'ascending', expected: ['A high', 'B highest', 'M middle', 'Y lowest', 'Z low'] },
    { sort: 'name', direction: 'descending', expected: ['Z low', 'Y lowest', 'M middle', 'B highest', 'A high'] }
]) {
    test(`anonymous replacements and surplus use current ${sort} ${direction} order before refilling slots`, async (t) => {
        const { page, options, base } = await refreshingFixture(t);
        options.liveView = { generation: '1', revision: '1' };
        const anonymous = (displayName, quantity) => ({
            ...iron,
            itemKey: null,
            displayName,
            quantity,
            craftable: false
        });
        const incoming = [
            anonymous('Y lowest', 60),
            anonymous('Z low', 70),
            anonymous('M middle', 80),
            anonymous('A high', 90),
            anonymous('B highest', 100)
        ];
        options.itemsA = [
            { ...iron, itemKey: 'key-a', displayName: 'Keyed A', quantity: 10 },
            { ...iron, itemKey: 'key-b', displayName: 'Keyed B', quantity: 20 },
            anonymous('Old anonymous 1', 5),
            anonymous('Old anonymous 2', 15)
        ];
        await page.goto(`${base}#/grids/${gridA}/items`);
        await page.locator('#refresh .live-icon').waitFor();
        if (sort === 'quantity') await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
        if (direction === 'descending')
            await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
        const before = await page.locator('#items strong').allTextContents();
        await page
            .locator('#items button')
            .filter({ hasText: 'Old anonymous' })
            .evaluateAll((buttons) => {
                window.oldAnonymous = buttons;
            });
        await page.locator('#items button').filter({ hasText: 'Old anonymous' }).first().focus();
        options.itemStreams[0].write(
            `event: delta\ndata: ${JSON.stringify({
                view: { generation: '1', revision: '2' },
                updates: [],
                removed: [],
                definitions: [
                    { ...iron, itemKey: 'new-z', displayName: 'Keyed new Z', quantity: 30 },
                    { ...iron, itemKey: 'new-a', displayName: 'Keyed new A', quantity: 1 }
                ],
                unkeyed: [...expected].reverse().map((name) => incoming.find((item) => item.displayName === name))
            })}\n\n`
        );
        await page.locator('#items button').filter({ hasText: 'Keyed new Z' }).waitFor();
        let position = 0;
        const held = before.map((name) => (name.startsWith('Old anonymous') ? expected[position++] : name));
        assert.deepEqual(await page.locator('#items strong').allTextContents(), [
            ...held,
            'Keyed new Z',
            'Keyed new A',
            ...expected.slice(2)
        ]);
        assert.equal(await page.evaluate(() => window.oldAnonymous.every((button) => !button.isConnected)), true);
        assert.equal(await page.getByRole('tooltip').isVisible(), false);
    });
}

test('unkeyed rows retain only object identity while explicit controls immediately recompute the held view', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    options.itemsA = [
        { ...iron, itemKey: 'alpha', displayName: 'Alpha', quantity: 10 },
        { ...iron, itemKey: 'bravo', displayName: 'Bravo', quantity: 20 },
        { ...iron, itemKey: null, displayName: 'Unknown', quantity: 15 }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#refresh .live-icon').waitFor();
    await page.getByRole('button', { name: 'Sort by: Name', exact: true }).click();
    const names = () => page.locator('#items strong').allTextContents();
    const unknown = page.locator('#items button').filter({ hasText: 'Unknown' });
    await unknown.evaluate((button) => {
        window.originalUnknown = button;
    });
    await unknown.focus();
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '1', revision: '2' },
            definitions: [],
            removed: [],
            updates: [{ itemKey: 'alpha', quantity: 40, craftable: true }]
        })}\n\n`
    );
    await page.locator('#items button').filter({ hasText: 'Alpha' }).getByText('40', { exact: true }).waitFor();
    assert.deepEqual(await names(), ['Alpha', 'Unknown', 'Bravo']);
    assert.equal(
        await unknown.evaluate((button) => button === window.originalUnknown && button === document.activeElement),
        true
    );
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '1', revision: '3' },
            definitions: [],
            removed: ['bravo'],
            updates: [],
            unkeyed: [{ ...options.itemsA[2], displayName: 'Replacement', quantity: 5 }]
        })}\n\n`
    );
    await page.locator('#items button').filter({ hasText: 'Replacement' }).waitFor();
    assert.deepEqual(await names(), ['Alpha', 'Replacement', 'Bravo']);
    await page.getByRole('button', { name: 'Availability: All', exact: true }).click();
    assert.deepEqual(
        await names(),
        ['Replacement', 'Alpha'],
        'A filter change removes ghosts and recomputes immediately'
    );
    await page.getByRole('button', { name: 'Sort order: Ascending', exact: true }).click();
    assert.deepEqual(await names(), ['Alpha', 'Replacement']);
    const search = page.getByRole('searchbox', { name: 'Search resources', exact: true });
    await search.fill('Alpha');
    assert.deepEqual(await names(), ['Alpha']);
    await search.fill('');
    assert.deepEqual(await names(), ['Alpha', 'Replacement']);
});

test('ghost expiry reveals the empty state and denied reads clear ghosts immediately', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    options.itemsA = [iron];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#refresh .live-icon').waitFor();
    const item = page.locator('#items button').first();
    await item.focus();
    const remove = (revision) =>
        options.itemStreams.at(-1).write(
            `event: delta\ndata: ${JSON.stringify({
                view: { generation: '1', revision },
                definitions: [],
                updates: [],
                removed: [iron.itemKey]
            })}\n\n`
        );
    remove('2');
    await page.getByRole('tooltip').getByText('Stored: 0', { exact: true }).waitFor();
    assert.equal(
        await page.locator('#item-message').textContent(),
        '',
        'The empty message must wait for the final ghost to disappear'
    );
    await page.clock.fastForward(30000);
    assert.equal(await page.locator('#items button').count(), 0);
    assert.equal(await page.getByRole('tooltip').isVisible(), false);
    assert.notEqual(await page.locator('#item-message').textContent(), '');
    options.liveView = { generation: '1', revision: '3' };
    await page.locator('#refresh').click();
    await item.waitFor();
    await page.locator('#refresh .live-icon').waitFor();
    await item.focus();
    remove('4');
    await page.getByRole('tooltip').getByText('Stored: 0', { exact: true }).waitFor();
    options.status = 403;
    await page.locator('#refresh').click();
    await item.waitFor({ state: 'detached' });
    await page.goto(`${base}#/web-settings`);
    await page.clock.fastForward(60000);
    assert.equal(
        await page.locator('#items button').count(),
        0,
        'An old ordering timer cannot restore denied data after navigation'
    );
});

test('flow-only SSE updates and expiry refresh hovered summaries without history reads', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    const now = Date.now();
    options.liveView = { generation: '1', revision: '1' };
    options.recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: null,
        flowSince: now - 120000,
        stockSince: null,
        flowComplete: true,
        stockComplete: false,
        resources: { iron: { input: '100', output: '100', stockChange: null } }
    };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    await page.locator('#items button').filter({ hasText: 'Iron' }).first().hover();
    await page.locator('#resource-tooltip [aria-label="+100"]').waitFor();
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [], recent: { ...options.recent, observedAt: now + 120000, resources: { iron: { input: '0', output: '0', stockChange: null } } } })}\n\n`
    );
    await page.locator('#resource-tooltip [aria-label="−0"]').waitFor();
    assert.equal(await page.locator('#resource-tooltip [aria-label="+100"]').count(), 0);
    assert.equal(options.requests.filter((request) => request.path.endsWith('/resource-history')).length, 0);
});

test('live resync replaces only this tab subscription through reload and cross-grid navigation', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '', {}, { mockClock: false });
    options.liveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#refresh .live-icon').waitFor();
    const resync = async (revision, action) => {
        options.liveView = { generation: '1', revision: String(revision) };
        const request = page.waitForRequest((request) => new URL(request.url()).pathname.endsWith('/items'));
        await action();
        const query = new URL((await request).url()).searchParams;
        await page.locator('#refresh .live-icon').waitFor();
        return query;
    };
    assert.equal((await resync(2, () => page.locator('#refresh').click())).get('replace'), 'subscription-1');
    assert.equal((await resync(3, () => page.reload())).get('replace'), 'subscription-2');
    assert.equal((await resync(4, () => page.goto(`${base}#/grids/${gridB}/items`))).get('replace'), 'subscription-3');
    assert.equal((await resync(5, () => page.goto(`${base}#/grids/${gridA}/items`))).get('replace'), 'subscription-4');
    options.liveView = { generation: '1', revision: '6' };
    const otherRequest = page.context().waitForEvent('request', {
        predicate: (request) => new URL(request.url()).pathname.endsWith('/items')
    });
    const opened = page.waitForEvent('popup');
    await page.evaluate((url) => window.open(url), `${base}#/grids/${gridA}/items`);
    const other = await opened;
    t.after(() => other.close());
    assert.equal(
        new URL((await otherRequest).url()).searchParams.has('replace'),
        false,
        'An opener-cloned tab must not retire the original tab'
    );
    await other.locator('#refresh .live-icon').waitFor();
});

test('terminal heartbeats renew a bounded lease without refreshing inventory or data time and failures recover', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/grids/${gridA}/items`);
    const live = page.locator('#refresh .live-icon');
    await live.waitFor();
    await page.locator('#refresh').focus();
    const eventTime = await page.getByRole('tooltip').locator('time').getAttribute('datetime');
    const reads = options.requests.filter((request) => request.path.endsWith('/items')).length;
    const renewals = () =>
        options.requests.filter((request) => request.path.endsWith('/items/events') && request.method === 'POST');
    await page.clock.fastForward(30000);
    const renewed = page.waitForResponse(
        (response) => response.url().includes('/items/events') && response.request().method() === 'POST'
    );
    options.itemStreams[0].write('event: heartbeat\ndata: {}\n\n');
    const response = await renewed;
    assert.equal(new URL(response.url()).searchParams.get('subscription'), 'subscription-1');
    assert.equal(response.request().headers()['x-ae2-request'], 'true');
    options.itemStreams[0].write('event: heartbeat\ndata: {}\n\nevent: heartbeat\ndata: {}\n\n');
    await page.waitForTimeout(100);
    assert.equal(renewals().length, 1, 'Repeated heartbeats share the renewal interval');
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, reads);
    assert.equal(await page.getByRole('tooltip').locator('time').getAttribute('datetime'), eventTime);
    options.renewalStatus = 400;
    await page.clock.fastForward(30000);
    options.itemStreams[0].write('event: heartbeat\ndata: {}\n\n');
    await live.waitFor({ state: 'hidden' });
    assert.equal(renewals().length, 2);
    options.renewalStatus = 200;
    const resumed = page.waitForResponse(
        (response) => response.url().includes('/items/events') && response.request().method() === 'GET'
    );
    await page.clock.fastForward(2000);
    await resumed;
    await live.waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, reads + 1);
    options.renewalDelay = 1000;
    await page.clock.fastForward(30000);
    const renewing = page.waitForRequest(
        (request) => request.url().includes('/items/events') && request.method() === 'POST'
    );
    options.itemStreams[0].write('event: heartbeat\ndata: {}\n\n');
    const pending = await renewing;
    const aborted = page.waitForEvent('requestfailed', { predicate: (request) => request === pending });
    await page.goto(`${base}#/web-settings`);
    await aborted;
    const afterClose = renewals().length;
    await page.clock.fastForward(120000);
    assert.equal(renewals().length, afterClose, 'A closed stream cannot keep renewing its lease');
});

test('header confirms live events and manual resync waits for a new stream before showing live', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    options.delayStreamReady = true;
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    const control = page.locator('#refresh');
    const dot = control.locator('.live-icon');
    assert.equal(await dot.isVisible(), false, 'A subscription is not a confirmed live connection');
    const initialBounds = await control.boundingBox();
    const frame = () =>
        control.evaluate((button) => {
            const style = getComputedStyle(button);
            return { background: style.backgroundColor, border: style.borderTopColor, shadow: style.boxShadow };
        });
    const initialFrame = await frame();
    options.itemStreams[0].write('event: ready\ndata: {}\n\n');
    await dot.waitFor({ state: 'visible' });
    assert.deepEqual(await control.boundingBox(), initialBounds);
    assert.deepEqual(await frame(), initialFrame, 'The live control retains its normal button frame');
    await control.focus();
    const time = page.locator('#resource-tooltip time');
    await time.waitFor();
    const firstEvent = await time.getAttribute('datetime');
    await page.clock.fastForward(1000);
    options.itemStreams[0].write(': keepalive\n\n');
    assert.equal(await time.getAttribute('datetime'), firstEvent);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({ view: { generation: '1', revision: '2' }, updates: [], definitions: [], removed: [], icons: null })}\n\n`
    );
    await page.waitForFunction(
        (previous) => document.querySelector('#resource-tooltip time')?.dateTime !== previous,
        firstEvent
    );
    const reads = options.requests.filter((request) => request.path.endsWith('/items')).length;
    const reconnect = page.waitForResponse((response) => response.url().includes('/items/events'));
    await control.click();
    await reconnect;
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, reads + 1);
    assert.equal(await dot.isVisible(), false);
    assert.equal(await control.getAttribute('aria-busy'), 'true');
    options.itemStreams.at(-1).write('event: ready\ndata: {}\n\n');
    await dot.waitFor({ state: 'visible' });
    options.itemStreams.at(-1).end();
    await dot.waitFor({ state: 'hidden' });
    await page.locator('a[data-view="web-settings"]').click();
    assert.equal(await dot.isVisible(), false);
    await page.locator('#auto-refresh').uncheck();
    await page.locator('#terminal-link').click();
    await page.locator('#items button').first().waitFor();
    assert.equal(await dot.isVisible(), false);
    assert.equal(await control.getAttribute('aria-busy'), 'false');
    const refresh = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/items'));
    await control.click();
    const snapshot = await refresh;
    assert.equal(new URL(snapshot.url()).searchParams.has('live'), false);
    assert.equal(options.itemStreams.length, 0);
});

test('live status can be focused and resynced by touch without changing header geometry', async (t) => {
    const { page, options, base } = await refreshingFixture(t, '', {
        viewport: { width: 390, height: 844 },
        hasTouch: true
    });
    options.liveView = { generation: '1', revision: '1' };
    options.delayStreamReady = true;
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    const control = page.locator('#refresh');
    const before = await control.boundingBox();
    options.itemStreams[0].write('event: ready\ndata: {}\n\n');
    await control.locator('.live-icon').waitFor({ state: 'visible' });
    assert.deepEqual(await control.boundingBox(), before);
    await control.focus();
    await page.getByRole('tooltip').locator('time').waitFor();
    const reconnected = page.waitForResponse((response) => response.url().includes('/items/events'));
    await control.tap();
    await reconnected;
    assert.equal(await control.locator('.live-icon').isVisible(), false);
    assert.deepEqual(await control.boundingBox(), before);
});

test('terminal applies compact updates, fetches a fresh snapshot on reconnect and stops outside terminal', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '9223372036854775000', revision: '1' };
    const opened = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.goto(`${base}#/grids/${gridA}/items`);
    await opened;
    await page.locator('#items button').first().waitFor();
    const initialReads = options.requests.filter((request) => request.path.endsWith('/items')).length;
    options.itemStreams[0].write(
        `event: delta\nid: 9223372036854775000:2\ndata: ${JSON.stringify({
            view: { generation: '9223372036854775000', revision: '2' },
            updates: [{ itemKey: iron.itemKey, quantity: 17, craftable: iron.craftable }],
            definitions: [{ ...iron, displayName: 'Live updated iron', quantity: 17 }],
            removed: [],
            unkeyed: [{ ...iron, itemKey: null, displayName: 'Live essentia', quantity: 3 }],
            icons: null
        })}\n\n`
    );
    await page.getByText('Live updated iron', { exact: true }).waitFor();
    await page.getByText('Live essentia', { exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, initialReads);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '9223372036854775000', revision: '3' },
            updates: [{ itemKey: iron.itemKey, quantity: 23, craftable: iron.craftable }],
            definitions: [],
            removed: []
        })}\n\n`
    );
    await page
        .locator('#items button')
        .filter({ hasText: 'Live updated iron' })
        .getByText('23', { exact: true })
        .waitFor();
    assert.equal(await page.getByText('Live essentia', { exact: true }).isVisible(), true);
    options.itemStreams[0].write(
        `event: delta\ndata: ${JSON.stringify({
            view: { generation: '9223372036854775000', revision: '4' },
            updates: [],
            definitions: [],
            removed: [iron.itemKey],
            unkeyed: [],
            icons: null
        })}\n\n`
    );
    await page
        .locator('#items button')
        .filter({ hasText: 'Live updated iron' })
        .getByText('0', { exact: true })
        .waitFor();
    await page.clock.fastForward(30000);
    await page.getByText('Live updated iron', { exact: true }).waitFor({ state: 'hidden' });
    await page.getByText('Live essentia', { exact: true }).waitFor({ state: 'hidden' });
    options.liveView = { generation: '9223372036854775000', revision: '4' };
    options.itemsA = [{ ...iron, displayName: 'Fresh snapshot iron' }];
    const reconnected = page.waitForResponse((response) => response.url().includes('subscription=subscription-4'));
    options.itemStreams[0].end();
    await reconnected;
    await page.getByText('Fresh snapshot iron', { exact: true }).waitFor();
    assert.equal(options.requests.filter((request) => request.path.endsWith('/items')).length, initialReads + 1);
    options.liveView = { generation: '9223372036854775000', revision: '5' };
    options.itemsA = [{ ...iron, displayName: 'Reset snapshot iron' }];
    options.itemStreams[0].write('event: invalidate\ndata: {}\n\n');
    await page.getByText('Reset snapshot iron', { exact: true }).waitFor();
    await page.goto(`${base}#/web-settings`);
    await page.locator('#auto-refresh').uncheck();
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByText('Reset snapshot iron', { exact: true }).waitFor();
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(options.itemStreams.length, 0);
});

test('terminal keeps its layout through a dropped stream and retains recovery errors until SSE is ready', async (t) => {
    const { page, options, base } = await refreshingFixture(t);
    options.liveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/grids/${gridA}/items`);
    const live = page.locator('#refresh .live-icon');
    await live.waitFor({ state: 'visible' });
    const row = page.locator('#items button').first();
    await row.click();
    const items = page.locator('#items');
    const count = await items.locator('button').count();
    const bounds = await items.boundingBox();
    const selected = await page.locator('#items button[aria-pressed="true"]').innerText();
    const message = page.locator('#item-message');
    let failSnapshot = true;
    await page.route('**/api/grids/*/items?*', async (route) => {
        if (!failSnapshot) return route.continue();
        await route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: JSON.stringify({ status: 'SERVER_BUSY', data: null })
        });
    });
    options.itemStreams.at(-1).end();
    await live.waitFor({ state: 'hidden' });
    assert.equal(await message.innerText(), '');
    assert.deepEqual(await items.boundingBox(), bounds, 'Disconnect does not move the resource grid');
    assert.equal(await page.locator('#refresh').getAttribute('aria-busy'), 'true');
    await page.clock.fastForward(2100);
    await page.waitForFunction(() => document.querySelector('#item-message')?.textContent.length > 0);
    assert.equal(await items.locator('button').count(), count);
    assert.equal(await page.locator('#items button[aria-pressed="true"]').innerText(), selected);
    const failedMessage = await message.innerText();
    failSnapshot = false;
    options.delayStreamReady = true;
    const retry = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.locator('#refresh').click();
    await retry;
    assert.equal(await message.innerText(), failedMessage, 'Snapshot success does not clear a stream recovery error');
    options.itemStreams.at(-1).end();
    await page.waitForFunction((previous) => {
        const text = document.querySelector('#item-message')?.textContent;
        return text && text !== previous;
    }, failedMessage);
    assert.equal(await items.locator('button').count(), count);
    const recovered = page.waitForResponse((response) => response.url().includes('/items/events'));
    await page.clock.fastForward(2100);
    await recovered;
    assert.notEqual(await message.innerText(), '');
    options.itemStreams.at(-1).write('event: ready\ndata: {}\n\n');
    await live.waitFor({ state: 'visible' });
    assert.equal(await message.innerText(), '');
    assert.equal(await page.locator('#items button[aria-pressed="true"]').innerText(), selected);
    assert.deepEqual(await items.boundingBox(), bounds);
});

test('resource pins occupy separate terminal rows and Home cards on phone and desktop', async (t) => {
    const { page, options, base } = await fixture(t);
    for (const width of [390, 1280]) {
        options.pins = [];
        await page.setViewportSize({ width, height: 900 });
        await page.goto('about:blank');
        await page.goto(`${base}#/grids/${gridA}/items`);
        const ironButton = page.locator('#items .item').filter({ hasText: 'Iron' }).first();
        await ironButton.click({ button: 'right' });
        await page.getByRole('menuitem', { name: 'Pin in terminal', exact: true }).click();
        await page.locator('#pinned-items .item').waitFor();
        assert.equal(await page.locator('#items .item').filter({ hasText: 'Iron' }).count(), 0);
        await page.locator('#search').fill('never matches');
        assert.equal(await page.locator('#pinned-items .item').count(), 1);
        assert.equal(await page.locator('#items .item').count(), 0);
        await page.locator('#pinned-items .item').click({ button: 'right' });
        await page.getByRole('menuitem', { name: 'Pin on Home', exact: true }).click();
        assert.equal(options.pins[0].terminalOrder, 0);
        await serverState(() => options.pins[0].homeOrder, 0);
        await page.goto(`${base}#/`);
        await page.locator('.home-pin-card').waitFor();
        assert.match(await page.locator('.home-pin-card').textContent(), /Iron/);
        await page.locator('.home-pin-card').getByRole('button', { name: 'Unpin from Home', exact: true }).click();
        assert.equal(options.pins[0].terminalOrder, 0);
        await serverState(() => options.pins[0].homeOrder, null);
    }
});

test('terminal pins respect Auto and preferred columns while keeping ordinary rows below the pin row', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 1280, height: 900 } });
    options.pins = [
        {
            pinKey: 'one',
            available: true,
            gridKey: gridA,
            itemKey: iron.itemKey,
            gridName: '',
            resource: iron,
            terminalOrder: 0,
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
        }
    ];
    for (const columns of ['auto', '3']) {
        await page.goto(`${base}#/web-settings`);
        await page.getByRole('combobox', { name: /Resource columns/ }).selectOption(columns);
        for (const width of [1280, 390]) {
            await page.setViewportSize({ width, height: 900 });
            await page.goto(`${base}#/grids/${gridA}/items`);
            await page.locator('#pinned-items .item').waitFor();
            await page.locator('#items .item').waitFor();
            await settleFrames(page);
            // Filler slots are added by the grid after it measures its columns.
            await page.waitForFunction(() => {
                const pin = document.querySelector('#pinned-items');
                return (
                    pin.querySelectorAll('.empty-slot').length ===
                    getComputedStyle(pin).gridTemplateColumns.split(' ').length - 1
                );
            });
            const bounds = await page.evaluate(() => {
                const pin = document.querySelector('#pinned-items'),
                    normal = document.querySelector('#items'),
                    view = document.querySelector('#item-scroll');
                return {
                    pin: pin.getBoundingClientRect().toJSON(),
                    normal: normal.getBoundingClientRect().toJSON(),
                    view: view.getBoundingClientRect().toJSON(),
                    pinColumns: getComputedStyle(pin).gridTemplateColumns,
                    columns: getComputedStyle(normal).gridTemplateColumns,
                    overflow: view.scrollHeight - view.clientHeight
                };
            });
            const pinColumns = bounds.pinColumns.split(' ').map(parseFloat),
                normalColumns = bounds.columns.split(' ').map(parseFloat);
            assert.equal(await page.locator('#pinned-items .empty-slot').count(), pinColumns.length - 1);
            assert.equal(await page.locator('#pinned-items .empty-slot:not([aria-hidden="true"])').count(), 0);
            assert.equal(pinColumns.length, normalColumns.length);
            assert.ok(pinColumns.every((value, index) => Math.abs(value - normalColumns[index]) < 0.1));
            assert.ok(bounds.normal.top >= bounds.pin.bottom - 0.5, 'ordinary resources start on a fresh row');
            assert.ok(bounds.normal.right <= bounds.view.right + 0.5, 'mobile grid fits');
            assert.ok(
                bounds.overflow < 20,
                `small inventories should not gain an empty extra scroll page: ${JSON.stringify(bounds)}`
            );
        }
    }
});

test('absent terminal pins show zero only after accepted inventory and remain tappable for history', async (t) => {
    const { page, options, base } = await fixture(t, '', { viewport: { width: 390, height: 900 } });
    options.itemsA = [quartz];
    options.delayA = 300;
    options.pins = [
        {
            pinKey: 'one',
            available: true,
            gridKey: gridA,
            itemKey: iron.itemKey,
            gridName: '',
            resource: iron,
            terminalOrder: 0,
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
        }
    ];
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.locator('#pinned-items .item').waitFor();
    await page.waitForFunction(() => document.querySelector('#pinned-items .quantity')?.textContent === '0');
    options.status = 500;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#pinned-items .quantity')?.textContent === '—');
    options.status = 200;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#pinned-items .quantity')?.textContent === '0');
    await page.locator('#pinned-items .item').click();
    await page.waitForURL(/tracking/);
    await page.locator('.tracking-view').getByRole('button', { name: 'Unpin from terminal', exact: true }).click();
    await serverState(() => options.pins.length, 0);
});

test('Home hides revoked metadata and removes each placement independently including terminal-only unavailable pins', async (t) => {
    const { page, options, base } = await fixture(t);
    const homeConfig = {
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
    };
    options.pins = [
        {
            pinKey: 'one',
            available: true,
            gridKey: gridA,
            itemKey: iron.itemKey,
            gridName: 'Secret network',
            resource: iron,
            terminalOrder: 0,
            homeOrder: 0,
            homeConfig
        }
    ];
    await page.goto(base);
    await page.locator('.home-pin-card').waitFor();
    assert.match(await page.locator('.home-pin-card').textContent(), /Secret network/);
    options.pins = [
        { ...options.pins[0], available: false, gridKey: null, itemKey: null, gridName: null, resource: null }
    ];
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('.home-pin-card')?.textContent.includes('Secret network'));
    assert.doesNotMatch(await page.locator('.home-pin-card').textContent(), /Iron/);
    assert.equal(await page.locator('.home-pin-card canvas').count(), 0);
    await page.locator('.home-pin-card').getByRole('button', { name: 'Unpin from Home', exact: true }).click();
    assert.equal(options.pins[0].terminalOrder, 0);
    await serverState(() => options.pins[0].homeOrder, null);
    await page.locator('.home-unavailable-pins').getByRole('button', { name: 'Remove pin', exact: true }).click();
    await serverState(() => options.pins.length, 0);
});

test('Home pin cards preserve exact sampled values and independent settings while avoiding unrelated reads', async (t) => {
    const { page, options, base } = await fixture(t);
    const config = {
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
    };
    options.pins = [
        {
            pinKey: 'one',
            available: true,
            gridKey: gridA,
            itemKey: iron.itemKey,
            gridName: 'Alpha source',
            resource: iron,
            terminalOrder: 0,
            homeOrder: 0,
            homeConfig: { ...config }
        },
        {
            pinKey: 'two',
            available: true,
            gridKey: gridB,
            itemKey: quartz.itemKey,
            gridName: 'Beta source',
            resource: quartz,
            terminalOrder: null,
            homeOrder: 1,
            homeConfig: { ...config }
        }
    ];
    const now = Date.now();
    options.pins[0].recent = {
        flowWindowMillis: 120000,
        stockWindowMillis: 3600000,
        bucketMillis: 5000,
        observedAt: now,
        flowObservedAt: now,
        stockObservedAt: null,
        flowSince: now - 45000,
        stockSince: null,
        flowComplete: false,
        stockComplete: false,
        resources: { [iron.itemKey]: { input: '9007199254740993', output: '9007199254740994', stockChange: null } }
    };
    options.resourceHistory = {
        baseline: null,
        complete: false,
        truncated: false,
        points: [
            {
                kind: 'STOCK',
                observedAt: now - 120000,
                generation: 1,
                flowFrom: null,
                resource: { ...iron, quantity: '0' },
                input: '0',
                output: '0'
            },
            {
                kind: 'FLOW',
                observedAt: now - 60000,
                generation: 1,
                flowFrom: now - 120000,
                resource: { ...iron, quantity: '0' },
                input: '9007199254740993',
                output: '2'
            },
            {
                kind: 'GAP',
                observedAt: now - 90000,
                generation: 1,
                flowFrom: null,
                resource: null,
                input: '0',
                output: '0'
            }
        ]
    };
    await page.goto(base);
    const first = page.locator('.home-pin-card').filter({ hasText: 'Alpha source' });
    await first.locator('canvas').waitFor();
    const flowLine = first.locator('.home-pin-recent .home-pin-flow-line');
    await flowLine.locator('[data-flow="input"]').hover();
    const flowTooltip = page.getByRole('tooltip');
    await flowTooltip.waitFor();
    assert.match(await flowTooltip.textContent(), /9,007,199,254,740,993/);
    assert.match(await flowTooltip.textContent(), /9,007,199,254,740,994/);
    assert.ok(await flowTooltip.locator('time').count(), 'flow measurement time uses the shared tooltip');
    assert.equal(await first.locator('.home-pin-flow-line[title], .home-pin-flow-line [title]').count(), 0);
    await page.mouse.move(0, 0);
    await flowLine.focus();
    await flowTooltip.waitFor();
    assert.equal(await flowLine.getAttribute('aria-describedby'), await flowTooltip.getAttribute('id'));
    await page.keyboard.press('Escape');
    assert.equal(await flowTooltip.isVisible(), false);
    assert.equal(await first.locator('dd').first().textContent(), '0');
    const recentValues = first.locator('.home-pin-recent [data-flow]');
    const exact = await recentValues.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('aria-label')));
    assert.match(exact[0], /9,007,199,254,740,993/);
    assert.match(exact[1], /9,007,199,254,740,994/);
    assert.match(exact[2], /[−-]1/);
    const positions = await recentValues.evaluateAll((nodes) => nodes.map((node) => node.getBoundingClientRect().top));
    assert.equal(new Set(positions).size, 1, 'flow counters share a compact line');
    const colors = await recentValues.evaluateAll((nodes) => nodes.map((node) => getComputedStyle(node).color));
    assert.notEqual(colors[0], colors[1]);
    assert.equal(colors[1], colors[2], 'negative net uses output color');
    assert.ok(await first.locator('.home-pin-recent .recent-note').textContent(), 'warmup/gap remains explicit');
    assert.deepEqual(
        await page.locator('.home-pin-card').nth(1).locator('.home-pin-recent [data-flow]').allTextContents(),
        ['—', '—', '—']
    );
    assert.equal(await page.locator('.home-pin-card').count(), 2);
    assert.equal(options.requests.filter((request) => /\/(items|cpus)$/.test(request.path)).length, 0);
    const reads = () => options.requests.filter((request) => request.path.endsWith('/resource-history')).length;
    const before = reads();
    await first.locator('summary').click();
    const historicalSettings = first.getByRole('group', { name: 'Historical flow', exact: true });
    const chartSettings = first.getByRole('group', { name: 'Chart', exact: true });
    assert.equal(
        await historicalSettings.getByRole('combobox', { name: 'Historical flow period', exact: true }).count(),
        1
    );
    assert.equal(await chartSettings.getByRole('combobox', { name: 'Chart period', exact: true }).count(), 1);
    assert.equal(await chartSettings.getByRole('checkbox', { name: 'Input', exact: true }).count(), 1);
    assert.equal(await historicalSettings.getByRole('checkbox', { name: 'Input', exact: true }).count(), 0);
    await first.getByRole('checkbox', { name: 'Show chart', exact: true }).uncheck();
    await first.getByRole('checkbox', { name: 'Show chart', exact: true }).waitFor();
    await page.waitForFunction(() => document.querySelector('.home-pin-settings fieldset')?.disabled === false);
    assert.equal(await first.locator('canvas').count(), 0);
    assert.equal(reads(), before);
    await first.getByRole('combobox', { name: 'Historical flow period', exact: true }).selectOption('3600000');
    await page.waitForFunction(() => document.querySelector('.home-pin-settings fieldset')?.disabled === false);
    assert.equal(options.pins[0].homeConfig.flowRangeMillis, 3600000);
    assert.equal(reads(), before + 1);
    assert.equal(options.pins[0].homeConfig.rangeMillis, 86400000, 'chart range is independent');
    await page
        .locator('.home-pin-card')
        .filter({ hasText: 'Beta source' })
        .getByRole('button', { name: 'Move earlier', exact: true })
        .click();
    await page.waitForFunction(() => document.querySelector('.home-pin-source')?.textContent === 'Beta source');
    assert.equal(options.pins.find((pin) => pin.pinKey === 'one').terminalOrder, 0);
    await page.reload();
    await first.locator('summary').waitFor();
    await first.locator('summary').click();
    assert.equal(await first.getByRole('checkbox', { name: 'Show chart', exact: true }).isChecked(), false);
    assert.equal(
        await first.getByRole('combobox', { name: 'Historical flow period', exact: true }).inputValue(),
        '3600000'
    );
    const pinned = options.pins.find((pin) => pin.pinKey === 'one');
    pinned.recent.resources[iron.itemKey] = { input: '0', output: '0', stockChange: null };
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.home-pin-recent [data-flow]')].some((node) => node.textContent === '0')
    );
    assert.deepEqual(await recentValues.allTextContents(), ['0', '0', '0']);
    const zeroColors = await recentValues.evaluateAll((nodes) => nodes.map((node) => getComputedStyle(node).color));
    assert.equal(new Set(zeroColors).size, 1, 'measured zero has neutral color in all columns');
    pinned.recent = null;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.waitForFunction(
        () => ![...document.querySelectorAll('.home-pin-recent [data-flow]')].some((node) => node.textContent === '0')
    );
    assert.deepEqual(await recentValues.allTextContents(), ['—', '—', '—']);
    const finalReads = reads();
    await page.goto(`${base}#/web-settings`);
    await page.clock.fastForward(35000);
    assert.equal(reads(), finalReads);
});

test('Home sampled stock uses the latest accepted range and survives an independent chart failure', async (t) => {
    const { page, options, base } = await fixture(t);
    options.pins = [
        {
            pinKey: 'stock',
            available: true,
            gridKey: gridA,
            itemKey: iron.itemKey,
            gridName: 'Source',
            resource: iron,
            recent: null,
            homeOrder: 0,
            terminalOrder: null,
            homeConfig: {
                showStock: true,
                showRecentFlow: true,
                showFlow: true,
                showChart: true,
                stock: true,
                input: true,
                output: true,
                net: false,
                rangeMillis: 86400000,
                flowRangeMillis: 3600000
            }
        }
    ];
    const now = Date.now();
    let pending;
    await page.route('**/resource-history?**', async (route) => {
        const duration = now - Number(new URL(route.request().url()).searchParams.get('from'));
        if (duration > 86401000) {
            await new Promise((resolve) => {
                pending = resolve;
            });
            await route.fulfill({
                status: 500,
                contentType: 'application/json',
                body: JSON.stringify({ status: 'INTERNAL_ERROR', data: null })
            });
            return;
        }
        const recentRange = duration < 4000000;
        await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
                status: 'OK',
                icons: null,
                data: {
                    enabled: true,
                    complete: true,
                    truncated: false,
                    baseline: null,
                    points: [
                        {
                            kind: 'STOCK',
                            observedAt: now - (recentRange ? 1000 : 60000),
                            generation: 1,
                            flowFrom: null,
                            input: '0',
                            output: '0',
                            resource: { ...iron, quantity: recentRange ? '7' : '0' }
                        }
                    ]
                }
            })
        });
    });
    await page.goto(base);
    const card = page.locator('.home-pin-card');
    await card.locator('canvas').waitFor();
    await page.waitForFunction(() => document.querySelector('.home-pin-historical .recent-note')?.textContent === '');
    assert.equal(await card.locator(':scope > .home-pin-summary dd').first().textContent(), '7');
    await card.locator('summary').click();
    await card.getByRole('combobox', { name: 'Chart period', exact: true }).selectOption('604800000');
    while (!pending) await new Promise((resolve) => setTimeout(resolve, 5));
    assert.equal(await card.locator(':scope > .home-pin-summary dd').first().textContent(), '7');
    pending();
    await page.waitForFunction(() => document.querySelector('.home-pin-settings fieldset')?.disabled === false);
    assert.equal(await card.locator(':scope > .home-pin-summary dd').first().textContent(), '7');
});

const liveCpuRow = (itemKey, displayName, extra = {}) => ({
    ...cpuWork.items[0],
    itemKey,
    displayName,
    registryPath: itemKey,
    crafting: false,
    ...extra
});

// Opens a busy CPU whose detail follows the simulated live stream; `send` writes a delta to the newest stream.
async function openLiveCpu(t, work = {}, prepare = async () => {}) {
    const context = await refreshingFixture(t);
    const { page, options, base } = context;
    await prepare(context);
    options.cpuLiveView = { generation: '1', revision: '1' };
    options.cpus['cpu-a'] = { ...cpu, isBusy: true, supportsPause: true };
    options.cpuDetails['cpu-a'] = {
        ...cpuWork,
        supportsPause: true,
        items: [
            liveCpuRow('iron', 'Iron Ingot'),
            liveCpuRow('gold', 'Gold Ingot', { active: 0, pending: 0, stored: 3 })
        ],
        ...work
    };
    const opened = page.waitForResponse((response) => response.url().includes('/cpus/cpu-a/events'));
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await opened;
    await page.locator('#refresh .live-icon').waitFor();
    return {
        ...context,
        resources: page.getByRole('region', { name: 'CPU resources', exact: true }),
        detailReads: () => options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a')),
        send(revision, content = {}) {
            const view = { generation: options.cpuLiveView.generation, revision: String(revision) };
            options.cpuStreams
                .at(-1)
                .write(
                    `event: delta\ndata: ${JSON.stringify({ view, updates: [], definitions: [], removed: [], icons: null, ...content })}\n\n`
                );
        }
    };
}

const liveUpdate = (itemKey, extra) => ({
    itemKey,
    active: 0,
    pending: 0,
    stored: 0,
    craftedTotal: 10,
    timeSpentCrafting: 5000,
    crafting: false,
    ...extra
});

test('one tab hands its live subscription across CPUs, terminals and grids', async (t) => {
    const { page, options } = await openLiveCpu(t);
    const move = async (hash, endpoint, expected, revision) => {
        options.cpuLiveView = { generation: '1', revision };
        options.liveView = { generation: '1', revision };
        const requested = page.waitForRequest((request) => {
            const url = new URL(request.url());
            return url.pathname.endsWith(endpoint) && url.searchParams.get('live') === 'true';
        });
        await page.evaluate((next) => {
            location.hash = next;
        }, hash);
        const request = await requested;
        assert.equal(new URL(request.url()).searchParams.get('replace'), expected);
        await page.locator('#refresh .live-icon').waitFor();
    };
    await move(`#/grids/${gridA}/cpus/cpu-b`, '/cpus/cpu-b', 'cpu-subscription-1', '2');
    await move(`#/grids/${gridA}/items`, '/items', 'cpu-subscription-2', '3');
    await move(`#/grids/${gridB}/cpus/cpu-a`, '/cpus/cpu-a', 'subscription-3', '4');
});

test('navigation reclaims a delayed obsolete live snapshot before opening the next CPU', async (t) => {
    const { page, options } = await openLiveCpu(t);
    options.cpuLiveView = { generation: '1', revision: '2' };
    let received;
    const held = new Promise((resolve) => {
        received = resolve;
    });
    let release;
    const resumed = new Promise((resolve) => {
        release = resolve;
    });
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-a'),
        async (route) => {
            const response = await route.fetch();
            received();
            await resumed;
            await route.fulfill({ response }).catch(() => {});
        }
    );
    await page.locator('#refresh').click();
    await held;
    options.cpuLiveView = { generation: '1', revision: '3' };
    const next = page.waitForRequest((request) => new URL(request.url()).pathname.endsWith('/cpus/cpu-b'));
    await page.evaluate((hash) => {
        location.hash = hash;
    }, `#/grids/${gridA}/cpus/cpu-b`);
    release();
    const request = await next;
    assert.equal(new URL(request.url()).searchParams.get('replace'), 'cpu-subscription-2');
    await page.locator('#refresh .live-icon').waitFor();
    assert.ok(page.url().endsWith('/cpus/cpu-b'));
});

test('reload reclaims the tab subscription while an opener-created tab owns an independent view', async (t) => {
    // Real navigation timing distinguishes a reload from an opener-created tab.
    const { page, options, base } = await refreshingFixture(t, '', {}, { mockClock: false });
    options.cpuLiveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page.locator('#refresh .live-icon').waitFor();
    options.cpuLiveView = { generation: '1', revision: '2' };
    const popupReady = page.waitForEvent('popup');
    await page.evaluate((url) => {
        window.open(url);
    }, `${base}#/grids/${gridA}/cpus/cpu-b`);
    const popup = await popupReady;
    await popup.locator('#refresh .live-icon').waitFor();
    const requests = () => options.requests.filter((request) => /\/cpus\/cpu-[ab]$/.test(request.path));
    assert.equal(new URLSearchParams(requests().at(-1).query).get('replace'), null);
    options.cpuLiveView = { generation: '1', revision: '3' };
    await popup.reload();
    await popup.locator('#refresh .live-icon').waitFor();
    assert.equal(new URLSearchParams(requests().at(-1).query).get('replace'), 'cpu-subscription-2');
    options.cpuLiveView = { generation: '1', revision: '4' };
    await page.reload();
    await page.locator('#refresh .live-icon').waitFor();
    assert.equal(new URLSearchParams(requests().at(-1).query).get('replace'), 'cpu-subscription-1');
});

test('logout clears live ownership and a late snapshot cannot restore it', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpuLiveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/about`);
    await page.evaluate(
        async ({ base, grid }) => {
            const { createApi } = await import('./assets/web/app/api.js');
            window.handoffApi = createApi(new URL(base), () => {});
            await window.handoffApi.cpu(grid, 'cpu-a', undefined, false, true);
        },
        { base, grid: gridA }
    );
    let received;
    const held = new Promise((resolve) => {
        received = resolve;
    });
    let release;
    const resumed = new Promise((resolve) => {
        release = resolve;
    });
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-b'),
        async (route) => {
            const response = await route.fetch();
            received();
            await resumed;
            await route.fulfill({ response });
        }
    );
    options.cpuLiveView = { generation: '1', revision: '2' };
    await page.evaluate((grid) => {
        window.pendingHandoff = window.handoffApi.cpu(grid, 'cpu-b', undefined, false, true).then(
            () => 'OK',
            (error) => error.name
        );
    }, gridA);
    await held;
    await page.evaluate((grid) => {
        window.queuedHandoff = window.handoffApi.cpu(grid, 'cpu-a', undefined, false, true).then(
            () => 'OK',
            (error) => error.name
        );
    }, gridA);
    await page.evaluate(() => window.handoffApi.logout());
    release();
    assert.equal(await page.evaluate(() => window.pendingHandoff), 'AbortError');
    assert.equal(await page.evaluate(() => window.queuedHandoff), 'AbortError');
    await page.evaluate((grid) => window.handoffApi.cpu(grid, 'cpu-a', undefined, false, true), gridA);
    const last = options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a')).at(-1);
    assert.equal(new URLSearchParams(last.query).get('replace'), null);
});

test('a stalled live bootstrap times out and releases the next navigation', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpuLiveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/about`);
    await page.evaluate(
        async ({ base, grid }) => {
            const { createApi } = await import('./assets/web/app/api.js');
            window.handoffApi = createApi(new URL(base), () => {});
            await window.handoffApi.cpu(grid, 'cpu-a', undefined, false, true);
        },
        { base, grid: gridA }
    );
    let received;
    const held = new Promise((resolve) => {
        received = resolve;
    });
    let release;
    const resumed = new Promise((resolve) => {
        release = resolve;
    });
    t.after(() => release());
    await page.route(
        (url) => url.pathname.endsWith('/cpus/cpu-b'),
        async (route) => {
            received();
            await resumed;
            await route.abort().catch(() => {});
        }
    );
    await page.evaluate((grid) => {
        window.pendingHandoff = window.handoffApi
            .cpu(grid, 'cpu-b', undefined, false, true)
            .catch((error) => error.status);
    }, gridA);
    await held;
    await page.evaluate((grid) => {
        const abandoned = new AbortController();
        window.abandonedHandoff = window.handoffApi
            .cpu(grid, 'abandoned', abandoned.signal, false, true)
            .catch((error) => error.name);
        abandoned.abort();
        window.nextHandoff = window.handoffApi.cpu(grid, 'cpu-a', undefined, false, true);
    }, gridA);
    options.cpuLiveView = { generation: '1', revision: '3' };
    await page.clock.fastForward(30001);
    assert.equal(await page.evaluate(() => window.pendingHandoff), 'TIMEOUT');
    assert.equal(await page.evaluate(() => window.abandonedHandoff), 'AbortError');
    assert.equal((await page.evaluate(() => window.nextHandoff)).subscription, 'cpu-subscription-3');
    assert.equal(
        options.requests.some((request) => request.path.endsWith('/cpus/abandoned')),
        false
    );
    const last = options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a')).at(-1);
    assert.equal(new URLSearchParams(last.query).get('replace'), 'cpu-subscription-1');
    release();
});

test('live CPU detail applies absolute updates, new and removed resources and unkeyed replacement without rereading', async (t) => {
    const { page, options, resources, detailReads, send } = await openLiveCpu(t);
    assert.equal(detailReads().length, 1);
    assert.equal(detailReads()[0].query, '?live=true');
    const iron = resources.getByRole('button', { name: /^Iron Ingot/ });
    assert.match(await iron.getAttribute('aria-label'), /Active: 4 · Pending: 6 · Stored: 2/);
    send(2, { updates: [liveUpdate('iron', { active: 9, stored: 5 })] });
    await resources.getByRole('button', { name: /^Iron Ingot.*Active: 9 · Stored: 5/ }).waitFor();
    send(3, {
        definitions: [liveCpuRow('silver', 'Silver Ingot', { active: 2, pending: 0, stored: 0 })],
        removed: ['gold']
    });
    await resources.getByRole('button', { name: /^Silver Ingot.*Active: 2/ }).waitFor();
    assert.equal(await resources.getByRole('button', { name: /^Gold Ingot/ }).isDisabled(), true);
    assert.doesNotMatch(
        await resources.getByRole('button', { name: /^Gold Ingot/ }).getAttribute('aria-label'),
        /Stored: 3/
    );
    send(4, { unkeyed: [liveCpuRow(null, 'Mystery Resource', { active: 1, pending: 0, stored: 0 })] });
    await resources.getByRole('button', { name: /^Mystery Resource/ }).waitFor();
    assert.equal(await resources.getByRole('button', { name: /^Iron Ingot/ }).count(), 1);
    send(5, { unkeyed: [] });
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.cpu-item')].some(
            (button) => button.textContent.includes('Mystery Resource') && button.disabled
        )
    );
    await page.clock.runFor(5000);
    await resources.getByRole('button', { name: /^Gold Ingot/ }).waitFor({ state: 'detached' });
    await resources.getByRole('button', { name: /^Mystery Resource/ }).waitFor({ state: 'detached' });
    options.cpus['cpu-b'] = { ...cpu, name: 'Renamed assembler' };
    const listed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/grids/${gridA}/cpus`);
    await poll(page);
    await settleResponse(page, (await listed).request());
    await page.getByRole('link', { name: /Renamed assembler/ }).waitFor();
    assert.equal(detailReads().length, 1, 'Polling the CPU list must not reread a CPU that follows live updates');
    assert.equal(options.cpuStreams.length, 1);
});

test('a live CPU header section replaces the CPU state and keeps its resources', async (t) => {
    const { page, options, resources, send } = await openLiveCpu(t);
    await page.getByRole('button', { name: 'Pause current work', exact: true }).waitFor();
    const header = { ...options.cpuDetails['cpu-a'], isPaused: true, timeElapsed: 20000, items: null };
    send(2, { cpu: header });
    await page.getByRole('button', { name: 'Resume current work', exact: true }).waitFor();
    await page
        .getByRole('region', { name: 'Current job', exact: true })
        .getByText(/Elapsed: 20 s/)
        .waitFor();
    assert.equal(await resources.getByRole('button', { name: /^Iron Ingot/ }).count(), 1);
    send(3, { cpu: { ...header, isPaused: false } });
    await page.getByRole('button', { name: 'Pause current work', exact: true }).waitFor();
    assert.equal(await resources.getByRole('button', { name: /^Gold Ingot/ }).count(), 1);
});

test('a live CPU header takes its product icon from the page merged for its own delta', async (t) => {
    const { page, options, resources, send } = await openLiveCpu(
        t,
        {
            finalOutput: { ...quartz, icon: { page: 0, x: 0, y: 0 } },
            items: [liveCpuRow('iron', 'Iron Ingot', { icon: { page: 0, x: 0, y: 0 } })]
        },
        ({ page, options }) => atlasFixture(page, options)
    );
    const product = page.getByRole('region', { name: 'Current job', exact: true }).locator('.resource-icon');
    await page.waitForFunction(
        () => document.querySelector('.cpu-detail-output .resource-icon')?.style.backgroundImage
    );
    const newPage = 'c'.repeat(64);
    const newPageRequests = () => options.requests.filter((request) => request.path.includes(`/pages/${newPage}`));
    assert.equal(newPageRequests().length, 0);
    const header = { ...options.cpuDetails['cpu-a'], isPaused: true, items: null };
    send(2, {
        cpu: { ...header, finalOutput: { ...quartz, icon: { page: 0, x: 64, y: 0 } } },
        icons: { ...options.icons, pages: [{ digest: newPage, width: 128, height: 64 }] }
    });
    await page.getByRole('button', { name: 'Resume current work', exact: true }).waitFor();
    await serverState(() => newPageRequests().length, 1);
    await page.waitForFunction(
        () =>
            getComputedStyle(document.querySelector('.cpu-detail-output .resource-icon')).backgroundPositionX !== '0px'
    );
    const crop = await product.evaluate((icon) => ({
        x: parseFloat(getComputedStyle(icon).backgroundPositionX),
        width: icon.getBoundingClientRect().width
    }));
    assert.equal(crop.x, -crop.width, 'The header icon is cropped from the second cell of the delta page');
    assert.equal(await resources.getByRole('button', { name: /^Iron Ingot/ }).count(), 1);
});

test('an invalidated live CPU takes a fresh snapshot that replaces its subscription and recovers from a lost stream', async (t) => {
    const { page, options, resources, detailReads, send } = await openLiveCpu(t);
    options.cpuDetails['cpu-a'] = {
        ...options.cpuDetails['cpu-a'],
        items: [liveCpuRow('copper', 'Copper Ingot', { active: 8, pending: 0, stored: 0 })]
    };
    options.cpuLiveView = { generation: '2', revision: '7' };
    const snapshot = page.waitForRequest(
        (request) => new URL(request.url()).pathname.endsWith('/cpus/cpu-a') && request.url().includes('replace=')
    );
    options.cpuStreams[0].write('event: invalidate\ndata: {}\n\n');
    const request = await snapshot;
    assert.equal(new URL(request.url()).searchParams.get('replace'), 'cpu-subscription-1');
    assert.equal(new URL(request.url()).searchParams.get('live'), 'true');
    await resources.getByRole('button', { name: /^Copper Ingot.*Active: 8/ }).waitFor();
    await resources.getByRole('button', { name: /^Iron Ingot/ }).waitFor({ state: 'detached' });
    await serverState(() => options.cpuStreams.length, 1);
    assert.equal(detailReads().length, 2);
    send(8, { updates: [liveUpdate('copper', { active: 3, stored: 9 })] });
    await resources.getByRole('button', { name: /^Copper Ingot.*Active: 3/ }).waitFor();
    options.cpuStreams[0].end();
    const reconnected = page.waitForResponse((response) => response.url().includes('/cpus/cpu-a/events'));
    await page.clock.fastForward(2000);
    await reconnected;
    assert.equal(detailReads().length, 3, 'A lost connection recovers through a new snapshot');
    await page.locator('#refresh .live-icon').waitFor();
});

test('running CPU measurements advance on the local clock without network traffic', async (t) => {
    const { page, options } = await openLiveCpu(t, {
        timeElapsed: 10000,
        items: [liveCpuRow('iron', 'Iron Ingot', { crafting: true, timeSpentCrafting: 5000, craftedTotal: 10 })]
    });
    const traffic = () => options.requests.filter((request) => /\/cpus\/cpu-a(\/events)?$/.test(request.path)).length;
    const before = traffic();
    const job = page.getByRole('region', { name: 'Current job', exact: true });
    const elapsed = async () => Math.round(Number(/Elapsed: ([\d.]+) s/.exec(await job.innerText())[1]) * 1000);
    await job.getByText(/Elapsed: \d/).waitFor();
    // The measurement arithmetic is unit-tested (cpu-timing.test.cjs). The page clock is frozen so that every tick
    // of the one second display timer advances the elapsed time by exactly one second.
    await page.clock.pauseAt(Date.now() + 1000);
    await page.clock.runFor(1000);
    const started = await elapsed();
    await page.clock.runFor(3000);
    assert.equal(await elapsed(), started + 3000);
    assert.equal(traffic(), before, 'Clock progress is derived locally and never requested');
});

test('a manual refresh during an automatic one takes a new snapshot of the live CPU', async (t) => {
    const { page, options, detailReads } = await openLiveCpu(t);
    assert.equal(detailReads().length, 1);
    let held;
    const discovery = new Promise((resolve) => {
        held = resolve;
    });
    let discoveries = 0;
    await page.route(
        (url) => url.pathname.endsWith('/api/grids'),
        (route) => {
            if (discoveries++ === 0) held(route);
            else return route.continue();
        }
    );
    await poll(page);
    const automatic = await discovery;
    await page.locator('#refresh').click();
    await automatic.continue();
    await serverState(() => detailReads().length, 2);
    assert.equal(new URLSearchParams(detailReads()[1].query).get('replace'), 'cpu-subscription-1');
    assert.equal(discoveries, 2, 'The manual refresh waits for the automatic one and then reads again');
    await serverState(() => options.cpuStreams.length, 1);
    await page.locator('#refresh .live-icon').waitFor();
});

test('leaving the live CPU detail closes its stream and returning opens a new one', async (t) => {
    const { page, options, resources, detailReads } = await openLiveCpu(t);
    await clickTab(page, 'CPUs');
    await serverState(() => options.cpuStreams.length, 0);
    assert.equal(await page.locator('#refresh .live-icon').isVisible(), false);
    assert.equal(await resources.isVisible(), false);
    await page.getByRole('link', { name: /Assembler.*cpu-a/ }).click();
    await page.locator('#refresh .live-icon').waitFor();
    await serverState(() => options.cpuStreams.length, 1);
    assert.equal(detailReads().length, 2);
    await page.locator('a[data-view="web-settings"]').click();
    await serverState(() => options.cpuStreams.length, 0);
});

test('CPU actions on a live detail confirm first and then restart its live view', async (t) => {
    const { page, options, resources, detailReads, send } = await openLiveCpu(t);
    await page.getByRole('button', { name: 'Pause current work', exact: true }).click();
    await page.getByRole('button', { name: 'Resume current work', exact: true }).waitFor();
    assert.equal(detailReads().length, 2);
    assert.equal(new URLSearchParams(detailReads()[1].query).get('replace'), 'cpu-subscription-1');
    await serverState(() => options.cpuStreams.length, 1);
    await page.locator('#refresh .live-icon').waitFor();
    send(2, { updates: [liveUpdate('iron', { active: 11 })] });
    await resources.getByRole('button', { name: /^Iron Ingot.*Active: 11/ }).waitFor();
});

test('a CPU detail is read by snapshots only while automatic refresh is off', async (t) => {
    const { page, options, base } = await fixture(t);
    options.cpuLiveView = { generation: '1', revision: '1' };
    await page.goto(`${base}#/grids/${gridA}/cpus/cpu-a`);
    await page
        .getByRole('region', { name: 'CPU resources', exact: true })
        .getByRole('button', { name: /Iron Ingot/ })
        .waitFor();
    const reads = options.requests.filter((request) => request.path.endsWith('/cpus/cpu-a'));
    assert.deepEqual(
        reads.map((request) => request.query),
        ['']
    );
    assert.equal(options.cpuStreams?.length ?? 0, 0);
    assert.equal(await page.locator('#refresh .live-icon').isVisible(), false);
});

test('CPU live counts preserve tile positions until five seconds and explicit sorting stays immediate', async (t) => {
    const { page, resources, send, options } = await openLiveCpu(t);
    await page.clock.pauseAt(Date.now() + 1000);
    await page.getByRole('button', { name: 'Sort by: Active quantity', exact: true }).click();
    const names = () =>
        resources
            .getByRole('button')
            .evaluateAll((buttons) => buttons.map((button) => button.getAttribute('aria-label').split(' · ')[0]));
    assert.deepEqual(await names(), ['Iron Ingot', 'Gold Ingot']);
    const ironName = await resources
        .getByRole('button', { name: /^Iron Ingot/ })
        .locator('strong')
        .evaluateHandle((node) => node.firstChild);
    const selectorName = await page
        .locator('.cpu-selector-name')
        .first()
        .evaluateHandle((node) => node.firstChild);
    send(2, { updates: [liveUpdate('gold', { active: 20 })] });
    await resources.getByRole('button', { name: /^Gold Ingot.*Active: 20/ }).waitFor();
    assert.deepEqual(await names(), ['Iron Ingot', 'Gold Ingot']);
    await page.clock.runFor(1000);
    assert.equal(
        await ironName.evaluate((node) => node.isConnected),
        true,
        'Unchanged tile text is retained through delta and clock'
    );
    assert.equal(
        await selectorName.evaluate((node) => node.isConnected),
        true,
        'CPU clock does not rebuild the unchanged sidebar'
    );
    await page.clock.runFor(3999);
    assert.deepEqual(await names(), ['Iron Ingot', 'Gold Ingot']);
    await page.clock.runFor(1);
    assert.deepEqual(await names(), ['Gold Ingot', 'Iron Ingot']);
    send(3, { updates: [liveUpdate('iron', { active: 30 })] });
    await resources.getByRole('button', { name: /^Iron Ingot.*Active: 30/ }).waitFor();
    assert.deepEqual(await names(), ['Gold Ingot', 'Iron Ingot']);
    await page.getByRole('button', { name: 'Sort by: Active quantity', exact: true }).click();
    assert.deepEqual(await names(), ['Iron Ingot', 'Gold Ingot'], 'An explicit sort applies immediately');
    options.cpuDetails['cpu-a'].items = [
        liveCpuRow('gold', 'Gold Ingot', { active: 50 }),
        liveCpuRow('iron', 'Iron Ingot', { active: 1 })
    ];
    await page.locator('#refresh').click();
    await resources.getByRole('button', { name: /^Gold Ingot.*Active: 50/ }).waitFor();
    assert.deepEqual(await names(), ['Gold Ingot', 'Iron Ingot'], 'Accepted full snapshots apply ordering immediately');
});

test('replacing anonymous CPU resources keeps slots but never adopts the old focused resource', async (t) => {
    const { page, resources, send } = await openLiveCpu(t, {
        items: [liveCpuRow(null, 'Anonymous', { active: 1 }), liveCpuRow('iron', 'Iron Ingot')]
    });
    const anonymous = resources.getByRole('button', { name: /^Anonymous/ });
    await anonymous.focus();
    const tooltip = page.getByRole('tooltip');
    await tooltip.waitFor({ state: 'visible' });
    const old = await anonymous.elementHandle();
    const iron = resources.getByRole('button', { name: /^Iron Ingot/ });
    const position = await iron.evaluate((node) => [...node.closest('ul').children].indexOf(node.parentElement));
    send(2, { unkeyed: [liveCpuRow(null, 'Anonymous', { active: 8 })] });
    await resources.getByRole('button', { name: /^Anonymous.*Active: 8/ }).waitFor();
    assert.equal(await old.evaluate((node) => node.isConnected), false);
    assert.equal(await anonymous.evaluate((node) => node === document.activeElement), false);
    assert.equal(await tooltip.isVisible(), false, 'Replacement must dismiss the previous anonymous resource tooltip');
    assert.equal(await iron.evaluate((node) => [...node.closest('ul').children].indexOf(node.parentElement)), position);
});
