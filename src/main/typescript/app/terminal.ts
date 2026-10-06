import type { Route } from './router.js';
import type { Grid, Item, IconMetadata, GridSettings } from './api-types.js';
import type { createIconLoader } from './icons.js';
import type { Preferences, createPreferences } from './preferences.js';
import type { CraftingState } from './crafting.js';
import { createHomeMonitor, type HomeState } from './home.js';
import type { CpuState } from './cpus.js';
import type { HistoryState } from './history.js';
import type { SettingsState } from './settings.js';
import type { Api, ApiFailure } from './api.js';
import { createCrafting } from './crafting.js';
import { createCpuMonitor } from './cpus.js';
import { createHistory } from './history.js';
import { createGridSettings } from './settings.js';

export type TerminalData = {
    route: Route;
    grids: Grid[];
    gridStatus: 'loading' | 'ready' | 'error';
    gridError: string | null;
    items: Item[];
    itemIcons: IconMetadata | null;
    iconPack: { available: boolean | null; status: 'loading' | 'ready' | 'error' };
    itemStatus: 'idle' | 'loading' | 'ready' | 'error';
    itemError: string | null;
    refreshing: boolean;
    updatedAt: number | null;
    search: string;
    selected: Item | null;
    preferences: Preferences;
};
export type TerminalState = TerminalData & {
    selectedGridKey: string | null;
    crafting: CraftingState;
    cpus: CpuState;
    home: HomeState;
    history: HistoryState;
    settings: SettingsState;
};

