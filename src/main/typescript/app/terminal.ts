import type { Route } from './router.js';
import type {
    Grid,
    StoredResource,
    IconMetadata,
    GridSettings,
    ApplicationContext,
    TerminalView,
    TerminalDelta
} from './api-types.js';
import type { createIconLoader } from './icons.js';
import type { Preferences, createPreferences } from './preferences.js';
import type { CraftingState } from './crafting.js';
import type { CpuState } from './cpus.js';
import type { HistoryState } from './history.js';
import type { SettingsState } from './settings.js';
import type { Api, ApiFailure } from './api.js';
import { createCrafting } from './crafting.js';
import { createCpuMonitor } from './cpus.js';
import { createHistory } from './history.js';
import { createResourceHistory, type ResourceHistoryState } from './resource-history.js';
import { createGridSettings } from './settings.js';
import { createNotifications } from './notifications.js';

export type TerminalData = {
    capabilities: ApplicationContext['capabilities'];
    route: Route;
    grids: Grid[];
    gridStatus: 'loading' | 'ready' | 'error';
    gridError: string | null;
    items: StoredResource[];
    itemIcons: IconMetadata | null;
    iconPack: { available: boolean | null; status: 'loading' | 'ready' | 'error' };
    itemStatus: 'idle' | 'loading' | 'ready' | 'error';
    itemError: string | null;
    refreshing: boolean;
    updatedAt: number | null;
    search: string;
    selected: StoredResource | null;
    preferences: Preferences;
};
export type TerminalState = TerminalData & {
    readonly refreshingView: boolean;
    selectedGridKey: string | null;
    crafting: CraftingState;
    cpus: CpuState;
    history: HistoryState;
    resourceHistory: ResourceHistoryState;
    settings: SettingsState;
};

