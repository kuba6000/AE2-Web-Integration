import type { CpuInfo, CpuDetail } from './api-types.js';
import type { Api, ApiFailure } from './api.js';
import type { Route } from './router.js';

export type CpuOutcome = { mutation?: 'cancel' | 'pause' | 'resume'; uncertain?: boolean; notice?: string };
export type CpuState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    cpus: (CpuInfo & { key: string })[];
    detail: CpuDetail | null;
    error: string | null;
    outcomes: Record<string, CpuOutcome>;
};
/** Grid CPU summaries and selected current work. The application owns refresh scheduling. */
export function createCpuMonitor(api: Api, changed: () => void) {
    const state: CpuState = { status: 'idle', cpus: [], detail: null, error: null, outcomes: {} };
    /** Outcomes belong to a grid/CPU, regardless of which screen initiated the mutation. */
    const outcomes = new Map<string, Record<string, CpuOutcome>>();
    let route: Route | { view?: undefined; gridKey?: undefined } = {};
    let generation = 0;
    let request: AbortController | undefined;
    let reading = false;
    let disposed = false;

    function invalidateRead() {
        generation++;
        request?.abort();
        reading = false;
    }

    function fail(
        status: string,
        cpuKey: string | null | undefined = route.view === 'cpus' ? route.cpuKey : undefined
    ) {
        if (status === 'CPU_NOT_FOUND') {
            state.cpus = state.cpus.filter((cpu) => cpu.key !== cpuKey);
            if (route.view === 'cpus' && route.cpuKey && route.cpuKey !== cpuKey) return;
        } else state.cpus = [];
        state.error = status;
        state.detail = null;
        state.status = 'error';
    }

    async function refresh() {
        if (route.view !== 'cpus' || reading) return;
        const version = generation;
        const current = route;
        reading = true;
        request = new AbortController();
        state.error = null;
        try {
            const cpus = await api.cpus(current.gridKey, request.signal);
            if (version !== generation) return;
            state.cpus = Object.entries(cpus).map(([key, cpu]) => ({ ...cpu, key }));
            if (current.cpuKey) {
                if (!state.cpus.some((cpu) => cpu.key === current.cpuKey)) {
                    fail('CPU_NOT_FOUND');
                    return;
                }
                const detail = await api.cpu(current.gridKey, current.cpuKey, request.signal);
                if (version !== generation) return;
                state.detail = detail;
            }
            state.status = 'ready';
        } catch (caught) {
            const error = caught as ApiFailure;
            if (version !== generation || error.name === 'AbortError') return;
            fail(error.status || 'NETWORK_ERROR');
        } finally {
            if (version === generation) {
                reading = false;
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
        route(next: Route) {
            invalidateRead();
            route = next;
            const gridOutcomes = next.view === 'cpus' ? outcomes.get(next.gridKey) || {} : {};
            if (next.view === 'cpus') outcomes.set(next.gridKey, gridOutcomes);
            Object.assign(state, {
                status: next.view === 'cpus' ? 'loading' : 'idle',
                cpus: [],
                detail: null,
                error: null,
                outcomes: gridOutcomes
            });
        },
        block(error: string | null) {
            invalidateRead();
            Object.assign(state, { status: 'error', cpus: [], detail: null, error });
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
