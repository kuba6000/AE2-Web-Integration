/** Grid CPU summaries and selected current work. The application owns refresh scheduling. */
export function createCpuMonitor(api, changed) {
    const state = {status: 'idle', cpus: [], detail: null, error: null, cancelling: false, uncertain: false, notice: null};
    const outcomes = new Map();
    let route = {};
    let generation = 0;
    let request;
    let reading = false;
    let disposed = false;
    const identity = () => `${route.gridKey}/${route.cpuKey}`;

    function fail(status) {
        state.error = status; state.detail = null; state.status = 'error';
        state.cpus = status === 'CPU_NOT_FOUND' ? state.cpus.filter(cpu => cpu.key !== route.cpuKey) : [];
    }

    async function refresh() {
        if (route.view !== 'cpus' || reading || state.cancelling) return;
        const version = generation;
        const current = route;
        reading = true;
        request = new AbortController();
        state.error = null;
        try {
            const cpus = await api.cpus(current.gridKey, request.signal);
            if (version !== generation) return;
            state.cpus = Object.entries(cpus).map(([key, cpu]) => ({...cpu, key}));
            if (current.cpuKey) {
                if (!state.cpus.some(cpu => cpu.key === current.cpuKey)) { fail('CPU_NOT_FOUND'); return; }
                const detail = await api.cpu(current.gridKey, current.cpuKey, request.signal);
                if (version !== generation) return;
                state.detail = detail;
            }
            state.status = 'ready';
        } catch (error) {
            if (version !== generation || error.name === 'AbortError') return;
            fail(error.status || 'NETWORK_ERROR');
        } finally {
            if (version === generation) { reading = false; changed(); }
        }
    }
    return {
        state,
        refresh,
        route(next) {
            generation++; request?.abort(); reading = false; route = next;
            Object.assign(state, {status: next.view === 'cpus' ? 'loading' : 'idle', cpus: [], detail: null, error: null, cancelling: false, uncertain: false, notice: null}, outcomes.get(identity()));
        },
        block(error) {
            generation++; request?.abort(); reading = false;
            Object.assign(state, {status: 'error', cpus: [], detail: null, error}); changed();
        },
        async cancel() {
            if (route.view !== 'cpus' || state.cancelling || state.uncertain || state.status !== 'ready' || !state.detail?.isBusy) return;
            const current = route;
            const key = identity();
            generation++;
            request?.abort(); reading = false;
            state.cancelling = true; state.error = null; state.notice = null; changed();
            outcomes.set(key, {cancelling: true});
            let outcome;
            try {
                await api.cancelCpu(current.gridKey, current.cpuKey);
                outcome = {notice: 'cpuCancelled'};
            } catch (error) {
                outcome = !error.status || ['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT', 'INTERNAL_ERROR'].includes(error.status)
                    ? {uncertain: true} : {notice: error.status};
            }
            if (disposed) return;
            outcomes.set(key, outcome);
            if (route.view !== 'cpus' || identity() !== key) return;
            Object.assign(state, {cancelling: false, detail: null}, outcome);
            if (['CPU_NOT_FOUND', 'NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(outcome.notice)) fail(outcome.notice);
            else if (!['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(state.error)) await refresh();
            changed();
        },
        dispose() { disposed = true; generation++; request?.abort(); outcomes.clear(); }
    };
}
