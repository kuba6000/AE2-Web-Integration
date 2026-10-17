import type { ThemeApplication, ThemeContext, ThemeDefinition } from './theme-api.js';
import type { createTerminal } from './terminal.js';
import type { createUpdateNotice } from './update-notice.js';
import * as helpers from './resource-helpers.js';

/** Owns presentation resources without owning the application or its pending requests. */
export function mountTheme(
    root: HTMLElement,
    definition: ThemeDefinition,
    application: ReturnType<typeof createTerminal>,
    context: Omit<ThemeContext, 'lifetime' | 'helpers' | 'updateNotice'> & {
        updateNotice: ReturnType<typeof createUpdateNotice>;
    },
    failed: (error: unknown) => void
) {
    const controller = new AbortController();
    const cleanups = new Set<() => void>();
    let mounting = true;
    let failure: { error: unknown } | undefined;
    const cleanup = (dispose: () => void) => {
        try {
            dispose();
        } catch (error) {
            console.error('Theme cleanup failed', error);
        }
    };
    function dispose() {
        if (controller.signal.aborted) return;
        controller.abort();
        for (const release of [...cleanups].reverse()) cleanup(release);
        cleanups.clear();
        root.replaceChildren();
    }
    function fail(error: unknown) {
        if (controller.signal.aborted || failure) return;
        failure = { error };
        if (!mounting) {
            dispose();
            failed(error);
        }
    }
    function onCleanup(release: () => void) {
        if (controller.signal.aborted) cleanup(release);
        else cleanups.add(release);
    }
    function subscribe<T>(source: (listener: (value: T) => void) => () => void, listener: (value: T) => void) {
        const unsubscribe = source((value) => {
            if (controller.signal.aborted || failure) return;
            try {
                listener(value);
            } catch (error) {
                fail(error);
            }
        });
        onCleanup(unsubscribe);
        return () => {
            cleanups.delete(unsubscribe);
            unsubscribe();
        };
    }
    const { crafting, cpus, settings } = application;
    const actions: ThemeApplication = {
        notifications: {
            subscribe: (listener) => subscribe(application.notifications.subscribe, listener),
            dismiss: application.notifications.dismiss,
            pause: application.notifications.pause
        },
        get state() {
            return application.state;
        },
        subscribe: (listener) => subscribe(application.subscribe, listener),
        refresh: application.refresh,
        search: application.search,
        select: application.select,
        resourceHistory: { range: application.resourceHistory.range },
        preference: application.preference,
        displayIcons: application.displayIcons,
        crafting: {
            search: crafting.search,
            sort: crafting.sort,
            reverse: crafting.reverse,
            selectCpu: crafting.selectCpu,
            create: crafting.create,
            recalculate: crafting.recalculate,
            submit: () => crafting.submit(),
            remove: crafting.remove
        },
        cpus: { cancel: cpus.cancel, pause: cpus.pause },
        settings: { edit: settings.edit, save: settings.save },
        icons: {
            observe(host, paint) {
                const observer = application.icons.observe(host, (target, sprite) => {
                    if (controller.signal.aborted || failure) return;
                    try {
                        paint(target, sprite);
                    } catch (error) {
                        fail(error);
                    }
                });
                let released = false;
                const release = () => {
                    if (released) return;
                    released = true;
                    cleanups.delete(release);
                    observer.dispose();
                };
                onCleanup(release);
                return { update: observer.update, dispose: release };
            }
        }
    };
    const notice = context.updateNotice;
    const themeContext: ThemeContext = {
        ...context,
        helpers,
        lifetime: { signal: controller.signal, onCleanup },
        updateNotice: {
            get update() {
                return notice.update;
            },
            get pending() {
                return notice.pending;
            },
            get error() {
                return notice.error;
            },
            get visible() {
                return notice.visible;
            },
            dismiss: notice.dismiss,
            skip: notice.skip,
            subscribe: (listener) => subscribe<void>((callback) => notice.subscribe(() => callback()), listener)
        }
    };
    try {
        definition.mount(root, actions, themeContext);
    } catch (error) {
        fail(error);
    }
    mounting = false;
    if (failure) {
        dispose();
        failed(failure.error);
    }
    return dispose;
}
