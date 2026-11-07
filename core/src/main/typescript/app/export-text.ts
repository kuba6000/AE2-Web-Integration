import { parseMinecraftText, type TextStyle } from './minecraft-text.js';

export type ExportTextStyle = {
    fontFamily: string;
    foreground: string;
    textColors: Partial<Record<NonNullable<TextStyle['color']>, string>>;
};

/** Measured runs keep Minecraft formatting and truncation identical across image formats. */
export function exportTextRuns(
    ctx: CanvasRenderingContext2D,
    text: string,
    width: number,
    style: ExportTextStyle,
    size: number
) {
    const runs: { text: string; offset: number; font: string; color: string; bold: boolean; italic: boolean }[] = [];
    if (width <= 0) return runs;
    const segments = parseMinecraftText(text);
    const font = (segment: Pick<TextStyle, 'bold' | 'italic'>) =>
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
    let last = { bold: false, italic: false };
    for (const segment of segments) {
        ctx.font = font(segment);
        last = { bold: segment.bold, italic: segment.italic };
        let part = '';
        for (const char of segment.text) {
            if (used + ctx.measureText(part + char).width > limit) break;
            part += char;
        }
        runs.push({
            text: part,
            offset: used,
            font: ctx.font,
            color: segment.color ? (style.textColors[segment.color] ?? style.foreground) : style.foreground,
            bold: segment.bold,
            italic: segment.italic
        });
        used += ctx.measureText(part).width;
        if (part !== segment.text) break;
    }
    if (truncated) runs.push({ text: '…', offset: used, font: ctx.font, color: style.foreground, ...last });
    // Each draw call aligns a single run; shift its anchor to align the entire formatted group.
    const alignment = ctx.textAlign === 'center' ? 0.5 : ctx.textAlign === 'right' || ctx.textAlign === 'end' ? 1 : 0;
    if (alignment && runs.length) {
        const widths = runs.map((run) => {
            ctx.font = run.font;
            return ctx.measureText(run.text).width;
        });
        const totalWidth = widths.reduce((sum, value) => sum + value, 0);
        runs.forEach((run, index) => {
            run.offset += alignment * (widths[index] - totalWidth);
        });
    }
    return runs;
}

export async function loadExportFonts(fontFamily: string, signal: AbortSignal) {
    signal.throwIfAborted();
    await new Promise<void>((resolve, reject) => {
        const abort = () => reject(signal.reason);
        signal.addEventListener('abort', abort, { once: true });
        Promise.all(['', 'bold ', 'italic '].map((weight) => document.fonts.load(`${weight}13px ${fontFamily}`)))
            .then(() => resolve(), reject)
            .finally(() => signal.removeEventListener('abort', abort));
        if (signal.aborted) abort();
    });
    signal.throwIfAborted();
}
