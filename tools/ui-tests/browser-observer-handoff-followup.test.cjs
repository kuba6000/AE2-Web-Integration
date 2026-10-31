const { test } = require('node:test');
const assert = require('node:assert/strict');
const { fixture, atlasFixture, gridA, iron, pauseClock } = require('./fixtures.cjs');
const { iconColor, waitIconColor } = require('./icon-pixels.cjs');

async function controlVisibility(page) {
    // Deliver an allowed ordered batch explicitly; scrolling alone cannot reliably reproduce it.
    await page.addInitScript(() => {
        const NativeObserver = window.IntersectionObserver;
        const deliveries = {};
        window.IntersectionObserver = class extends NativeObserver {
            constructor(callback, options) {
                const controlled = options?.root?.id === 'item-scroll';
                super((entries, observer) => {
                    if (!controlled) callback(entries, observer);
                }, options);
                if (controlled) deliveries[options.rootMargin ? 'nearby' : 'viewport'] = { callback, observer: this };
            }
        };
        window.deliverVisibility = (kind, changes) => {
            const { callback, observer } = deliveries[kind];
            const targets = document.querySelectorAll('#items .item');
            const entries = changes.map(([index, isIntersecting]) => {
                const target = targets[index];
                const bounds = target.getBoundingClientRect();
                return {
                    target,
                    isIntersecting,
                    time: performance.now(),
                    rootBounds: observer.root.getBoundingClientRect(),
                    boundingClientRect: bounds,
                    intersectionRect: isIntersecting ? bounds : new DOMRect(),
                    intersectionRatio: isIntersecting ? 1 : 0
                };
            });
            callback(entries, observer);
        };
    });
}

async function handoffFixture(t, frames) {
    const result = await fixture(t, '', { viewport: { width: 1000, height: 650 } });
    const { page, options } = result;
    await atlasFixture(page, options);
    options.icons.pages.push({ digest: 'c'.repeat(64), width: 128, height: 64 });
    options.icons.animations = [{ page: 1, frames }];
    options.itemsA = [0, 1].map((index) => ({
        ...iron,
        itemKey: `resource-${index}`,
        displayName: `Resource ${index}`,
        icon: { page: 0, x: 0, y: 0, animation: 0 }
    }));
    await controlVisibility(page);
    await page.goto(`${result.base}#/web-settings`);
    await page.getByRole('slider', { name: 'Animation update interval', exact: true }).press('Home');
    return result;
}

async function openItems(page, base) {
    await page.goto(`${base}#/grids/${gridA}/items`);
    await page.getByRole('button', { name: /Resource 0/ }).waitFor();
    await page.getByRole('button', { name: /Resource 1/ }).waitFor();
    return [
        page.getByRole('button', { name: /Resource 0/ }).locator('.resource-icon'),
        page.getByRole('button', { name: /Resource 1/ }).locator('.resource-icon')
    ];
}

async function deliver(page, kind, changes) {
    await page.evaluate(({ kind, changes }) => window.deliverVisibility(kind, changes), { kind, changes });
}

async function beginHandoff(page, kind) {
    const other = kind === 'viewport' ? 'nearby' : 'viewport';
    await deliver(page, other, [
        [0, true],
        [1, true]
    ]);
    await deliver(page, kind, [[1, true]]);
}

const animationPage = 'c'.repeat(64);

for (const kind of ['viewport', 'nearby'])
    test(`a pending shared animation page survives an outgoing-first ${kind} batch`, async (t) => {
        const { page, options, base } = await handoffFixture(t, [
            { x: 64, y: 0, durationMillis: 60000 },
            { x: 0, y: 0, durationMillis: 60000 }
        ]);
        const requests = [];
        const failures = [];
        page.on('request', (request) => {
            if (request.url().endsWith(animationPage)) requests.push(request);
        });
        page.on('requestfailed', (request) => {
            if (request.url().endsWith(animationPage)) failures.push(request);
        });
        let capture;
        const captured = new Promise((resolve) => {
            capture = resolve;
        });
        let routed = 0;
        await page.route(`**/pages/${animationPage}`, (route) => {
            if (++routed === 1) capture(route);
            else return route.fulfill({ status: 200, contentType: 'image/png', body: options.atlas });
        });
        const [incoming, outgoing] = await openItems(page, base);
        const started = page.waitForRequest(`**/pages/${animationPage}`);
        await beginHandoff(page, kind);
        await started;
        const pending = await captured;
        await waitIconColor(page, outgoing, 'red');
        await deliver(page, kind, [
            [1, false],
            [0, true]
        ]);
        await pending.fulfill({ status: 200, contentType: 'image/png', body: options.atlas }).catch(() => {});
        await waitIconColor(page, incoming, 'green');
        assert.equal(requests.length, 1, 'the pending shared page is not requested again');
        assert.equal(failures.length, 0, 'the pending shared page is not canceled');
    });

