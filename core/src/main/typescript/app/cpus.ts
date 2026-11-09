import type {
    CpuInfo,
    CpuDetail,
    CpuHeader,
    CpuResource,
    CpuResourceUpdate,
    CpuDelta,
    IconMetadata
} from './api-types.js';
import type { Api, ApiFailure } from './api.js';
import { deriveCpuDetail, type CpuMeasured } from './cpu-timing.js';
import { createLiveStream, type LiveStreamState } from './live-stream.js';
import type { Route } from './router.js';
import type { Notify } from './notifications.js';

export type CpuOutcome = { mutation?: 'cancel' | 'pause' | 'resume'; uncertain?: boolean; notice?: string };
export type CpuState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    reading: boolean;
    cpus: (CpuInfo & { key: string })[];
    detail: CpuDetail | null;
    /** Advances only when a full selected-CPU snapshot is accepted. */
    detailSnapshotRevision: number;
    /** Anonymous lists replace their rows without establishing identity between matching descriptions. */
    unkeyedRevision: number;
    icons: IconMetadata | null;
    overviewIcons: IconMetadata | null;
    error: string | null;
    outcomes: Record<string, CpuOutcome>;
    /** Connection of the selected CPU's live updates; null while its detail is read by snapshots only. */
    stream: LiveStreamState | null;
};
/**
 * Grid CPU summaries and selected current work. The application owns refresh scheduling. While the selected CPU
 * follows a live stream, `live.updated` reports its changes without rescheduling the polling of the summaries.
 */
