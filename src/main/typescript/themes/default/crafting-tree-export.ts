import { loadExportFonts } from '../../app/export-text.js';
import { prepareCanvas, drawText } from '../../app/plan-export.js';
import { planExportFits, type PlanExportSettings } from '../../app/plan-export-layout.js';
import type { IconMetadata } from '../../app/api-types.js';
import type { ThemeApplication } from '../../app/theme-api.js';
import type { craftingTreeContent } from './crafting-tree-content.js';
import type { layoutCraftingTree } from './crafting-tree-layout.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { craftingHammer, terminalIcons } from './icons/pixel/terminal.js';
import type { SvgFont } from '../../app/svg-export.js';
export { renderTreeSvg } from './crafting-tree-export-svg.js';
import type { TerminalDisplay } from './web-settings.js';
import { treeCardPaint, treeConnectionColor, treeConnectionOrder, type TreePalette } from './crafting-tree-paint.js';

export type TreeExportScene = {
    layout: ReturnType<typeof layoutCraftingTree>;
    display: TerminalDisplay;
    cards: ReturnType<typeof craftingTreeContent>[];
    icons: IconMetadata | null;
    title: string;
    scope: string[];
};
export type TreeExportSettings = Pick<
    PlanExportSettings,
    'format' | 'scale' | 'icon' | 'name' | 'colors' | 'background'
>;
export type TreeExportStyle = TreePalette & {
    fontFamily: string;
    fonts?: readonly SvgFont[];
    background: string;
    textColors: Record<string, string>;
};

/** Image bounds cover all expanded nodes, independent of zoom or viewport clipping. */
export function treeSvgSize(scene: TreeExportScene, scale: number) {
    const header = 48 + scene.scope.length * 22;
    return {
        width: Math.ceil(Math.max(720, scene.layout.width) * scale),
        height: Math.ceil((scene.layout.height + header) * scale),
        scale,
        header
    };
}

export function treePngSize(scene: TreeExportScene, requestedScale: number) {
    for (const scale of [4, 3, 2, 1.5, 1, 0.75, 0.5, 0.25]) {
        if (scale > requestedScale) continue;
        const size = treeSvgSize(scene, scale);
        if (planExportFits(size)) return size;
    }
    return null;
}

