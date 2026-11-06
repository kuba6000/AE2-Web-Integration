import { treeCardGeometry, treeCardTextLayout } from './crafting-tree-layout.js';
import type { ThemeApplication } from '../../app/theme-api.js';
import { createSvgAssets, escapeSvg, svgText } from '../../app/svg-export.js';
import { loadExportFonts } from '../../app/export-text.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import {
    treeSvgSize,
    type TreeExportScene,
    type TreeExportSettings,
    type TreeExportStyle
} from './crafting-tree-export.js';
import { craftingHammer, terminalIcons } from './icons/pixel/terminal.js';
import { treeCardPaint, treeConnectionColor, treeConnectionOrder } from './crafting-tree-paint.js';

/** Standalone vector diagram; only individual item crops use a small raster surface. */
export async function renderTreeSvg(
    scene: TreeExportScene,
    settings: TreeExportSettings,
    style: TreeExportStyle,
    icons: ThemeApplication['icons'],
    signal: AbortSignal
) {
    signal.throwIfAborted();
    if (!scene.cards.length) throw new Error('empty');
    const size = treeSvgSize(scene, settings.scale);
    await loadExportFonts(style.fontFamily, signal);
    const references =
        settings.icon && scene.display !== 'names' ? scene.cards.flatMap((card) => (card.icon ? [card.icon] : [])) : [];
    const resolved = await icons.resolve(references, scene.icons, signal);
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    try {
        const assets = await createSvgAssets(style.fonts ?? [], resolved.icons.values(), signal);
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('SVG text measurement unavailable');
        ctx.textBaseline = 'top';
        const body: string[] = [];
        const rect = (x: number, y: number, width: number, height: number, fill: string) =>
            `<rect x="${x}" y="${y}" width="${width}" height="${height}" fill="${escapeSvg(fill)}"/>`;
        const symbol = (id: string, svg: string) =>
            `<symbol id="${id}" viewBox="0 0 32 32">${svg.replace(/^<svg[^>]*>/, '').replace(/<\/svg>$/, '')}</symbol>`;
        if (settings.background === 'theme') body.push(rect(0, 0, size.width, size.height, style.background));
        body.push(
            `<g transform="scale(${size.scale})">`,
            svgText(ctx, scene.title, 16, 12, size.width / size.scale - 32, style, 17)
        );
        scene.scope.forEach((line, index) =>
            body.push(svgText(ctx, line, 16, 40 + index * 22, size.width / size.scale - 32, style, 12))
        );
        body.push(`<g transform="translate(0 ${size.header})">`);
        const connectionOrder = treeConnectionOrder(scene.cards);
        for (let offset = 0; offset < connectionOrder.length; offset++) {
            const index = connectionOrder[offset];
            signal.throwIfAborted();
            const node = scene.layout.nodes[index];
            if (node.parent !== null) {
                const parent = scene.layout.nodes[node.parent];
                body.push(
                    `<path d="M${parent.x + parent.width / 2} ${parent.y + parent.height}V${node.y - 16}H${node.x + node.width / 2}V${node.y}" fill="none" stroke="${escapeSvg(treeConnectionColor(scene.cards[index], style, settings.colors))}" stroke-width="2"/>`
                );
            }
            if (offset % 256 === 255) await new Promise<void>((resolve) => setTimeout(resolve, 0));
        }
        const compact = scene.display === 'icons';
        for (let index = 0; index < scene.cards.length; index++) {
            signal.throwIfAborted();
            const card = scene.cards[index];
            const node = scene.layout.nodes[index];
            const paint = treeCardPaint(card, style, settings.colors);
            const cardStyle = { ...style, foreground: paint.foreground };
            body.push(
                rect(node.x, node.y, node.width, node.height, paint.fill),
                `<rect x="${node.x}" y="${node.y}" width="${node.width}" height="${node.height}" fill="none" stroke="${escapeSvg(paint.border)}" stroke-width="2"${card.reference ? ' stroke-dasharray="5 3"' : ''}/>`
            );
            const hasResource =
                settings.icon && scene.display !== 'names' && (compact ? card.hasResource : !!card.icon);
            const icon = hasResource && card.icon ? resolved.icons.get(card.icon) : undefined;
            const geometry = treeCardGeometry(node, compact, hasResource, !!card.symbol);
            const { iconSize, iconX, iconY } = geometry;
            const id = icon && assets.iconId(icon);
            const glyph = (text: string, x: number, y: number, width: number, fontSize: number) => {
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                body.push(svgText(ctx, text, x, y, width, cardStyle, fontSize));
                ctx.textAlign = 'start';
                ctx.textBaseline = 'top';
            };
            if (id) body.push(`<use href="#${id}" transform="translate(${iconX} ${iconY}) scale(${iconSize / 64})"/>`);
            else if (hasResource) glyph('?', iconX + iconSize / 2, iconY + iconSize / 2, iconSize, 24);
            if (card.symbol) {
                const { symbolSize: sourceSize, symbolX: x, symbolY: y } = geometry;
                if (card.symbol === 'craft' || card.symbol === 'storage')
                    body.push(
                        `<use href="#source-${card.symbol}" x="${x}" y="${y}" width="${sourceSize}" height="${sourceSize}"/>`
                    );
                else
                    glyph(
                        ({ reused: '↻', missing: '!', emitted: '↑', unknown: '?' } as Record<string, string>)[
                            card.symbol
                        ] ?? '',
                        x + sourceSize / 2,
                        y + sourceSize / 2,
                        sourceSize,
                        sourceSize - 2
                    );
            }
            if (compact) {
                ctx.textAlign = 'right';
                if (card.count)
                    body.push(
                        svgText(
                            ctx,
                            card.count,
                            node.x + node.width - 4,
                            node.y + node.height - 14,
                            node.width - 8,
                            cardStyle,
                            11
                        )
                    );
                ctx.textAlign = 'start';
            } else {
                const textLayout = treeCardTextLayout(node.height, card.details.length, settings.name);
                if (settings.name)
                    body.push(
                        svgText(
                            ctx,
                            card.title,
                            geometry.textX,
                            node.y + textLayout.top,
                            geometry.textWidth,
                            cardStyle,
                            13
                        )
                    );
                const lineHeight = 14;
                const lines = card.details.slice(0, textLayout.lineCount);
                for (const [lineIndex, line] of lines.entries()) {
                    ctx.font = `11px ${style.fontFamily}`;
                    const fontSize = Math.min(
                        11,
                        (11 * geometry.textWidth) / Math.max(1, ctx.measureText(plainMinecraftText(line)).width)
                    );
                    body.push(
                        svgText(
                            ctx,
                            line,
                            geometry.textX,
                            node.y + textLayout.top + textLayout.detailOffset + lineIndex * lineHeight,
                            geometry.textWidth,
                            cardStyle,
                            fontSize
                        )
                    );
                }
            }
            if (index % 32 === 31) await new Promise<void>((resolve) => setTimeout(resolve, 0));
        }
        signal.throwIfAborted();
        const blob = new Blob(
            [
                `<svg xmlns="http://www.w3.org/2000/svg" width="${size.width}" height="${size.height}" viewBox="0 0 ${size.width} ${size.height}">`,
                assets.defs,
                `<defs>${symbol('source-craft', craftingHammer)}${symbol('source-storage', terminalIcons.stored)}</defs>`,
                ...body,
                '</g></g></svg>'
            ],
            { type: 'image/svg+xml' }
        );
        return { blob, ...size };
    } finally {
        canvas.width = canvas.height = 0;
        resolved.dispose();
    }
}
