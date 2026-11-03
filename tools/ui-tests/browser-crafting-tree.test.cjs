const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { fixture, gridA, readyPlan, atlasFixture } = require('./fixtures.cjs');

const node = (kind, id, extra = {}) => ({
    kind,
    id,
    resourceId: null,
    recipeId: null,
    crafts: null,
    multiplier: null,
    complete: null,
    candidates: null,
    contribution: null,
    children: [],
    ...extra
});
const edge = (relation, nodeId, quantity, missing = false) => ({ relation, nodeId, quantity, primary: null, missing });
const names = ['Machine', 'Plate', 'Rod', 'Ore'];
const resources = names.map((displayName, id) => ({
    id,
    displayName,
    registryNamespace: 'fixture',
    registryPath: `resource_${id}`,
    componentCount: 0,
    damage: 0,
    resourceType: 'ITEM',
    itemKey: null,
    icon: { page: 0, x: (id % 2) * 64, y: 0 },
    missingAmount: '0'
}));
function tree(url) {
    return {
        status: 'OK',
        icons: url.searchParams.get('icons') === 'true' ? tree.icons : null,
        data: {
            state: 'READY',
            mode: 'AGGREGATE',
            captureId: 'capture',
            root: { resourceId: 0, quantity: '1', simulated: false },
            roots: [0],
            resources,
            recipes: [
                {
                    id: 0,
                    inputs: [],
                    outputs: [
                        { resourceId: 0, quantity: '1', primary: true },
                        { resourceId: 1, quantity: '2', primary: false }
                    ]
                }
            ],
            nodes: [
                node('RESOURCE', 0, { resourceId: 0, children: [edge('DECLARED_OUTPUT', 4, '1')] }),
                node('RESOURCE', 1, { resourceId: 1 }),
                node('RESOURCE', 2, { resourceId: 2 }),
                node('RESOURCE', 3, { resourceId: 3 }),
                node('RECIPE', 4, {
                    recipeId: 0,
                    crafts: '2',
                    complete: true,
                    children: [edge('INGREDIENT', 5, null), edge('INGREDIENT', 6, null)]
                }),
                node('INGREDIENT', 5, {
                    candidates: 1,
                    multiplier: '1',
                    children: [edge('DECLARED_ALTERNATIVE', 1, '3')]
                }),
                node('INGREDIENT', 6, {
                    candidates: 2,
                    multiplier: '1',
                    children: [edge('DECLARED_ALTERNATIVE', 2, '1'), edge('DECLARED_ALTERNATIVE', 3, '1')]
                })
            ],
            partial: false,
            limits: [],
            missingComplete: true,
            missingResourceCount: 0
        }
    };
}

async function openTree(t, viewport = { width: 1280, height: 900 }, source = tree, display = 'both') {
    const { page, options, base } = await fixture(t, '', { viewport }, { mockClock: false });
    await atlasFixture(page, options);
    await page.addInitScript(
        (terminalDisplay) => localStorage.setItem('ae2web:/:theme:default', JSON.stringify({ terminalDisplay })),
        display
    );
    source.icons = options.icons;
    options.tree = source;
    options.plan = { ...readyPlan, plan: [{ ...readyPlan.plan[0], icon: { page: 0, x: 0, y: 0 } }] };
    await page.goto(`${base}#/grids/${gridA}/plans/7`);
    await page.getByRole('button', { name: 'Tree', exact: true }).click();
    return page;
}
const card = (page, title) => page.locator('.tree-card', { has: page.locator('strong', { hasText: title }) });
async function expand(page, title) {
    const target = card(page, title).first();
    await target.click();
    await page.waitForFunction(
        (name) =>
            [...document.querySelectorAll('.tree-card')].some(
                (item) => item.querySelector('strong')?.textContent === name && item.ariaExpanded === 'true'
            ),
        title,
        { timeout: 5000 }
    );
}
async function openRecipe(page) {
    // The calculated target opens by itself; its recipe is the first card to open.
    await expand(page, 'Recipe');
    await card(page, 'Plate').first().waitFor();
}

