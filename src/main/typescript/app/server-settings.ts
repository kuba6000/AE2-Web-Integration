import type { Api, ApiFailure } from './api.js';
import type { Route } from './router.js';
import type { SettingRange } from './api-types.js';

/** Editable server configuration. The web listener port and administrator password stay file-only. */
export type ServerConfiguration = {
    general: {
        allowNoPasswordOnLocalhost: boolean;
        trustedProxies: string;
        publicMode: boolean;
        maxRequestsBeforeLoggedInPerMinute: number;
        checkForUpdates: boolean;
        defaultTheme: string;
    };
    notifications: { minimumCraftingDurationSeconds: number; minimumCraftingAmount: number; fullDomain: string };
    discord: { webhook: string; roleId: string };
    ntfy: { host: string; topic: string; user: string; password: string };
    resourceHistory: {
        stockSampleIntervalSeconds: SettingRange;
        flowFlushIntervalSeconds: SettingRange;
        historyRetentionDays: SettingRange;
        historyMaxRowsPerGrid: number;
    };
    craftingHistory: { historyRetentionDays: SettingRange; historyMaxJobsPerGrid: number };
    resourceTracking: {
        activeIntervalMillis: number;
        reconciliationIntervalSeconds: number;
        maxResourcesPerGrid: number;
        maxQueuedRows: number;
    };
};
export type ServerSettingsResponse = { revision: string; settings: ServerConfiguration };
export type ServerSettingsState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    current: ServerConfiguration | null;
    draft: ServerConfiguration | null;
    dirty: boolean;
    saving: boolean;
    error: string | null;
    saved: boolean;
    uncertain: boolean;
};

export function createServerSettings(api: Api, changed: () => void, isAdmin: boolean) {
    const state: ServerSettingsState = {
        status: 'idle',
        current: null,
        draft: null,
        dirty: false,
        saving: false,
        error: null,
        saved: false,
        uncertain: false
    };
    let active = false;
    let disposed = false;
    let generation = 0;
    let revision: string | null = null;
    let request: AbortController | undefined;
    let reading: Promise<void> | undefined;

    function denied() {
        Object.assign(state, { status: 'error', error: 'NO_PERMISSIONS', current: null, draft: null, dirty: false });
        revision = null;
    }
    async function load(discard = false) {
        if (disposed || !active || state.saving) return;
        if (!isAdmin) {
            denied();
            changed();
            return;
        }
        if (reading) return reading;
        if (state.dirty && !discard) return;
        const version = ++generation;
        request = new AbortController();
        state.status = 'loading';
        state.error = null;
        state.saved = false;
        reading = (async () => {
            try {
                const result = await api.serverSettings(request!.signal);
                if (disposed || version !== generation) return;
                revision = result.revision;
                Object.assign(state, {
                    status: 'ready',
                    current: result.settings,
                    draft: structuredClone(result.settings),
                    dirty: false,
                    uncertain: false
                });
            } catch (caught) {
                const error = caught as ApiFailure;
                if (disposed || version !== generation || error.name === 'AbortError') return;
                if (['NO_PERMISSIONS', 'UNAUTHORIZED'].includes(error.status ?? '')) denied();
                else {
                    state.status = 'error';
                    state.error = error.status || 'NETWORK_ERROR';
                }
            } finally {
                if (version === generation && !disposed) {
                    reading = undefined;
                    changed();
                }
            }
        })();
        changed();
        return reading;
    }
    return {
        state,
        route(route: Route) {
            active = route.view === 'server-settings';
            generation++;
            request?.abort();
            reading = undefined;
            if (!active) {
                if (state.status === 'loading') state.status = state.current ? 'ready' : 'idle';
                return;
            }
            if (!isAdmin) denied();
            else if (!state.current) void load();
        },
        refresh: () => load(),
        reload: () => load(true),
        edit<S extends keyof ServerConfiguration, F extends keyof ServerConfiguration[S]>(
            section: S,
            field: F,
            value: ServerConfiguration[S][F]
        ) {
            if (disposed || !isAdmin || !state.draft || state.saving || state.status !== 'ready') return;
            state.draft[section][field] = value;
            state.dirty = JSON.stringify(state.draft) !== JSON.stringify(state.current);
            state.saved = false;
            changed();
        },
        async save() {
            if (
                disposed ||
                !isAdmin ||
                !active ||
                !revision ||
                !state.draft ||
                state.status !== 'ready' ||
                state.saving ||
                !state.dirty ||
                state.uncertain ||
                state.error === 'CONFIG_CHANGED'
            )
                return;
            const body = { revision, settings: structuredClone(state.draft) };
            state.saving = true;
            state.error = null;
            state.saved = false;
            changed();
            try {
                const result = await api.saveServerSettings(body);
                if (disposed) return;
                revision = result.revision;
                Object.assign(state, {
                    status: 'ready',
                    current: result.settings,
                    draft: structuredClone(result.settings),
                    dirty: false,
                    saved: true,
                    uncertain: false
                });
            } catch (caught) {
                if (disposed) return;
                const error = caught as ApiFailure;
                if (['NO_PERMISSIONS', 'UNAUTHORIZED'].includes(error.status ?? '')) denied();
                else {
                    state.error = error.status || 'NETWORK_ERROR';
                    state.uncertain = ['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT', 'INTERNAL_ERROR'].includes(
                        state.error
                    );
                }
            } finally {
                if (!disposed) {
                    state.saving = false;
                    changed();
                }
            }
        },
        dispose() {
            disposed = true;
            generation++;
            request?.abort();
            state.current = null;
            state.draft = null;
        }
    };
}
