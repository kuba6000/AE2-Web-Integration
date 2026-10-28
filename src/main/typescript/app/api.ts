import type { TreePage, TreeQuery } from './crafting-tree.js';
import type { ThemeManifest } from './theme-manifest.js';
import type { ServerSettingsResponse, ServerSettingsUpdate } from './server-settings.js';
import type {
    Grid,
    StoredResource,
    Plan,
    CpuInfo,
    CpuDetail,
    CpuDelta,
    HistoryEntry,
    CraftingHistory,
    GridSettings,
    GridSettingsResponse,
    ResourceHistory,
    ResourcePin,
    ResourcePinUpdate,
    PinPlacement
} from './api-types.js';
import type { ResourceResponse, IconMetadata, ApplicationContext, TerminalView, TerminalDelta } from './api-types.js';

/** Wire codes declared by core ApiStatus; unrecognized replies leave mutations unconfirmed. */
const responseStatuses = new Set([
    'OK',
    'INVALID_USER',
    'INVALID_PASSWORD',
    'NOT_ONLINE',
    'UNAUTHORIZED',
    'BAD_PARAM',
    'INVALID_QUANTITY',
    'NO_PERMISSIONS',
    'CSRF_REJECTED',
    'GRID_NOT_FOUND',
    'CPU_NOT_FOUND',
    'ITEM_NOT_FOUND',
    'ITEM_IDENTITY_UNKNOWN',
    'TRACKING_NOT_FOUND',
    'INVALID_ID',
    'CPU_PAUSE_UNSUPPORTED',
    'CPU_NOT_BUSY',
    'AMBIGUOUS_ITEM_KEY',
    'JOB_NOT_DONE',
    'FAIL',
    'UPDATE_CHANGED',
    'CONFIG_CHANGED',
    'SERVER_SETTINGS_DISABLED',
    'REQUEST_TOO_LARGE',
    'UNSUPPORTED_MEDIA_TYPE',
    'NOT_FOUND',
    'METHOD_NOT_ALLOWED',
    'TOO_MANY_REQUESTS',
    'INTERNAL_ERROR',
    'SERVER_BUSY',
    'SERVER_STOPPING',
    'TIMEOUT'
]);

export type EventHandlers<Delta> = {
    ready: () => void;
    delta: (delta: Delta) => void;
    reset: () => void;
    error: () => void;
};
export type ApiFailure = Error & { status?: string; data?: unknown };
export type Api = {
    planTree(
        grid: string,
        plan: string | number,
        query: TreeQuery,
        signal?: AbortSignal
    ): Promise<ResourceResponse<TreePage>>;
    serverSettings(signal?: AbortSignal): Promise<ServerSettingsResponse>;
    saveServerSettings(body: ServerSettingsUpdate): Promise<ServerSettingsResponse>;
    resourcePins(signal?: AbortSignal, icons?: boolean): Promise<ResourceResponse<ResourcePin[]>>;
    addResourcePin(
        gridKey: string,
        itemKey: string,
        placement: PinPlacement,
        icons?: boolean
    ): Promise<ResourceResponse<ResourcePin[]>>;
    updateResourcePin(
        pinKey: string,
        update: ResourcePinUpdate,
        icons?: boolean
    ): Promise<ResourceResponse<ResourcePin[]>>;
    deleteResourcePin(pinKey: string, icons?: boolean): Promise<ResourceResponse<ResourcePin[]>>;
    themes: (signal?: AbortSignal) => Promise<{ defaultTheme: string; themes: ThemeManifest[] }>;
    context: (signal?: AbortSignal) => Promise<ApplicationContext>;
    skipUpdate: (tag: string) => Promise<null>;
    iconPack: (
        signal?: AbortSignal
    ) => Promise<{ available: boolean; packId: string | null; width: number; height: number }>;
    grids: (signal?: AbortSignal) => Promise<Grid[]>;
    items: (
        gridKey: string,
        signal?: AbortSignal,
        icons?: boolean,
        live?: boolean
    ) => Promise<ResourceResponse<StoredResource[]>>;
    itemEvents: (
        gridKey: string,
        subscription: string,
        icons: boolean,
        handlers: EventHandlers<TerminalDelta>
    ) => () => void;
    createPlan: (
        gridKey: string,
        body: { itemKey: string; quantity: number; lightMode?: boolean; ignoreMissing?: boolean; autoStart?: boolean }
    ) => Promise<{ jobId: number }>;
    plan: (
        gridKey: string,
        planId: string | number,
        signal?: AbortSignal,
        icons?: boolean
    ) => Promise<ResourceResponse<Plan>>;
    cpus: (
        gridKey: string,
        signal?: AbortSignal,
        icons?: boolean
    ) => Promise<ResourceResponse<Record<string, CpuInfo>>>;
    cpu: (
        gridKey: string,
        cpuKey: string,
        signal?: AbortSignal,
        icons?: boolean,
        live?: boolean
    ) => Promise<ResourceResponse<CpuDetail>>;
    cpuEvents: (
        gridKey: string,
        cpuKey: string,
        subscription: string,
        icons: boolean,
        handlers: EventHandlers<CpuDelta>
    ) => () => void;
    cancelCpu: (gridKey: string, cpuKey: string) => Promise<null>;
    pauseCpu: (gridKey: string, cpuKey: string, paused: boolean) => Promise<null>;
    history: (
        gridKey: string,
        signal?: AbortSignal,
        icons?: boolean,
        before?: string | null
    ) => Promise<ResourceResponse<HistoryEntry[]>>;
    historyEntry: (
        gridKey: string,
        entryId: string,
        signal?: AbortSignal,
        icons?: boolean
    ) => Promise<ResourceResponse<CraftingHistory>>;
    resourceHistory: (
        gridKey: string,
        itemKey: string,
        signal?: AbortSignal,
        from?: number,
        icons?: boolean
    ) => Promise<ResourceResponse<ResourceHistory>>;
    settings: (gridKey: string, signal?: AbortSignal) => Promise<GridSettingsResponse>;
    saveSettings: (gridKey: string, body: Partial<GridSettings>) => Promise<GridSettingsResponse>;
    submitPlan: (gridKey: string, planId: string | number, cpuKey?: string) => Promise<null>;
    deletePlan: (gridKey: string, planId: string | number) => Promise<null>;
    logout: () => Promise<null>;
};
export class ApiError extends Error {
    declare status: string;
    declare httpStatus: number;
    declare data: unknown;