export function createCpuMonitor(
    api: Api,
    changed: () => void,
    iconsEnabled: () => boolean,
    notify: Notify,
    live: { enabled: () => boolean; updated: (hint?: 'cpu-timing') => void }
) {
    const state: CpuState = {
        status: 'idle',
        reading: false,
        cpus: [],
        detail: null,
        detailSnapshotRevision: 0,
        unkeyedRevision: 0,
        icons: null,
        overviewIcons: null,
        error: null,
        outcomes: {},
        stream: null
    };
    /** Outcomes belong to a grid/CPU, regardless of which screen initiated the mutation. */
    const outcomes = new Map<string, Record<string, CpuOutcome>>();
    let route: Route | { view?: undefined; gridKey?: undefined } = {};
    let generation = 0;
    let request: AbortController | undefined;
    let disposed = false;
    /** What a live snapshot and its deltas delivered; the detail themes see is derived from it for the present. */
    let source: { header: CpuHeader; headerAt: number; rows: CpuMeasured[] | null } | null = null;
    let clock: ReturnType<typeof setInterval> | undefined;

    const stream = createLiveStream<CpuResource, CpuResourceUpdate, CpuDelta>({
        enabled: live.enabled,
        rows: () => source?.rows?.map(({ resource }) => resource) ?? [],
        icons: () => state.icons,
        section(delta, remap) {
            if (delta.unkeyed) state.unkeyedRevision++;
            if (!delta.cpu || !source) return;
            const { finalOutput, ...cpu } = delta.cpu;
            source = {
                ...source,
                header: { ...source.header, ...cpu, finalOutput: finalOutput && remap(finalOutput) },
                headerAt: Date.now()
            };
        },
        status(next) {
            state.stream = next;
        },
        apply({ rows, icons }) {
            if (rows && source) {
                const arrivals = new Map(source.rows?.map(({ resource, receivedAt }) => [resource, receivedAt]));
                const now = Date.now();
                source.rows = rows.all.map((resource) => ({ resource, receivedAt: arrivals.get(resource) ?? now }));
            }
            if (icons) state.icons = icons;
            showSource();
        },
        changed: live.updated,
        resync() {
            invalidateRead();
            void refresh();
        }
    });

    /** Themes see running measurements advance, so the detail is derived again while the CPU works. */
    function showSource() {
        if (source) state.detail = deriveCpuDetail(source.header, source.headerAt, source.rows, Date.now());
        const ticking =
            !!source?.header.isBusy && source.header.hasTrackingInfo && route.view === 'cpus' && !!route.cpuKey;
        if (ticking && !clock) {
            clock = setInterval(() => {
                if (document.hidden) return;
                showSource();
                live.updated('cpu-timing');
            }, 1000);
        } else if (!ticking && clock) {
            clearInterval(clock);
            clock = undefined;
        }
    }

    function stopLive() {
        stream.stop();
        source = null;
        showSource();
    }

    function invalidateRead() {
        generation++;
        request?.abort();
        state.reading = false;
        stopLive();
    }

    function fail(
        status: string,
        cpuKey: string | null | undefined = route.view === 'cpus' ? route.cpuKey : undefined
    ) {
        if (status === 'CPU_NOT_FOUND') {
            state.cpus = state.cpus.filter((cpu) => cpu.key !== cpuKey);
            if (route.view === 'cpus' && route.cpuKey && route.cpuKey !== cpuKey) return;
        } else state.cpus = [];
        stopLive();
        state.error = status;
        state.detail = null;
        state.icons = null;
        state.overviewIcons = null;
        state.status = 'error';
    }

    /** Takes the selected CPU's snapshot and follows it with live updates when they are wanted. */
    async function loadDetail(gridKey: string, cpuKey: string, signal: AbortSignal, version: number) {
        stopLive();
        const detail = await api.cpu(gridKey, cpuKey, signal, iconsEnabled(), live.enabled());
        if (version !== generation) return;
        const { items, ...header } = detail.data;
        const at = Date.now();
        source = { header, headerAt: at, rows: items?.map((resource) => ({ resource, receivedAt: at })) ?? null };
        stream.start(
            detail,
            (subscription, handlers) => api.cpuEvents(gridKey, cpuKey, subscription, iconsEnabled(), handlers),
            () => !disposed && version === generation
        );
        // A snapshot that is not followed keeps the values the server derived for it.
        if (!stream.active || !live.enabled()) source = null;
        state.detail = detail.data;
        state.detailSnapshotRevision++;
        state.unkeyedRevision++;
        state.icons = detail.icons;
        showSource();
    }

    /** A selected CPU that is followed live is only read again by an explicit refresh, not by polling. */
    async function refresh(automatic = false) {
        if (route.view !== 'cpus' || state.reading) return;
        const version = generation;
        const current = route;
        state.reading = true;
        request = new AbortController();
        state.error = null;
        changed();
        try {
            const cpus = await api.cpus(current.gridKey, request.signal, iconsEnabled());
            if (version !== generation) return;
            state.cpus = Object.entries(cpus.data).map(([key, cpu]) => ({ ...cpu, key }));
            state.overviewIcons = cpus.icons;
            if (current.cpuKey) {
                if (!state.cpus.some((cpu) => cpu.key === current.cpuKey)) {
                    fail('CPU_NOT_FOUND');
                    return;
                }
                if (!automatic || !(stream.active || stream.reconnecting)) {
                    await loadDetail(current.gridKey, current.cpuKey, request.signal, version);
                    if (version !== generation) return;
                }
            }
            state.status = 'ready';
        } catch (caught) {
            const error = caught as ApiFailure;
            if (version !== generation || error.name === 'AbortError') return;
            fail(error.status || 'NETWORK_ERROR');
        } finally {
            if (version === generation) {
                state.reading = false;
                changed();
            }
        }
    }

    async function mutate(cpuKey: string, mutation: 'cancel' | 'pause' | 'resume') {
        if (route.view !== 'cpus' || state.status !== 'ready') return;
        const cpu = route.cpuKey === cpuKey ? state.detail : state.cpus.find((cpu) => cpu.key === cpuKey);
        const previous = state.outcomes[cpuKey];
        if (!cpu?.isBusy || previous?.mutation || previous?.uncertain) return;
        if (mutation !== 'cancel' && (!cpu.supportsPause || cpu.isPaused === (mutation === 'pause'))) return;
        const gridKey = route.gridKey;
        const cpuName = state.cpus.find((entry) => entry.key === cpuKey)?.name || cpuKey;
        const gridOutcomes = state.outcomes;
        invalidateRead();
        gridOutcomes[cpuKey] = { mutation };
        state.error = null;
        changed();
        let outcome: CpuOutcome;
        try {
            if (mutation === 'cancel') await api.cancelCpu(gridKey, cpuKey);
            else await api.pauseCpu(gridKey, cpuKey, mutation === 'pause');
            outcome = mutation === 'cancel' ? { notice: 'cpuCancelled' } : {};
        } catch (caught) {
            const error = caught as ApiFailure;
            outcome =
                !error.status ||
                ['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT', 'INTERNAL_ERROR'].includes(error.status)
                    ? { uncertain: true }
                    : { notice: error.status };
        }
        if (disposed) return;
        gridOutcomes[cpuKey] = outcome;
        const failed = outcome.uncertain || (outcome.notice && outcome.notice !== 'cpuCancelled');
        let message = { cancel: 'cpuCancelAccepted', pause: 'cpuPausedNotice', resume: 'cpuResumedNotice' }[mutation];
        if (failed) message = outcome.uncertain ? 'actionUnconfirmed' : 'cpuActionFailed';
        let kind: Parameters<Notify>[0]['kind'] = 'success';
        if (outcome.uncertain) kind = 'warning';
        else if (failed) kind = 'error';
        notify({ kind, message, subject: cpuName });
        if (route.view !== 'cpus' || route.gridKey !== gridKey) return;
        invalidateRead();
        // Settled mutations still need a fresh snapshot before these observations authorize another action.
        state.status = 'loading';
        if (outcome.notice === 'CPU_NOT_FOUND' && route.cpuKey && route.cpuKey !== cpuKey) await refresh();
        else if (['CPU_NOT_FOUND', 'NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(outcome.notice ?? ''))
            fail(outcome.notice as string, cpuKey);
        else if (!['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(state.error ?? '')) await refresh();
        changed();
    }

    return {
        state,
        refresh,
        stopLive,
        invalidateIcons() {
            invalidateRead();
            state.icons = null;
            state.overviewIcons = null;
        },
        route(next: Route) {
            invalidateRead();
            const sameGrid = route.view === 'cpus' && next.view === 'cpus' && route.gridKey === next.gridKey;
            route = next;
            const gridOutcomes = next.view === 'cpus' ? outcomes.get(next.gridKey) || {} : {};
            if (next.view === 'cpus') outcomes.set(next.gridKey, gridOutcomes);
            Object.assign(state, {
                status: next.view === 'cpus' ? 'loading' : 'idle',
                cpus: sameGrid ? state.cpus : [],
                detail: null,
                icons: null,
                overviewIcons: sameGrid ? state.overviewIcons : null,
                error: null,
                outcomes: gridOutcomes
            });
        },
        block(error: string | null) {
            invalidateRead();
            Object.assign(state, { status: 'error', cpus: [], detail: null, icons: null, overviewIcons: null, error });
            changed();
        },
        cancel: (cpuKey: string) => mutate(cpuKey, 'cancel'),
        pause: (cpuKey: string, paused: boolean) => mutate(cpuKey, paused ? 'pause' : 'resume'),
        dispose() {
            disposed = true;
            invalidateRead();
            outcomes.clear();
        }
    };
}