/** Shared state/actions for terminal renderers; a theme does not own requests or refresh timers. */
export function createTerminal(
    api: Api,
    preferences: ReturnType<typeof createPreferences>,
    icons: ReturnType<typeof createIconLoader>
) {
    let iconsEnabled = false;
    const listeners = new Set<(state: TerminalState) => void>();
    let gridRequest: AbortController | undefined;
    let itemRequest: AbortController | undefined;
    let iconRequest: AbortController | undefined;
    let iconPackId: string | null = null;
    let serial = 0;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let refreshingGrids: Promise<void> | null = null;
    const discoveryUpdates = new Map<string, GridSettings>();
    const notify = () => {
        if (!disposed) for (const listener of listeners) listener(state);
    };
    const crafting = createCrafting(api, () => {
        notify();
        schedule();
    });
    const cpus = createCpuMonitor(
        api,
        () => {
            notify();
            schedule();
        },
        () => iconsEnabled && state.iconPack.available === true
    );
    const home = createHomeMonitor(api, notify, () => iconsEnabled && state.iconPack.available === true);
    const history = createHistory(
        api,
        () => {
            notify();
            schedule();
        },
        () => iconsEnabled && state.iconPack.available === true
    );
    const settings = createGridSettings(
        api,
        () => {
            notify();
            schedule();
        },
        (key, saved) => {
            if (refreshingGrids) discoveryUpdates.set(key, saved);
            for (const grid of state.grids)
                if (grid.key === key) {
                    grid.name = saved.name;
                    grid.isTrackingEnabled = saved.isTracked;
                }
            for (const network of state.home.networks)
                if (network.grid.key === key) {
                    network.grid.name = saved.name;
                    network.grid.isTrackingEnabled = saved.isTracked;
                }
            notify();
        }
    );

    const state: TerminalState = {
        route: { view: 'home', gridKey: null },
        selectedGridKey: null,
        grids: [],
        gridStatus: 'loading',
        gridError: null,
        items: [],
        itemIcons: null,
        iconPack: { available: null, status: 'loading' },
        itemStatus: 'idle',
        itemError: null,
        refreshing: false,
        updatedAt: null,
        search: '',
        selected: null,
        preferences: preferences.values,
        crafting: crafting.state,
        cpus: cpus.state,
        home: home.state,
        history: history.state,
        settings: settings.state
    };

    function invalidateItems() {
        serial++;
        itemRequest?.abort();
        state.items = [];
        state.itemIcons = null;
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
            const result = await api.items(key, request.signal, iconsEnabled && state.iconPack.available === true);
            if (disposed || version !== serial) return;
            const items = result.data;
            state.itemIcons = result.icons;
            state.items = items;
            state.selected = state.selected
                ? items.find((item) => item.itemKey && item.itemKey === state.selected?.itemKey) || null
                : null;
            state.itemStatus = 'ready';
            state.updatedAt = Date.now();
        } catch (caught) {
            const error = caught as ApiFailure;
            if (disposed || version !== serial || error.name === 'AbortError') return;
            state.items = [];
            state.itemIcons = null;
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
    async function loadIconPack() {
        iconRequest?.abort();
        const request = new AbortController();
        iconRequest = request;
        try {
            const pack = await api.iconPack(request.signal);
            if (disposed || request !== iconRequest) return;
            if (typeof pack?.available !== 'boolean') throw new Error('Invalid icon availability');
            const changed =
                (state.iconPack.available === true) !== pack.available ||
                (pack.available && iconPackId !== pack.packId);
            state.iconPack = { available: pack.available, status: 'ready' };
            iconPackId = pack.packId;
            icons.enabled(iconsEnabled && pack.available);
            if (changed) {
                state.itemIcons = null;
                cpus.invalidateIcons();
                history.invalidateIcons();
                home.invalidateIcons();
            }
            notify();
            if (changed) {
                void loadItems();
                void loadCpus();
                void loadHome();
                if (state.route.view === 'history' && state.route.entryId === null) void loadHistory();
            }
        } catch (caught) {
            if (disposed || request !== iconRequest || (caught as ApiFailure).name === 'AbortError') return;
            // A transient discovery failure must not replace the last confirmed capability.
            state.iconPack = { ...state.iconPack, status: 'error' };
            notify();
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
    function loadHome() {
        if (state.route.view === 'home' && state.gridStatus === 'ready') return home.refresh(state.grids);
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
        discoveryUpdates.clear();
        gridRequest = new AbortController();
        void loadIconPack();
        refreshingGrids = (async () => {
            try {
                const grids = await api.grids(gridRequest.signal);
                if (disposed) return;
                // Grid-scoped settings results outrank discovery that was already in flight.
                for (const grid of grids) {
                    const saved = discoveryUpdates.get(grid.key);
                    if (saved) {
                        grid.name = saved.name;
                        grid.isTrackingEnabled = saved.isTracked;
                    }
                }
                state.grids = grids;
                if (!state.grids.some((grid) => grid.key === state.selectedGridKey)) state.selectedGridKey = null;
                state.gridStatus = 'ready';
                state.gridError = null;
                notify();
                await loadHome();
                await loadItems();
                await loadCrafting();
                await loadCpus();
                await loadHistory(reloadDetail);
                await loadSettings();
            } catch (caught) {
                const error = caught as ApiFailure;
                if (disposed || error.name === 'AbortError') return;
                state.gridStatus = 'error';
                state.grids = [];
                state.selectedGridKey = null;
                home.clear();
                state.gridError = error.status || 'NETWORK_ERROR';
                invalidateItems();
                crafting.block(state.gridError);
                cpus.block(state.gridError);
                history.block(state.gridError);
                settings.block(state.gridError);
                notify();
            } finally {
                discoveryUpdates.clear();
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
        icons,
        displayIcons(enabled: boolean) {
            if (iconsEnabled === enabled) return;
            iconsEnabled = enabled;
            state.itemIcons = null;
            cpus.invalidateIcons();
            history.invalidateIcons();
            home.invalidateIcons();
            icons.enabled(enabled && state.iconPack.available === true);
            void loadItems();
            void loadCpus();
            void loadHome();
            if (state.route.view === 'history' && state.route.entryId === null) void loadHistory();
            notify();
        },
        subscribe(listener: (state: TerminalState) => void) {
            listeners.add(listener);
            listener(state);
            return () => listeners.delete(listener);
        },
        refresh,
        route(route: Route) {
            invalidateItems();
            state.route = route;
            if (route.gridKey) state.selectedGridKey = route.gridKey;
            crafting.route(route);
            cpus.route(route);
            history.route(route);
            settings.route(route);
            home.route(route.view === 'home');
            notify();
            loadItems();
            loadCrafting();
            loadCpus();
            loadHistory();
            loadSettings();
            void loadHome();
            schedule();
        },
        search(value: string) {
            state.search = value;
            notify();
        },
        select(item: Item | null) {
            state.selected = item;
            notify();
        },
        preference<K extends keyof Preferences>(name: K, value: Preferences[K]) {
            preferences.set(name, value);
            notify();
            if (name === 'autoRefresh') schedule();
        },
        dispose() {
            disposed = true;
            clearTimeout(timer);
            gridRequest?.abort();
            itemRequest?.abort();
            iconRequest?.abort();
            crafting.dispose();
            cpus.dispose();
            history.dispose();
            settings.dispose();
            home.dispose();
            icons.dispose();
            listeners.clear();
        }
    };
}
