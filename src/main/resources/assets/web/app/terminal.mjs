/** Shared state/actions for terminal renderers; a theme does not own requests or refresh timers. */
export function createTerminal(api, preferences) {
    const state = {
        route: { view: 'home', gridKey: null }, grids: [], gridStatus: 'loading', gridError: null,
        items: [], itemStatus: 'idle', itemError: null, refreshing: false, updatedAt: null,
        search: '', selected: null, preferences: preferences.values
    };
    const listeners = new Set();
    let gridRequest;
    let itemRequest;
    let serial = 0;
    let disposed = false;
    let timer;
    let refreshingGrids = null;
    const notify = () => { if (!disposed) for (const listener of listeners) listener(state); };

    function invalidateItems() {
        serial++;
        itemRequest?.abort();
        state.items = [];
        state.selected = null;
        state.itemStatus = 'idle';
        state.itemError = null;
        state.updatedAt = null;
        state.refreshing = false;
    }
    async function loadItems() {
        const key = state.route.gridKey;
        if (disposed || !key || state.gridStatus !== 'ready') return;
        if (!state.grids.some(grid => grid.key === key)) {
            invalidateItems(); state.itemStatus = 'error'; state.itemError = 'GRID_NOT_FOUND'; notify(); return;
        }
        itemRequest?.abort();
        const request = new AbortController();
        itemRequest = request;
        const version = ++serial;
        state.refreshing = true;
        if (state.itemStatus !== 'ready') state.itemStatus = 'loading';
        state.itemError = null;
        notify();
        try {
            const items = await api.items(key, request.signal);
            if (disposed || version !== serial) return;
            state.items = items;
            state.selected = state.selected ? items.find(item => item.itemKey && item.itemKey === state.selected.itemKey) || null : null;
            state.itemStatus = 'ready';
            state.updatedAt = Date.now();
        } catch (error) {
            if (disposed || version !== serial || error.name === 'AbortError') return;
            state.items = []; state.selected = null; state.updatedAt = null;
            state.itemStatus = 'error'; state.itemError = error.status || 'NETWORK_ERROR';
        } finally {
            if (!disposed && version === serial) { state.refreshing = false; notify(); }
        }
    }
    function schedule() {
        clearTimeout(timer);
        if (!disposed && state.preferences.autoRefresh) timer = setTimeout(refresh, 5000);
    }
    async function refresh() {
        if (disposed) return;
        if (refreshingGrids) return refreshingGrids;
        clearTimeout(timer);
        gridRequest = new AbortController();
        refreshingGrids = (async () => {
            try {
                state.grids = await api.grids(gridRequest.signal);
                if (disposed) return;
                state.gridStatus = 'ready'; state.gridError = null;
                notify();
                await loadItems();
            } catch (error) {
                if (disposed || error.name === 'AbortError') return;
                state.gridStatus = 'error'; state.gridError = error.status || 'NETWORK_ERROR';
                invalidateItems(); notify();
            } finally { refreshingGrids = null; schedule(); }
        })();
        return refreshingGrids;
    }
    return {
        state,
        subscribe(listener) { listeners.add(listener); listener(state); return () => listeners.delete(listener); },
        refresh,
        route(route) { invalidateItems(); state.route = route; notify(); loadItems(); },
        search(value) { state.search = value; notify(); },
        select(item) { state.selected = item; notify(); },
        preference(name, value) { preferences.set(name, value); notify(); if (name === 'autoRefresh') schedule(); },
        dispose() { disposed = true; clearTimeout(timer); gridRequest?.abort(); itemRequest?.abort(); listeners.clear(); }
    };
}
