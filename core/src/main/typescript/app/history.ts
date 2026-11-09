import type { HistoryEntry, CraftingHistory, IconMetadata } from './api-types.js';
import type { Api, ApiFailure } from './api.js';
import type { Route } from './router.js';

export type HistoryState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    reading: boolean;
    nextBefore: string | null;
    hasPrevious: boolean;
    entries: HistoryEntry[];
    detail: CraftingHistory | null;
    icons: IconMetadata | null;
    error: string | null;
};
/** Completed history snapshots are read on entry or explicit refresh, not on each polling tick. */
export function createHistory(api: Api, changed: () => void, iconsEnabled: () => boolean) {
    const state: HistoryState = {
        status: 'idle',
        reading: false,
        nextBefore: null,
        hasPrevious: false,
        entries: [],
        detail: null,
        icons: null,
        error: null
    };
    let route: Route | { view?: undefined; gridKey?: undefined } = {};
    let generation = 0;
    let request: AbortController | undefined;
    let detailIconsStale = false;
    let before: string | null = null;
    const previous: (string | null)[] = [];
    const history = {
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
            before = null;
            previous.length = 0;
            detailIconsStale = false;
            Object.assign(state, {
                status: next.view === 'history' ? 'loading' : 'idle',
                entries: [],
                nextBefore: null,
                hasPrevious: false,
                detail: null,
                icons: null,
                error: null
            });
        },
        async next() {
            if (state.reading || state.nextBefore === null) return;
            const target = state.nextBefore;
            await history.refresh(false, target, 1);
        },
        async previous() {
            if (state.reading || !previous.length) return;
            await history.refresh(false, previous[previous.length - 1], -1);
        },
        async refresh(reloadDetail = false, target = before, direction = 0) {
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
                    const data = await api.history(current.gridKey, request.signal, iconsEnabled(), target);
                    if (version !== generation) return;
                    if (direction > 0) previous.push(before);
                    if (direction < 0) previous.pop();
                    before = target;
                    state.hasPrevious = previous.length > 0;
                    state.nextBefore = data.nextBefore ?? null;
                    state.entries = data.data;
                    state.icons = data.icons;
                }
                state.status = 'ready';
                state.error = null;
            } catch (caught) {
                const error = caught as ApiFailure;
                if (version !== generation || error.name === 'AbortError') return;
                if (
                    direction !== 0 &&
                    !['NO_PERMISSIONS', 'GRID_NOT_FOUND', 'UNAUTHORIZED'].includes(error.status || '')
                ) {
                    state.error = error.status || 'NETWORK_ERROR';
                    return;
                }
                Object.assign(state, {
                    status: 'error',
                    nextBefore: null,
                    hasPrevious: false,
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
            Object.assign(state, {
                status: 'error',
                nextBefore: null,
                hasPrevious: false,
                entries: [],
                detail: null,
                icons: null,
                error
            });
            changed();
        },
        dispose() {
            generation++;
            request?.abort();
            state.reading = false;
        }
    };
    return history;
}
