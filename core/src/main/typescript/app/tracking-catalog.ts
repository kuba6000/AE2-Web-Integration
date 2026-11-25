import type { Api, ApiFailure } from './api.js';
import type { HistoricalResource } from './api-types.js';
import type { Route } from './router.js';

export type TrackingCatalogState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    resources: HistoricalResource[];
    nextAfter: string | null;
    error: string | null;
};

export function createTrackingCatalog(api: Pick<Api, 'historicalResources'>, changed: () => void) {
    const state: TrackingCatalogState = { status: 'idle', resources: [], nextAfter: null, error: null };
    let request: AbortController | undefined;
    function clear(error: string | null = null) {
        request?.abort();
        request = undefined;
        Object.assign(state, { status: error ? 'error' : 'idle', resources: [], nextAfter: null, error });
    }
    return {
        state,
        clear,
        async load(route: Extract<Route, { itemKey: null }>, manual: boolean) {
            if (request || (state.status !== 'idle' && !manual)) return;
            const current = (request = new AbortController());
            state.status = 'loading';
            state.error = null;
            changed();
            try {
                const page = await api.historicalResources(route.gridKey, route.search, route.after, current.signal);
                if (!current.signal.aborted)
                    Object.assign(state, { status: 'ready', resources: page.data, nextAfter: page.nextAfter ?? null });
            } catch (caught) {
                if (!current.signal.aborted) clear((caught as ApiFailure).status || 'NETWORK_ERROR');
            } finally {
                if (request === current) request = undefined;
                changed();
            }
        }
    };
}
