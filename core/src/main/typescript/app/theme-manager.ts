import type { Api } from './api.js';
import type { ThemeDefinition } from './theme-api.js';
import { createSettings } from './storage.js';
import { validateThemeManifest, validateThemePath, type ThemeManifest } from './theme-manifest.js';
export type ThemeChoice = ThemeManifest & { key: string; source: 'builtin' | 'server' };
export type ThemeSelectionState = {
    choices: readonly ThemeChoice[];
    selected: string | null;
    active: string;
    serverDefault: string;
    busy: boolean;
    error: string | null;
    warning: string | null;
    safe: boolean;
};
export type ThemeAssets = { url(path: string): string };
type Presentation = { definition: ThemeDefinition; assets: ThemeAssets; release(): void };

/** Selection changes only presentation; the application and its pending operations keep living. */
export function createThemeManager(
    base: URL,
    builtin: ThemeDefinition,
    api: Api,
    modVersion: string | null,
    show: (definition: ThemeDefinition, assets: ThemeAssets, failed: (error: unknown) => void) => void,
    builtinAssetBase = new URL('assets/web/themes/default/', base)
) {
    const settings = createSettings(base, 'theme-selection');
    const listeners = new Set<() => void>();
    const builtinChoice: ThemeChoice = {
        id: 'default',
        name: builtin.name,
        version: modVersion ?? '',
        supportedModVersion: builtin.supportedModVersion,
        views: [...builtin.views],
        entry: '',
        styles: [],
        key: 'builtin:default',
        source: 'builtin'
    };
    const saved = settings.get('selected');
    const state: ThemeSelectionState = {
        choices: [builtinChoice],
        selected: typeof saved === 'string' ? saved : null,
        active: 'builtin:default',
        serverDefault: 'builtin:default',
        busy: false,
        error: null,
        warning: null,
        safe: new URL(location.href).searchParams.get('safe') === '1'
    };
    let current: Presentation | null = null;
    let disposed = false;
    let generation = 0;
    const controller = new AbortController();
    function notify() {
        if (!disposed) for (const listener of listeners) listener();
    }
    const message = (error: unknown) => (error instanceof Error ? error.message : 'Theme could not be loaded.');
    const builtinAssets = {
        url: (path: string) => new URL(validateThemePath(path), builtinAssetBase).href
    };
    function display(presentation: Presentation, key: string, token: number) {
        const old = current;
        current = presentation;
        state.active = key;
        // Unmount the previous presentation before releasing its stylesheets.
        show(presentation.definition, presentation.assets, (error) => {
            queueMicrotask(() => {
                if (disposed || token !== generation) return;
                state.error = message(error);
                if (key !== 'builtin:default') fallback(token);
                notify();
            });
        });
        old?.release();
    }
    function fallback(token: number) {
        display({ definition: builtin, assets: builtinAssets, release() {} }, 'builtin:default', token);
    }
    async function load(choice: ThemeChoice, token: number): Promise<Presentation> {
        if (choice.source === 'builtin') return { definition: builtin, assets: builtinAssets, release() {} };
        const styles: HTMLLinkElement[] = [];
        const pending = new Set<() => void>();
        const release = () => {
            for (const cancel of pending) cancel();
            for (const style of styles) style.remove();
        };
        const assets: ThemeAssets = {
            url: (path) => new URL(`themes/${choice.id}/${validateThemePath(path)}`, base).href
        };
        try {
            await Promise.all(
                choice.styles.map(
                    (path) =>
                        new Promise<void>((resolve, reject) => {
                            const style = document.createElement('link');
                            style.rel = 'stylesheet';
                            style.href = assets.url(path);
                            // Fetch with its real base URL without applying the next theme to the current view.
                            style.media = 'not all';
                            styles.push(style);
                            const finish = (error?: Error) => {
                                style.onload = null;
                                style.onerror = null;
                                controller.signal.removeEventListener('abort', abort);
                                pending.delete(abort);
                                if (error) reject(error);
                                else resolve();
                            };
                            const abort = () => finish(new Error('Theme activation cancelled.'));
                            pending.add(abort);
                            style.onload = () => finish();
                            style.onerror = () => finish(new Error('Theme stylesheet is unavailable.'));
                            controller.signal.addEventListener('abort', abort, { once: true });
                            if (controller.signal.aborted) abort();
                            else document.head.append(style);
                        })
                )
            );
            if (disposed || token !== generation) throw new Error('Theme activation cancelled.');
            const module: { default?: ThemeDefinition } = await import(assets.url(choice.entry));
            if (!module.default || typeof module.default.mount !== 'function' || module.default.id !== choice.id)
                throw new Error('This theme could not be started. Choose another theme.');
            const definition: ThemeDefinition = {
                ...choice,
                mount(root, application, context) {
                    for (const style of styles) {
                        style.media = 'all';
                        context.lifetime.onCleanup(() => style.remove());
                    }
                    module.default!.mount(root, application, context);
                }
            };
            return { definition, assets, release };
        } catch (error) {
            release();
            throw error;
        }
    }
    function compatibility(choice: ThemeChoice) {
        const line = /^(\d+)\.(\d+)\./.exec(modVersion ?? '');
        if (!line || choice.source === 'builtin') return null;
        return choice.supportedModVersion === `${line[1]}.${line[2]}.x`
            ? null
            : `This theme supports version ${choice.supportedModVersion}; the server uses ${modVersion}. Some views may be unavailable.`;
    }
    async function apply(key: string | null, persist: boolean) {
        if (disposed) return;
        const token = ++generation;
        state.busy = true;
        state.error = null;
        state.warning = null;
        notify();
        try {
            let target = state.safe ? 'builtin:default' : (key ?? state.serverDefault);
            let choice = state.choices.find((item) => item.key === target);
            if (!choice) {
                state.error = 'Selected theme is no longer available. Using the server default.';
                target = state.serverDefault;
                choice = state.choices.find((item) => item.key === target) ?? builtinChoice;
            }
            const presentation = await load(choice, token);
            if (disposed || token !== generation) {
                presentation.release();
                return;
            }
            display(presentation, choice.key, token);
            state.warning = compatibility(choice);
            if (persist) {
                state.selected = key;
                if (!settings.set('selected', key))
                    state.error = 'Theme applied, but your browser could not save this choice.';
            }
        } catch (error) {
            if (disposed || token !== generation) return;
            state.error = message(error);
            fallback(token);
        } finally {
            if (!disposed && token === generation) {
                state.busy = false;
                notify();
            }
        }
    }
    return {
        get state(): Readonly<ThemeSelectionState> {
            return state;
        },
        subscribe(listener: () => void) {
            listeners.add(listener);
            listener();
            return () => {
                listeners.delete(listener);
            };
        },
        async start() {
            state.busy = !state.safe;
            if (state.safe) fallback(++generation);
            notify();
            const errors: string[] = [];
            try {
                const catalog = await api.themes(controller.signal);
                if (disposed) return;
                const manifests = catalog.themes.map(validateThemeManifest);
                state.choices = [
                    builtinChoice,
                    ...manifests.map((manifest) => ({
                        ...manifest,
                        key: `server:${manifest.id}`,
                        source: 'server' as const
                    }))
                ];
                state.serverDefault = state.choices.some((item) => item.key === catalog.defaultTheme)
                    ? catalog.defaultTheme
                    : 'builtin:default';
            } catch (error) {
                errors.push(message(error));
            }
            if (disposed) return;
            if (!state.safe) await apply(state.selected, false);
            else notify();
            if (errors.length && !state.error) {
                state.error = errors.join(' ');
                notify();
            }
        },
        async select(key: string | null) {
            if (state.busy || disposed) return;
            if (state.safe && key !== 'builtin:default' && key !== null) {
                state.error = 'Exit safe mode before loading another theme.';
                notify();
                return;
            }
            await apply(key, true);
        },
        dispose() {
            disposed = true;
            generation++;
            controller.abort();
            listeners.clear();
            current?.release();
        }
    };
}
export type ThemeManager = ReturnType<typeof createThemeManager>;
