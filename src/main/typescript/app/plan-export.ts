import type { Plan, IconMetadata, PlanItem, IconReference } from './api-types.js';
import type { ReadonlyData } from './theme-api.js';
import type { createIconLoader, DrawableIcon } from './icons.js';
import { parseMinecraftText, plainMinecraftText, type TextStyle } from './minecraft-text.js';
import { selectPlanResources } from './resource-helpers.js';
import {
    planExportLayout,
    planExportFits,
    smallerPlanExportLayout,
    type PlanExportSettings,
    type PlanExportGrid,
    type PlanExportGeometry,
    type PlanExportLayout
} from './plan-export-layout.js';

export type PlanExportStyle = {
    geometry: PlanExportGeometry;
    padding: number;
    contentLeft: number;
    iconSize: number;
    iconLeft: number;
    borderWidth: number;
    labelFontSize: number;
    quantityFontSize: number;
    metricLineHeight: number;
    metricGap: number;
    usageColors: string[];
    fontFamily: string;
    fontSize: number;
    foreground: string;
    background: string;
    tile: string;
    border: string;
    highlight: string;
    tints: Record<'missing' | 'pending' | 'stored', string>;
    textColors: Partial<Record<NonNullable<TextStyle['color']>, string>>;
};
export type PlanExportRequest = {
    plan: ReadonlyData<Plan>;
    icons: ReadonlyData<IconMetadata> | null;
    settings: PlanExportSettings;
    grid: PlanExportGrid;
    style: PlanExportStyle;
    language: string;
    title: string;
    labels: Record<'missing' | 'requested' | 'stored' | 'steps' | 'usage', string>;
    number(value: number): string;
};

