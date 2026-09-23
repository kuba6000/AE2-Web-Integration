import { createCrafting } from './crafting.mjs';

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
    const crafting = createCrafting(api, () => { notify(); schedule(); });
    state.crafting = crafting.state;

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
        if (disposed || state.route.view !== 'items' || !key || state.gridStatus !== 'ready') return;
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
        if (disposed) return;
        if (crafting.pending) timer = setTimeout(() => crafting.refresh(), 1000);
        else if (state.preferences.autoRefresh) timer = setTimeout(refresh, 5000);
    }
    function loadCrafting() {
        if (state.route.view !== 'plan' || state.gridStatus !== 'ready') return;
        if (!state.grids.some(grid => grid.key === state.route.gridKey)) crafting.block('GRID_NOT_FOUND');
        else return crafting.refresh();
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
                await loadCrafting();
            } catch (error) {
                if (disposed || error.name === 'AbortError') return;
                state.gridStatus = 'error'; state.gridError = error.status || 'NETWORK_ERROR';
                invalidateItems(); crafting.block(state.gridError); notify();
            } finally { refreshingGrids = null; schedule(); }
        })();
        return refreshingGrids;
    }
    return {
        state,
        crafting,
        subscribe(listener) { listeners.add(listener); listener(state); return () => listeners.delete(listener); },
        refresh,
        route(route) { invalidateItems(); state.route = route; crafting.route(route); notify(); loadItems(); loadCrafting(); schedule(); },
        search(value) { state.search = value; notify(); },
        select(item) { state.selected = item; notify(); },
        preference(name, value) { preferences.set(name, value); notify(); if (name === 'autoRefresh') schedule(); },
        dispose() { disposed = true; clearTimeout(timer); gridRequest?.abort(); itemRequest?.abort(); crafting.dispose(); listeners.clear(); }
    };
}
