const assert = require('node:assert/strict');

/** Observe the composited icon, independent of its CSS/canvas rendering implementation. */
async function iconColor(page, icon) {
    const box = await icon.boundingBox();
    assert.ok(box, 'the resource icon has screen coordinates');
    const bytes = await page.screenshot({
        clip: { x: Math.floor(box.x + box.width / 2), y: Math.floor(box.y + box.height / 2), width: 1, height: 1 }
    });
    return page.evaluate(async (data) => {
        const bitmap = await createImageBitmap(
            new Blob([Uint8Array.from(atob(data), (character) => character.charCodeAt(0))], { type: 'image/png' })
        );
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 1;
        const context = canvas.getContext('2d');
        context.drawImage(bitmap, 0, 0, 1, 1);
        bitmap.close();
        const pixel = context.getImageData(0, 0, 1, 1).data;
        if (pixel[0] > 240 && pixel[1] < 15 && pixel[2] < 15) return 'red';
        if (pixel[1] > 240 && pixel[0] < 15 && pixel[2] < 15) return 'green';
        return [...pixel].join(',');
    }, bytes.toString('base64'));
}
async function waitIconColor(page, icon, color) {
    await icon.waitFor({ state: 'attached' });
    let actual;
    const deadline = Date.now() + 5000;
    do {
        actual = await iconColor(page, icon);
        if (actual === color) return;
        await page.waitForTimeout(30);
    } while (Date.now() < deadline);
    assert.equal(actual, color, 'the visible icon reaches its expected frame');
}
module.exports = { iconColor, waitIconColor };