export function createPlanExporter(loader: Pick<ReturnType<typeof createIconLoader>, 'resolve'>) {
    return async function download(request: PlanExportRequest, signal: AbortSignal): Promise<PlanExportLayout> {
        const { settings, style } = request;
        const rows = selectPlanResources(
            (request.plan.plan ?? []).filter((row) => !settings.onlyMissing || row.missing > 0),
            {
                search: '',
                language: request.language,
                sort: settings.sort,
                sortOrder: settings.direction,
                groupByState: settings.group
            }
        );
        let layout = planExportLayout(rows.length, settings, request.grid, style.geometry);
        if (!layout) throw new Error('empty');
        while (layout && !planExportFits(layout)) layout = smallerPlanExportLayout(layout, style.geometry);
        if (!layout) throw new Error('too-large');
        const font = `${style.fontSize}px ${style.fontFamily}`;
        const fonts = Promise.all([
            document.fonts.load(font),
            document.fonts.load(`bold ${font}`),
            document.fonts.load(`italic ${font}`)
        ]);
        await new Promise<void>((resolve, reject) => {
            const abort = () => reject(signal.reason);
            signal.addEventListener('abort', abort, { once: true });
            fonts.then(() => resolve(), reject).finally(() => signal.removeEventListener('abort', abort));
            if (signal.aborted) abort();
        });
        signal.throwIfAborted();
        const resolved = await loader.resolve(
            settings.icon ? rows.flatMap((row) => (row.icon ? [row.icon] : [])) : [],
            request.icons as IconMetadata | null,
            signal
        );
        try {
            while (layout) {
                signal.throwIfAborted();
                if (!planExportFits(layout)) {
                    layout = smallerPlanExportLayout(layout, style.geometry);
                    continue;
                }
                const canvas = document.createElement('canvas');
                try {
                    const ctx = prepareCanvas(canvas, layout);
                    if (!ctx) {
                        layout = smallerPlanExportLayout(layout, style.geometry);
                        continue;
                    }
                    ctx.clearRect(0, 0, layout.width, layout.height);
                    if (settings.background === 'theme') {
                        ctx.fillStyle = style.background;
                        ctx.fillRect(0, 0, layout.width, layout.height);
                    }
                    ctx.imageSmoothingEnabled = false;
                    ctx.textBaseline = 'top';
                    if (settings.header)
                        drawText(
                            ctx,
                            request.title,
                            6 * layout.scale,
                            6 * layout.scale,
                            layout.width - 12 * layout.scale,
                            style,
                            style.fontSize * layout.scale
                        );
                    for (let index = 0; index < layout.columns * layout.rows; index++) {
                        ctx.save();
                        ctx.translate(
                            (index % layout.columns) * layout.tileWidth,
                            layout.headerHeight + Math.floor(index / layout.columns) * layout.tileHeight
                        );
                        ctx.scale(layout.tileWidth / style.geometry.width, layout.tileHeight / style.geometry.height);
                        ctx.beginPath();
                        ctx.rect(0, 0, style.geometry.width, style.geometry.height);
                        ctx.clip();
                        drawTile(ctx, rows[index], resolved.icons, request);
                        ctx.restore();
                    }
                    const blob = await new Promise<Blob | null>((resolve) => {
                        try {
                            canvas.toBlob(resolve, 'image/png');
                        } catch {
                            resolve(null);
                        }
                    });
                    signal.throwIfAborted();
                    if (!blob) {
                        layout = smallerPlanExportLayout(layout, style.geometry);
                        continue;
                    }
                    const url = URL.createObjectURL(blob);
                    const link = document.createElement('a');
                    link.href = url;
                    const name =
                        plainMinecraftText(request.plan.finalOutput.displayName)
                            .replace(/[<>:"/\\|?*\x00-\x1f]/g, '_')
                            .slice(0, 100)
                            .replace(/[. ]+$/, '') || 'crafting';
                    const now = new Date();
                    const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}-${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}${String(now.getSeconds()).padStart(2, '0')}`;
                    link.download = `${name}-plan-${stamp}.png`;
                    document.body.append(link);
                    link.click();
                    link.remove();
                    setTimeout(() => URL.revokeObjectURL(url), 1000);
                    return layout;
                } finally {
                    canvas.width = 0;
                    canvas.height = 0;
                }
            }
            throw new Error('too-large');
        } finally {
            resolved.dispose();
        }
    };
}

function prepareCanvas(canvas: HTMLCanvasElement, layout: PlanExportLayout) {
    try {
        canvas.width = layout.width;
        canvas.height = layout.height;
        const ctx = canvas.getContext('2d');
        if (!ctx) return null;
        ctx.fillRect(layout.width - 1, layout.height - 1, 1, 1);
        return ctx.getImageData(layout.width - 1, layout.height - 1, 1, 1).data[3] ? ctx : null;
    } catch {
        return null;
    }
}

function drawText(
    ctx: CanvasRenderingContext2D,
    text: string,
    x: number,
    y: number,
    width: number,
    style: PlanExportStyle,
    size: number
) {
    if (width <= 0) return;
    const segments = parseMinecraftText(text);
    const font = (segment: TextStyle) =>
        `${segment.italic ? 'italic ' : ''}${segment.bold ? 'bold ' : ''}${size}px ${style.fontFamily}`;
    let total = 0;
    for (const segment of segments) {
        ctx.font = font(segment);
        total += ctx.measureText(segment.text).width;
    }
    const truncated = total > width;
    ctx.font = `${size}px ${style.fontFamily}`;
    const limit = width - (truncated ? ctx.measureText('…').width : 0);
    let used = 0;
    for (const segment of segments) {
        ctx.font = font(segment);
        ctx.fillStyle = segment.color ? (style.textColors[segment.color] ?? style.foreground) : style.foreground;
        let part = '';
        for (const char of segment.text) {
            if (used + ctx.measureText(part + char).width > limit) break;
            part += char;
        }
        ctx.fillText(part, x + used, y);
        used += ctx.measureText(part).width;
        if (part !== segment.text) break;
    }
    if (truncated && width > 0) {
        ctx.fillStyle = style.foreground;
        ctx.fillText('…', x + used, y);
    }
}

function drawTile(
    ctx: CanvasRenderingContext2D,
    row: Readonly<PlanItem> | undefined,
    icons: ReadonlyMap<IconReference, DrawableIcon>,
    request: PlanExportRequest
) {
    const { settings, style } = request;
    const { width, height } = style.geometry;
    ctx.fillStyle = style.tile;
    ctx.fillRect(0, 0, width, height);
    if (settings.colors && row) {
        ctx.fillStyle = style.tints[row.missing > 0 ? 'missing' : row.requested > 0 ? 'pending' : 'stored'];
        ctx.fillRect(0, 0, width, height);
    }
    ctx.fillStyle = style.border;
    ctx.fillRect(0, 0, width, style.borderWidth);
    ctx.fillRect(0, 0, style.borderWidth, height);
    ctx.fillStyle = style.highlight;
    ctx.fillRect(0, height - style.borderWidth, width, style.borderWidth);
    ctx.fillRect(width - style.borderWidth, 0, style.borderWidth, height);
    if (!row) return;
    const left = style.contentLeft + style.borderWidth;
    const right = width - style.padding - style.borderWidth;
    if (settings.name)
        drawText(ctx, row.displayName, left, style.padding + style.borderWidth, right - left, style, style.fontSize);
    const icon = settings.icon && row.icon ? icons.get(row.icon) : undefined;
    if (icon)
        ctx.drawImage(
            icon.image,
            icon.x,
            icon.y,
            icon.width,
            icon.height,
            style.iconLeft + style.borderWidth,
            (height - style.iconSize) / 2,
            style.iconSize,
            style.iconSize
        );
    const amounts: { value: string; label: string; usage?: string }[] = [];
    for (const field of ['missing', 'requested', 'steps', 'stored'] as const) {
        if (!settings[field] || row[field] <= 0) continue;
        amounts.push({
            value: request.number(row[field]),
            label: request.labels[field],
            usage:
                field === 'stored' && settings.usage && row.usedPercent > 0
                    ? ` (${Math.round(row.usedPercent * 100)}%)`
                    : undefined
        });
    }
    if (!settings.stored && settings.usage && row.stored > 0 && row.usedPercent > 0)
        amounts.push({ value: `${Math.round(row.usedPercent * 100)}%`, label: request.labels.usage });
    const line = style.metricLineHeight + style.metricGap;
    let y = height - style.padding - style.borderWidth - amounts.length * line + style.metricGap;
    for (const amount of amounts) {
        ctx.font = `${style.quantityFontSize}px ${style.fontFamily}`;
        const combinedWidth = ctx.measureText(amount.value + (amount.usage ?? '')).width;
        const quantitySize = Math.min(
            style.quantityFontSize,
            (style.quantityFontSize * (right - left)) / combinedWidth
        );
        ctx.font = `${quantitySize}px ${style.fontFamily}`;
        const valueWidth = ctx.measureText(amount.value).width;
        const usageWidth = amount.usage ? ctx.measureText(amount.usage).width : 0;
        const quantityX = Math.max(left, right - valueWidth - usageWidth);
        drawText(
            ctx,
            amount.label,
            left,
            y + style.quantityFontSize - style.labelFontSize,
            Math.max(0, quantityX - left - 4),
            style,
            style.labelFontSize
        );
        drawText(ctx, amount.value, quantityX, y, right - quantityX, style, quantitySize);
        if (amount.usage) {
            const level = row.usedPercent <= 0.25 ? 0 : row.usedPercent <= 0.5 ? 1 : row.usedPercent <= 0.75 ? 2 : 3;
            ctx.fillStyle = style.usageColors[level];
            ctx.font = `${quantitySize}px ${style.fontFamily}`;
            ctx.fillText(amount.usage, quantityX + valueWidth, y);
        }
        y += line;
    }
}