/** Renders detached diagram data only; cancellation releases the canvas and resolved atlas bitmaps. */
export async function renderTreePng(
    scene: TreeExportScene,
    settings: TreeExportSettings,
    style: TreeExportStyle,
    icons: ThemeApplication['icons'],
    signal: AbortSignal
) {
    signal.throwIfAborted();
    if (!scene.cards.length) throw new Error('empty');
    let size = treePngSize(scene, settings.scale);
    if (!size) throw new Error('too-large');
    await loadExportFonts(style.fontFamily, signal);
    const references =
        settings.icon && scene.display !== 'names' ? scene.cards.flatMap((card) => (card.icon ? [card.icon] : [])) : [];
    const resolved = await icons.resolve(references, scene.icons, signal);
    const symbols = new Map<string, HTMLImageElement>();
    try {
        for (const [name, svg] of [
            ['craft', craftingHammer],
            ['storage', terminalIcons.stored]
        ]) {
            if (!scene.cards.some((card) => card.symbol === name)) continue;
            const image = new Image();
            image.src = `data:image/svg+xml,${encodeURIComponent(svg)}`;
            await image.decode();
            signal.throwIfAborted();
            symbols.set(name, image);
        }
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
                for (const index of treeConnectionOrder(scene.cards)) {
                    const position = scene.layout.nodes[index];
                    if (position.parent === null) continue;
                    ctx.strokeStyle = treeConnectionColor(scene.cards[index], style, settings.colors);
                    const parent = scene.layout.nodes[position.parent];
                    ctx.beginPath();
                    ctx.moveTo(parent.x + parent.width / 2, parent.y + parent.height);
                    ctx.lineTo(parent.x + parent.width / 2, position.y - 16);
                    ctx.lineTo(position.x + position.width / 2, position.y - 16);
                    ctx.lineTo(position.x + position.width / 2, position.y);
                    ctx.stroke();
                }
                for (let index = 0; index < scene.cards.length; index++) {
                    const card = scene.cards[index];
                    const node = scene.layout.nodes[index];
                    const compact = scene.display === 'icons';
                    const paint = treeCardPaint(card, style, settings.colors);
                    const cardStyle = { ...style, foreground: paint.foreground };
                    ctx.fillStyle = paint.fill;
                    ctx.strokeStyle = paint.border;
                    ctx.fillRect(node.x, node.y, node.width, node.height);
                    ctx.setLineDash(card.reference ? [5, 3] : []);
                    ctx.strokeRect(node.x, node.y, node.width, node.height);
                    ctx.setLineDash([]);
                    if (paint.inset) {
                        ctx.lineWidth = 1;
                        ctx.strokeRect(node.x + 4, node.y + 4, node.width - 8, node.height - 8);
                        ctx.lineWidth = 2;
                    }
                    const hasResource =
                        settings.icon && scene.display !== 'names' && (compact ? card.hasResource : !!card.icon);
                    const icon = hasResource && card.icon ? resolved.icons.get(card.icon) : undefined;
                    const iconSize = compact ? 32 : 24;
                    const iconX = node.x + (compact ? (node.width - iconSize) / 2 : 10);
                    const iconY = node.y + (node.height - iconSize) / 2;
                    if (icon)
                        ctx.drawImage(
                            icon.image,
                            icon.x,
                            icon.y,
                            icon.width,
                            icon.height,
                            iconX,
                            iconY,
                            iconSize,
                            iconSize
                        );
                    else if (hasResource) {
                        ctx.textAlign = 'center';
                        ctx.textBaseline = 'middle';
                        drawText(ctx, '?', iconX + iconSize / 2, iconY + iconSize / 2, iconSize, cardStyle, 24);
                        ctx.textAlign = 'start';
                        ctx.textBaseline = 'top';
                    }
                    if (card.symbol) {
                        const size = hasResource ? (compact ? 16 : 14) : iconSize;
                        const x = hasResource ? node.x + 6 : iconX;
                        const y = hasResource ? node.y + (compact ? 7 : node.height / 2 - 17) : iconY;
                        if (hasResource) {
                            ctx.fillStyle = style.tile;
                            ctx.fillRect(x, y, size, size);
                        }
                        const image = symbols.get(card.symbol);
                        if (image) ctx.drawImage(image, x, y, size, size);
                        else {
                            const glyph =
                                ({ reused: '↻', missing: '!', emitted: '↑', unknown: '?' } as Record<string, string>)[
                                    card.symbol
                                ] ?? '';
                            ctx.textAlign = 'center';
                            ctx.textBaseline = 'middle';
                            drawText(ctx, glyph, x + size / 2, y + size / 2, size, cardStyle, size - 2);
                            ctx.textAlign = 'start';
                            ctx.textBaseline = 'top';
                        }
                    }
                    if (compact) {
                        if (card.count)
                            drawText(
                                ctx,
                                card.count,
                                node.x + 4,
                                node.y + node.height - 14,
                                node.width - 8,
                                cardStyle,
                                11
                            );
                    } else {
                        if (settings.name)
                            drawText(ctx, card.title, node.x + 40, node.y + 8, node.width - 48, cardStyle, 13);
                        const lines = card.details;
                        const lineHeight = Math.min(14, 68 / Math.max(1, lines.length));
                        for (const [lineIndex, line] of lines.entries()) {
                            ctx.font = `11px ${style.fontFamily}`;
                            const fontSize = Math.min(
                                11,
                                (11 * (node.width - 48)) / Math.max(1, ctx.measureText(plainMinecraftText(line)).width)
                            );
                            drawText(
                                ctx,
                                line,
                                node.x + 40,
                                node.y + 34 + lineIndex * lineHeight,
                                node.width - 48,
                                cardStyle,
                                fontSize
                            );
                        }
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