/** Shared state/actions for terminal renderers; a theme does not own requests or refresh timers. */
export function createTerminal(
    api: Api,
    preferences: ReturnType<typeof createPreferences>,
    icons: ReturnType<typeof createIconLoader>,
    capabilities: ApplicationContext['capabilities']
) {
    const notifications = createNotifications();
    let iconsEnabled = false;
    const listeners = new Set<(state: TerminalState) => void>();
    let gridRequest: AbortController | undefined;
    let itemRequest: AbortController | undefined;
    let iconRequest: AbortController | undefined;
    let iconPackId: string | null = null;
    let liveView: TerminalView | undefined;
    let subscription: string | undefined;
    let closeStream: (() => void) | undefined;
    let reconnect: ReturnType<typeof setTimeout> | undefined;
    let serial = 0;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let refreshingGrids: Promise<void> | null = null;
    const discoveryUpdates = new Map<string, GridSettings>();
    const notify = () => {
        if (!disposed) for (const listener of listeners) listener(state);
    };
    const crafting = createCrafting(
        api,
        () => {
            if (
                state.route.view === 'items' &&
                ['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(crafting.state.error ?? '')
            ) {
                invalidateItems();
                state.itemStatus = 'error';
                state.itemError = crafting.state.error ?? null;
            }
            notify();
            schedule();
        },
        () => iconsEnabled && state.iconPack.available === true,
        preferences,
        notifications.publish
    );
    const cpus = createCpuMonitor(
        api,
        () => {
            notify();
            schedule();
        },
        () => iconsEnabled && state.iconPack.available === true,
        notifications.publish
    );
    const history = createHistory(
        api,
        () => {
            notify();
            schedule();
        },
        () => iconsEnabled && state.iconPack.available === true
    );
    const resourceHistory = createResourceHistory(api, notify);
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
            notify();
        },
        notifications.publish
    );

    const state: TerminalState = {
        get refreshingView() {
            return refreshingGrids !== null;
        },
        capabilities,
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
        history: history.state,
        resourceHistory: resourceHistory.state,
        settings: settings.state
    };

    function restoreSelectedGrid() {
        const key = state.route.gridKey || state.selectedGridKey || preferences.values.selectedGridKey;
        state.selectedGridKey = state.grids.some((grid) => grid.key === key) ? key : null;
        if (state.selectedGridKey && state.selectedGridKey !== preferences.values.selectedGridKey)
            preferences.set('selectedGridKey', state.selectedGridKey);
    }

    function stopStream() {
        closeStream?.();
        closeStream = undefined;
        clearTimeout(reconnect);
        liveView = undefined;
        subscription = undefined;
    }
    function startStream(key: string, version: number) {
        if (!liveView || !subscription || !state.preferences.autoRefresh || disposed || version !== serial) return;
        closeStream?.();
        closeStream = api.itemEvents(key, subscription, iconsEnabled && state.iconPack.available === true, {
            ready() {
                if (disposed || version !== serial || !liveView) return;
                state.itemError = null;
                notify();
            },
            delta(delta: TerminalDelta) {
                if (disposed || version !== serial || !liveView) return;
                if (delta.view.generation !== liveView.generation) {
                    stopStream();
                    void loadItems();
                    return;
                }
                if (BigInt(delta.view.revision) <= BigInt(liveView.revision)) return;
                const previous = state.itemIcons;
                if (previous && delta.icons && previous.packId !== delta.icons.packId) {
                    stopStream();
                    void loadItems();
                    return;
                }
                const pages = [...(previous?.pages ?? [])];
                const indices = (delta.icons?.pages ?? []).map((page) => {
                    let index = pages.findIndex((existing) => existing.digest === page.digest);
                    if (index < 0) {
                        index = pages.length;
                        pages.push(page);
                    }
                    return index;
                });
                const remap = (item: StoredResource): StoredResource => ({
                    ...item,
                    icon: item.icon && delta.icons ? { ...item.icon, page: indices[item.icon.page] } : null
                });
                const keyed = new Map(state.items.filter((item) => item.itemKey).map((item) => [item.itemKey!, item]));
                for (const removed of delta.removed) keyed.delete(removed);
                for (const item of delta.definitions) if (item.itemKey) keyed.set(item.itemKey, remap(item));
                for (const update of delta.updates) {
                    const item = keyed.get(update.itemKey);
                    if (!item) {
                        stopStream();
                        void loadItems();
                        return;
                    }
                    keyed.set(update.itemKey, { ...item, quantity: update.quantity, craftable: update.craftable });
                }
                const unkeyed = delta.unkeyed?.map(remap) ?? state.items.filter((item) => !item.itemKey);
                state.items = [...keyed.values(), ...unkeyed];
                if (delta.icons) state.itemIcons = { ...delta.icons, pages };
                state.selected = state.selected?.itemKey ? (keyed.get(state.selected.itemKey) ?? null) : null;
                if (!state.selected) resourceHistory.clear();
                state.updatedAt = Date.now();
                state.itemError = null;
                liveView = delta.view;
                notify();
            },
            reset() {
                if (disposed || version !== serial) return;
                stopStream();
                void loadItems();
            },
            error() {
                if (disposed || version !== serial) return;
                state.itemError = 'NETWORK_ERROR';
                notify();
                stopStream();
                reconnect = setTimeout(() => {
                    if (!disposed && version === serial) void loadItems();
                }, 2000);
            }
        });
    }
    function invalidateItems() {
        stopStream();
        resourceHistory.clear();
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
        stopStream();
        itemRequest?.abort();
        const request = new AbortController();
        itemRequest = request;
        const version = ++serial;
        state.refreshing = true;
        if (state.itemStatus !== 'ready') state.itemStatus = 'loading';
        state.itemError = null;
        notify();
        try {
            const result = await api.items(
                key,
                request.signal,
                iconsEnabled && state.iconPack.available === true,
                state.preferences.autoRefresh
            );
            if (disposed || version !== serial) return;
            const items = result.data;
            state.itemIcons = result.icons;
            state.items = items;
            state.selected = state.selected
                ? items.find((item) => item.itemKey && item.itemKey === state.selected?.itemKey) || null
                : null;
            state.itemStatus = 'ready';
            state.updatedAt = Date.now();
            liveView = result.view;
            subscription = result.subscription;
            startStream(key, version);
            if (state.selected) await resourceHistory.refresh();
            else resourceHistory.clear();
        } catch (caught) {
            const error = caught as ApiFailure;
            if (disposed || version !== serial || error.name === 'AbortError') return;
            resourceHistory.clear();
            state.items = [];
            state.itemIcons = null;
            state.selected = null;
            state.updatedAt = null;
            state.itemStatus = 'error';
            state.itemError = error.status || 'NETWORK_ERROR';
            if (['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(state.itemError)) crafting.block(state.itemError);
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
                crafting.invalidateIcons();
                history.invalidateIcons();
            }
            notify();
            if (changed) {
                void loadItems();
                void loadCpus();
                void loadCrafting();
                if (state.route.view === 'history') void loadHistory();
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
        else if (state.preferences.autoRefresh) timer = setTimeout(() => refresh({ automatic: true }), 5000);
    }
    function loadCrafting() {
        if (!crafting.active || state.gridStatus !== 'ready') return;
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
    async function refresh({ reloadDetail = false, automatic = false } = {}) {
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
                restoreSelectedGrid();
                state.gridStatus = 'ready';
                state.gridError = null;
                notify();
                if (!automatic || !liveView) await loadItems();
                else if (!state.grids.some((grid) => grid.key === state.route.gridKey)) invalidateItems();
                else if (state.selected) await resourceHistory.refresh();
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
                notify();
                schedule();
            }
        })();
        notify();
        return refreshingGrids;
    }
    return {
        state,
        notifications,
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
            crafting.invalidateIcons();
            history.invalidateIcons();
            icons.enabled(enabled && state.iconPack.available === true);
            void loadItems();
            void loadCpus();
            void loadCrafting();
            if (state.route.view === 'history') void loadHistory();
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
            if (route.gridKey) {
                state.selectedGridKey = route.gridKey;
                if (state.gridStatus === 'ready') restoreSelectedGrid();
            }
            crafting.route(route);
            cpus.route(route);
            history.route(route);
            settings.route(route);
            notify();
            void loadItems();
            loadCrafting();
            loadCpus();
            loadHistory();
            loadSettings();
            schedule();
        },
        search(value: string) {
            state.search = value;
            notify();
        },
        select(item: StoredResource | null) {
            state.selected = item;
            void resourceHistory.select(state.route.gridKey, item?.itemKey ?? null);
            notify();
        },
        preference<K extends keyof Preferences>(name: K, value: Preferences[K]) {
            preferences.set(name, value);
            notify();
            if (name === 'autoRefresh') {
                if (value) void loadItems();
                else stopStream();
                schedule();
            }
        },
        dispose() {
            disposed = true;
            stopStream();
            notifications.dispose();
            clearTimeout(timer);
            gridRequest?.abort();
            itemRequest?.abort();
            iconRequest?.abort();
            crafting.dispose();
            cpus.dispose();
            history.dispose();
            resourceHistory.dispose();
            settings.dispose();
            icons.dispose();
            listeners.clear();
        }
    };
}