test('tree cards show resource icons and recipes show only their craft count', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    const icon = page.locator('.tree-card .resource-icon').first();
    await icon.waitFor({ state: 'visible' });
    const box = await icon.boundingBox();
    assert.ok(box.width >= 20 && box.height >= 20, `icon is sized: ${box.width}x${box.height}`);
    assert.match(await icon.evaluate((element) => element.style.backgroundImage), /url\(/);
    const cardBox = await card(page, 'Machine').first().boundingBox();
    assert.ok(box.x >= cardBox.x && box.x + box.width <= cardBox.x + cardBox.width, 'icon stays inside its card');
    const details = await card(page, 'Recipe').locator('.tree-details').textContent();
    assert.match(details, /2/);
    assert.doesNotMatch(details, /Plate|Machine/);
});

test('a visible resource icon stays painted while a branch opens and closes', async (t) => {
    const page = await openTree(t);
    const machine = card(page, 'Machine').first();
    await machine.locator('.resource-icon').waitFor({ state: 'visible' });
    await page.waitForFunction(() => {
        const icon = [...document.querySelectorAll('.tree-card')]
            .find((item) => item.querySelector('strong')?.textContent === 'Machine')
            ?.querySelector('.resource-icon');
        return icon && getComputedStyle(icon).backgroundImage !== 'none';
    });
    await page.evaluate(() => {
        window.treeIconSamples = [];
        const sample = () => {
            const icon = [...document.querySelectorAll('.tree-card')]
                .find((item) => item.querySelector('strong')?.textContent === 'Machine')
                ?.querySelector('.resource-icon');
            window.treeIconSamples.push(!!icon && getComputedStyle(icon).backgroundImage !== 'none');
        };
        const observer = new MutationObserver(sample);
        observer.observe(document.querySelector('.tree-content'), { attributes: true, childList: true, subtree: true });
        window.stopTreeIconSamples = () => {
            observer.disconnect();
            sample();
            return window.treeIconSamples;
        };
        sample();
    });
    await expand(page, 'Recipe');
    await card(page, 'Recipe').first().click();
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.tree-card')].some(
            (item) => item.querySelector('strong')?.textContent === 'Recipe' && item.ariaExpanded === 'false'
        )
    );
    const samples = await page.evaluate(() => window.stopTreeIconSamples());
    assert.ok(samples.every(Boolean), `resource icon remained painted: ${samples}`);
});

test('a recipe has only ingredient children and a single-candidate group shows its candidate directly', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    const titles = await page.locator('.tree-card strong').allTextContents();
    assert.equal(titles.filter((title) => title === 'Ingredient alternatives').length, 1, 'only the two-way group');
    assert.ok(titles.includes('Plate'), 'the single candidate stands in for its group');
    assert.equal(titles.filter((title) => title === 'Machine').length, 1, 'outputs are not child cards');
    assert.deepEqual(
        titles.filter((title) => title !== 'Ingredient alternatives'),
        ['Machine', 'Recipe', 'Plate']
    );
});