for (const kind of ['viewport', 'nearby'])
    test(`a ready shared animation page and its current frame survive an outgoing-first ${kind} batch`, async (t) => {
        const { page, base } = await handoffFixture(t, [
            { x: 0, y: 0, durationMillis: 5000 },
            { x: 64, y: 0, durationMillis: 5000 }
        ]);
        const requests = [];
        page.on('request', (request) => {
            if (request.url().endsWith(animationPage)) requests.push(request);
        });
        const [incoming, outgoing] = await openItems(page, base);
        const response = page.waitForResponse((entry) => entry.url().endsWith(animationPage));
        await beginHandoff(page, kind);
        await (await response).finished();
        await pauseClock(page);
        // A red-to-green transition under the paused clock leaves ample time in the green hold.
        for (const expected of ['red', 'green']) {
            let color = await iconColor(page, outgoing);
            for (let step = 0; color !== expected && step < 12; step++) {
                await page.clock.runFor(1000);
                color = await iconColor(page, outgoing);
            }
            assert.equal(color, expected, `the outgoing resource reaches the ${expected} frame`);
        }
        await deliver(page, kind, [
            [1, false],
            [0, true]
        ]);
        await page.clock.runFor(50);
        assert.equal(await iconColor(page, incoming), 'green', 'the incoming resource keeps the shared current frame');
        assert.equal(requests.length, 1, 'the ready shared page is not fetched again');
    });

test('a theme can replace a target during painting without restoring its old icon', async (t) => {
    const { page, options, base } = await fixture(t);
    await atlasFixture(page, options);
    options.icons.pages.push({ digest: animationPage, width: 128, height: 64 });
    options.icons.animations = [
        {
            page: 1,
            frames: [
                { x: 64, y: 0, durationMillis: 5000 },
                { x: 0, y: 0, durationMillis: 5000 }
            ]
        }
    ];
    await controlVisibility(page);
    options.themeEntry = `
        import { boot } from './assets/web/app/boot.js';
        boot(document.getElementById('app'), {
            id: 'handoff-theme', name: 'Handoff theme', supportedModVersion: '1.2.x', views: ['home'],
            mount(root, application) {
                root.innerHTML = '<button id="replace">Replace on next paint</button><output aria-label="Replacement performed" hidden>Replacement performed</output><div id="item-scroll"><div id="items"><button class="item"><span class="resource-icon"></span></button><button class="item"><span class="resource-icon"></span></button></div></div>';
                const [first, second] = root.querySelectorAll('.item');
                const metadata = ${JSON.stringify(options.icons)};
                const original = { page: 0, x: 0, y: 0, animation: 0 };
                const replacement = { page: 0, x: 64, y: 0 };
                let armed = false;
                const view = application.icons.observe(root.querySelector('#item-scroll'), (target, sprite) => {
                    const icon = target.querySelector('.resource-icon');
                    icon.style.cssText = 'display:block;width:32px;height:32px;background-color:white;background-size:64px 32px';
                    icon.style.backgroundImage = sprite ? 'url("' + sprite.url + '")' : 'none';
                    icon.style.backgroundPosition = sprite ? (-sprite.x / 2) + 'px ' + (-sprite.y / 2) + 'px' : '0 0';
                    if (armed && target === first && sprite) {
                        armed = false;
                        view.update([{ element: first, icon: original }], metadata);
                        view.update([{ element: first, icon: original }, { element: second, icon: replacement }], metadata);
                        root.querySelector('output').hidden = false;
                    }
                });
                view.update([{ element: first, icon: original }, { element: second, icon: original }], metadata);
                application.displayIcons(true);
                application.icons.animate(true);
                root.querySelector('#replace').onclick = () => { armed = true; };
            }
        });`;
    await page.goto(base);
    const first = page.locator('#items .resource-icon').first();
    const second = page.locator('#items .resource-icon').last();
    await first.waitFor();
    await beginHandoff(page, 'viewport');
    await waitIconColor(page, first, 'red');
    await waitIconColor(page, second, 'green');
    await page.getByRole('button', { name: 'Replace on next paint' }).click();
    await deliver(page, 'viewport', [
        [1, false],
        [0, true]
    ]);
    assert.equal(await page.getByRole('status', { name: 'Replacement performed' }).isVisible(), true);
    assert.equal(await iconColor(page, second), '255,255,255,255', 'the replaced target keeps its placeholder');
    await deliver(page, 'nearby', [[1, true]]);
    await deliver(page, 'viewport', [[1, true]]);
    await waitIconColor(page, second, 'green');
});
