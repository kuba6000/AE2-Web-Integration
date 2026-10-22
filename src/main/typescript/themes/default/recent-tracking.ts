import type { ThemeState, Translator } from '../../app/theme-api.js';
import { element } from './dom.js';
import { flowOperationLines } from './flow-operations.js';

export function recentTrackingLines(
    recent: ThemeState['recent'],
    itemKey: string | null,
    locale: Translator
): HTMLElement[] {
    if (!recent || !itemKey || (recent.flowSince === null && recent.stockSince === null)) return [];
    const resource = recent.resources[itemKey];
    if (!resource) return [];
    const result: HTMLElement[] = [];
    const numbers = new Intl.NumberFormat(document.documentElement.lang, {
        notation: 'compact',
        maximumFractionDigits: 1
    });
    const counter = (value: bigint, prefix = '') => {
        const node = element(
            'span',
            prefix + numbers.format(value),
            value > 0n ? 'tracking-positive' : value < 0n ? 'tracking-negative' : 'tracking-neutral'
        );
        node.setAttribute('aria-label', prefix + value.toString());
        return node;
    };
    const label = (key: string, period: number) => `${locale.t(key)} (${locale.duration(period)}): `;
    const appendPeriod = (
        since: number | null,
        until: number | null,
        window: number,
        complete: boolean,
        kind: string
    ) => {
        const period = since === null || until === null ? 0 : Math.min(window, Math.max(0, until - since));
        if (!complete || period < window) {
            result.push(
                element(
                    'span',
                    locale.t(complete ? 'trackingMeasuredPeriod' : 'trackingIncompletePeriod', {
                        period: locale.duration(period)
                    }),
                    `hint recent-note recent-${kind}-period`
                )
            );
        }
    };
    if (resource.input !== null && resource.output !== null) {
        const input = BigInt(resource.input),
            output = BigInt(resource.output);
        const line = element('span', label('trackingFlow', recent.flowWindowMillis), 'recent-flow');
        line.append(
            counter(input, '+'),
            document.createTextNode(' / '),
            counter(-output, output === 0n ? '−' : ''),
            document.createTextNode(' / '),
            counter(input - output, input >= output ? '+' : '')
        );
        result.push(line);
        const period =
            recent.flowComplete && recent.flowSince !== null && recent.flowObservedAt !== null
                ? Math.min(recent.flowWindowMillis, Math.max(0, recent.flowObservedAt - recent.flowSince))
                : null;
        result.push(...flowOperationLines(resource.inputOperations, resource.outputOperations, period, locale));
        appendPeriod(recent.flowSince, recent.flowObservedAt, recent.flowWindowMillis, recent.flowComplete, 'flow');
    }
    if (recent.stockSince !== null && recent.stockObservedAt !== null) {
        const line = element('span', label('trackingStockChange', recent.stockWindowMillis), 'recent-stock');
        if (resource.stockChange === null) {
            line.append(document.createTextNode('—'));
        } else {
            const value = BigInt(resource.stockChange);
            line.append(counter(value, value >= 0n ? '+' : ''));
        }
        result.push(line);
        if (resource.stockChange === null) {
            result.push(element('span', locale.t('trackingStockWaiting'), 'hint recent-note recent-stock-period'));
        } else {
            appendPeriod(
                recent.stockSince,
                recent.stockObservedAt,
                recent.stockWindowMillis,
                recent.stockComplete,
                'stock'
            );
        }
        const sampled = element('span', `${locale.t('trackingLastStockSample')}: `, 'hint recent-note');
        const time = element('time', locale.dateTime(recent.stockObservedAt, recent.observedAt));
        time.dateTime = new Date(recent.stockObservedAt).toISOString();
        sampled.append(time);
        result.push(sampled);
    }
    return result;
}
