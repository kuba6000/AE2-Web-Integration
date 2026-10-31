const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { fixture, gridA, readyPlan, atlasFixture } = require('./fixtures.cjs');

const node = (kind, id, extra = {}) => ({
    kind,
    id,
    crafts: kind === 'RECIPE' ? '2' : null,
    multiplier: kind === 'INGREDIENT' ? '1' : null,
    complete: kind === 'RECIPE' ? true : null,
    candidates: null,
    outputs: null,
    ...extra
});
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
const nodes = {
    'RESOURCE:0': node('RESOURCE', 0),
    'RESOURCE:1': node('RESOURCE', 1),
    'RESOURCE:2': node('RESOURCE', 2),
    'RESOURCE:3': node('RESOURCE', 3),
    'RECIPE:0': node('RECIPE', 0, {
        outputs: [
            { resourceId: 0, quantity: '1', primary: true },
            { resourceId: 1, quantity: '2', primary: false }
        ]
    }),
    'INGREDIENT:0': node('INGREDIENT', 0, { candidates: 1 }),
    'INGREDIENT:1': node('INGREDIENT', 1, { candidates: 2 })
};
const child = (relation, key, quantity) => ({ relation, node: nodes[key], quantity, primary: null });
const adjacency = {
    'RESOURCE:0': [child('DECLARED_OUTPUT', 'RECIPE:0', '1')],
    'RECIPE:0': [child('INGREDIENT', 'INGREDIENT:0', null), child('INGREDIENT', 'INGREDIENT:1', null)],
    'INGREDIENT:0': [child('DECLARED_ALTERNATIVE', 'RESOURCE:1', '3')],
    'INGREDIENT:1': [
        child('DECLARED_ALTERNATIVE', 'RESOURCE:2', '1'),
        child('DECLARED_ALTERNATIVE', 'RESOURCE:3', '1')
    ],
    'RESOURCE:1': [],
    'RESOURCE:2': [],
    'RESOURCE:3': []
};
function tree(url) {
    const header = {
        state: 'READY',
        captureId: 'capture',
        root: { resourceId: 0, quantity: '1', simulated: false },
        resources,
        partial: false,
        limits: [],
        unplacedCount: 0,
        unlinkedMissingCount: 0,
        missingOnly: false,
        missingComplete: true,
        missingResourceCount: 0
    };
    const icons = url.searchParams.get('icons') === 'true' ? tree.icons : null;
    const batch = url.searchParams.get('nodes');
    if (batch) {
        const pages = batch.split(',').map((token) => {
            const key = token.split(':').slice(0, 2).join(':');
            return { node: nodes[key], offset: 0, children: adjacency[key], nextOffset: null };
        });
        return { status: 'OK', icons, data: { ...header, pages } };
    }
    const key =
        url.searchParams.get('kind') === 'ROOT'
            ? 'RESOURCE:0'
            : `${url.searchParams.get('kind')}:${url.searchParams.get('id')}`;
    return {
        status: 'OK',
        icons,
        data: { ...header, node: nodes[key], children: adjacency[key], nextOffset: null }
    };
}

async function openTree(t, viewport = { width: 1280, height: 900 }) {
    const { page, options, base } = await fixture(t, '', { viewport }, { mockClock: false });
    await atlasFixture(page, options);
    tree.icons = options.icons;
    options.tree = tree;
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

test('tree cards show their resource icons and a recipe lists its outputs on its own card', async (t) => {
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
    assert.match(details, /Plate/);
    assert.match(details, /Machine/);
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
    await page.mouse.move(again.x + 20, again.y + 20);
    await page.mouse.down();
    await page.mouse.move(again.x + 22, again.y + 21);
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

test('the PNG export paints the tree icons', async (t) => {
    const page = await openTree(t);
    await openRecipe(page);
    await page.getByRole('button', { name: 'Export current tree as PNG', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Export current tree as PNG', exact: true });
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
});
