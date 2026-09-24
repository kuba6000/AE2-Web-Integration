/**
 * @typedef {{status: 'idle' | 'loading' | 'ready' | 'error', current: boolean | null, draft: boolean, edited: boolean, sources: import('./api-types.mjs').AccessSources, saving: boolean, uncertain: boolean, error: string | null | undefined, notice: string | null}} SettingsState
 * @typedef {{pending: boolean, desired: boolean, uncertain: boolean, result?: import('./api-types.mjs').GridSettings, error?: string}} SaveOperation
 */
/** The saved setting and the edited draft have separate ownership across refreshes.
 * @param {import('./api.mjs').Api} api
 * @param {() => void} changed
 */
export function createGridSettings(api, changed) {
    /** @type {SettingsState} */
    const state = {
        status: 'idle',
        current: null,
        draft: false,
        edited: false,
        sources: {},
        saving: false,
        uncertain: false,
        error: null,
        notice: null
    };
    /** @type {Map<string | null | undefined, SaveOperation>} */
    const saves = new Map();
    /** @type {import('./router.mjs').Route | {view?: undefined, gridKey?: undefined}} */
    let route = {};
    let generation = 0;
    /** @type {AbortController | undefined} */
    let request;
    let reading = false;
    let disposed = false;
    /** @type {import('./api-types.mjs').AccessSources} */
    let sourcesForRoute = {};
    /** @param {string | null | undefined} error */
    function clear(error) {
        sourcesForRoute = {};
        Object.assign(state, {
            status: 'error',
            current: null,
            draft: false,
            edited: false,
            sources: {},
            error,
            notice: null
        });
    }
    /** @param {string | null} error */
    function readFailure(error) {
        sourcesForRoute = {};
        if (['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(error ?? '')) clear(error);
        else {
            state.status = 'error';
            state.sources = {};
            state.error = error;
        }
    }
    return {
        state,
        /** @param {import('./router.mjs').Route} next */
        route(next) {
            generation++;
            request?.abort();
            reading = false;
            route = next;
            sourcesForRoute = {};
            const previous = saves.get(next.gridKey);
            Object.assign(state, {
                status: next.view === 'settings' ? 'loading' : 'idle',
                current: null,
                draft: previous?.desired ?? false,
                edited: !!previous?.uncertain,
                sources: {},
                saving: !!previous?.pending,
                uncertain: !!previous?.uncertain,
                error: null,
                notice: null
            });
        },
        /** @param {import('./api-types.mjs').AccessSources} sources */
        async refresh(sources) {
            if (route.view !== 'settings') return;
            sourcesForRoute = sources;
            if (reading || state.saving) return;
            const version = generation;
            request = new AbortController();
            reading = true;
            try {
                const data = await api.settings(route.gridKey, request.signal);
                if (version !== generation) return;
                state.current = data.isTracked;
                if (!state.edited) state.draft = data.isTracked;
                state.sources = sourcesForRoute;
                state.status = 'ready';
                state.error = null;
            } catch (caught) {
                const error = /** @type {import('./api.mjs').ApiFailure} */ (caught);
                if (version !== generation || error.name === 'AbortError') return;
                readFailure(error.status || 'NETWORK_ERROR');
            } finally {
                if (version === generation) {
                    reading = false;
                    changed();
                }
            }
        },
        /** @param {boolean} value */
        edit(value) {
            if (state.status !== 'ready' || state.saving) return;
            state.draft = value;
            state.edited = true;
            state.notice = null;
            changed();
        },
        async save() {
            if (
                route.view !== 'settings' ||
                state.saving ||
                state.status !== 'ready' ||
                (!state.uncertain && state.draft === state.current)
            )
                return;
            const version = ++generation;
            const key = route.gridKey;
            /** @type {SaveOperation} */
            const operation = { pending: true, desired: state.draft, uncertain: state.uncertain };
            saves.set(key, operation);
            request?.abort();
            reading = false;
            state.saving = true;
            state.error = null;
            state.notice = null;
            changed();
            try {
                operation.result = await api.saveSettings(key, { isTracked: operation.desired });
                operation.uncertain = false;
            } catch (caught) {
                const error = /** @type {import('./api.mjs').ApiFailure} */ (caught);
                operation.error = error.status || 'NETWORK_ERROR';
                if (['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT', 'INTERNAL_ERROR'].includes(operation.error))
                    operation.uncertain = true;
            }
            operation.pending = false;
            if (disposed || route.view !== 'settings' || route.gridKey !== key) return;
            state.saving = false;
            state.uncertain = operation.uncertain;
            // A route refresh can change the status while the save is awaiting its response.
            if (version === generation || /** @type {SettingsState['status']} */ (state.status) !== 'error') {
                if (operation.result)
                    Object.assign(state, {
                        status: 'ready',
                        current: operation.result.isTracked,
                        draft: operation.result.isTracked,
                        edited: false,
                        sources: sourcesForRoute,
                        notice: 'settingsSaved'
                    });
                else if (['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(operation.error ?? '')) clear(operation.error);
                else {
                    state.error = operation.error;
                    state.draft = operation.desired;
                    state.edited = true;
                }
            }
            changed();
        },
        /** @param {string | null} error */
        block(error) {
            generation++;
            request?.abort();
            reading = false;
            readFailure(error);
            changed();
        },
        dispose() {
            disposed = true;
            generation++;
            request?.abort();
            saves.clear();
        }
    };
}
