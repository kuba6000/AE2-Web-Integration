/** @typedef {{status: 'idle' | 'loading' | 'ready' | 'error', entries: import('./api-types.mjs').HistoryEntry[], detail: import('./api-types.mjs').HistoryDetail | null, error: string | null}} HistoryState */
/** Completed history snapshots are read on entry or explicit refresh, not on each polling tick.
 * @param {import('./api.mjs').Api} api
 * @param {() => void} changed
 */
export function createHistory(api, changed) {
    /** @type {HistoryState} */
    const state = { status: 'idle', entries: [], detail: null, error: null };
    /** @type {import('./router.mjs').Route | {view?: undefined, gridKey?: undefined}} */
    let route = {};
    let generation = 0;
    /** @type {AbortController | undefined} */
    let request;
    let reading = false;
    return {
        state,
        /** @param {import('./router.mjs').Route} next */
        route(next) {
            generation++;
            request?.abort();
            reading = false;
            route = next;
            Object.assign(state, {
                status: next.view === 'history' ? 'loading' : 'idle',
                entries: [],
                detail: null,
                error: null
            });
        },
        async refresh(reloadDetail = false) {
            if (route.view !== 'history' || reading || (route.entryId !== null && state.detail && !reloadDetail))
                return;
            const current = route;
            const version = generation;
            request = new AbortController();
            reading = true;
            try {
                if (current.entryId !== null) {
                    const data = await api.historyEntry(current.gridKey, current.entryId, request.signal);
                    if (version !== generation) return;
                    state.detail = data;
                } else {
                    const data = await api.history(current.gridKey, request.signal);
                    if (version !== generation) return;
                    state.entries = data;
                }
                state.status = 'ready';
                state.error = null;
            } catch (caught) {
                const error = /** @type {import('./api.mjs').ApiFailure} */ (caught);
                if (version !== generation || error.name === 'AbortError') return;
                Object.assign(state, {
                    status: 'error',
                    entries: [],
                    detail: null,
                    error: error.status || 'NETWORK_ERROR'
                });
            } finally {
                if (version === generation) {
                    reading = false;
                    changed();
                }
            }
        },
        /** @param {string | null} error */
        block(error) {
            generation++;
            request?.abort();
            reading = false;
            Object.assign(state, { status: 'error', entries: [], detail: null, error });
            changed();
        },
        dispose() {
            generation++;
            request?.abort();
        }
    };
}
