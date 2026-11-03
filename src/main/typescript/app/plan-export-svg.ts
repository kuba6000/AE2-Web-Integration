import { planExportAmounts } from './plan-export-content.js';
import type { IconReference, PlanItem } from './api-types.js';
import type { DrawableIcon } from './icons.js';
import type { PlanExportRequest } from './plan-export.js';
import type { PlanExportLayout } from './plan-export-layout.js';
import { createSvgAssets, escapeSvg, svgText } from './svg-export.js';

/** Vector version of the ordinary plan grid, using its selected rows and measured theme geometry. */
export async function renderPlanSvg(
    request: PlanExportRequest,
    rows: readonly Readonly<PlanItem>[],
    layout: PlanExportLayout,
    icons: ReadonlyMap<IconReference, DrawableIcon>,
    signal: AbortSignal
) {
    const { settings, style } = request;
    const assets = await createSvgAssets(style.fonts ?? [], icons.values(), signal);
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('SVG text measurement unavailable');
    ctx.textBaseline = 'top';
    const body: string[] = [];
    const rect = (x: number, y: number, width: number, height: number, fill: string) =>
        `<rect x="${x}" y="${y}" width="${width}" height="${height}" fill="${escapeSvg(fill)}"/>`;
    const image = (icon: DrawableIcon | undefined, x: number, y: number, size: number) => {
        const id = icon && assets.iconId(icon);
        return id
            ? `<use href="#${id}" transform="translate(${x} ${y}) scale(${size / 64})" image-rendering="pixelated"/>`
            : '';
    };
    try {
        if (settings.background === 'theme') body.push(rect(0, 0, layout.width, layout.height, style.background));
        if (settings.header) {
            const padding = 6 * layout.scale;
            const icon =
                settings.icon && request.plan.finalOutput.icon ? icons.get(request.plan.finalOutput.icon) : undefined;
            const size = icon
                ? Math.max(0, Math.min(style.iconSize * layout.scale, layout.headerHeight - 2 * padding))
                : 0;
            if (size > 0) body.push(image(icon, padding, (layout.headerHeight - size) / 2, size));
            const left = padding + (size > 0 ? size + padding : 0);
            body.push(
                svgText(
                    ctx,
                    request.title,
                    left,
                    (layout.headerHeight - style.fontSize * layout.scale) / 2,
                    layout.width - left - padding,
                    style,
                    style.fontSize * layout.scale
                )
            );
        }
        const { width, height } = style.geometry;
        for (let index = 0; index < layout.columns * layout.rows; index++) {
            signal.throwIfAborted();
            const row = rows[index];
            const tile = [rect(0, 0, width, height, style.tile)];
            if (settings.colors && row)
                tile.push(
                    rect(
                        0,
                        0,
                        width,
                        height,
                        style.tints[row.missing > 0 ? 'missing' : row.requested > 0 ? 'pending' : 'stored']
                    )
                );
            tile.push(
                rect(0, 0, width, style.borderWidth, style.border),
                rect(0, 0, style.borderWidth, height, style.border),
                rect(0, height - style.borderWidth, width, style.borderWidth, style.highlight),
                rect(width - style.borderWidth, 0, style.borderWidth, height, style.highlight)
            );
            if (row) {
                const left = style.contentLeft + style.borderWidth;
                const right = width - style.padding - style.borderWidth;
                if (settings.name)
                    tile.push(
                        svgText(
                            ctx,
                            row.displayName,
                            left,
                            style.padding + style.borderWidth,
                            right - left,
                            style,
                            style.fontSize
                        )
                    );
                if (settings.icon && row.icon)
                    tile.push(
                        image(
                            icons.get(row.icon),
                            style.iconLeft + style.borderWidth,
                            (height - style.iconSize) / 2,
                            style.iconSize
                        )
                    );
                const amounts = planExportAmounts(row, request);
                const line = style.metricLineHeight + style.metricGap;
                const blockHeight = Math.max(0, amounts.length * line - style.metricGap);
                let y = settings.name
                    ? height - style.padding - style.borderWidth - blockHeight
                    : (height - blockHeight) / 2;
                for (const amount of amounts) {
                    ctx.font = `${style.labelFontSize}px ${style.fontFamily}`;
                    const labelWidth = ctx.measureText(amount.label).width;
                    ctx.font = `${style.quantityFontSize}px ${style.fontFamily}`;
                    const valueWidth = ctx.measureText(amount.value).width;
                    const usageWidth = amount.usage ? ctx.measureText(amount.usage).width : 0;
                    const scale = Math.min(1, Math.max(1, right - left - labelWidth - 4) / (valueWidth + usageWidth));
                    const x = right - (valueWidth + usageWidth) * scale;
                    tile.push(
                        svgText(
                            ctx,
                            amount.label,
                            left,
                            y + style.quantityFontSize - style.labelFontSize,
                            right - left,
                            style,
                            style.labelFontSize
                        )
                    );
                    tile.push(
                        `<g transform="translate(${x} ${y}) scale(${scale})">`,
                        svgText(ctx, amount.value, 0, 0, Infinity, style, style.quantityFontSize)
                    );
                    if (amount.usage) {
                        const level =
                            row.usedPercent <= 0.25 ? 0 : row.usedPercent <= 0.5 ? 1 : row.usedPercent <= 0.75 ? 2 : 3;
                        tile.push(
                            svgText(
                                ctx,
                                amount.usage,
                                valueWidth,
                                0,
                                Infinity,
                                { ...style, foreground: style.usageColors[level] },
                                style.quantityFontSize
                            )
                        );
                    }
                    tile.push('</g>');
                    y += line;
                }
            }
            body.push(
                `<g transform="translate(${(index % layout.columns) * layout.tileWidth} ${layout.headerHeight + Math.floor(index / layout.columns) * layout.tileHeight}) scale(${layout.tileWidth / width} ${layout.tileHeight / height})" clip-path="url(#tile-clip)">${tile.join('')}</g>`
            );
            if (index % 32 === 31) await new Promise<void>((resolve) => setTimeout(resolve, 0));
        }
        signal.throwIfAborted();
        return new Blob(
            [
                `<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width}" height="${layout.height}" viewBox="0 0 ${layout.width} ${layout.height}">`,
                assets.defs,
                `<defs><clipPath id="tile-clip"><rect width="${width}" height="${height}"/></clipPath></defs>`,
                ...body,
                '</svg>'
            ],
            { type: 'image/svg+xml' }
        );
    } finally {
        canvas.width = canvas.height = 0;
    }
}
