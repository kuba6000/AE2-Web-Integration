const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, gridA, iron, settleFrames } = require('./fixtures.cjs');
const { iconColor, waitIconColor } = require('./icon-pixels.cjs');

async function waitPainted(page, icon, description) {
    const deadline = Date.now() + 5000;
    let actual;
    do {
        actual = await iconColor(page, icon);
        if (actual === 'green') return;
        await page.waitForTimeout(30);
    } while (Date.now() < deadline);
    assert.equal(actual, 'green', `${description} within 5 seconds`);
}

async function scrollAndWait(page, viewport, direction, step) {
    const initial = await viewport.evaluate((element) => element.scrollTop);
    await page.mouse.wheel(0, direction * 240);
    const result = await viewport.evaluate(
        (element, { initial, direction }) =>
            new Promise((resolve) => {
                let previous = element.scrollTop;
                const started = performance.now();
                let stableSince = started;
                let stableFrames = 0;
                let frame;
                const timeout = setTimeout(() => {
                    cancelAnimationFrame(frame);
                    resolve({ settled: false, position: element.scrollTop });
                }, 5000);
                function check(now) {
                    const position = element.scrollTop;
                    if (position !== previous) {
                        previous = position;
                        stableSince = now;
                        stableFrames = 0;
                    } else stableFrames++;
                    const advanced = (position - initial) * direction > 0;
                    // A wheel event can be a no-op; the bounded traversal still has to reach both endpoints.
                    const noMovement = position === initial && now - started >= 500;
                    if ((advanced || noMovement) && stableFrames >= 2 && now - stableSince >= 100) {
                        clearTimeout(timeout);
                        resolve({ settled: true, position });
                    } else frame = requestAnimationFrame(check);
                }
                frame = requestAnimationFrame(check);
            }),
        { initial, direction }
    );
    assert.ok(
        result.settled,
        `step ${step}, direction ${direction}: scroll settles within 5 seconds (from ${initial} to ${result.position})`
    );
    return (result.position - initial) * direction > 0;
}

for (const display of ['both', 'icons'])
    test(`500 resources retain visible icons throughout forward and reverse scrolling in ${display} mode`, async (t) => {
        const { page, options, base } = await fixture(t, '', { viewport: { width: 1100, height: 800 } });
        await atlasFixture(page, options);
        options.icons.pages.push({ digest: 'c'.repeat(64), width: 128, height: 64 });
        // Equal fallback/frame colors isolate missing pixels from normal animation loading transitions.
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
        await waitIconColor(
            page,
            page.getByRole('button', { name: /Resource 001/ }).locator('.resource-icon'),
            'green'
        );
        const viewport = page.locator('#item-scroll');
        assert.equal(await page.locator('#items .item').count(), 500);
        const box = await viewport.boundingBox();
        // Keep the pointer in the viewport gutter so a hover tooltip cannot cover sampled icons.
        await page.mouse.move(box.x + 4, box.y + box.height / 2);
        for (const direction of [1, -1]) {
            let wheels = 0;
            for (let step = 0; step < 100; step++) {
                await settleFrames(page);
                const visible = await viewport.evaluate((viewport) => {
                    const area = viewport.getBoundingClientRect();
                    const rows = [...viewport.querySelectorAll('#items .item')]
                        .map((row, index) => ({ index, box: row.getBoundingClientRect() }))
                        .filter(({ box }) => box.top >= area.top && box.bottom <= area.bottom);
                    return [rows[0]?.index, rows[Math.floor(rows.length / 2)]?.index, rows.at(-1)?.index].filter(
                        (index) => index !== undefined
                    );
                });
                assert.ok(visible.length > 0, 'each scroll position contains complete visible resource rows');
                for (const index of visible)
                    await waitPainted(
                        page,
                        page.locator('#items .item').nth(index).locator('.resource-icon'),
                        `step ${step}, direction ${direction}: resource ${index} remains painted`
                    );
                const atEnd = await viewport.evaluate(
                    (el, direction) =>
                        direction > 0 ? el.scrollTop + el.clientHeight >= el.scrollHeight - 1 : el.scrollTop <= 0,
                    direction
                );
                if (atEnd) {
                    const endpointIndex = direction > 0 ? 499 : 0;
                    const endpoint = page.locator('#items .item').nth(endpointIndex).locator('.resource-icon');
                    const iconBox = await endpoint.boundingBox();
                    const viewportBox = await viewport.boundingBox();
                    assert.ok(
                        iconBox.y + iconBox.height / 2 >= viewportBox.y &&
                            iconBox.y + iconBox.height / 2 < viewportBox.y + viewportBox.height,
                        'the endpoint icon is inside the viewport'
                    );
                    await waitPainted(
                        page,
                        endpoint,
                        `step ${step}, direction ${direction}: endpoint resource ${endpointIndex} remains painted`
                    );
                    break;
                }
                let advanced;
                do {
                    assert.ok(wheels++ < 99, 'ordinary scroll reaches the end of the 500-resource page');
                    advanced = await scrollAndWait(page, viewport, direction, step);
                } while (!advanced);
            }
        }
    });
