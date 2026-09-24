/**
 * @typedef {{route: import('./router.mjs').Route, grids: import('./api-types.mjs').Grid[], gridStatus: 'loading' | 'ready' | 'error', gridError: string | null, items: import('./api-types.mjs').Item[], itemStatus: 'idle' | 'loading' | 'ready' | 'error', itemError: string | null, refreshing: boolean, updatedAt: number | null, search: string, selected: import('./api-types.mjs').Item | null, preferences: import('./preferences.mjs').Preferences}} TerminalData
 * @typedef {TerminalData & {crafting: import('./crafting.mjs').CraftingState, cpus: import('./cpus.mjs').CpuState, history: import('./history.mjs').HistoryState, settings: import('./settings.mjs').SettingsState}} TerminalState
 */
import { createCrafting } from './crafting.mjs';
import { createCpuMonitor } from './cpus.mjs';
import { createHistory } from './history.mjs';
import { createGridSettings } from './settings.mjs';

/**
 * Shared state/actions for terminal renderers; a theme does not own requests or refresh timers.
 * @param {import('./api.mjs').Api} api
 * @param {ReturnType<typeof import('./preferences.mjs').createPreferences>} preferences
 */
export function createTerminal(api, preferences) {
    /** @type {Set<(state: TerminalState) => void>} */
    const listeners = new Set();
    /** @type {AbortController | undefined} */
    let gridRequest;
    /** @type {AbortController | undefined} */
    let itemRequest;
    let serial = 0;
    let disposed = false;
    /** @type {ReturnType<typeof setTimeout> | undefined} */
    let timer;
    /** @type {Promise<void> | null} */
    let refreshingGrids = null;
    const notify = () => {
        if (!disposed) for (const listener of listeners) listener(state);
    };
    const crafting = createCrafting(api, () => {
        notify();
        schedule();
    });
    const cpus = createCpuMonitor(api, () => {
        notify();
        schedule();
    });
    const history = createHistory(api, () => {
        notify();
        schedule();
    });
    const settings = createGridSettings(api, () => {
        notify();
        schedule();
    });

    /** @type {TerminalState} */
    const state = {
        route: { view: 'home', gridKey: null },
        grids: [],
        gridStatus: 'loading',
        gridError: null,
        items: [],
        itemStatus: 'idle',
        itemError: null,
        refreshing: false,
        updatedAt: null,
        search: '',
        selected: null,
        preferences: preferences.values,
        crafting: crafting.state,
        cpus: cpus.state,
        history: history.state,
        settings: settings.state
    };

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
        if (!state.grids.some((grid) => grid.key === key)) {
            invalidateItems();
            state.itemStatus = 'error';
            state.itemError = 'GRID_NOT_FOUND';
            notify();
            return;
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
            state.selected = state.selected
                ? items.find((item) => item.itemKey && item.itemKey === state.selected?.itemKey) || null
                : null;
            state.itemStatus = 'ready';
            state.updatedAt = Date.now();
        } catch (caught) {
            const error = /** @type {import('./api.mjs').ApiFailure} */ (caught);
            if (disposed || version !== serial || error.name === 'AbortError') return;
            state.items = [];
            state.selected = null;
            state.updatedAt = null;
            state.itemStatus = 'error';
            state.itemError = error.status || 'NETWORK_ERROR';
        } finally {
            if (!disposed && version === serial) {
                state.refreshing = false;
                notify();
            }
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
        if (!state.grids.some((grid) => grid.key === state.route.gridKey)) crafting.block('GRID_NOT_FOUND');
        else return crafting.refresh();
    }
    function loadCpus() {
        if (state.route.view !== 'cpus' || state.gridStatus !== 'ready') return;
        if (!state.grids.some((grid) => grid.key === state.route.gridKey)) cpus.block('GRID_NOT_FOUND');
        else return cpus.refresh();
    }
    function loadHistory(reloadDetail = false) {
        if (state.route.view !== 'history' || state.gridStatus !== 'ready') return;
        if (!state.grids.some((grid) => grid.key === state.route.gridKey)) history.block('GRID_NOT_FOUND');
        else return history.refresh(reloadDetail);
    }
    function loadSettings() {
        if (state.route.view !== 'settings') return;
        if (state.gridStatus === 'error') {
            settings.block(state.gridError);
            return;
        }
        if (state.gridStatus !== 'ready') return;
        const grid = state.grids.find((grid) => grid.key === state.route.gridKey);
        if (!grid) settings.block('GRID_NOT_FOUND');
        else return settings.refresh(grid.accessSources);
    }
    async function refresh({ reloadDetail = false } = {}) {
        if (disposed) return;
        if (refreshingGrids) return reloadDetail ? refreshingGrids.then(() => loadHistory(true)) : refreshingGrids;
        clearTimeout(timer);
        gridRequest = new AbortController();
        refreshingGrids = (async () => {
            try {
                state.grids = await api.grids(gridRequest.signal);
                if (disposed) return;
                state.gridStatus = 'ready';
                state.gridError = null;
                notify();
                await loadItems();
                await loadCrafting();
                await loadCpus();
                await loadHistory(reloadDetail);
                await loadSettings();
            } catch (caught) {
                const error = /** @type {import('./api.mjs').ApiFailure} */ (caught);
                if (disposed || error.name === 'AbortError') return;
                state.gridStatus = 'error';
                state.gridError = error.status || 'NETWORK_ERROR';
                invalidateItems();
                crafting.block(state.gridError);
                cpus.block(state.gridError);
                history.block(state.gridError);
                settings.block(state.gridError);
                notify();
            } finally {
                refreshingGrids = null;
                schedule();
            }
        })();
        return refreshingGrids;
    }
    return {
        state,
        crafting,
        cpus,
        history,
        settings,
        /** @param {(state: TerminalState) => void} listener */
        subscribe(listener) {
            listeners.add(listener);
            listener(state);
            return () => listeners.delete(listener);
        },
        refresh,
        /** @param {import('./router.mjs').Route} route */
        route(route) {
            invalidateItems();
            state.route = route;
            crafting.route(route);
            cpus.route(route);
            history.route(route);
            settings.route(route);
            notify();
            loadItems();
            loadCrafting();
            loadCpus();
            loadHistory();
            loadSettings();
            schedule();
        },
        /** @param {string} value */
        search(value) {
            state.search = value;
            notify();
        },
        /** @param {import('./api-types.mjs').Item | null} item */
        select(item) {
            state.selected = item;
            notify();
        },
        /** @template {keyof import('./preferences.mjs').Preferences} K @param {K} name @param {import('./preferences.mjs').Preferences[K]} value */
        preference(name, value) {
            preferences.set(name, value);
            notify();
            if (name === 'autoRefresh') schedule();
        },
        dispose() {
            disposed = true;
            clearTimeout(timer);
            gridRequest?.abort();
            itemRequest?.abort();
            crafting.dispose();
            cpus.dispose();
            history.dispose();
            settings.dispose();
            listeners.clear();
        }
    };
}
