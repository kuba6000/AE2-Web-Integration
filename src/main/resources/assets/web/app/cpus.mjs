/**
 * @typedef {{mutation?: 'cancel' | 'pause' | 'resume', uncertain?: boolean, notice?: string}} CpuOutcome
 * @typedef {{status: 'idle' | 'loading' | 'ready' | 'error', cpus: (import('./api-types.mjs').CpuInfo & {key: string})[], detail: import('./api-types.mjs').CpuDetail | null, error: string | null, outcomes: Record<string, CpuOutcome>}} CpuState
 */
/** Grid CPU summaries and selected current work. The application owns refresh scheduling.
 * @param {import('./api.mjs').Api} api
 * @param {() => void} changed
 */
export function createCpuMonitor(api, changed) {
    /** @type {CpuState} */
    const state = { status: 'idle', cpus: [], detail: null, error: null, outcomes: {} };
    /** Outcomes belong to a grid/CPU, regardless of which screen initiated the mutation.
     * @type {Map<string, Record<string, CpuOutcome>>} */
    const outcomes = new Map();
    /** @type {import('./router.mjs').Route | {view?: undefined, gridKey?: undefined}} */
    let route = {};
    let generation = 0;
    /** @type {AbortController | undefined} */
    let request;
    let reading = false;
    let disposed = false;

    function invalidateRead() {
        generation++;
        request?.abort();
        reading = false;
    }

    /** @param {string} status @param {string | null | undefined} [cpuKey] */
    function fail(status, cpuKey = route.view === 'cpus' ? route.cpuKey : undefined) {
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
            const error = /** @type {import('./api.mjs').ApiFailure} */ (caught);
            if (version !== generation || error.name === 'AbortError') return;
            fail(error.status || 'NETWORK_ERROR');
        } finally {
            if (version === generation) {
                reading = false;
                changed();
            }
        }
    }

    /** @param {string} cpuKey @param {'cancel' | 'pause' | 'resume'} mutation */
    async function mutate(cpuKey, mutation) {
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
        /** @type {CpuOutcome} */
        let outcome;
        try {
            if (mutation === 'cancel') await api.cancelCpu(gridKey, cpuKey);
            else await api.pauseCpu(gridKey, cpuKey, mutation === 'pause');
            outcome = mutation === 'cancel' ? { notice: 'cpuCancelled' } : {};
        } catch (caught) {
            const error = /** @type {import('./api.mjs').ApiFailure} */ (caught);
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
            fail(/** @type {string} */ (outcome.notice), cpuKey);
        else if (!['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(state.error ?? '')) await refresh();
        changed();
    }

    return {
        state,
        refresh,
        /** @param {import('./router.mjs').Route} next */
        route(next) {
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
        /** @param {string | null} error */
        block(error) {
            invalidateRead();
            Object.assign(state, { status: 'error', cpus: [], detail: null, error });
            changed();
        },
        /** @param {string} cpuKey */
        cancel: (cpuKey) => mutate(cpuKey, 'cancel'),
        /** @param {string} cpuKey @param {boolean} paused */
        pause: (cpuKey, paused) => mutate(cpuKey, paused ? 'pause' : 'resume'),
        dispose() {
            disposed = true;
            invalidateRead();
            outcomes.clear();
        }
    };
}
