import type { Grid, Item, Plan, CpuInfo, CpuDetail, HistoryEntry, HistoryDetail, GridSettings } from './api-types.js';

export type ApiFailure = Error & { status?: string; data?: unknown };
export type Api = {
    grids: (signal?: AbortSignal) => Promise<Grid[]>;
    items: (gridKey: string, signal?: AbortSignal) => Promise<Item[]>;
    createPlan: (gridKey: string, body: { itemKey: string; quantity: number }) => Promise<{ jobID: number }>;
    plan: (gridKey: string, planId: string | number, signal?: AbortSignal) => Promise<Plan>;
    cpus: (gridKey: string, signal?: AbortSignal) => Promise<Record<string, CpuInfo>>;
    cpu: (gridKey: string, cpuKey: string, signal?: AbortSignal) => Promise<CpuDetail>;
    cancelCpu: (gridKey: string, cpuKey: string) => Promise<null>;
    pauseCpu: (gridKey: string, cpuKey: string, paused: boolean) => Promise<null>;
    history: (gridKey: string, signal?: AbortSignal) => Promise<HistoryEntry[]>;
    historyEntry: (gridKey: string, entryId: string | number, signal?: AbortSignal) => Promise<HistoryDetail>;
    settings: (gridKey: string, signal?: AbortSignal) => Promise<GridSettings>;
    saveSettings: (gridKey: string, body: { isTracked: boolean }) => Promise<GridSettings>;
    submitPlan: (gridKey: string, planId: string | number, cpuKey: string) => Promise<null>;
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
    async function request<T>(
        path: string,
        {
            method = 'GET',
            signal,
            body
        }: {
            method?: string;
            signal?: AbortSignal;
            body?:
                | { itemKey: string; quantity: number }
                | { cpuKey: string }
                | { isTracked: boolean }
                | { paused: boolean };
        } = {}
    ): Promise<T> {
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
        let envelope: { status: string; data: T };
        try {
            envelope = await response.json();
        } catch {
            throw new ApiError('INVALID_RESPONSE', response.status);
        }
        if (!response.ok || envelope.status !== 'OK') {
            throw new ApiError(envelope.status || 'INVALID_RESPONSE', response.status, envelope.data);
        }
        return envelope.data;
    }
    return {
        grids: (signal) => request('api/grids', { signal }),
        items: (gridKey, signal) => request(`api/grids/${encodeURIComponent(gridKey)}/items`, { signal }),
        createPlan: (gridKey, body) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans`, { method: 'POST', body }),
        plan: (gridKey, planId, signal) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/crafting-plans/${planId}`, { signal }),
        cpus: (gridKey, signal) => request(`api/grids/${encodeURIComponent(gridKey)}/cpus`, { signal }),
        cpu: (gridKey, cpuKey, signal) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}`, { signal }),
        cancelCpu: (gridKey, cpuKey) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}/cancel`, {
                method: 'POST'
            }),
        pauseCpu: (gridKey, cpuKey, paused) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/cpus/${encodeURIComponent(cpuKey)}/pause`, {
                method: 'POST',
                body: { paused }
            }),
        history: (gridKey, signal) => request(`api/grids/${encodeURIComponent(gridKey)}/crafting-history`, { signal }),
        historyEntry: (gridKey, entryId, signal) =>
            request(`api/grids/${encodeURIComponent(gridKey)}/crafting-history/${entryId}`, { signal }),
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
