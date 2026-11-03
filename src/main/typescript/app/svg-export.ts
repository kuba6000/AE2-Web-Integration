import type { DrawableIcon } from './icons.js';
import { exportTextRuns, type ExportTextStyle } from './export-text.js';

export type SvgFont = { family: string; weight: 'normal' | 'bold'; asset: string; license: string };

/** Escape arbitrary imported text for XML 1.0, including invalid control characters. */
export function escapeSvg(value: string): string {
    return value.replace(/[^\u0009\u000a\u000d\u0020-\ud7ff\ue000-\ufffd\u{10000}-\u{10ffff}]|[&<>"']/gu, (char) => {
        switch (char) {
            case '&':
                return '&amp;';
            case '<':
                return '&lt;';
            case '>':
                return '&gt;';
            case '"':
                return '&quot;';
            case "'":
                return '&apos;';
            default:
                return '�';
        }
    });
}

/** Embed font files and licenses, and each distinct image crop, without external references. */
export async function createSvgAssets(fonts: readonly SvgFont[], icons: Iterable<DrawableIcon>, signal: AbortSignal) {
    const definitions: string[] = [];
    const styles: string[] = [];
    const licenses = new Map<string, string>();
    for (const font of fonts) {
        signal.throwIfAborted();
        const asset = await fetch(font.asset, { signal });
        if (!asset.ok) throw new Error('SVG font asset unavailable');
        const bytes = new Uint8Array(await asset.arrayBuffer());
        let binary = '';
        for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
        if (!licenses.has(font.license)) {
            const license = await fetch(font.license, { signal });
            if (!license.ok) throw new Error('SVG font license unavailable');
            licenses.set(font.license, await license.text());
        }
        const family = JSON.stringify(font.family);
        styles.push(
            `@font-face{font-family:${escapeSvg(family)};font-weight:${font.weight};src:url(data:font/woff2;base64,${btoa(binary)}) format('woff2')}`
        );
    }
    const ids = new Map<DrawableIcon, string>();
    const crops = new Map<CanvasImageSource, Map<string, string>>();
    let count = 0;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 64;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('SVG icon canvas unavailable');
    ctx.imageSmoothingEnabled = false;
    try {
        for (const icon of icons) {
            signal.throwIfAborted();
            let imageCrops = crops.get(icon.image);
            if (!imageCrops) crops.set(icon.image, (imageCrops = new Map()));
            const bounds = `${icon.x}/${icon.y}/${icon.width}/${icon.height}`;
            const existing = imageCrops.get(bounds);
            if (existing) {
                ids.set(icon, existing);
                continue;
            }
            const id = `icon${count++}`;
            ctx.clearRect(0, 0, 64, 64);
            ctx.drawImage(icon.image, icon.x, icon.y, icon.width, icon.height, 0, 0, 64, 64);
            definitions.push(`<image id="${id}" width="64" height="64" href="${canvas.toDataURL('image/png')}"/>`);
            imageCrops.set(bounds, id);
            ids.set(icon, id);
            if (count % 32 === 0) await new Promise<void>((resolve) => setTimeout(resolve, 0));
        }
    } finally {
        canvas.width = canvas.height = 0;
    }
    signal.throwIfAborted();
    return {
        defs: `<metadata>${escapeSvg([...licenses.values()].join('\n\n'))}</metadata><defs><style>${styles.join('\n')}</style>${definitions.join('')}</defs>`,
        iconId: (icon: DrawableIcon) => ids.get(icon)
    };
}

export function svgText(
    ctx: CanvasRenderingContext2D,
    text: string,
    x: number,
    y: number,
    width: number,
    style: ExportTextStyle,
    size: number
): string {
    const anchor = ctx.textAlign === 'center' ? 'middle' : 'start';
    return exportTextRuns(ctx, text, width, style, size)
        .map((run) => {
            ctx.font = run.font;
            const baseline = ctx.textBaseline;
            const aligned = ctx.measureText('Mg').actualBoundingBoxAscent;
            ctx.textBaseline = 'alphabetic';
            const alphabetic = ctx.measureText('Mg').actualBoundingBoxAscent;
            ctx.textBaseline = baseline;
            // Canvas top/middle use font metrics that differ from SVG's text-before-edge/central.
            return `<text x="${x + run.offset}" y="${y + alphabetic - aligned}" text-anchor="${anchor}" font-family="${escapeSvg(style.fontFamily)}" font-size="${size}" font-weight="${run.bold ? 'bold' : 'normal'}" font-style="${run.italic ? 'italic' : 'normal'}" fill="${escapeSvg(run.color)}" xml:space="preserve">${escapeSvg(run.text)}</text>`;
        })
        .join('');
}