test('the wheel zooms around the cursor and ctrl+wheel zooms too, only inside the viewport', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    await page.evaluate(() => {
        window.wheels = [];
        window.addEventListener('wheel', (event) => window.wheels.push(event.defaultPrevented));
    });
    const percent = async () => Number((await page.locator('.tree-controls button').nth(1).textContent()).slice(0, -1));
    const target = card(page, 'Plate').first();
    const box = await target.boundingBox();
    const point = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    const before = await percent();
    await page.mouse.move(point.x, point.y);
    await page.mouse.wheel(0, 300);
    await page.waitForFunction(
        (previous) => document.querySelector('.tree-controls button:nth-child(2)').textContent !== `${previous}%`,
        before
    );
    const out = await percent();
    assert.ok(out < before, `wheel down zooms out: ${before} -> ${out}`);
    for (const delta of [-300, -300, -300]) await page.mouse.wheel(0, delta);
    await page.waitForFunction(
        (previous) => parseInt(document.querySelector('.tree-controls button:nth-child(2)').textContent) > previous,
        out
    );
    const hit = await page.evaluate(
        ({ x, y }) => document.elementFromPoint(x, y)?.closest('.tree-card')?.querySelector('strong')?.textContent,
        point
    );
    assert.equal(hit, 'Plate', 'the point under the cursor stays under the cursor');
    const pinched = await percent();
    await page.keyboard.down('Control');
    await page.mouse.wheel(0, 100);
    await page.keyboard.up('Control');
    await page.waitForFunction(
        (previous) => parseInt(document.querySelector('.tree-controls button:nth-child(2)').textContent) < previous,
        pinched
    );
    const toolbar = await page.locator('.tree-controls').boundingBox();
    await page.mouse.move(toolbar.x + 2, toolbar.y + 2);
    await page.mouse.wheel(0, 100);
    await page.waitForFunction(() => window.wheels.length === 6);
    const wheels = await page.evaluate(() => window.wheels);
    assert.ok(wheels.slice(0, -1).every(Boolean), 'wheel over the diagram is taken over');
    assert.equal(wheels.at(-1), false, 'wheel elsewhere is left alone');
    await page.getByRole('button', { name: 'Zoom in', exact: true }).click();
});

test('wheel zoom keeps the exact diagram point under the cursor fixed', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    await expand(page, 'Ingredient alternatives');
    await page.getByRole('button', { name: 'Zoom in', exact: true }).click();
    await page.locator('.tree-viewport').evaluate((element) => {
        element.scrollLeft = (element.scrollWidth - element.clientWidth) / 2;
        element.scrollTop = (element.scrollHeight - element.clientHeight) / 2;
    });
    const target = card(page, 'Plate').first();
    const fraction = async (point) => {
        const box = await target.boundingBox();
        return { x: (point.x - box.x) / box.width, y: (point.y - box.y) / box.height };
    };
    const box = await target.boundingBox();
    const point = { x: box.x + box.width * 0.3, y: box.y + box.height * 0.3 };
    const before = await fraction(point);
    await page.mouse.move(point.x, point.y);
    const label = () => page.locator('.tree-controls button').nth(1).textContent();
    const start = await label();
    await page.mouse.wheel(0, -200);
    await page.waitForFunction(
        (previous) => document.querySelector('.tree-controls button:nth-child(2)').textContent !== previous,
        start
    );
    const after = await fraction(point);
    assert.ok(
        Math.abs(after.x - before.x) < 0.02 && Math.abs(after.y - before.y) < 0.02,
        `${JSON.stringify([before, after])}`
    );
});

test('dragging pans the diagram without selecting text or pressing the card it started on', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    await page.getByRole('button', { name: 'Zoom in', exact: true }).click();
    for (let i = 0; i < 4; i++) await page.getByRole('button', { name: 'Zoom in', exact: true }).click();
    const viewport = page.locator('.tree-viewport');
    assert.equal(await viewport.evaluate((element) => getComputedStyle(element).cursor), 'grab');
    const target = card(page, 'Recipe').first();
    const box = await target.boundingBox();
    const scroll = () => viewport.evaluate((element) => [element.scrollLeft, element.scrollTop]);
    const [left, top] = await scroll();
    await page.mouse.move(box.x + 120, box.y + 60);
    await page.mouse.down();
    await page.mouse.move(box.x + 100, box.y + 50, { steps: 5 });
    assert.equal(await viewport.evaluate((element) => getComputedStyle(element).cursor), 'grabbing');
    await page.mouse.move(box.x + 40, box.y + 20, { steps: 5 });
    await page.mouse.up();
    const [movedLeft, movedTop] = await scroll();
    assert.ok(
        movedLeft > left || movedTop > top,
        `the diagram followed the pointer: ${left},${top} -> ${movedLeft},${movedTop}`
    );
    assert.equal(await page.evaluate(() => getSelection().toString()), '');
    assert.equal(await target.getAttribute('aria-expanded'), 'true', 'the drag did not collapse the recipe');
    assert.equal(await viewport.evaluate((element) => element.dataset.dragging ?? null), null);
    const again = await target.boundingBox();
    const viewportBox = await viewport.boundingBox();
    const clickX = Math.max(again.x + 20, viewportBox.x + 8);
    const clickY = Math.max(again.y + 20, viewportBox.y + 8);
    await page.mouse.move(clickX, clickY);
    await page.mouse.down();
    await page.mouse.move(clickX + 2, clickY + 1);
    await page.mouse.up();
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.tree-card')].some(
            (item) => item.querySelector('strong')?.textContent === 'Recipe' && item.ariaExpanded === 'false'
        )
    );
});

