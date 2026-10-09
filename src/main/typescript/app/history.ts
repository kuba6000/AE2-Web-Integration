import type { HistoryEntry, CraftingHistory, IconMetadata } from './api-types.js';
import type { Api, ApiFailure } from './api.js';
import type { Route } from './router.js';

export type HistoryState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    reading: boolean;
    entries: HistoryEntry[];
    detail: CraftingHistory | null;
    icons: IconMetadata | null;
    error: string | null;
};
/** Completed history snapshots are read on entry or explicit refresh, not on each polling tick. */
export function createHistory(api: Api, changed: () => void, iconsEnabled: () => boolean) {
    const state: HistoryState = { status: 'idle', reading: false, entries: [], detail: null, icons: null, error: null };
    let route: Route | { view?: undefined; gridKey?: undefined } = {};
    let generation = 0;
    let request: AbortController | undefined;
    let detailIconsStale = false;
    return {
        state,
        invalidateIcons() {
            state.icons = null;
            if (route.view !== 'history') return;
            detailIconsStale = true;
            generation++;
            request?.abort();
            state.reading = false;
        },
        route(next: Route) {
            generation++;
            request?.abort();
            state.reading = false;
            route = next;
            detailIconsStale = false;
            Object.assign(state, {
                status: next.view === 'history' ? 'loading' : 'idle',
                entries: [],
                detail: null,
                icons: null,
                error: null
            });
        },
        async refresh(reloadDetail = false) {
            if (
                route.view !== 'history' ||
                state.reading ||
                (route.entryId !== null && state.detail && !reloadDetail && !detailIconsStale)
            )
                return;
            const current = route;
            const version = generation;
            request = new AbortController();
            state.reading = true;
            changed();
            try {
                if (current.entryId !== null) {
                    const data = await api.historyEntry(
                        current.gridKey,
                        current.entryId,
                        request.signal,
                        iconsEnabled()
                    );
                    if (version !== generation) return;
                    state.detail = data.data;
                    state.icons = data.icons;
                    detailIconsStale = false;
                } else {
                    const data = await api.history(current.gridKey, request.signal, iconsEnabled());
                    if (version !== generation) return;
                    state.entries = data.data;
                    state.icons = data.icons;
                }
                state.status = 'ready';
                state.error = null;
            } catch (caught) {
                const error = caught as ApiFailure;
                if (version !== generation || error.name === 'AbortError') return;
                Object.assign(state, {
                    status: 'error',
                    entries: [],
                    detail: null,
                    icons: null,
                    error: error.status || 'NETWORK_ERROR'
                });
            } finally {
                if (version === generation) {
                    state.reading = false;
                    changed();
                }
            }
        },
        block(error: string | null) {
            generation++;
            request?.abort();
            state.reading = false;
            Object.assign(state, { status: 'error', entries: [], detail: null, icons: null, error });
            changed();
        },
        dispose() {
            generation++;
            request?.abort();
            state.reading = false;
        }
    };
}
