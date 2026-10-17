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
import type { RecentTracking } from './api-types.js';
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

export type TerminalStreamState = { status: 'connecting'; lastEventAt: null } | { status: 'live'; lastEventAt: number };

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
    stream: TerminalStreamState | null;
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
    recent: RecentTracking | null;
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
    let selectedHistoryRange: number | undefined;
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
    let streamSerial = 0;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let refreshingGrids: Promise<void> | null = null;
    let refreshingAutomatically = false;
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
    const resourceHistory = createResourceHistory(api, notify, () => iconsEnabled && state.iconPack.available === true);
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
        stream: null,
        search: '',
        selected: null,
        preferences: preferences.values,
        crafting: crafting.state,
        cpus: cpus.state,
        history: history.state,
        resourceHistory: resourceHistory.state,
        recent: null,
        settings: settings.state
    };

    function restoreSelectedGrid() {
        const key = state.route.gridKey || state.selectedGridKey || preferences.values.selectedGridKey;
        state.selectedGridKey = state.grids.some((grid) => grid.key === key) ? key : null;
        if (state.selectedGridKey && state.selectedGridKey !== preferences.values.selectedGridKey)
            preferences.set('selectedGridKey', state.selectedGridKey);
    }

    function isTransientFailure(status: string) {
        return [
            'NETWORK_ERROR',
            'INTERNAL_ERROR',
            'SERVER_BUSY',
            'SERVER_STOPPING',
            'TIMEOUT',
            'TOO_MANY_REQUESTS'
        ].includes(status);
    }

    function stopStream() {
        streamSerial++;
        state.stream = null;
        closeStream?.();
        closeStream = undefined;
        clearTimeout(reconnect);
        reconnect = undefined;
        liveView = undefined;
        subscription = undefined;
    }
    function startStream(key: string, version: number) {
        if (!liveView || !subscription || !state.preferences.autoRefresh || disposed || version !== serial) return;
        const connection = ++streamSerial;
        closeStream?.();
        state.stream = { status: 'connecting', lastEventAt: null };
        const current = () =>
            !disposed && version === serial && connection === streamSerial && state.preferences.autoRefresh;
        closeStream = api.itemEvents(key, subscription, iconsEnabled && state.iconPack.available === true, {
            ready() {
                if (!current() || !liveView) return;
                state.stream = { status: 'live', lastEventAt: Date.now() };
                state.itemError = null;
                notify();
            },
            delta(delta: TerminalDelta) {
                if (!current() || !liveView) return;
                if (delta.view.generation !== liveView.generation) {
                    stopStream();
                    void loadItems();
                    return;
                }
                state.stream = { status: 'live', lastEventAt: Date.now() };
                state.itemError = null;
                if (BigInt(delta.view.revision) <= BigInt(liveView.revision)) {
                    notify();
                    return;
                }
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
                if (delta.recent) {
                    state.recent = {
                        ...delta.recent,
                        resources: { ...state.recent?.resources, ...delta.recent.resources }
                    };
                }
                if (state.recent) for (const removed of delta.removed) delete state.recent.resources[removed];
                if (delta.definitions.length || delta.updates.length || delta.removed.length || delta.unkeyed) {
                    const keyed = new Map(
                        state.items.filter((item) => item.itemKey).map((item) => [item.itemKey!, item])
                    );
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
                    state.selected = state.selected?.itemKey ? (keyed.get(state.selected.itemKey) ?? null) : null;
                    if (!state.selected) resourceHistory.clear();
                }
                if (delta.icons) state.itemIcons = { ...delta.icons, pages };
                state.updatedAt = Date.now();
                state.itemError = null;
                liveView = delta.view;
                notify();
            },
            reset() {
                if (!current()) return;
                stopStream();
                void loadItems();
            },
            error() {
                if (!current()) return;
                if (state.stream?.status !== 'live') state.itemError = 'NETWORK_ERROR';
                stopStream();
                state.stream = { status: 'connecting', lastEventAt: null };
                notify();
                reconnect = setTimeout(() => {
                    reconnect = undefined;
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
        state.recent = null;
        state.itemIcons = null;
        state.selected = null;
        selectedHistoryRange = undefined;
        state.itemStatus = 'idle';
        state.itemError = null;
        state.updatedAt = null;
        state.refreshing = false;
    }
    async function loadItems(automatic = false) {
        const key = state.route.gridKey;
        if (disposed || state.route.view !== 'items' || !key || state.gridStatus !== 'ready') return;
        if (!state.grids.some((grid) => grid.key === key)) {
            invalidateItems();
            state.itemStatus = 'error';
            state.itemError = 'GRID_NOT_FOUND';
            notify();
            return;
        }
        const recovering = state.stream?.status === 'connecting' && state.itemStatus === 'ready';
        stopStream();
        if (recovering) state.stream = { status: 'connecting', lastEventAt: null };
        itemRequest?.abort();
        const request = new AbortController();
        itemRequest = request;
        const version = ++serial;
        state.refreshing = true;
        if (state.itemStatus !== 'ready') state.itemStatus = 'loading';
        if (!recovering) state.itemError = null;
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
            state.recent = result.recent ?? null;
            state.selected = state.selected
                ? items.find((item) => item.itemKey && item.itemKey === state.selected?.itemKey) || null
                : null;
            state.itemStatus = 'ready';
            state.updatedAt = Date.now();
            liveView = result.view;
            subscription = result.subscription;
            startStream(key, version);
            if (!liveView || !subscription || !state.preferences.autoRefresh) {
                state.stream = null;
                state.itemError = null;
            }
            if (state.selected?.itemKey && resourceHistory.state.status === 'idle')
                await resourceHistory.select(key, state.selected.itemKey, selectedHistoryRange);
            else if (state.selected) await resourceHistory.refresh(automatic);
            else resourceHistory.clear();
        } catch (caught) {
            const error = caught as ApiFailure;
            if (disposed || version !== serial || error.name === 'AbortError') return;
            const status = error.status || 'NETWORK_ERROR';
            if (recovering && isTransientFailure(status)) {
                state.itemError = status;
                return;
            }
            stopStream();
            resourceHistory.clear();
            state.items = [];
            state.recent = null;
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
                resourceHistory.invalidateIcons();
            }
            notify();
            if (changed) {
                void loadItems();
                void loadCpus();
                void loadCrafting();
                if (state.route.view === 'history') void loadHistory();
                void resourceHistory.refresh();
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
    async function refresh({ reloadDetail = false, automatic = false } = {}): Promise<void> {
        if (disposed) return;
        if (refreshingGrids) {
            if (!automatic && state.route.view === 'items') {
                if (!refreshingAutomatically) return refreshingGrids;
                const route = state.route;
                return refreshingGrids.then(() => {
                    if (!disposed && state.route === route) return refresh({ reloadDetail });
                });
            }
            return reloadDetail ? refreshingGrids.then(() => loadHistory(true)) : refreshingGrids;
        }
        if (!automatic && state.stream) {
            stopStream();
            state.stream = { status: 'connecting', lastEventAt: null };
        }
        clearTimeout(timer);
        discoveryUpdates.clear();
        gridRequest = new AbortController();
        void loadIconPack();
        refreshingAutomatically = automatic;
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
                if (!automatic || (!liveView && !reconnect && !state.refreshing)) await loadItems(automatic);
                else if (!state.grids.some((grid) => grid.key === state.route.gridKey)) invalidateItems();
                else if (state.selected) await resourceHistory.refresh(automatic);
                await loadCrafting();
                await loadCpus();
                await loadHistory(reloadDetail);
                await loadSettings();
                if (state.route.view === 'tracking') await resourceHistory.refresh(automatic);
            } catch (caught) {
                const error = caught as ApiFailure;
                if (disposed || error.name === 'AbortError') return;
                const status = error.status || 'NETWORK_ERROR';
                if (
                    state.route.view === 'items' &&
                    state.itemStatus === 'ready' &&
                    state.stream?.status === 'connecting' &&
                    isTransientFailure(status)
                ) {
                    if (!reconnect && !state.refreshing) state.itemError = status;
                    notify();
                    return;
                }
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
                refreshingAutomatically = false;
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
        resourceHistory,
        icons,
        displayIcons(enabled: boolean) {
            if (iconsEnabled === enabled) return;
            iconsEnabled = enabled;
            state.itemIcons = null;
            cpus.invalidateIcons();
            crafting.invalidateIcons();
            history.invalidateIcons();
            resourceHistory.invalidateIcons();
            icons.enabled(enabled && state.iconPack.available === true);
            void loadItems();
            void loadCpus();
            void loadCrafting();
            if (state.route.view === 'history') void loadHistory();
            void resourceHistory.refresh();
            notify();
        },
        subscribe(listener: (state: TerminalState) => void) {
            listeners.add(listener);
            listener(state);
            return () => listeners.delete(listener);
        },
        refresh,
        route(route: Route) {
            const related =
                route.gridKey &&
                route.gridKey === state.route.gridKey &&
                ['items', 'tracking'].includes(route.view) &&
                ['items', 'tracking'].includes(state.route.view);
            if (related) {
                stopStream();
                serial++;
                itemRequest?.abort();
                state.refreshing = false;
            } else invalidateItems();
            state.route = route;
            if (route.view === 'tracking') void resourceHistory.select(route.gridKey, route.itemKey);
            else if (related) resourceHistory.clear();
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
        select(item: StoredResource | null, historyRangeMillis?: number) {
            state.selected = item;
            selectedHistoryRange = item ? historyRangeMillis : undefined;
            void resourceHistory.select(state.route.gridKey, item?.itemKey ?? null, selectedHistoryRange);
            notify();
        },
        preference<K extends keyof Preferences>(name: K, value: Preferences[K]) {
            preferences.set(name, value);
            if (name === 'autoRefresh') {
                if (value) void loadItems();
                else stopStream();
                schedule();
            }
            notify();
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