test('dragging from empty space across the cards selects no text', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    const box = await page.locator('.tree-viewport').boundingBox();
    await page.mouse.move(box.x + 4, box.y + 4);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height - 30, { steps: 10 });
    const during = await page.evaluate(() => getSelection().toString());
    await page.mouse.up();
    assert.equal(during, '');
    assert.equal(await page.evaluate(() => getSelection().toString()), '');
});

test('tree export offers PNG and SVG with the current tree icons', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    await page.getByRole('button', { name: 'Export current tree', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export current tree', exact: true });
    const download = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download PNG', exact: true }).click();
    const bytes = await fs.readFile(await (await download).path());
    assert.equal(bytes.subarray(1, 4).toString(), 'PNG');
    const colors = await page.evaluate(async (data) => {
        const bitmap = await createImageBitmap(await (await fetch(`data:image/png;base64,${data}`)).blob());
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(bitmap, 0, 0);
        const values = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        let red = 0,
            green = 0;
        for (let i = 0; i < values.length; i += 4) {
            if (values[i] === 255 && values[i + 1] === 0 && values[i + 2] === 0) red++;
            if (values[i] === 0 && values[i + 1] === 255 && values[i + 2] === 0) green++;
        }
        return { red, green };
    }, bytes.toString('base64'));
    assert.ok(colors.red > 0 && colors.green > 0, `both icons were painted: ${JSON.stringify(colors)}`);
    await dialog.getByRole('combobox', { name: 'Format', exact: true }).selectOption('svg');
    const vectorDownload = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download SVG', exact: true }).click();
    const svg = await fs.readFile(await (await vectorDownload).path(), 'utf8');
    const vector = await page.evaluate(async (xml) => {
        const parsed = new DOMParser().parseFromString(xml, 'image/svg+xml');
        const image = new Image();
        image.src = URL.createObjectURL(new Blob([xml], { type: 'image/svg+xml' }));
        await image.decode();
        URL.revokeObjectURL(image.src);
        return {
            errors: parsed.querySelectorAll('parsererror').length,
            text: parsed.documentElement.textContent,
            width: image.naturalWidth,
            images: parsed.querySelectorAll('image').length
        };
    }, svg);
    assert.equal(vector.errors, 0);
    assert.ok(vector.width > 0 && vector.text.includes('Machine') && vector.text.includes('Plate'));
    assert.ok(vector.images > 0);
});

// A TRACE plan: four machines, one from storage and three crafted from plates and rods.
function traceTree(url) {
    return {
        status: 'OK',
        icons: url.searchParams.get('icons') === 'true' ? traceTree.icons : null,
        data: {
            state: 'READY',
            mode: 'TRACE',
            captureId: 'trace',
            root: { resourceId: 0, quantity: '4', simulated: false },
            roots: [0],
            resources,
            recipes: [
                {
                    id: 0,
                    inputs: [
                        { multiplier: '1', candidates: [{ resourceId: 1, quantity: '2' }] },
                        { multiplier: '1', candidates: [{ resourceId: 2, quantity: '1' }] }
                    ],
                    outputs: [{ resourceId: 0, quantity: '1', primary: true }]
                }
            ],
            nodes: [
                node('REQUEST', 0, {
                    resourceId: 0,
                    children: [edge('CONTRIBUTION', 4, '1'), edge('CRAFT', 3, '3', true)]
                }),
                node('REQUEST', 1, {
                    resourceId: 1,
                    children: [edge('CONTRIBUTION', 5, '2', true), edge('CONTRIBUTION', 6, '4')]
                }),
                node('REQUEST', 2, { resourceId: 2, children: [edge('CONTRIBUTION', 7, '3')] }),
                node('TASK', 3, {
                    recipeId: 0,
                    crafts: '3',
                    complete: true,
                    children: [edge('CHILD_REQUEST', 1, '6', true), edge('CHILD_REQUEST', 2, '3')]
                }),
                node('CONTRIBUTION', 4, { resourceId: 0, contribution: 'STORAGE' }),
                node('CONTRIBUTION', 5, { resourceId: 1, contribution: 'MISSING' }),
                node('CONTRIBUTION', 6, { resourceId: 1, contribution: 'STORAGE' }),
                node('CONTRIBUTION', 7, { resourceId: 2, contribution: 'STORAGE' })
            ],
            partial: false,
            limits: [],
            missingComplete: true,
            missingResourceCount: 1
        }
    };
}
const openTrace = (t, viewport) => openTree(t, viewport, traceTree);
const caveatText = (page) => page.locator('.tree-caveat').evaluate((element) => element.textContent.trim());

test('tree artwork is centered and overlapping controls remain visible in each display mode', async (t) => {
    const failures = [];
    for (const display of ['icons', 'both', 'names']) {
        const page = await openTree(t, { width: 1280, height: 900 }, traceTree, display);
        const root = card(page, 'Machine').first();
        const task = card(page, 'Recipe').first();
        const storage = page.locator('.tree-card[data-kind="CONTRIBUTION"]').first();
        await task.locator('.tree-symbol svg').waitFor();
        if (display !== 'names') await storage.locator('.resource-icon-ready').waitFor();

        const centered = async (target, selector, horizontal = false) => {
            const tile = await target.boundingBox();
            const artwork = await target.locator(selector).boundingBox();
            const deltaX = artwork.x + artwork.width / 2 - tile.x - tile.width / 2;
            const deltaY = artwork.y + artwork.height / 2 - tile.y - tile.height / 2;
            if (Math.abs(deltaY) > 0.5 || (horizontal && Math.abs(deltaX) > 0.5))
                failures.push(`${display} ${selector} center offset: ${deltaX}, ${deltaY}`);
        };
        await centered(task, '.tree-symbol', display === 'icons');
        if (display === 'names') await centered(storage, '.tree-symbol');
        else {
            await centered(root, '.resource-icon', display === 'icons');
            await centered(storage, '.resource-icon', display === 'icons');

            // Deliberately place controls over opaque fixture artwork so the pixels prove the paint order.
            await storage.locator('.tree-symbol').evaluate((element) => (element.style.backgroundColor = '#02111f'));
            await root.evaluate((element) => {
                const marker = element.querySelector('.tree-marker');
                Object.assign(marker.style, {
                    position: 'absolute',
                    left: '20px',
                    right: 'auto',
                    top: element.closest('.plan-tree').dataset.display === 'icons' ? '19px' : '42px',
                    backgroundColor: '#123456'
                });
                const amount = element.querySelector('.tree-amount');
                Object.assign(amount.style, {
                    left: '30px',
                    right: 'auto',
                    top: '35px',
                    bottom: 'auto',
                    backgroundColor: '#563412'
                });
            });

            const paintedAboveIcon = async (target, selector, color) => {
                const screenshot = (await target.screenshot()).toString('base64');
                const intersection = await target.evaluate((element, overlaySelector) => {
                    const tile = element.getBoundingClientRect();
                    const icon = element.querySelector('.resource-icon').getBoundingClientRect();
                    const overlay = element.querySelector(overlaySelector).getBoundingClientRect();
                    return {
                        left: Math.ceil(Math.max(icon.left, overlay.left) - tile.left),
                        right: Math.floor(Math.min(icon.right, overlay.right) - tile.left),
                        top: Math.ceil(Math.max(icon.top, overlay.top) - tile.top),
                        bottom: Math.floor(Math.min(icon.bottom, overlay.bottom) - tile.top)
                    };
                }, selector);
                const painted = await page.evaluate(
                    async ({ data, bounds, rgb }) => {
                        const image = await createImageBitmap(
                            await (await fetch(`data:image/png;base64,${data}`)).blob()
                        );
                        const canvas = document.createElement('canvas');
                        canvas.width = image.width;
                        canvas.height = image.height;
                        const ctx = canvas.getContext('2d');
                        ctx.drawImage(image, 0, 0);
                        const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
                        for (let y = bounds.top; y < bounds.bottom; y++) {
                            for (let x = bounds.left; x < bounds.right; x++) {
                                const offset = (y * canvas.width + x) * 4;
                                if (rgb.every((value, index) => pixels[offset + index] === value)) return true;
                            }
                        }
                        return false;
                    },
                    { data: screenshot, bounds: intersection, rgb: color }
                );
                if (!painted) failures.push(`${display} ${selector} is not visible over the resource icon`);
            };
            await paintedAboveIcon(storage, '.tree-symbol', [2, 17, 31]);
            await paintedAboveIcon(root, '.tree-marker', [18, 52, 86]);
            if (display === 'icons') await paintedAboveIcon(root, '.tree-amount', [86, 52, 18]);
        }
    }
    assert.deepEqual(failures, []);
});

async function exportScope(page) {
    await page.getByRole('button', { name: 'Export current tree', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export current tree', exact: true });
    return (await dialog.locator('p').first().textContent()).trim();
}

test('a trace shows requests, leaves and a task card like the in-game tree and makes no approximation claim', async (t) => {
    const page = await openTrace(t);
    await card(page, 'Machine').first().waitFor();
    await expand(page, 'Recipe');
    await card(page, 'Plate').first().waitFor();
    await card(page, 'Rod').first().waitFor();
    const task = await card(page, 'Recipe').locator('.tree-details').textContent();
    assert.doesNotMatch(task, /Machine|Plate|Rod/);
    assert.match(task, /3/);
    await expand(page, 'Plate');
    await page.locator('.tree-card[data-kind="CONTRIBUTION"]').nth(2).waitFor();
    const leaves = await page.locator('.tree-card[data-kind="CONTRIBUTION"]').evaluateAll((cards) =>
        cards.map((element) => ({
            title: element.querySelector('strong').textContent,
            missing: element.dataset.missing,
            expandable: element.hasAttribute('aria-expanded')
        }))
    );
    assert.equal(leaves.length, 3);
    assert.ok(leaves.every((entry) => !entry.expandable));
    assert.deepEqual(
        leaves.map((entry) => entry.missing),
        ['false', 'true', 'false']
    );
    assert.deepEqual(
        leaves.map((entry) => entry.title),
        ['Machine', 'Plate', 'Plate']
    );
    assert.equal(await caveatText(page), '');
    assert.equal(await page.locator('.tree-caveat').isVisible(), false);
});

test('only an aggregate tree states that it is an approximation, on screen and in the PNG export notes', async (t) => {
    const aggregate = await openTree(t);
    await aggregate.locator('.tree-card').first().waitFor();
    assert.notEqual(await caveatText(aggregate), '');
    assert.equal(await aggregate.locator('.tree-caveat').isVisible(), true);
    const aggregateNotes = await exportScope(aggregate);
    const trace = await openTrace(t);
    await trace.locator('.tree-card').first().waitFor();
    const traceNotes = await exportScope(trace);
    assert.ok(traceNotes.length > 0);
    assert.ok(
        aggregateNotes.length > traceNotes.length,
        'the aggregate export adds notes that a trace export does not'
    );
    assert.ok(aggregateNotes.startsWith(traceNotes.slice(0, 20)), 'both exports share the scope notes');
});

test('the trace tree exports to PNG with its leaves and cards', async (t) => {
    const page = await openTrace(t);
    await expand(page, 'Recipe');
    await page.getByRole('button', { name: 'Export current tree', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export current tree', exact: true });
    const download = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download PNG', exact: true }).click();
    const bytes = await fs.readFile(await (await download).path());
    assert.equal(bytes.subarray(1, 4).toString(), 'PNG');
    assert.ok(bytes.length > 1000);
});

test('compact tree keeps a clicked task anchored and shows resource, source, and craft icons', async (t) => {
    const page = await openTree(t, { width: 1280, height: 900 }, traceTree, 'icons');
    const task = card(page, 'Recipe').first();
    const before = await task.boundingBox();
    assert.ok(before.width <= 72 && before.height <= 72, `compact task: ${JSON.stringify(before)}`);
    const root = card(page, 'Machine').first();
    const rootBox = await root.boundingBox();
    assert.ok(rootBox.width <= 72 && rootBox.height <= 72);
    const storage = page.locator('.tree-card[data-kind="CONTRIBUTION"]').first();
    assert.ok(await storage.locator('.tree-symbol svg').count());
    assert.ok(await task.locator('.tree-symbol svg').count());
    await task.click();
    const after = await task.boundingBox();
    assert.ok(
        Math.abs(before.x - after.x) < 1 && Math.abs(before.y - after.y) < 1,
        `task moved: ${JSON.stringify([before, after])}`
    );
    const plate = card(page, 'Plate').first();
    await plate.click();
    assert.ok(await page.locator('.tree-card[data-missing="true"] .tree-symbol').count());
    assert.match(await plate.getAttribute('aria-label'), /Plate/);
    await page.getByRole('button', { name: 'Export current tree', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export current tree', exact: true });
    const download = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download PNG', exact: true }).click();
    const bytes = await fs.readFile(await (await download).path());
    const symbolColors = await page.evaluate(async (data) => {
        const bitmap = await createImageBitmap(await (await fetch(`data:image/png;base64,${data}`)).blob());
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(bitmap, 0, 0);
        const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        let hammer = 0,
            storage = 0;
        for (let i = 0; i < pixels.length; i += 4) {
            if (pixels[i] === 167 && pixels[i + 1] === 179 && pixels[i + 2] === 183) hammer++;
            if (pixels[i] === 161 && pixels[i + 1] === 107 && pixels[i + 2] === 59) storage++;
        }
        return { hammer, storage };
    }, bytes.toString('base64'));
    assert.ok(
        symbolColors.hammer && symbolColors.storage,
        `PNG includes both source symbols: ${JSON.stringify(symbolColors)}`
    );
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('button', { name: 'Expand all', exact: true }).click();
    await page.waitForFunction(() =>
        [...document.querySelectorAll('.tree-controls button')].some(
            (button) => button.textContent === 'Expand all' && button.disabled
        )
    );
    await page.getByRole('button', { name: 'Fit visible nodes', exact: true }).click();
    const beforeCollapse = await root.boundingBox();
    await root.click();
    const afterCollapse = await root.boundingBox();
    assert.ok(
        Math.abs(beforeCollapse.x - afterCollapse.x) < 1 && Math.abs(beforeCollapse.y - afterCollapse.y) < 1,
        `root moved: ${JSON.stringify([beforeCollapse, afterCollapse])}`
    );
});

test('compact mobile outline starts at the viewport edge with its root visible', async (t) => {
    const page = await openTree(t, { width: 600, height: 900 }, traceTree, 'icons');
    const viewport = await page.locator('.tree-viewport').boundingBox();
    const root = await card(page, 'Machine').first().boundingBox();
    assert.ok(root.x >= viewport.x && root.x < viewport.x + 30, JSON.stringify({ viewport, root }));
    assert.ok(root.y >= viewport.y && root.y < viewport.y + 30, JSON.stringify({ viewport, root }));
});
