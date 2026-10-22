import type { Api, ApiFailure } from './api.js';
import type { Route } from './router.js';
import type { SettingRange } from './api-types.js';

/** Editable configuration; listener and login access settings remain file-only. */
export type ServerConfiguration = {
    general: {
        trustedProxies: string;
        maxRequestsBeforeLoggedInPerMinute: number;
        checkForUpdates: boolean;
        defaultTheme: string;
    };
    notifications: { minimumCraftingDurationSeconds: number; minimumCraftingAmount: number; fullDomain: string };
    discord: { roleId: string };
    ntfy: { host: string; topic: string; user: string };
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
export type ServerSettingsReadOnly = {
    port: number;
    publicMode: boolean;
    allowNoPasswordOnLocalhost: boolean;
    adminPasswordConfigured: boolean;
    discordWebhookConfigured: boolean;
    ntfyPasswordConfigured: boolean;
};
export type ServerSecretName = 'discordWebhook' | 'ntfyPassword';
export type ServerSecretChanges = Partial<Record<ServerSecretName, string>>;
export type ServerSettingsUpdate = { revision: string; settings: ServerConfiguration; secrets?: ServerSecretChanges };
export type ServerSettingsResponse = {
    revision: string;
    settings: ServerConfiguration;
    readOnly: ServerSettingsReadOnly;
};
export type ServerSettingsState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    current: ServerConfiguration | null;
    draft: ServerConfiguration | null;
    readOnly: ServerSettingsReadOnly | null;
    secretChanges: ServerSecretChanges;
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
        readOnly: null,
        secretChanges: {},
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
        Object.assign(state, {
            status: 'error',
            error: 'NO_PERMISSIONS',
            current: null,
            draft: null,
            readOnly: null,
            secretChanges: {},
            dirty: false
        });
        revision = null;
    }
    function edited() {
        state.dirty =
            Object.keys(state.secretChanges).length > 0 ||
            JSON.stringify(state.draft) !== JSON.stringify(state.current);
        state.saved = false;
        changed();
    }
    function changeSecret(name: ServerSecretName, value: string | undefined) {
        if (disposed || !isAdmin || !state.draft || state.saving || state.status !== 'ready') return;
        if (value === undefined) delete state.secretChanges[name];
        else state.secretChanges[name] = value;
        edited();
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
                    readOnly: result.readOnly,
                    draft: structuredClone(result.settings),
                    secretChanges: {},
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
            edited();
        },
        editSecret: (name: ServerSecretName, value: string) => changeSecret(name, value === '' ? undefined : value),
        removeSecret: (name: ServerSecretName) => changeSecret(name, ''),
        resetSecret: (name: ServerSecretName) => changeSecret(name, undefined),
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
            const body: ServerSettingsUpdate = { revision, settings: structuredClone(state.draft) };
            if (Object.keys(state.secretChanges).length) body.secrets = { ...state.secretChanges };
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
                    readOnly: result.readOnly,
                    draft: structuredClone(result.settings),
                    secretChanges: {},
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
            state.readOnly = null;
            state.secretChanges = {};
        }
    };
}
