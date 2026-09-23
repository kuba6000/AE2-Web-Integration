export class ApiError extends Error {
    constructor(status, httpStatus) {
        super(status);
        this.status = status;
        this.httpStatus = httpStatus;
    }
}

/** One transport for the application. Authentication remains in the server's HttpOnly cookie. */
export function createApi(base, onUnauthorized) {
    async function request(path, { method = 'GET', signal } = {}) {
        let response;
        try {
            response = await fetch(new URL(path, base), {
                method, signal, credentials: 'same-origin', cache: 'no-store',
                headers: { Accept: 'application/json', ...(method === 'GET' ? {} : { 'X-AE2-Request': 'true' }) }
            });
        } catch (error) {
            if (error.name === 'AbortError') throw error;
            throw new ApiError('NETWORK_ERROR', 0);
        }
        if (response.status === 401) {
            onUnauthorized();
            throw new ApiError('UNAUTHORIZED', 401);
        }
        let envelope;
        try { envelope = await response.json(); }
        catch { throw new ApiError('INVALID_RESPONSE', response.status); }
        if (!response.ok || envelope.status !== 'OK') {
            throw new ApiError(envelope.status || 'INVALID_RESPONSE', response.status);
        }
        return envelope.data;
    }
    return {
        grids: signal => request('api/grids', { signal }),
        items: (gridKey, signal) => request(`api/grids/${encodeURIComponent(gridKey)}/items`, { signal }),
        logout: () => request('api/auth/logout', { method: 'POST' })
    };
}
