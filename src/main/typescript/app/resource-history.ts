import type { ResourceHistoryPoint } from './api-types.js';
import type { Api, ApiFailure } from './api.js';

export type ResourceHistoryState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    points: ResourceHistoryPoint[];
    baseline: ResourceHistoryPoint | null;
    enabled: boolean;
    complete: boolean;
    truncated: boolean;
    error: string | null;
};

/** Selection-scoped history reads share the application refresh lifecycle; themes only render data. */
export function createResourceHistory(api: Pick<Api, 'resourceHistory'>, changed: () => void) {
    const state: ResourceHistoryState = {
        status: 'idle',
        points: [],
        baseline: null,
        enabled: false,
        complete: true,
        truncated: false,
        error: null
    };
    let selected: { gridKey: string; itemKey: string } | null = null;
    let request: AbortController | undefined;
    let generation = 0;
    let reading = false;
    async function refresh() {
        if (!selected || reading) return;
        const current = selected;
        const version = generation;
        request = new AbortController();
        reading = true;
        if (state.status !== 'ready') state.status = 'loading';
        changed();
        try {
            const history = await api.resourceHistory(current.gridKey, current.itemKey, request.signal);
            if (version !== generation) return;
            Object.assign(state, history, { status: 'ready', error: null });
        } catch (caught) {
            const error = caught as ApiFailure;
            if (version !== generation || error.name === 'AbortError') return;
            Object.assign(state, {
                status: 'error',
                points: [],
                baseline: null,
                error: error.status || 'NETWORK_ERROR'
            });
        } finally {
            if (version === generation) {
                reading = false;
                changed();
            }
        }
    }
    function clear() {
        generation++;
        request?.abort();
        reading = false;
        selected = null;
        Object.assign(state, {
            status: 'idle',
            points: [],
            baseline: null,
            enabled: false,
            complete: true,
            truncated: false,
            error: null
        });
    }
    return {
        state,
        async select(gridKey: string | null, itemKey: string | null) {
            clear();
            if (gridKey && itemKey) {
                selected = { gridKey, itemKey };
                await refresh();
            } else changed();
        },
        refresh,
        clear,
        dispose: clear
    };
}
