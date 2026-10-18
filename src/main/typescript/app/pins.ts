import type { Api, ApiFailure } from './api.js';
import type { ResourcePin, ResourcePinUpdate, PinPlacement, IconMetadata, ResourceResponse } from './api-types.js';
import type { ResourceHistoryState } from './resource-history.js';
import type { Route } from './router.js';
import type { Notify } from './notifications.js';

export type PinsState = {
    items: ResourcePin[];
    icons: IconMetadata | null;
    histories: Record<string, ResourceHistoryState>;
    status: 'idle' | 'loading' | 'ready' | 'error';
    pending: boolean;
    error: string | null;
};

/** Account-owned pins and bounded history reads share the application's navigation lifetime. */
export function createPins(api: Api, changed: () => void, iconsEnabled: () => boolean, notify: Notify) {
    const state: PinsState = { items: [], icons: null, histories: {}, status: 'idle', pending: false, error: null };
    let route: Route = { view: 'home', gridKey: null };
    let generation = 0;
    let request: AbortController | undefined;
    let reading: Promise<void> | undefined;
    let disposed = false;
    const active = () => !disposed && ['home', 'items', 'tracking'].includes(route.view);
    function cancel() {
        generation++;
        request?.abort();
        reading = undefined;
    }
    function accept(result: ResourceResponse<ResourcePin[]>) {
        state.items = result.data;
        state.icons = result.icons;
        state.status = 'ready';
        state.error = null;
        for (const key of Object.keys(state.histories)) {
            const pin = state.items.find((pin) => pin.pinKey === key);
            if (!pin?.available || pin.homeOrder === null) delete state.histories[key];
        }
        changed();
    }
    async function histories(version: number, signal: AbortSignal, automatic: boolean) {
        if (route.view !== 'home') return;
        const queue = state.items.filter(
            (pin) =>
                pin.available &&
                pin.homeOrder !== null &&
                pin.gridKey &&
                pin.itemKey &&
                (pin.homeConfig.showStock || pin.homeConfig.showFlow || pin.homeConfig.showChart)
        );
        async function worker() {
            while (queue.length && version === generation && !signal.aborted) {
                const pin = queue.shift()!;
                if (!state.items.some((current) => current.pinKey === pin.pinKey && current.available)) continue;
                const previous = state.histories[pin.pinKey];
                if (
                    automatic &&
                    previous?.status === 'ready' &&
                    previous.rangeMillis === pin.homeConfig.rangeMillis &&
                    Date.now() - previous.until < 30000
                )
                    continue;
                const until = Date.now(),
                    from = Math.max(0, until - pin.homeConfig.rangeMillis);
                if (!previous || previous.rangeMillis !== pin.homeConfig.rangeMillis || previous.status !== 'ready')
                    state.histories[pin.pinKey] = {
                        rangeMillis: pin.homeConfig.rangeMillis,
                        from,
                        until,
                        status: 'loading',
                        points: [],
                        baseline: null,
                        icons: null,
                        enabled: false,
                        complete: false,
                        truncated: false,
                        error: null
                    };
                changed();
                try {
                    const result = await api.resourceHistory(pin.gridKey!, pin.itemKey!, signal, from, iconsEnabled());
                    if (version !== generation || signal.aborted) return;
                    if (!state.items.some((current) => current.pinKey === pin.pinKey && current.available)) continue;
                    state.histories[pin.pinKey] = {
                        ...result.data,
                        rangeMillis: pin.homeConfig.rangeMillis,
                        from,
                        until,
                        status: 'ready',
                        icons: result.icons,
                        error: null
                    };
                } catch (caught) {
                    const error = caught as ApiFailure;
                    if (version !== generation || error.name === 'AbortError') return;
                    if (
                        !state.items.some((current) => current.pinKey === pin.pinKey && current.available) ||
                        !state.histories[pin.pinKey]
                    )
                        continue;
                    const status = error.status || 'NETWORK_ERROR';
                    if (['NO_PERMISSIONS', 'GRID_NOT_FOUND', 'UNAUTHORIZED'].includes(status)) {
                        state.items = state.items.map((value) => {
                            if (value.gridKey !== pin.gridKey) return value;
                            delete state.histories[value.pinKey];
                            return {
                                ...value,
                                available: false,
                                gridKey: null,
                                itemKey: null,
                                gridName: null,
                                resource: null
                            };
                        });
                    } else Object.assign(state.histories[pin.pinKey], { status: 'error', error: status });
                }
                changed();
            }
        }
        await Promise.all([worker(), worker()]);
    }
    async function refresh(automatic = false) {
        if (!active() || state.pending) return;
        if (reading) return reading;
        const version = generation;
        request = new AbortController();
        const signal = request.signal;
        if (state.status !== 'ready') state.status = 'loading';
        changed();
        reading = (async () => {
            try {
                const result = await api.resourcePins(signal, iconsEnabled());
                if (version !== generation || signal.aborted) return;
                accept(result);
                await histories(version, signal, automatic);
            } catch (caught) {
                const error = caught as ApiFailure;
                if (version !== generation || error.name === 'AbortError') return;
                block(error.status || 'NETWORK_ERROR');
            } finally {
                if (version === generation) {
                    reading = undefined;
                    changed();
                }
            }
        })();
        return reading;
    }
    function block(error: string | null) {
        cancel();
        Object.assign(state, { items: [], icons: null, histories: {}, status: 'error', error });
        changed();
    }
    async function mutate(action: () => Promise<ResourceResponse<ResourcePin[]>>) {
        if (disposed || state.pending || state.status !== 'ready') return;
        cancel();
        const version = generation;
        state.pending = true;
        changed();
        try {
            const result = await action();
            if (disposed || version !== generation) return;
            accept(result);
            request = new AbortController();
            await histories(version, request.signal, true);
        } catch (caught) {
            if (disposed || version !== generation) return;
            const error = caught as ApiFailure;
            state.error = error.status || 'NETWORK_ERROR';
            if (
                [
                    'UNAUTHORIZED',
                    'NO_PERMISSIONS',
                    'GRID_NOT_FOUND',
                    'NETWORK_ERROR',
                    'TIMEOUT',
                    'INVALID_RESPONSE',
                    'INTERNAL_ERROR'
                ].includes(state.error)
            )
                block(state.error);
            notify({ kind: 'error', message: state.error === 'ITEM_NOT_FOUND' ? 'pinRefreshNeeded' : state.error });
        } finally {
            state.pending = false;
            if (!disposed) {
                changed();
                if (version !== generation) await refresh();
            }
        }
    }
    return {
        state,
        refresh,
        block,
        add: (grid: string, item: string, placement: PinPlacement) =>
            mutate(() => api.addResourcePin(grid, item, placement, iconsEnabled())),
        update: (pin: string, update: ResourcePinUpdate) =>
            mutate(() => api.updateResourcePin(pin, update, iconsEnabled())),
        remove: (pin: string) => mutate(() => api.deleteResourcePin(pin, iconsEnabled())),
        route(next: Route) {
            cancel();
            route = next;
            state.histories = {};
            state.items = [];
            state.icons = null;
            state.status = 'idle';
            state.error = null;
        },
        invalidateIcons() {
            cancel();
            state.icons = null;
            for (const history of Object.values(state.histories)) history.icons = null;
        },
        dispose() {
            disposed = true;
            cancel();
            state.items = [];
            state.histories = {};
        }
    };
}
