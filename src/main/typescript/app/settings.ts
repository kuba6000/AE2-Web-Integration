import type { AccessSources, GridSettings } from './api-types.js';
import type { Api, ApiFailure } from './api.js';
import type { Route } from './router.js';

type Edited = { name: boolean; isTracked: boolean };
export type SettingsState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    current: GridSettings | null;
    draft: GridSettings;
    edited: Edited;
    sources: AccessSources;
    saving: boolean;
    uncertain: boolean;
    error: string | null | undefined;
    notice: string | null;
};
type SaveOperation = {
    pending: boolean;
    desired: Partial<GridSettings>;
    uncertain: boolean;
    result?: GridSettings;
    error?: string;
};
export function networkNameError(name: string) {
    const controls = [...name].some((character) => {
        const code = character.charCodeAt(0);
        return code <= 31 || (code >= 127 && code <= 159);
    });
    return controls || name.trim().length > 128 ? 'networkNameInvalid' : null;
}

/** Field drafts and in-flight saves belong to their source grid across route changes. */
export function createGridSettings(
    api: Api,
    changed: () => void,
    updated: (gridKey: string, settings: GridSettings) => void
) {
    const empty = (): GridSettings => ({ name: '', isTracked: false });
    const unedited = (): Edited => ({ name: false, isTracked: false });
    const state: SettingsState = {
        status: 'idle',
        current: null,
        draft: empty(),
        edited: unedited(),
        sources: {},
        saving: false,
        uncertain: false,
        error: null,
        notice: null
    };
    const saves = new Map<string, SaveOperation>();
    const drafts = new Map<string, { draft: GridSettings; edited: Edited }>();
    let route: Route | { view?: undefined; gridKey?: undefined } = {};
    let generation = 0;
    let request: AbortController | undefined;
    let reading = false;
    let disposed = false;
    let sourcesForRoute: AccessSources = {};

    function clear(error: string | null | undefined) {
        if (route.gridKey) drafts.delete(route.gridKey);
        sourcesForRoute = {};
        Object.assign(state, {
            status: 'error',
            current: null,
            draft: empty(),
            edited: unedited(),
            sources: {},
            error,
            notice: null
        });
    }
    function readFailure(error: string | null) {
        sourcesForRoute = {};
        if (['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(error ?? '')) clear(error);
        else {
            state.status = 'error';
            state.sources = {};
            state.error = error;
        }
    }
    return {
        state,
        route(next: Route) {
            if (route.view === 'settings' && state.current && (state.edited.name || state.edited.isTracked))
                drafts.set(route.gridKey, { draft: { ...state.draft }, edited: { ...state.edited } });
            generation++;
            request?.abort();
            reading = false;
            route = next;
            sourcesForRoute = {};
            const previous = next.gridKey ? saves.get(next.gridKey) : undefined;
            const draft = next.gridKey ? drafts.get(next.gridKey) : undefined;
            Object.assign(state, {
                status: next.view === 'settings' ? 'loading' : 'idle',
                current: null,
                draft: draft ? { ...draft.draft } : { ...empty(), ...(previous?.uncertain ? previous.desired : {}) },
                edited: draft
                    ? { ...draft.edited }
                    : {
                          name: !!previous?.uncertain && previous.desired.name !== undefined,
                          isTracked: !!previous?.uncertain && previous.desired.isTracked !== undefined
                      },
                sources: {},
                saving: !!previous?.pending,
                uncertain: !!previous?.uncertain,
                error: null,
                notice: null
            });
        },
        async refresh(sources: AccessSources) {
            if (route.view !== 'settings') return;
            sourcesForRoute = sources;
            if (reading || state.saving) return;
            const version = generation;
            request = new AbortController();
            reading = true;
            try {
                const data = await api.settings(route.gridKey, request.signal);
                if (version !== generation) return;
                state.current = data;
                if (!state.edited.name) state.draft.name = data.name;
                if (!state.edited.isTracked) state.draft.isTracked = data.isTracked;
                state.sources = sourcesForRoute;
                state.status = 'ready';
                state.error = null;
                updated(route.gridKey, data);
            } catch (caught) {
                const error = caught as ApiFailure;
                if (version !== generation || error.name === 'AbortError') return;
                readFailure(error.status || 'NETWORK_ERROR');
            } finally {
                if (version === generation) {
                    reading = false;
                    changed();
                }
            }
        },
        edit<K extends keyof GridSettings>(field: K, value: GridSettings[K]) {
            if (state.status !== 'ready' || state.saving) return;
            state.draft[field] = value;
            state.edited[field] = true;
            state.notice = null;
            changed();
        },
        async save() {
            if (route.view !== 'settings' || state.saving || state.status !== 'ready' || !state.current) return;
            const invalidName = networkNameError(state.draft.name);
            if (invalidName) {
                state.error = invalidName;
                changed();
                return;
            }
            const key = route.gridKey;
            const uncertain = state.uncertain ? saves.get(key)?.desired : undefined;
            const desired: Partial<GridSettings> = {};
            if (state.draft.isTracked !== state.current.isTracked || uncertain?.isTracked !== undefined)
                desired.isTracked = state.draft.isTracked;
            if (state.draft.name.trim() !== state.current.name || uncertain?.name !== undefined)
                desired.name = state.draft.name.trim();
            if (!Object.keys(desired).length) return;
            const version = ++generation;
            const operation: SaveOperation = { pending: true, desired, uncertain: state.uncertain };
            saves.set(key, operation);
            request?.abort();
            reading = false;
            state.saving = true;
            state.error = null;
            state.notice = null;
            changed();
            try {
                operation.result = await api.saveSettings(key, desired);
                operation.uncertain = false;
            } catch (caught) {
                const error = caught as ApiFailure;
                operation.error = error.status || 'NETWORK_ERROR';
                if (['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT', 'INTERNAL_ERROR'].includes(operation.error))
                    operation.uncertain = true;
            }
            operation.pending = false;
            if (disposed) return;
            if (operation.result) {
                drafts.delete(key);
                updated(key, operation.result);
            }
            if (route.view !== 'settings' || route.gridKey !== key) return;
            state.saving = false;
            state.uncertain = operation.uncertain;
            // A route refresh can revoke access while the save awaits its response.
            if (version === generation || (state.status as SettingsState['status']) !== 'error') {
                if (operation.result)
                    Object.assign(state, {
                        status: 'ready',
                        current: operation.result,
                        draft: { ...operation.result },
                        edited: unedited(),
                        sources: sourcesForRoute,
                        notice: 'settingsSaved'
                    });
                else if (['NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(operation.error ?? '')) clear(operation.error);
                else {
                    state.error = operation.error;
                    Object.assign(state.draft, operation.desired);
                    if (operation.desired.name !== undefined) state.edited.name = true;
                    if (operation.desired.isTracked !== undefined) state.edited.isTracked = true;
                }
            }
            changed();
        },
        block(error: string | null) {
            generation++;
            request?.abort();
            reading = false;
            readFailure(error);
            changed();
        },
        dispose() {
            disposed = true;
            generation++;
            request?.abort();
            saves.clear();
            drafts.clear();
        }
    };
}
