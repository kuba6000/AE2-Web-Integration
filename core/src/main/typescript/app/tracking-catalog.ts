import type { Api, ApiFailure } from './api.js';
import type { ActivityTotals, HistoricalResource, IconMetadata, StockObservation } from './api-types.js';
import type { Route } from './router.js';

export type TrackingCatalogState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    resources: HistoricalResource[];
    icons: IconMetadata | null;
    activity: Record<string, ActivityTotals>;
    stock: Record<string, StockObservation>;
    windowEnd: number | null;
    lastObservedAt: Record<string, number>;
    nextAfter: string | null;
    error: string | null;
};

export function createTrackingCatalog(
    api: Pick<Api, 'historicalResources'>,
    changed: () => void,
    showIcons: () => boolean
) {
    const empty = (error: string | null = null): TrackingCatalogState => ({
        status: error ? 'error' : 'idle',
        resources: [],
        icons: null,
        activity: {},
        stock: {},
        windowEnd: null,
        lastObservedAt: {},
        nextAfter: null,
        error
    });
    const state = empty();
    let request: AbortController | undefined;
    function clear(error: string | null = null) {
        request?.abort();
        request = undefined;
        Object.assign(state, empty(error));
    }
    return {
        state,
        clear,
        invalidateIcons() {
            request?.abort();
            request = undefined;
            state.icons = null;
        },
        async load(route: Extract<Route, { itemKey: null }>, manual: boolean) {
            if (request || (state.status !== 'idle' && !manual)) return;
            const current = (request = new AbortController());
            state.status = 'loading';
            state.error = null;
            changed();
            try {
                const page = await api.historicalResources(route.gridKey, route, current.signal, showIcons());
                if (!current.signal.aborted)
                    Object.assign(state, {
                        status: 'ready',
                        resources: page.data,
                        icons: page.icons,
                        activity: page.activity ?? {},
                        stock: page.stock ?? {},
                        windowEnd: page.windowEnd ?? null,
                        lastObservedAt: page.lastObservedAt ?? {},
                        nextAfter: page.nextAfter ?? null
                    });
            } catch (caught) {
                if (!current.signal.aborted) clear((caught as ApiFailure).status || 'NETWORK_ERROR');
            } finally {
                if (request === current) request = undefined;
                changed();
            }
        }
    };
}
