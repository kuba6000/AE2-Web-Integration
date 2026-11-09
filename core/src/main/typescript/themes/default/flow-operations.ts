import type { Translator } from '../../app/theme-api.js';
import { element } from './dom.js';

/** Rates need a complete measured interval; partial counters remain useful without an inferred rate. */
export function flowOperationLines(
    input: string | null,
    output: string | null,
    periodMillis: number | null,
    locale: Translator
): HTMLElement[] {
    const numbers = new Intl.NumberFormat(document.documentElement.lang);
    const rates = new Intl.NumberFormat(document.documentElement.lang, { maximumFractionDigits: 2 });
    const result: HTMLElement[] = [];
    for (const [direction, key, count] of [
        ['input', 'trackingInputOperations', input],
        ['output', 'trackingOutputOperations', output]
    ] as const) {
        if (count == null) continue;
        let text = `${locale.t(key)}: ${numbers.format(BigInt(count))}`;
        if (periodMillis !== null && periodMillis > 0) {
            const rate = rates.format((Number(count) * 1000) / periodMillis);
            text += ` (${locale.t('trackingOperationsRate', { rate })})`;
        }
        const line = element('span', text, 'recent-note flow-operations');
        line.dataset.flowOperations = direction;
        result.push(line);
    }
    return result;
}
