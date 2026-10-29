import { prepareCanvas, drawText } from '../../app/plan-export.js';
import { planExportFits, type PlanExportSettings } from '../../app/plan-export-layout.js';
import type { IconMetadata } from '../../app/api-types.js';
import type { ThemeApplication } from '../../app/theme-api.js';
import type { craftingTreeContent } from './crafting-tree-content.js';
import type { layoutCraftingTree } from './crafting-tree-layout.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';

export type TreePngScene = {
    layout: ReturnType<typeof layoutCraftingTree>;
    cards: ReturnType<typeof craftingTreeContent>[];
    icons: IconMetadata | null;
    title: string;
    scope: string[];
};
export type TreePngSettings = Pick<PlanExportSettings, 'scale' | 'icon' | 'name' | 'colors' | 'background'>;
export type TreePngStyle = {
    fontFamily: string;
    foreground: string;
    background: string;
    tile: string;
    border: string;
    missing: string;
    textColors: Record<string, string>;
};

/** Image bounds cover the complete selected window, independent of current zoom or viewport clipping. */
export function treePngSize(scene: TreePngScene, requestedScale: number) {
    const header = 48 + scene.scope.length * 22;
    for (const scale of [4, 3, 2, 1.5, 1, 0.75, 0.5, 0.25]) {
        if (scale > requestedScale) continue;
        const size = {
            width: Math.ceil(Math.max(720, scene.layout.width) * scale),
            height: Math.ceil((scene.layout.height + header) * scale),
            scale,
            header
        };
        if (planExportFits(size)) return size;
    }
    return null;
}

/** Renders detached diagram data only; cancellation releases the canvas and resolved atlas bitmaps. */
export async function renderTreePng(
    scene: TreePngScene,
    settings: TreePngSettings,
    style: TreePngStyle,
    icons: ThemeApplication['icons'],
    signal: AbortSignal
) {
    signal.throwIfAborted();
    if (!scene.cards.length) throw new Error('empty');
    let size = treePngSize(scene, settings.scale);
    if (!size) throw new Error('too-large');
    const fonts = document.fonts.load(`13px ${style.fontFamily}`);
    await new Promise<void>((resolve, reject) => {
        const abort = () => reject(signal.reason);
        signal.addEventListener('abort', abort, { once: true });
        fonts.then(() => resolve(), reject).finally(() => signal.removeEventListener('abort', abort));
        if (signal.aborted) abort();
    });
    signal.throwIfAborted();
    const references = settings.icon ? scene.cards.flatMap((card) => (card.icon ? [card.icon] : [])) : [];
    const resolved = await icons.resolve(references, scene.icons, signal);
    try {
        while (size) {
            signal.throwIfAborted();
            const canvas = document.createElement('canvas');
            try {
                const ctx = prepareCanvas(canvas, size);
                if (!ctx) {
                    size = treePngSize(scene, size.scale - 0.001);
                    continue;
                }
                ctx.clearRect(0, 0, size.width, size.height);
                if (settings.background === 'theme') {
                    ctx.fillStyle = style.background;
                    ctx.fillRect(0, 0, size.width, size.height);
                }
                ctx.scale(size.scale, size.scale);
                ctx.textBaseline = 'top';
                ctx.imageSmoothingEnabled = true;
                const logicalWidth = size.width / size.scale;
                drawText(ctx, scene.title, 16, 12, logicalWidth - 32, style, 17);
                scene.scope.forEach((line, index) =>
                    drawText(ctx, line, 16, 40 + index * 22, logicalWidth - 32, style, 12)
                );
                ctx.translate(0, size.header);
                ctx.strokeStyle = style.border;
                ctx.lineWidth = 2;
                for (const position of scene.layout.nodes) {
                    if (position.parent === null) continue;
                    const parent = scene.layout.nodes[position.parent];
                    ctx.beginPath();
                    ctx.moveTo(parent.x + parent.width / 2, parent.y + 108);
                    ctx.lineTo(parent.x + parent.width / 2, position.y - 16);
                    ctx.lineTo(position.x + position.width / 2, position.y - 16);
                    ctx.lineTo(position.x + position.width / 2, position.y);
                    ctx.stroke();
                }
                for (let index = 0; index < scene.cards.length; index++) {
                    const card = scene.cards[index];
                    const node = scene.layout.nodes[index];
                    ctx.fillStyle = settings.colors && card.missing ? style.missing : style.tile;
                    ctx.fillRect(node.x, node.y, node.width, 108);
                    ctx.setLineDash(card.reference ? [5, 3] : []);
                    ctx.strokeRect(node.x, node.y, node.width, 108);
                    ctx.setLineDash([]);
                    const icon = card.icon && settings.icon ? resolved.icons.get(card.icon) : undefined;
                    if (icon)
                        ctx.drawImage(
                            icon.image,
                            icon.x,
                            icon.y,
                            icon.width,
                            icon.height,
                            node.x + 8,
                            node.y + 8,
                            24,
                            24
                        );
                    const left = node.x + (icon ? 40 : 8);
                    const width = node.width - (icon ? 48 : 16);
                    if (settings.name) drawText(ctx, card.title, left, node.y + 8, width, style, 13);
                    const lines = card.details;
                    const lineHeight = Math.min(14, 68 / Math.max(1, lines.length));
                    for (const [lineIndex, line] of lines.entries()) {
                        ctx.font = `11px ${style.fontFamily}`;
                        const fontSize = Math.min(
                            11,
                            (11 * (node.width - 16)) / Math.max(1, ctx.measureText(plainMinecraftText(line)).width)
                        );
                        drawText(
                            ctx,
                            line,
                            node.x + 8,
                            node.y + 34 + lineIndex * lineHeight,
                            node.width - 16,
                            style,
                            fontSize
                        );
                    }
                    if (index % 32 === 31) {
                        await new Promise<void>((resolve) => setTimeout(resolve, 0));
                        signal.throwIfAborted();
                    }
                }
                const blob = await new Promise<Blob | null>((resolve) => {
                    try {
                        canvas.toBlob(resolve, 'image/png');
                    } catch {
                        resolve(null);
                    }
                });
                signal.throwIfAborted();
                if (blob) return { blob, ...size };
                size = treePngSize(scene, size.scale - 0.001);
            } finally {
                canvas.width = 0;
                canvas.height = 0;
            }
        }
        throw new Error('too-large');
    } finally {
        resolved.dispose();
    }
}
