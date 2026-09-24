/**
 * @typedef {Error & {status?: string, data?: unknown}} ApiFailure
 * @typedef {object} Api
 * @property {(signal?: AbortSignal) => Promise<import('./api-types.mjs').Grid[]>} grids
 * @property {(gridKey: string, signal?: AbortSignal) => Promise<import('./api-types.mjs').Item[]>} items
 * @property {(gridKey: string, body: {itemKey: string, quantity: number}) => Promise<{jobID: number}>} createPlan
 * @property {(gridKey: string, planId: string | number, signal?: AbortSignal) => Promise<import('./api-types.mjs').Plan>} plan
 * @property {(gridKey: string, signal?: AbortSignal) => Promise<Record<string, import('./api-types.mjs').CpuInfo>>} cpus
 * @property {(gridKey: string, cpuKey: string, signal?: AbortSignal) => Promise<import('./api-types.mjs').CpuDetail>} cpu
 * @property {(gridKey: string, cpuKey: string) => Promise<null>} cancelCpu
 * @property {(gridKey: string, signal?: AbortSignal) => Promise<import('./api-types.mjs').HistoryEntry[]>} history
 * @property {(gridKey: string, entryId: string | number, signal?: AbortSignal) => Promise<import('./api-types.mjs').HistoryDetail>} historyEntry
 * @property {(gridKey: string, signal?: AbortSignal) => Promise<import('./api-types.mjs').GridSettings>} settings
 * @property {(gridKey: string, body: {isTracked: boolean}) => Promise<import('./api-types.mjs').GridSettings>} saveSettings
 * @property {(gridKey: string, planId: string | number, cpuKey: string) => Promise<null>} submitPlan
 * @property {(gridKey: string, planId: string | number) => Promise<null>} deletePlan
 * @property {() => Promise<null>} logout
 */
export class ApiError extends Error {
    /** @param {string} status @param {number} httpStatus @param {unknown} [data] */
    constructor(status, httpStatus, data = null) {
        super(status);
        this.status = status;
        this.httpStatus = httpStatus;
        this.data = data;
    }
}

/**
 * One transport for the application. Authentication remains in the server's HttpOnly cookie.
 * @param {URL} base
 * @param {() => void} onUnauthorized
 * @returns {Api}
 */
export function createApi(base, onUnauthorized) {
    /**
     * @template T
     * @param {string} path
     * @param {{method?: string, signal?: AbortSignal, body?: {itemKey: string, quantity: number} | {cpuKey: string} | {isTracked: boolean}}} [options]
     * @returns {Promise<T>}
     */
    async function request(path, { method = 'GET', signal, body } = {}) {
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
        /** @type {{status: string, data: T}} */
        let envelope;
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