    constructor(status: string, httpStatus: number, data: unknown = null) {
        super(status);
        this.status = status;
        this.httpStatus = httpStatus;
        this.data = data;
    }
}

/** One transport for the application. Authentication remains in the server's HttpOnly cookie. */
export function createApi(base: URL, onUnauthorized: () => void): Api {
    const subscriptionStorageKey = `ae2web:${base.pathname}:live-subscription`;
    let subscription: string | undefined;
    let liveBootstrap: Promise<void> = Promise.resolve();
    let loginGeneration = 0;
    try {
        // New tabs may inherit their opener's sessionStorage; only reloads reclaim a previous connection.
        if ((performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming)?.type === 'reload') {
            subscription = sessionStorage.getItem(subscriptionStorageKey) ?? undefined;
        } else sessionStorage.removeItem(subscriptionStorageKey);
    } catch {
        // A blocked or corrupt browser store still permits in-document resync.
    }
    function rememberSubscription(token: string) {
        subscription = token;
        try {
            sessionStorage.setItem(subscriptionStorageKey, token);
        } catch {
            // In-memory ownership remains usable when browser storage is unavailable.
        }
    }
    function forgetSubscription() {
        loginGeneration++;
        subscription = undefined;
        try {
            sessionStorage.removeItem(subscriptionStorageKey);
        } catch {
            // Forget in-memory ownership even when browser storage is unavailable.
        }
    }
    async function read<T>(
        path: string,
        {
            method = 'GET',
            signal,
            body
        }: {
            method?: string;
            signal?: AbortSignal;
            body?:
                | {
                      itemKey: string;
                      quantity: number;
                      lightMode?: boolean;
                      ignoreMissing?: boolean;
                      autoStart?: boolean;
                  }
                | { cpuKey?: string }
                | Partial<GridSettings>
                | ServerSettingsUpdate
                | { paused: boolean }
                | { tag: string }
                | { itemKey: string; placement: PinPlacement }
                | ResourcePinUpdate;
        } = {}
    ): Promise<ResourceResponse<T>> {
        let response;
        try {
            response = await fetch(new URL(path, base), {
                method,
                signal,
                credentials: 'same-origin',
                cache: 'no-store',
                headers: {
                    Accept: 'application/json',
                    ...(method === 'GET' ? {} : { 'X-AE2-Request': 'true' }),
                    ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
                },
                ...(body === undefined ? {} : { body: JSON.stringify(body) })
            });
        } catch (error) {
            if (error instanceof Error && error.name === 'AbortError') throw error;
            throw new ApiError('NETWORK_ERROR', 0);
        }
        if (response.status === 401) {
            forgetSubscription();
            onUnauthorized();
            throw new ApiError('UNAUTHORIZED', 401);
        }
        let parsed: unknown;
        try {
            parsed = await response.json();
        } catch {
            throw new ApiError('INVALID_RESPONSE', response.status);
        }
        // A malformed reply cannot confirm success or prove that a mutation was rejected.
        if (
            parsed === null ||
            typeof parsed !== 'object' ||
            Array.isArray(parsed) ||
            !('status' in parsed) ||
            typeof parsed.status !== 'string' ||
            !responseStatuses.has(parsed.status) ||
            (parsed.status === 'OK' && (!response.ok || !Object.hasOwn(parsed, 'data')))
        )
            throw new ApiError('INVALID_RESPONSE', response.status);
        const envelope = parsed as {
            status: string;
            data: T;
            icons?: IconMetadata | null;
            nextBefore?: string | null;
            view?: TerminalView;
            subscription?: string;
            recent?: import('./api-types.js').RecentTracking;
        };
        if (!response.ok || envelope.status !== 'OK') {
            throw new ApiError(envelope.status, response.status, envelope.data);
        }
        return {
            data: envelope.data,
            recent: envelope.recent,
            icons: envelope.icons ?? null,
            nextBefore: envelope.nextBefore ?? null,
            view: envelope.view,
            subscription: envelope.subscription
        };
    }
    function request<T>(path: string, options?: Parameters<typeof read>[1]): Promise<T> {
        return read<T>(path, options).then((result) => result.data);
    }
    function snapshot<T>(path: string, signal: AbortSignal | undefined, icons: boolean, live: boolean) {
        const query = new URLSearchParams();
        if (icons) query.set('icons', 'true');
        if (!live) return read<T>(`${path}${query.size ? `?${query}` : ''}`, { signal });
        const generation = loginGeneration;
        const requireCurrent = () => {
            signal?.throwIfAborted();
            if (generation !== loginGeneration) throw new DOMException('Login changed', 'AbortError');
        };
        // A tab owns one view. Finish a dispatched handoff even after navigation, so the next view can reclaim it.
        const result = liveBootstrap.then(async () => {
            requireCurrent();
            query.set('live', 'true');
            if (subscription) query.set('replace', subscription);
            const deadline = new AbortController();
            // The server waits at most ten seconds for game work; bound transport and response-body stalls too.
            const timeout = setTimeout(() => deadline.abort(), 30000);
            try {
                const response = await read<T>(`${path}?${query}`, { signal: deadline.signal });
                if (generation !== loginGeneration) throw new DOMException('Login changed', 'AbortError');
                if (response.subscription) rememberSubscription(response.subscription);
                requireCurrent();
                return response;
            } catch (error) {
                requireCurrent();
                if (deadline.signal.aborted) throw new ApiError('TIMEOUT', 0);
                throw error;
            } finally {
                clearTimeout(timeout);
            }
        });
        liveBootstrap = result.then(
            () => {},
            () => {}
        );
        return result;
    }
    /** One server-sent stream of a live subscription; the lease is renewed from its heartbeats. */
    function events<Delta>(path: string, subscription: string, icons: boolean, handlers: EventHandlers<Delta>) {
        const query = new URLSearchParams({ subscription });
        if (icons) query.set('icons', 'true');
        const source = new EventSource(new URL(`${path}?${query}`, base));
        const renewal = new AbortController();
        let renewedAt = performance.now();
        let renewing = false;
        const close = () => {
            source.close();
            renewal.abort();
        };
        const failed = () => {
            if (renewal.signal.aborted) return;
            close();
            handlers.error();
        };
        source.addEventListener('heartbeat', () => {
            if (renewal.signal.aborted || renewing || performance.now() - renewedAt < 30000) return;
            renewing = true;
            renewedAt = performance.now();
            void request<null>(`${path}?subscription=${encodeURIComponent(subscription)}`, {
                method: 'POST',
                signal: renewal.signal
            })
                .catch(failed)
                .finally(() => {
                    renewing = false;
                });
        });
        source.addEventListener('ready', handlers.ready);
        source.addEventListener('delta', (event) => {
            try {
                handlers.delta(JSON.parse(event.data) as Delta);
            } catch {
                close();
                handlers.reset();
            }
        });
        for (const event of ['reset', 'invalidate', 'revoked'])
            source.addEventListener(event, () => {
                close();
                handlers.reset();
            });
        source.onerror = failed;
        return close;
    }
    return {
        context: (signal) => request('api/context', { signal }),
        themes: (signal) => request('api/themes', { signal }),
        skipUpdate: (tag) => request('api/updates/skip', { method: 'POST', body: { tag } }),
        iconPack: (signal) => request('api/icon-pack', { signal }),
        resourcePins: (signal, icons = false) => read(`api/resource-pins?icons=${icons}`, { signal }),
        addResourcePin: (gridKey, itemKey, placement, icons = false) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/resource-pins?icons=${icons}`, {
                method: 'POST',
                body: { itemKey, placement }
            }),
        updateResourcePin: (pinKey, update, icons = false) =>
            read(`api/resource-pins/${encodeURIComponent(pinKey)}?icons=${icons}`, { method: 'PATCH', body: update }),
        deleteResourcePin: (pinKey, icons = false) =>
            read(`api/resource-pins/${encodeURIComponent(pinKey)}?icons=${icons}`, { method: 'DELETE' }),
        grids: (signal) => request('api/grids', { signal }),
        items: (gridKey, signal, icons = false, live = false) =>
            snapshot<StoredResource[]>(`api/grids/${encodeURIComponent(gridKey)}/items`, signal, icons, live),
        itemEvents: (gridKey, subscription, icons, handlers) =>
            events(`api/grids/${encodeURIComponent(gridKey)}/items/events`, subscription, icons, handlers),
        createPlan: (gridKey, body) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans`, { method: 'POST', body }),
        plan: (gridKey, planId, signal, icons) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans/${planId}${icons ? '?icons=true' : ''}`, {
                signal
            }),
        planTree: (gridKey, planId, query, signal) => {
            const parameters = new URLSearchParams(Object.entries(query).map(([key, value]) => [key, String(value)]));
            return read(
                `api/grids/${encodeURIComponent(gridKey)}/crafting-plans/${encodeURIComponent(String(planId))}/tree?${parameters}`,
                { signal }
            );
        },
        cpus: (gridKey, signal, icons = false) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/cpus${icons ? '?icons=true' : ''}`, { signal }),
        cpu: (gridKey, cpuKey, signal, icons = false, live = false) =>
            snapshot<CpuDetail>(
                `api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}`,
                signal,
                icons,
                live
            ),
        cpuEvents: (gridKey, cpuKey, subscription, icons, handlers) =>
            events(
                `api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}/events`,
                subscription,
                icons,
                handlers
            ),
        cancelCpu: (gridKey, cpuKey) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}/cancel`, {
                method: 'POST'
            }),
        pauseCpu: (gridKey, cpuKey, paused) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}/pause`, {
                method: 'POST',
                body: { paused }
            }),
        history: (gridKey, signal, icons = false, before = null) => {
            const query = new URLSearchParams({ limit: '50' });
            if (icons) query.set('icons', 'true');
            if (before !== null) query.set('before', before);
            return read(`api/grids/${encodeURIComponent(gridKey)}/crafting-history?${query}`, { signal });
        },
        historyEntry: (gridKey, entryId, signal, icons = false) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/crafting-history/${entryId}${icons ? '?icons=true' : ''}`, {
                signal
            }),
        resourceHistory: (gridKey, itemKey, signal, from = Date.now() - 900000, icons = false) =>
            read(
                `api/grids/${encodeURIComponent(gridKey)}/resource-history?itemKey=${encodeURIComponent(itemKey)}&from=${from}&limit=10000${icons ? '&icons=true' : ''}`,
                { signal }
            ),
        serverSettings: (signal) => request('api/server/settings', { signal }),
        saveServerSettings: (body) => request('api/server/settings', { method: 'POST', body }),
        settings: (gridKey, signal) => request(`api/grids/${encodeURIComponent(gridKey)}/settings`, { signal }),
        saveSettings: (gridKey, body) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/settings`, { method: 'PATCH', body }),
        submitPlan: (gridKey, planId, cpuKey) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans/${planId}/submit`, {
                method: 'POST',
                body: { cpuKey }
            }),
        deletePlan: (gridKey, planId) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans/${planId}`, { method: 'DELETE' }),
        logout: async () => {
            const result = await request<null>('api/auth/logout', { method: 'POST' });
            forgetSubscription();
            return result;
        }
    };
}
