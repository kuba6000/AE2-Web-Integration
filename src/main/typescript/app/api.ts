import type { ThemeManifest } from './theme-manifest.js';
import type {
    Grid,
    StoredResource,
    Plan,
    CpuInfo,
    CpuDetail,
    HistoryEntry,
    CraftingHistory,
    GridSettings,
    ResourceHistory
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

export type ApiFailure = Error & { status?: string; data?: unknown };
export type Api = {
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
        handlers: {
            ready: () => void;
            delta: (delta: TerminalDelta) => void;
            reset: () => void;
            error: () => void;
        }
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
        icons?: boolean
    ) => Promise<ResourceResponse<CpuDetail>>;
    cancelCpu: (gridKey: string, cpuKey: string) => Promise<null>;
    pauseCpu: (gridKey: string, cpuKey: string, paused: boolean) => Promise<null>;
    history: (gridKey: string, signal?: AbortSignal, icons?: boolean) => Promise<ResourceResponse<HistoryEntry[]>>;
    historyEntry: (
        gridKey: string,
        entryId: string | number,
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
    settings: (gridKey: string, signal?: AbortSignal) => Promise<GridSettings>;
    saveSettings: (gridKey: string, body: Partial<GridSettings>) => Promise<GridSettings>;
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
                | { paused: boolean }
                | { tag: string };
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
            view: envelope.view,
            subscription: envelope.subscription
        };
    }
    function request<T>(path: string, options?: Parameters<typeof read>[1]): Promise<T> {
        return read<T>(path, options).then((result) => result.data);
    }
    return {
        context: (signal) => request('api/context', { signal }),
        themes: (signal) => request('api/themes', { signal }),
        skipUpdate: (tag) => request('api/updates/skip', { method: 'POST', body: { tag } }),
        iconPack: (signal) => request('api/icon-pack', { signal }),
        grids: (signal) => request('api/grids', { signal }),
        items: (gridKey, signal, icons = false, live = false) => {
            const query = new URLSearchParams();
            if (icons) query.set('icons', 'true');
            if (live) query.set('live', 'true');
            return read(`api/grids/${encodeURIComponent(gridKey)}/items${query.size ? `?${query}` : ''}`, { signal });
        },
        itemEvents(gridKey, subscription, icons, handlers) {
            const query = new URLSearchParams({ subscription });
            if (icons) query.set('icons', 'true');
            const source = new EventSource(
                new URL(`api/grids/${encodeURIComponent(gridKey)}/items/events?${query}`, base)
            );
            source.addEventListener('ready', handlers.ready);
            source.addEventListener('delta', (event) => {
                try {
                    handlers.delta(JSON.parse(event.data) as TerminalDelta);
                } catch {
                    source.close();
                    handlers.reset();
                }
            });
            for (const event of ['reset', 'invalidate', 'revoked'])
                source.addEventListener(event, () => {
                    source.close();
                    handlers.reset();
                });
            source.onerror = () => {
                source.close();
                handlers.error();
            };
            return () => source.close();
        },
        createPlan: (gridKey, body) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans`, { method: 'POST', body }),
        plan: (gridKey, planId, signal, icons) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans/${planId}${icons ? '?icons=true' : ''}`, {
                signal
            }),
        cpus: (gridKey, signal, icons = false) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/cpus${icons ? '?icons=true' : ''}`, { signal }),
        cpu: (gridKey, cpuKey, signal, icons = false) =>
            read(
                `api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}${icons ? '?icons=true' : ''}`,
                { signal }
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
        history: (gridKey, signal, icons = false) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/crafting-history${icons ? '?icons=true' : ''}`, { signal }),
        historyEntry: (gridKey, entryId, signal, icons = false) =>
            read(`api/grids/${encodeURIComponent(gridKey)}/crafting-history/${entryId}${icons ? '?icons=true' : ''}`, {
                signal
            }),
        resourceHistory: (gridKey, itemKey, signal, from = Date.now() - 900000, icons = false) =>
            read(
                `api/grids/${encodeURIComponent(gridKey)}/resource-history?itemKey=${encodeURIComponent(itemKey)}&from=${from}&limit=10000${icons ? '&icons=true' : ''}`,
                { signal }
            ),
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
        logout: () => request('api/auth/logout', { method: 'POST' })
    };
}
