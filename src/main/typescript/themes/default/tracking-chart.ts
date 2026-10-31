import uPlot from './uplot.js';
import { element } from './dom.js';
import type { ThemeState, Translator, PinHomeConfig } from '../../app/theme-api.js';
import { chartSamples, flowDisplayInterval, flowResolution, type ChartSample } from './tracking-chart-data.js';

/** Theme-owned renderer. Exact counters stay in samples; Canvas coordinates are approximate. */
export function createTrackingChart(
    host: HTMLElement,
    compact: boolean,
    intervalChanged: (millis: number) => void,
    height?: number
) {
    const readout = compact ? element('div', '', 'tracking-sparkline-readout') : null;
    if (readout) {
        readout.hidden = true;
        host.append(readout);
    }
    let selectedSeries: Pick<PinHomeConfig, 'stock' | 'input' | 'output' | 'net'> | undefined;
    let plot: uPlot | undefined;
    let samples: ChartSample[] = [];
    let pixel = true;
    let history: ThemeState['resourceHistory'] | undefined;
    let locale: Translator;
    let signature = '';
    let previousPoints: ThemeState['resourceHistory']['points'] | undefined;
    let mobile = false;
    let identity = '';
    let rangeMillis = 0;
    let preserveZoom = true;
    let building = false;
    let rebuildPending = false;
    let disposed = false;
    function scheduleBuild() {
        if (building || rebuildPending || disposed) return;
        rebuildPending = true;
        queueMicrotask(() => {
            rebuildPending = false;
            if (!disposed) build();
        });
    }
    let pinned: { time: number; top: number } | null = null;
    let pressed: { x: number; y: number; touch: boolean; moved: boolean } | null = null;
    function clearPin() {
        pinned = null;
        host.classList.remove('tracking-chart-pinned');
        plot?.setCursor({ left: -10, top: -10 });
    }
    function restorePin(chart: uPlot) {
        host.classList.toggle('tracking-chart-pinned', pinned !== null);
        if (pinned)
            chart.setCursor({ left: chart.valToPos(pinned.time, 'x'), top: pinned.top * chart.over.clientHeight });
    }
    const releaseOnEscape = (event: KeyboardEvent) => {
        if (event.key === 'Escape' && pinned && host.getClientRects().length) clearPin();
    };
    window.addEventListener('keydown', releaseOnEscape);
    const chartHeight = () => height ?? (compact ? 110 : mobile ? 280 : 380);
    const pixelCanvas = document.createElement('canvas');
    const pixelContext = pixelCanvas.getContext('2d', { willReadFrequently: true })!;
    const amount = (value: bigint | null) =>
        value === null ? '—' : new Intl.NumberFormat(document.documentElement.lang).format(value);
    function drawPixelLine(chart: uPlot, net: boolean) {
        if (!pixel || !chart.series[net ? 4 : 3]?.show) return;
        const { left, top, width, height } = chart.bbox;
        const pitch = (net ? 1 : 2) * devicePixelRatio;
        pixelCanvas.width = Math.max(1, Math.ceil(width / pitch));
        pixelCanvas.height = Math.max(1, Math.ceil(height / pitch));
        pixelContext.strokeStyle = net ? '#80c9ed' : '#d69b28';
        pixelContext.lineWidth = 1;
        pixelContext.beginPath();
        let connected = false;
        let previousY = 0;
        for (const sample of samples) {
            if (net && sample.flowBreak) connected = false;
            if (net && sample.intervalFrom === undefined) continue;
            const value = net
                ? sample.input === null || sample.output === null
                    ? null
                    : sample.input - sample.output
                : sample.stock;
            if (value === null) {
                connected = false;
                continue;
            }
            const x = Math.round((chart.valToPos(sample.time / 1000, 'x', true) - left) / pitch);
            const y = Math.round((chart.valToPos(Number(value), net ? 'flow' : 'stock', true) - top) / pitch);
            if (!Number.isFinite(x) || !Number.isFinite(y)) {
                connected = false;
                continue;
            }
            if (connected) {
                if (!net) pixelContext.lineTo(x, previousY);
                pixelContext.lineTo(x, y);
            } else pixelContext.moveTo(x, y);
            previousY = y;
            connected = true;
        }
        // Native Canvas clips offscreen segments during zoom. Threshold only the bounded raster,
        // then upscale without smoothing; labels never enter this low-resolution layer.
        pixelContext.stroke();
        const raster = pixelContext.getImageData(0, 0, pixelCanvas.width, pixelCanvas.height);
        for (let offset = 3; offset < raster.data.length; offset += 4) {
            raster.data[offset] = raster.data[offset] >= 96 ? 255 : 0;
            raster.data[offset - 3] = net ? 128 : 214;
            raster.data[offset - 2] = net ? 201 : 155;
            raster.data[offset - 1] = net ? 237 : 40;
        }
        pixelContext.putImageData(raster, 0, 0);
        chart.ctx.save();
        chart.ctx.beginPath();
        chart.ctx.rect(left, top, width, height);
        chart.ctx.clip();
        chart.ctx.imageSmoothingEnabled = false;
        chart.ctx.drawImage(pixelCanvas, left, top, pixelCanvas.width * pitch, pixelCanvas.height * pitch);
        chart.ctx.restore();
    }
    function drawStockMeasurements(chart: uPlot) {
        if (!chart.series[3]?.show) return;
        const { left, top, width, height } = chart.bbox;
        const size = 5 * devicePixelRatio;
        chart.ctx.save();
        chart.ctx.beginPath();
        chart.ctx.rect(left, top, width, height);
        chart.ctx.clip();
        chart.ctx.fillStyle = '#d69b28';
        for (const sample of samples) {
            if (!sample.stockMeasured || sample.stock === null) continue;
            const x = chart.valToPos(sample.time / 1000, 'x', true);
            const y = chart.valToPos(Number(sample.stock), 'stock', true);
            if (Number.isFinite(x) && Number.isFinite(y))
                chart.ctx.fillRect(Math.round(x - size / 2), Math.round(y - size / 2), size, size);
        }
        chart.ctx.restore();
    }
    function build() {
        if (!history || !host.clientWidth || host.closest('[hidden]')) return;
        building = true;
        const previousScale = plot?.scales.x;
        const zoom =
            preserveZoom &&
            previousScale?.min != null &&
            previousScale.max != null &&
            previousScale.max - previousScale.min < history.rangeMillis / 1000 - 0.1
                ? { min: previousScale.min, max: previousScale.max }
                : null;
        preserveZoom = true;
        const visibleSeries = plot?.series.map((series) => series.show !== false);
        plot?.destroy();
        pressed = null;
        mobile = host.clientWidth < 480;
        const resolution = flowResolution(history);
        let visibleFrom = Math.max(history.from, zoom ? zoom.min * 1000 : history.from);
        let visibleUntil = Math.min(history.until, zoom ? zoom.max * 1000 : history.until);
        if (visibleUntil - visibleFrom < resolution) {
            const start = Math.floor((visibleFrom + visibleUntil) / 2 / resolution) * resolution;
            visibleUntil = Math.min(history.until, start + resolution);
            visibleFrom = Math.max(history.from, visibleUntil - resolution);
        }
        const intervalMillis = flowDisplayInterval(
            visibleUntil - visibleFrom,
            Math.max(80, host.clientWidth - (compact ? 60 : 160)),
            resolution
        );
        const sampleFrom = Math.max(history.from, Math.floor(visibleFrom / intervalMillis) * intervalMillis);
        const sampleUntil = Math.min(history.until, Math.ceil(visibleUntil / intervalMillis) * intervalMillis);
        samples = chartSamples(history, intervalMillis, sampleFrom, sampleUntil);
        intervalChanged(intervalMillis);
        const hasFlow = samples.some((sample) => sample.input !== null || sample.output !== null);
        const hasStock = samples.some((sample) => sample.stock !== null);
        const flowIntervals = samples.flatMap((sample, index) =>
            sample.intervalFrom === undefined ? [] : [{ from: sample.intervalFrom, until: sample.time, index }]
        );
        const flowIndex = (seconds: number) => {
            const time = seconds * 1000;
            let low = 0;
            let high = flowIntervals.length;
            while (low < high) {
                const middle = (low + high) >>> 1;
                if (flowIntervals[middle].until <= time) low = middle + 1;
                else high = middle;
            }
            const interval = flowIntervals[low];
            return interval && time >= interval.from ? interval.index : undefined;
        };
        // Explicit interval geometry avoids narrow bars caused by nearby stock extrema. Include
        // boundary-crossing bars during zoom; Canvas clips their rectangles to the plotting area.
        const bars: uPlot.Series.PathBuilder = (chart, series) => {
            const path = new Path2D();
            const zero = chart.valToPos(0, 'flow', true);
            for (const sample of samples) {
                const amount = series === 1 ? sample.input : sample.output;
                if (sample.intervalFrom === undefined || amount === null) continue;
                const left = chart.valToPos(sample.intervalFrom / 1000, 'x', true);
                const right = chart.valToPos(sample.time / 1000, 'x', true);
                const value = chart.valToPos(Number(amount) * (series === 1 ? 1 : -1), 'flow', true);
                path.rect(left, Math.min(zero, value), right - left, Math.abs(zero - value));
            }
            return { fill: path, stroke: null, flags: 0 };
        };
        const numbers = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
        const time = new Intl.DateTimeFormat(document.documentElement.lang, {
            hour: '2-digit',
            minute: '2-digit',
            hourCycle: 'h23'
        });
        const timeWithSeconds = new Intl.DateTimeFormat(document.documentElement.lang, {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hourCycle: 'h23'
        });
        const date = new Intl.DateTimeFormat(document.documentElement.lang, { month: 'short', day: 'numeric' });
        const axis = {
            stroke: '#888888',
            grid: { stroke: '#88888830' },
            ticks: { stroke: '#88888855' },
            font: `11px ${getComputedStyle(host).fontFamily}`
        };
        const valueAxisSize: uPlot.Axis.Size = (chart, values) => {
            chart.ctx.save();
            chart.ctx.font = axis.font;
            let width = 24;
            for (const value of values ?? []) width = Math.max(width, chart.ctx.measureText(value ?? '').width);
            chart.ctx.restore();
            return Math.ceil(width) + 12;
        };
        plot = new uPlot(
            {
                width: host.clientWidth,
                height: chartHeight(),
                padding: compact ? [8, 8, 8, 8] : [12, 8, 0, 0],
                cursor: {
                    drag: { x: !compact, y: false, dist: 4 },
                    points: { show: false },
                    move: (chart, left, top) =>
                        pinned && !pressed
                            ? [chart.valToPos(pinned.time, 'x'), pinned.top * chart.over.clientHeight]
                            : [left, top],
                    dataIdx: (_chart, series, closest, time) => {
                        if (series === 3) {
                            // A held stock value belongs to the last sample at or before the cursor.
                            return samples[closest]?.time > time * 1000 ? Math.max(0, closest - 1) : closest;
                        }
                        if (compact) return closest;
                        return flowIndex(time) ?? null;
                    }
                },
                legend: { show: !compact && !selectedSeries, live: true },
                scales: {
                    x: { time: true },
                    stock: { auto: true },
                    flow: {
                        auto: true,
                        range: (chart) => {
                            let input = 0,
                                output = 0;
                            const left = (chart.scales.x.min ?? history!.from / 1000) * 1000;
                            const right = (chart.scales.x.max ?? history!.until / 1000) * 1000;
                            for (const sample of samples)
                                if (
                                    sample.intervalFrom !== undefined &&
                                    sample.intervalFrom <= right &&
                                    sample.time >= left
                                ) {
                                    input = Math.max(input, Number(sample.input ?? 0n));
                                    output = Math.max(output, Number(sample.output ?? 0n));
                                }
                            return input || output ? [-output * 1.1, input * 1.1] : [0, 1];
                        }
                    }
                },
                axes: compact
                    ? [{ show: false }, { show: false }]
                    : [
                          {
                              ...axis,
                              space: 85,
                              values: (_chart, values) => {
                                  const clock = values.some(
                                      (value, index) => index > 0 && value - values[index - 1] < 60
                                  )
                                      ? timeWithSeconds
                                      : time;
                                  return values.map((value, index) => {
                                      const instant = new Date(value * 1000);
                                      const previous = new Date((values[index - 1] ?? value) * 1000);
                                      if (index > 0 && instant.toDateString() !== previous.toDateString())
                                          return `${date.format(instant)}\n${clock.format(instant)}`;
                                      return clock.format(instant);
                                  });
                              }
                          },
                          {
                              ...axis,
                              scale: 'stock',
                              show: hasStock,
                              size: valueAxisSize,
                              label: mobile ? undefined : locale.common('stored'),
                              labelFont: axis.font,
                              labelGap: 6,
                              values: (_u, values) => values.map((value) => numbers.format(value))
                          },
                          {
                              ...axis,
                              show: hasFlow,
                              scale: 'flow',
                              side: 1,
                              grid: { ...axis.grid, show: !hasStock },
                              size: valueAxisSize,
                              label: mobile ? undefined : locale.t('trackingFlow'),
                              labelFont: axis.font,
                              labelGap: 6,
                              values: (_u, values) => values.map((value) => numbers.format(value))
                          }
                      ],
                series: [
                    {
                        label: locale.t('trackingTime'),
                        value: (chart, value, _series, index) => {
                            if (value == null || index === null) return '—';
                            const bucket = flowIndex(chart.posToVal(chart.cursor.left ?? -10, 'x'));
                            const sample = samples[bucket ?? index];
                            if (sample.intervalFrom !== undefined) {
                                const partial = sample.flowPartial ? ` (${locale.t('trackingPartial')})` : '';
                                return `${locale.dateTime(sample.intervalFrom, sample.time)}–${locale.time(sample.time)}${partial}`;
                            }
                            return locale.dateTime(value * 1000);
                        }
                    },
                    {
                        label: locale.common('resourceHistoryInput'),
                        scale: 'flow',
                        stroke: '#58a66b',
                        fill: '#58a66b77',
                        paths: bars,
                        width: 0,
                        show: selectedSeries?.input ?? (!compact && (visibleSeries?.[1] ?? true)),
                        points: { show: false },
                        value: (_u, _v, _s, index) => amount(index === null ? null : (samples[index]?.input ?? null))
                    },
                    {
                        label: locale.common('resourceHistoryOutput'),
                        scale: 'flow',
                        stroke: '#cf6464',
                        fill: '#cf646477',
                        paths: bars,
                        width: 0,
                        show: selectedSeries?.output ?? (!compact && (visibleSeries?.[2] ?? true)),
                        points: { show: false },
                        value: (_u, _v, _s, index) => amount(index === null ? null : (samples[index]?.output ?? null))
                    },
                    {
                        label: locale.common('stored'),
                        show: selectedSeries?.stock ?? visibleSeries?.[3] ?? true,
                        scale: 'stock',
                        stroke: '#d69b28',
                        paths: pixel ? () => null : uPlot.paths.stepped!({ align: 1 }),
                        width: 2,
                        points: { show: false },
                        spanGaps: false,
                        value: (_u, _v, _s, index) => {
                            const sample = index === null ? undefined : samples[index];
                            const quantity = amount(sample?.stock ?? null);
                            return sample?.stockObservedAt === undefined
                                ? quantity
                                : `${quantity} (${locale.time(sample.stockObservedAt)})`;
                        }
                    },
                    {
                        label: locale.t('trackingNet'),
                        scale: 'flow',
                        stroke: '#80c9ed',
                        paths: pixel ? () => null : undefined,
                        width: 1,
                        show: selectedSeries?.net ?? (!compact && (visibleSeries?.[4] ?? true)),
                        points: { show: false },
                        spanGaps: false,
                        value: (_u, _v, _s, index) => {
                            const sample = index === null ? undefined : samples[index];
                            return amount(
                                sample?.input == null || sample.output == null ? null : sample.input - sample.output
                            );
                        }
                    }
                ],
                hooks: {
                    setCursor: [
                        (chart) => {
                            if (!readout) return;
                            const index = chart.cursor.idxs?.[3];
                            if ((chart.cursor.left ?? -1) < 0 || index == null) {
                                readout.hidden = true;
                                return;
                            }
                            readout.hidden = false;
                            const sample = samples[index];
                            readout.replaceChildren(
                                element('strong', `${locale.common('stored')}: ${amount(sample.stock)}`)
                            );
                            if (sample.stock !== null && sample.stockObservedAt !== undefined) {
                                const time = element('time', locale.dateTime(sample.stockObservedAt, history!.until));
                                time.dateTime = new Date(sample.stockObservedAt).toISOString();
                                readout.append(time);
                            }
                        }
                    ],
                    draw: [
                        (chart) => {
                            drawPixelLine(chart, false);
                            drawPixelLine(chart, true);
                        },
                        drawStockMeasurements
                    ],
                    setSize: [restorePin],
                    setScale: [
                        (chart, key) => {
                            if (key !== 'x' || building || chart !== plot) return;
                            const { min, max } = chart.scales.x;
                            if (min == null || max == null) return;
                            if (Math.abs(min * 1000 - visibleFrom) < 0.1 && Math.abs(max * 1000 - visibleUntil) < 0.1)
                                return;
                            scheduleBuild();
                        }
                    ]
                }
            },
            [
                samples.map((sample) => sample.time / 1000),
                samples.map((sample) => (sample.input === null ? null : Number(sample.input))),
                samples.map((sample) => (sample.output === null ? null : -Number(sample.output))),
                samples.map((sample) => (sample.stock === null ? null : Number(sample.stock))),
                samples.map((sample) => {
                    if (sample.flowBreak) return null;
                    if (sample.intervalFrom === undefined)
                        return flowIndex(sample.time / 1000) === undefined ? null : undefined;
                    if (sample.input === null || sample.output === null) return null;
                    return Number(sample.input - sample.output);
                })
            ],
            host
        );
        const current = plot;
        current.over.tabIndex = 0;
        current.over.style.touchAction = 'pan-y pinch-zoom';
        current.over.addEventListener('pointerdown', (event) => {
            if (event.button !== 0 || !event.isPrimary) return;
            pressed = { x: event.clientX, y: event.clientY, touch: event.pointerType === 'touch', moved: false };
            current.over.setPointerCapture(event.pointerId);
        });
        current.over.addEventListener('pointermove', (event) => {
            if (!event.isPrimary) return;
            if (pressed && Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) > 4) pressed.moved = true;
        });
        current.over.addEventListener('pointerup', (event) => {
            if (!event.isPrimary) return;
            const start = pressed;
            if (!start) return;
            pressed = null;
            const bounds = current.over.getBoundingClientRect();
            const dx = event.clientX - start.x,
                dy = event.clientY - start.y;
            if (start.moved || Math.hypot(dx, dy) > 4) {
                pinned = null;
                host.classList.remove('tracking-chart-pinned');
                if (!compact && start.touch && Math.abs(dx) > 4 && Math.abs(dx) > Math.abs(dy)) {
                    const first = current.posToVal(start.x - bounds.left, 'x');
                    const last = current.posToVal(event.clientX - bounds.left, 'x');
                    current.setScale('x', { min: Math.min(first, last), max: Math.max(first, last) });
                }
                return;
            }
            const left = event.clientX - bounds.left;
            if (pinned && Math.abs(current.valToPos(pinned.time, 'x') - left) <= 4) {
                clearPin();
                current.setSelect({ left: 0, top: 0, width: 0, height: 0 }, false);
                return;
            }
            pinned = {
                time: current.posToVal(left, 'x'),
                top: Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height))
            };
            restorePin(current);
            // Pointerup precedes uPlot's mouseup: moving the pinned cursor must not leave
            // a synthetic drag selection for that subsequent native handler to zoom into.
            current.setSelect({ left: 0, top: 0, width: 0, height: 0 }, false);
        });
        current.over.addEventListener('pointercancel', (event) => {
            if (!event.isPrimary) return;
            pressed = null;
            restorePin(current);
        });
        current.over.addEventListener(
            'dblclick',
            (event) => {
                // The plot holds only the visible sample window; native reset would stop there.
                event.stopImmediatePropagation();
                clearPin();
                preserveZoom = false;
                scheduleBuild();
            },
            { capture: true }
        );
        const touchCursor = (event: PointerEvent) => {
            if (event.pointerType !== 'touch' || !event.isPrimary) return;
            const bounds = current.over.getBoundingClientRect();
            current.setCursor({ left: event.clientX - bounds.left, top: event.clientY - bounds.top });
        };
        current.over.addEventListener('pointerdown', touchCursor);
        current.over.addEventListener('pointermove', touchCursor);
        current.setScale('x', { min: visibleFrom / 1000, max: visibleUntil / 1000 });
        restorePin(current);
        building = false;
    }
    const resize = new ResizeObserver(() => {
        if (host.clientWidth && plot && plot.width !== host.clientWidth) scheduleBuild();
        else if (!plot) build();
    });
    resize.observe(host);
    return {
        render(
            next: ThemeState['resourceHistory'],
            translator: Translator,
            pixelated: boolean,
            resourceKey: string,
            series?: Pick<PinHomeConfig, 'stock' | 'input' | 'output' | 'net'>
        ) {
            if (identity !== resourceKey || rangeMillis !== next.rangeMillis) {
                preserveZoom = false;
                clearPin();
            }
            if (pinned && (pinned.time * 1000 < next.from || pinned.time * 1000 > next.until)) clearPin();
            identity = resourceKey;
            rangeMillis = next.rangeMillis;
            selectedSeries = series;
            const nextSignature = `${next.from}/${next.until}/${translator.t('trackingFlow')}/${pixelated}/${series ? `${series.stock}/${series.input}/${series.output}/${series.net}` : ''}`;
            const changed = next.points !== previousPoints || nextSignature !== signature;
            previousPoints = next.points;
            history = next;
            locale = translator;
            pixel = pixelated;
            signature = nextSignature;
            if (changed || !plot) build();
        },
        resetZoom() {
            clearPin();
            preserveZoom = false;
            build();
        },
        dispose() {
            disposed = true;
            window.removeEventListener('keydown', releaseOnEscape);
            resize.disconnect();
            plot?.destroy();
            readout?.remove();
        }
    };
}
