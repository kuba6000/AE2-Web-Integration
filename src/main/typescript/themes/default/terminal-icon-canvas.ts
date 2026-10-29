import type { IconSprite } from '../../app/icons.js';
import { paintResourceIcon } from './resource-icon.js';

type Entry = {
    icon: HTMLElement;
    sprite: IconSprite;
    painted?: boolean;
    box?: { x: number; y: number; width: number; height: number };
};

/** The viewport owns one drawing surface; icon observations retain ownership of atlas pages. */
export function createTerminalIconCanvas(viewport: HTMLElement) {
    const layer = document.createElement('div');
    layer.className = 'terminal-icon-layer';
    layer.setAttribute('aria-hidden', 'true');
    const canvas = document.createElement('canvas');
    layer.append(canvas);
    viewport.prepend(layer);
    const context = canvas.getContext('2d');
    const entries = new Map<HTMLElement, Entry>();
    let dirty = true;
    let frame = 0;
    let disposed = false;

    function clear(entry: Entry) {
        if (context && entry.box) {
            const { x, y, width, height } = entry.box;
            context.clearRect(x, y, width, height);
        }
    }

    function draw(entry: Entry) {
        const { sprite, box } = entry;
        if (!context || !box || !sprite.image?.width) return;
        clear(entry);
        context.drawImage(
            sprite.image,
            sprite.x,
            sprite.y,
            sprite.width,
            sprite.height,
            box.x,
            box.y,
            box.width,
            box.height
        );
        if (!entry.painted) {
            entry.icon.classList.add('resource-icon-ready', 'resource-icon-on-canvas');
            entry.painted = true;
        }
    }

    function layout() {
        frame = 0;
        if (disposed || !context) return;
        dirty = false;
        if (!entries.size) {
            canvas.width = canvas.height = 0;
            return;
        }
        const bounds = layer.getBoundingClientRect();
        const height = viewport.clientHeight;
        const scale = devicePixelRatio;
        for (const entry of entries.values()) {
            const box = entry.icon.getBoundingClientRect();
            const x = box.left - bounds.left;
            const y = box.top - bounds.top;
            entry.box =
                box.width && box.height && x < bounds.width && y < height && x + box.width > 0 && y + box.height > 0
                    ? { x, y, width: box.width, height: box.height }
                    : undefined;
        }
        canvas.width = Math.ceil(bounds.width * scale);
        canvas.height = Math.ceil(height * scale);
        canvas.style.width = `${bounds.width}px`;
        canvas.style.height = `${height}px`;
        context.setTransform(scale, 0, 0, scale, 0, 0);
        for (const entry of entries.values()) draw(entry);
    }

    function invalidate() {
        if (disposed) return;
        dirty = true;
        if (!frame) frame = requestAnimationFrame(layout);
    }

    const resize = new ResizeObserver(invalidate);
    resize.observe(viewport);
    for (const grid of viewport.querySelectorAll('#items, #pinned-items')) resize.observe(grid);
    const mutations = new MutationObserver((records) => {
        if (
            records.some(
                (record) =>
                    (record.type === 'attributes' &&
                        (record.attributeName === 'data-display' ||
                            (record.target as HTMLElement).id === 'terminal-pins') &&
                        (record.target as HTMLElement).getAttribute(record.attributeName!) !== record.oldValue) ||
                    [...record.addedNodes, ...record.removedNodes].some(
                        (node) =>
                            node instanceof HTMLElement &&
                            (node.matches('li, .resource-icon') || node.querySelector('.resource-icon'))
                    )
            )
        )
            invalidate();
    });
    mutations.observe(viewport, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeOldValue: true,
        attributeFilter: ['data-display', 'hidden']
    });
    viewport.addEventListener('scroll', invalidate, { passive: true });
    window.addEventListener('resize', invalidate);

    return {
        invalidate,
        paint(target: HTMLElement, sprite: IconSprite | null) {
            const previous = entries.get(target);
            const icon = target.querySelector<HTMLElement>('.resource-icon');
            if (!context || !sprite?.image || !icon) {
                if (previous) {
                    clear(previous);
                    previous.icon.classList.remove('resource-icon-on-canvas');
                    if (!sprite) previous.icon.classList.remove('resource-icon-ready');
                    entries.delete(target);
                    if (!entries.size) invalidate();
                }
                paintResourceIcon(target, sprite);
                return;
            }
            if (previous?.icon === icon) {
                const before = previous.sprite;
                const changed =
                    before.image !== sprite.image ||
                    before.x !== sprite.x ||
                    before.y !== sprite.y ||
                    before.width !== sprite.width ||
                    before.height !== sprite.height;
                previous.sprite = sprite;
                if (!dirty && changed) draw(previous);
            } else {
                if (previous) {
                    clear(previous);
                    previous.icon.classList.remove('resource-icon-on-canvas', 'resource-icon-ready');
                }
                // The page is already decoded. Keep the placeholder until geometry is ready rather
                // than making the browser decode the same atlas again as a CSS background.
                entries.set(target, { icon, sprite });
                invalidate();
            }
        },
        dispose() {
            disposed = true;
            cancelAnimationFrame(frame);
            resize.disconnect();
            mutations.disconnect();
            viewport.removeEventListener('scroll', invalidate);
            window.removeEventListener('resize', invalidate);
            for (const entry of entries.values())
                entry.icon.classList.remove('resource-icon-on-canvas', 'resource-icon-ready');
            entries.clear();
            canvas.width = canvas.height = 0;
            layer.remove();
        }
    };
}
