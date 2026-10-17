import type { ResourceHistoryPoint, IconMetadata } from './api-types.js';
import type { Api, ApiFailure } from './api.js';

export type ResourceHistoryState = {
    rangeMillis: number;
    from: number;
    until: number;
    status: 'idle' | 'loading' | 'ready' | 'error';
    points: ResourceHistoryPoint[];
    baseline: ResourceHistoryPoint | null;
    icons: IconMetadata | null;
    enabled: boolean;
    complete: boolean;
    truncated: boolean;
    error: string | null;
};

/** Selection-scoped history reads share the application refresh lifecycle; themes only render data. */
export function createResourceHistory(
    api: Pick<Api, 'resourceHistory'>,
    changed: () => void,
    iconsEnabled: () => boolean
) {
    const state: ResourceHistoryState = {
        rangeMillis: 900000,
        from: 0,
        until: 0,
        status: 'idle',
        points: [],
        baseline: null,
        icons: null,
        enabled: false,
        complete: true,
        truncated: false,
        error: null
    };
    let selected: { gridKey: string; itemKey: string } | null = null;
    let request: AbortController | undefined;
    let generation = 0;
    let reading = false;
    async function refresh(automatic = false) {
        if (!selected || reading) return;
        if (automatic && state.status === 'ready' && Date.now() - state.until < 30000) return;
        const current = selected;
        const version = generation;
        request = new AbortController();
        reading = true;
        if (state.status !== 'ready') state.status = 'loading';
        changed();
        try {
            const until = Date.now();
            const from = Math.max(0, until - state.rangeMillis);
            const history = await api.resourceHistory(
                current.gridKey,
                current.itemKey,
                request.signal,
                from,
                iconsEnabled()
            );
            if (version !== generation) return;
            Object.assign(state, history.data, { icons: history.icons, status: 'ready', error: null, from, until });
        } catch (caught) {
            const error = caught as ApiFailure;
            if (version !== generation || error.name === 'AbortError') return;
            Object.assign(state, {
                status: 'error',
                points: [],
                baseline: null,
                icons: null,
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
            icons: null,
            enabled: false,
            complete: true,
            truncated: false,
            error: null
        });
    }
    return {
        state,
        invalidateIcons() {
            state.icons = null;
            generation++;
            request?.abort();
            reading = false;
        },
        async select(gridKey: string | null, itemKey: string | null) {
            clear();
            if (gridKey && itemKey) {
                selected = { gridKey, itemKey };
                await refresh();
            } else changed();
        },
        refresh,
        async range(millis: number) {
            if (!Number.isSafeInteger(millis) || millis <= 0 || state.rangeMillis === millis) return;
            const current = selected;
            clear();
            state.rangeMillis = millis;
            selected = current;
            await refresh();
        },
        clear,
        dispose: clear
    };
}
