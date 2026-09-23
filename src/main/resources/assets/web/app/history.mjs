/** Completed history snapshots are read on entry or explicit refresh, not on each polling tick. */
export function createHistory(api, changed) {
    const state = {status: 'idle', entries: [], detail: null, error: null};
    let route = {};
    let generation = 0;
    let request;
    let reading = false;
    return {
        state,
        route(next) {
            generation++; request?.abort(); reading = false; route = next;
            Object.assign(state, {status: next.view === 'history' ? 'loading' : 'idle', entries: [], detail: null, error: null});
        },
        async refresh(reloadDetail = false) {
            if (route.view !== 'history' || reading || route.entryId !== null && state.detail && !reloadDetail) return;
            const current = route;
            const version = generation;
            request = new AbortController(); reading = true;
            try {
                const data = current.entryId !== null ? await api.historyEntry(current.gridKey, current.entryId, request.signal)
                    : await api.history(current.gridKey, request.signal);
                if (version !== generation) return;
                if (current.entryId !== null) state.detail = data; else state.entries = data;
                state.status = 'ready'; state.error = null;
            } catch (error) {
                if (version !== generation || error.name === 'AbortError') return;
                Object.assign(state, {status: 'error', entries: [], detail: null, error: error.status || 'NETWORK_ERROR'});
            } finally { if (version === generation) { reading = false; changed(); } }
        },
        block(error) {
            generation++; request?.abort(); reading = false;
            Object.assign(state, {status: 'error', entries: [], detail: null, error}); changed();
        },
        dispose() { generation++; request?.abort(); }
    };
}
