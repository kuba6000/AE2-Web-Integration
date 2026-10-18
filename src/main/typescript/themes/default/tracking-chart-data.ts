import type { ThemeState } from '../../app/theme-api.js';

export type ChartSample = {
    time: number;
    stock: bigint | null;
    input: bigint | null;
    output: bigint | null;
    intervalFrom?: number;
    flowPartial?: boolean;
    flowBreak?: boolean;
    stockObservedAt?: number;
    stockMeasured?: boolean;
};

/** Whole measured intervals remain the minimum display unit, including sparse and short final records. */
export function flowResolution(history: ThemeState['resourceHistory']): number {
    let resolution = 1000;
    for (const point of history.points) {
        if (
            point.flowFrom !== null &&
            point.observedAt > history.from &&
            point.flowFrom < history.until &&
            (point.kind === 'FLOW' || point.kind === 'FLOW_OBSERVATION')
        )
            resolution = Math.max(resolution, point.observedAt - point.flowFrom);
    }
    return resolution;
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
        history.baseline?.kind === 'STOCK' && history.baseline.resource && history.baseline.observedAt <= from
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
        if (point.kind === 'GAP' || (generation && generation !== point.generation)) {
            stock = null;
            stockObservedAt = undefined;
        }
        generation = point.generation;
        if (point.kind === 'STOCK' && point.resource) stock = BigInt(point.resource.quantity);
        if ((point.kind === 'STOCK' || point.kind === 'OBSERVATION') && stock !== null)
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
        let gap = false;
        let gapAt = start;
        while (index < points.length && points[index].observedAt <= end) {
            const point = points[index++];
            const reset = point.kind === 'GAP' || (generation && generation !== point.generation);
            if (reset) {
                gap = true;
                gapAt = point.observedAt;
                stock = null;
                stockObservedAt = undefined;
                changes.length = 0;
            }
            generation = point.generation;
            if (point.kind === 'STOCK' && point.resource) {
                stock = BigInt(point.resource.quantity);
            }
            if ((point.kind === 'STOCK' || point.kind === 'OBSERVATION') && stock !== null) {
                stockObservedAt = point.observedAt;
                changes.push({ time: point.observedAt, stock });
            }
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
            stock,
            stockObservedAt,
            input: null,
            output: null
        });
        start = end;
    }
    const lastGap = new Map<string, number>();
    const intervals = new Map<
        string,
        { from: number; until: number; generation: string; input: bigint; output: bigint; gap: boolean }
    >();
    for (const point of history.points) {
        if (point.kind === 'GAP') lastGap.set(point.generation, point.observedAt);
        if (
            (point.kind !== 'FLOW' && point.kind !== 'FLOW_OBSERVATION') ||
            point.flowFrom === null ||
            point.observedAt <= from ||
            point.flowFrom >= until
        )
            continue;
        const key = `${point.generation}:${point.flowFrom}:${point.observedAt}`;
        let interval = intervals.get(key);
        if (!interval) {
            interval = {
                from: point.flowFrom,
                until: point.observedAt,
                generation: point.generation,
                input: 0n,
                output: 0n,
                gap: (lastGap.get(point.generation) ?? -Infinity) > point.flowFrom
            };
            intervals.set(key, interval);
        }
        if (point.kind === 'FLOW') {
            interval.input += BigInt(point.input);
            interval.output += BigInt(point.output);
        }
    }
    const groups: Array<{
        from: number;
        until: number;
        generation: string;
        input: bigint;
        output: bigint;
        gap: boolean;
    }> = [];
    for (const interval of [...intervals.values()].sort((a, b) => a.until - b.until)) {
        const previous = groups[groups.length - 1];
        if (
            previous &&
            previous.generation === interval.generation &&
            previous.until === interval.from &&
            interval.until - previous.from <= width
        ) {
            previous.until = interval.until;
            previous.input += interval.input;
            previous.output += interval.output;
            previous.gap ||= interval.gap;
        } else groups.push({ ...interval });
    }
    const stockSamples = [...samples].sort((a, b) => a.time - b.time);
    let stockIndex = 0;
    let previousFlowEnd: number | undefined;
    for (const group of groups) {
        if (previousFlowEnd !== undefined && group.from > previousFlowEnd) {
            const beforeGap = stockSamples[stockIndex];
            samples.push({
                time: previousFlowEnd + 0.001,
                stock: beforeGap.stock,
                stockObservedAt: beforeGap.stockObservedAt,
                input: null,
                output: null,
                flowBreak: true
            });
            while (stockIndex + 1 < stockSamples.length && stockSamples[stockIndex + 1].time <= group.from)
                stockIndex++;
            const held = stockSamples[stockIndex];
            samples.push({
                time: group.from,
                stock: held.stock,
                stockObservedAt: held.stockObservedAt,
                input: null,
                output: null,
                flowBreak: true
            });
        }
        while (stockIndex + 1 < stockSamples.length && stockSamples[stockIndex + 1].time <= group.until) stockIndex++;
        const held = stockSamples[stockIndex];
        samples.push({
            time: group.until,
            intervalFrom: group.from,
            stock: held.stock,
            stockObservedAt: held.stockObservedAt,
            input: group.input,
            output: group.output,
            flowPartial: group.gap
        });
        previousFlowEnd = group.until;
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
