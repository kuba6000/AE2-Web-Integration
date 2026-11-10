import type { ThemeState } from '../../app/theme-api.js';

type History = ThemeState['resourceHistory'];

/** One card retains only its current measurements; unrelated state updates reuse the derived totals. */
export function createHomePinMetrics() {
    let previous: unknown[] = [];
    let result: (ReturnType<typeof recordedStock> & { totals: ReturnType<typeof historicalFlow> }) | undefined;
    return (itemKey: string | null, history: History | undefined, flowHistory: History | undefined) => {
        const inputs = [
            itemKey,
            ...[history, flowHistory].flatMap((source) => [
                source?.points,
                source?.baseline,
                source?.status,
                source?.from,
                source?.until
            ])
        ];
        if (!result || inputs.some((value, index) => value !== previous[index])) {
            result = { ...recordedStock(itemKey, [history, flowHistory]), totals: historicalFlow(flowHistory) };
            previous = inputs;
        }
        return result;
    };
}

export function recordedStock(itemKey: string | null, sources: (History | undefined)[]) {
    let stock: History['baseline'] = null;
    let until = 0;
    for (const source of new Set(sources)) {
        if (source?.status !== 'ready') continue;
        for (const point of [source.baseline, ...source.points]) {
            if (point?.kind !== 'STOCK' || point.resource?.itemKey !== itemKey || point.observedAt > source.until)
                continue;
            if (!stock || point.observedAt > stock.observedAt) {
                stock = point;
                until = source.until;
            }
        }
    }
    return { stock, until };
}

export function historicalFlow(history: History | undefined) {
    let input = 0n,
        output = 0n,
        inputOperations = 0n,
        outputOperations = 0n,
        observed = false;
    if (history?.status === 'ready') {
        for (const point of history.points) {
            if (point.observedAt <= history.from || point.observedAt > history.until) continue;
            if (point.kind === 'FLOW' || point.kind === 'FLOW_OBSERVATION') observed = true;
            if (point.kind === 'FLOW') {
                input += BigInt(point.input);
                output += BigInt(point.output);
                inputOperations += BigInt(point.inputOperations);
                outputOperations += BigInt(point.outputOperations);
            }
        }
    }
    return { input, output, inputOperations, outputOperations, observed };
}
