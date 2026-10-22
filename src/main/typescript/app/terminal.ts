import { createPins, type PinsState } from './pins.js';
import { createServerSettings, type ServerSettingsState } from './server-settings.js';
import type { Route } from './router.js';
import type {
    Grid,
    StoredResource,
    IconMetadata,
    GridSettings,
    ApplicationContext,
    TerminalDelta,
    StoredResourceUpdate
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
import { createPlanExporter } from './plan-export.js';
import { createCpuMonitor } from './cpus.js';
import { createHistory } from './history.js';
import { createResourceHistory, type ResourceHistoryState } from './resource-history.js';
import { createLiveStream, type LiveStreamState } from './live-stream.js';
import { createGridSettings } from './settings.js';
import { createNotifications } from './notifications.js';

export type TerminalStreamState = LiveStreamState;
/** Timing-only CPU updates still deliver complete state; themes can skip unrelated presentation. */
export type TerminalUpdateHint = 'cpu-timing';

export type TerminalData = {
    capabilities: ApplicationContext['capabilities'];
    route: Route;
    grids: Grid[];
    gridStatus: 'loading' | 'ready' | 'error';
    gridError: string | null;
    items: StoredResource[];
    itemSnapshotRevision: number;
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
    serverSettings: ServerSettingsState;
    pins: PinsState;
};

/** Shared state/actions for terminal renderers; a theme does not own requests or refresh timers. */
export function createTerminal(
    api: Api,
    preferences: ReturnType<typeof createPreferences>,
    icons: ReturnType<typeof createIconLoader>,
    capabilities: ApplicationContext['capabilities'],
    isAdmin = false
) {
    const notifications = createNotifications();
    let iconsEnabled = false;
    let selectedHistoryRange: number | undefined;
    const listeners = new Set<(state: TerminalState, hint?: TerminalUpdateHint) => void>();
    let gridRequest: AbortController | undefined;
    let itemRequest: AbortController | undefined;
    let iconRequest: AbortController | undefined;
    let iconPackId: string | null = null;
    let serial = 0;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let refreshingGrids: Promise<void> | null = null;
    let refreshingAutomatically = false;
    const discoveryUpdates = new Map<string, GridSettings>();
    const notify = (hint?: TerminalUpdateHint) => {
        if (!disposed) for (const listener of listeners) listener(state, hint);
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
        notifications.publish,
        { enabled: () => state.preferences.autoRefresh, updated: notify }
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
    const pins = createPins(
        api,
        notify,
        () => iconsEnabled && state.iconPack.available === true,
        notifications.publish
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
            notify();
        },
        notifications.publish
    );

    const serverSettings = createServerSettings(api, notify, isAdmin);
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
        itemSnapshotRevision: 0,
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
        pins: pins.state,
        settings: settings.state,
        serverSettings: serverSettings.state
    };

    const live = createLiveStream<StoredResource, StoredResourceUpdate, TerminalDelta>({
        enabled: () => state.preferences.autoRefresh,
        rows: () => state.items,
        icons: () => state.itemIcons,
        section(delta) {
            if (delta.recent) {
                state.recent = {
                    ...delta.recent,
                    resources: { ...state.recent?.resources, ...delta.recent.resources }
                };
            }
            if (state.recent) for (const removed of delta.removed) delete state.recent.resources[removed];
        },
        status(next) {
            state.stream = next;
            if (next?.status === 'live') state.itemError = null;
        },
        apply({ rows, icons }) {
            if (rows) {
                state.items = rows.all;
                state.selected = state.selected?.itemKey ? (rows.keyed.get(state.selected.itemKey) ?? null) : null;
                if (!state.selected) resourceHistory.clear();
            }
            if (icons) state.itemIcons = icons;
            state.updatedAt = Date.now();
        },
        failed(confirmed) {
            if (!confirmed) state.itemError = 'NETWORK_ERROR';
        },
        changed: notify,
        resync() {
            void loadItems();
        }
    });

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

    function invalidateItems() {
        live.stop();
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
        live.stop();
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
            state.itemSnapshotRevision++;
            state.recent = result.recent ?? null;
            state.selected = state.selected
                ? items.find((item) => item.itemKey && item.itemKey === state.selected?.itemKey) || null
                : null;
            state.itemStatus = 'ready';
            state.updatedAt = Date.now();
            live.start(
                result,
                (subscription, handlers) =>
                    api.itemEvents(key, subscription, iconsEnabled && state.iconPack.available === true, handlers),
                () => !disposed && version === serial
            );
            if (!live.active || !state.preferences.autoRefresh) {
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
            live.stop();
            resourceHistory.clear();
            state.items = [];
            state.recent = null;
            state.itemIcons = null;
            state.selected = null;
            state.updatedAt = null;
            state.itemStatus = 'error';
            state.itemError = error.status || 'NETWORK_ERROR';
            if (['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(state.itemError)) {
                crafting.block(state.itemError);
                pins.block(state.itemError);
            }
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
                pins.invalidateIcons();
            }
            notify();
            if (changed) {
                void loadItems();
                void loadCpus();
                void loadCrafting();
                if (state.route.view === 'history') void loadHistory();
                void resourceHistory.refresh();
                void pins.refresh();
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
        if (disposed || state.route.view === 'server-settings') return;
        if (crafting.pending) timer = setTimeout(() => crafting.refresh(), 1000);
        else if (state.preferences.autoRefresh) timer = setTimeout(() => refresh({ automatic: true }), 5000);
    }
    function loadCrafting() {
        if (!crafting.active || state.gridStatus !== 'ready') return;
        if (!state.grids.some((grid) => grid.key === state.route.gridKey)) crafting.block('GRID_NOT_FOUND');
        else return crafting.refresh();
    }
    function loadCpus(automatic = false) {
        if (state.route.view !== 'cpus' || state.gridStatus !== 'ready') return;
        if (!state.grids.some((grid) => grid.key === state.route.gridKey)) cpus.block('GRID_NOT_FOUND');
        else return cpus.refresh(automatic);
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
        if (state.route.view === 'server-settings') {
            if (!automatic) await serverSettings.refresh();
            if (disposed) return;
        }
        if (automatic && state.route.view === 'home') {
            clearTimeout(timer);
            await pins.refresh(true);
            schedule();
            return;
        }
        const homeRefresh = state.route.view === 'home' ? pins.refresh() : undefined;
        if (homeRefresh) void homeRefresh.then(schedule);
        if (refreshingGrids) {
            if (homeRefresh) {
                await Promise.all([homeRefresh, refreshingGrids]);
                return;
            }
            if (!automatic && ['items', 'cpus'].includes(state.route.view)) {
                if (!refreshingAutomatically) return refreshingGrids;
                const route = state.route;
                return refreshingGrids.then(() => {
                    if (!disposed && state.route === route) return refresh({ reloadDetail });
                });
            }
            return reloadDetail ? refreshingGrids.then(() => loadHistory(true)) : refreshingGrids;
        }
        if (!automatic && state.stream) {
            live.stop();
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
                if (!automatic || (!live.active && !live.reconnecting && !state.refreshing)) await loadItems(automatic);
                else if (!state.grids.some((grid) => grid.key === state.route.gridKey)) invalidateItems();
                else if (state.selected) await resourceHistory.refresh(automatic);
                await loadCrafting();
                await loadCpus(automatic);
                await loadHistory(reloadDetail);
                await loadSettings();
                if (state.route.view === 'tracking') await resourceHistory.refresh(automatic);
                if (!homeRefresh || state.route.view !== 'home') await pins.refresh(automatic);
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
                    if (!live.reconnecting && !state.refreshing) state.itemError = status;
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
                if (state.route.view !== 'home' || status === 'UNAUTHORIZED') pins.block(state.gridError);
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
        await Promise.all([refreshingGrids, homeRefresh]);
    }
    return {
        state,
        notifications,
        crafting,
        cpus,
        history,
        settings,
        serverSettings,
        pins,
        resourceHistory,
        exportPlan: createPlanExporter(icons),
        icons,
        displayIcons(enabled: boolean) {
            if (iconsEnabled === enabled) return;
            iconsEnabled = enabled;
            state.itemIcons = null;
            cpus.invalidateIcons();
            crafting.invalidateIcons();
            history.invalidateIcons();
            resourceHistory.invalidateIcons();
            pins.invalidateIcons();
            void pins.refresh();
            icons.enabled(enabled && state.iconPack.available === true);
            void loadItems();
            void loadCpus();
            void loadCrafting();
            if (state.route.view === 'history') void loadHistory();
            void resourceHistory.refresh();
            notify();
        },
        subscribe(listener: (state: TerminalState, hint?: TerminalUpdateHint) => void) {
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
                live.stop();
                serial++;
                itemRequest?.abort();
                state.refreshing = false;
            } else invalidateItems();
            state.route = route;
            pins.route(route);
            if (route.view === 'tracking') void resourceHistory.select(route.gridKey, route.itemKey);
            else if (related) resourceHistory.clear();
            const needsDiscovery =
                !!route.gridKey &&
                (state.gridStatus !== 'ready' || !state.grids.some((grid) => grid.key === route.gridKey));
            if (route.gridKey) {
                state.selectedGridKey = route.gridKey;
                if (!needsDiscovery) restoreSelectedGrid();
            }
            crafting.route(route);
            cpus.route(route);
            history.route(route);
            settings.route(route);
            serverSettings.route(route);
            notify();
            if (needsDiscovery) void refresh();
            else {
                void loadItems();
                loadCrafting();
                loadCpus();
                loadHistory();
                loadSettings();
            }
            void pins.refresh();
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
                if (value) {
                    void loadItems();
                    void loadCpus();
                } else {
                    live.stop();
                    cpus.stopLive();
                }
                schedule();
            }
            notify();
        },
        dispose() {
            disposed = true;
            live.stop();
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
            serverSettings.dispose();
            pins.dispose();
            icons.dispose();
            listeners.clear();
        }
    };
}
