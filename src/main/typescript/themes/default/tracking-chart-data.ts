import type { ThemeState } from '../../app/theme-api.js';

export type ChartSample = {
    time: number;
    stock: bigint | null;
    input: bigint | null;
    output: bigint | null;
    intervalFrom?: number;
    stockObservedAt?: number;
    stockMeasured?: boolean;
};

/** Use recorded flush cadence, not the spacing between individual resource changes. */
export function flowResolution(history: ThemeState['resourceHistory']): number {
    let previous: number | undefined;
    let shortest = Infinity;
    for (const point of history.points) {
        if (point.kind !== 'FLOW_OBSERVATION') continue;
        if (previous !== undefined && point.observedAt > previous)
            shortest = Math.min(shortest, point.observedAt - previous);
        previous = point.observedAt;
    }
    return Number.isFinite(shortest) ? shortest : 5000;
}

export function flowDisplayInterval(span: number, pixels: number, resolution: number): number {
    const target = Math.max(resolution, span / Math.max(1, pixels / 8));
    if (target === resolution) return resolution;
    const intervals = [1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000, 900000, 1800000, 3600000];
    const candidates = [...intervals, Math.round(target / 3600000) * 3600000].filter((value) => value >= resolution);
    return candidates.reduce(
        (best, value) => (Math.abs(value - target) < Math.abs(best - target) ? value : best),
        Math.ceil(target / 3600000) * 3600000
    );
}

/** Re-read original points for each visible window; never aggregate previous display buckets. */
export function chartSamples(
    history: ThemeState['resourceHistory'],
    width: number,
    from: number,
    until: number
): ChartSample[] {
    const samples: ChartSample[] = [];
    let stock =
        history.baseline?.complete && history.baseline.resource && history.baseline.observedAt <= from
            ? BigInt(history.baseline.resource.quantity)
            : null;
    let generation = history.baseline?.generation;
    let stockObservedAt = stock === null ? undefined : history.baseline?.observedAt;
    const allPoints = [
        ...(history.baseline && history.baseline.observedAt >= from ? [history.baseline] : []),
        ...history.points
    ];
    for (const point of allPoints) {
        if (point.observedAt >= from) break;
        if (
            point.kind === 'GAP' ||
            (generation && generation !== point.generation) ||
            (!point.complete && (point.kind === 'STOCK' || point.kind === 'OBSERVATION'))
        ) {
            stock = null;
            stockObservedAt = undefined;
        }
        generation = point.generation;
        if (point.kind === 'STOCK' && point.complete && point.resource) stock = BigInt(point.resource.quantity);
        if ((point.kind === 'STOCK' || point.kind === 'OBSERVATION') && point.complete && stock !== null)
            stockObservedAt = point.observedAt;
    }
    const points = allPoints.filter((point) => point.observedAt >= from && point.observedAt <= until);
    let index = 0;
    samples.push({
        time: from,
        stock,
        stockObservedAt,
        stockMeasured: stockObservedAt === from,
        input: null,
        output: null
    });
    for (let start = from; start < until;) {
        const end = Math.min(until, (Math.floor(start / width) + 1) * width);
        const changes: { time: number; stock: bigint }[] = [];
        let input = 0n;
        let output = 0n;
        let flowObserved = false;
        let gap = false;
        let gapAt = start;
        let flowGap = false;
        while (index < points.length && points[index].observedAt <= end) {
            const point = points[index++];
            const reset = point.kind === 'GAP' || (generation && generation !== point.generation);
            if (reset || (!point.complete && (point.kind === 'STOCK' || point.kind === 'OBSERVATION'))) {
                gap = true;
                gapAt = point.observedAt;
                stock = null;
                stockObservedAt = undefined;
                changes.length = 0;
            }
            if (reset || (!point.complete && (point.kind === 'FLOW' || point.kind === 'FLOW_OBSERVATION')))
                flowGap = true;
            generation = point.generation;
            if (point.kind === 'STOCK' && point.resource && point.complete) {
                stock = BigInt(point.resource.quantity);
            }
            if ((point.kind === 'STOCK' || point.kind === 'OBSERVATION') && point.complete && stock !== null) {
                stockObservedAt = point.observedAt;
                changes.push({ time: point.observedAt, stock });
            }
            if (point.kind === 'FLOW' && point.complete && point.observedAt > from) {
                input += BigInt(point.input);
                output += BigInt(point.output);
                flowObserved = true;
            }
            if (point.kind === 'FLOW_OBSERVATION' && point.complete && point.observedAt > from) flowObserved = true;
        }
        if (gap) samples.push({ time: gapAt - 0.001, stock: null, input: null, output: null });
        if (changes.length) {
            let low = changes[0];
            let high = changes[0];
            for (const point of changes) {
                if (point.stock < low.stock) low = point;
                if (point.stock > high.stock) high = point;
            }
            const retained = new Set([changes[0], low, high, changes[changes.length - 1]]);
            for (const point of [...retained].sort((a, b) => a.time - b.time)) {
                samples.push({ ...point, stockObservedAt: point.time, stockMeasured: true, input: null, output: null });
            }
        }
        samples.push({
            time: end,
            intervalFrom: start,
            stock,
            stockObservedAt,
            input: flowObserved && !flowGap ? input : null,
            output: flowObserved && !flowGap ? output : null
        });
        start = end;
    }
    // uPlot requires strictly increasing timestamps; observations may share a bucket boundary.
    const unique = new Map<number, ChartSample>();
    for (const sample of samples) {
        const previous = unique.get(sample.time);
        unique.set(sample.time, {
            ...sample,
            stockMeasured: sample.stockMeasured || previous?.stockMeasured,
            input: sample.input ?? previous?.input ?? null,
            output: sample.output ?? previous?.output ?? null
        });
    }
    return [...unique.values()].sort((a, b) => a.time - b.time);
}
