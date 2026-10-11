import { createApi } from './api.js';
import { createIconLoader } from './icons.js';
import { createPreferences } from './preferences.js';
import { createTerminal } from './terminal.js';
import { readRoute } from './router.js';
import { createThemeContext } from './theme-context.js';
import { mountTheme } from './theme-host.js';
import type { ThemeDefinition } from './theme-api.js';
import { createUpdateNotice } from './update-notice.js';

export function boot(root: HTMLElement, definition: ThemeDefinition, base = new URL('./', location.href)) {
    const preferences = createPreferences(base);
    const theme = createThemeContext(base, definition.id);
    const locale = theme.i18n.forLanguage(preferences.values.language);
    const controller = new AbortController();
    let application: ReturnType<typeof createTerminal> | undefined;
    let unmount = () => {};
    let leaving = false;
    let disposed = false;
    function returnToLogin() {
        if (leaving || disposed) return;
        leaving = true;
        application?.dispose();
        const target = new URL(base);
        target.hash = location.hash;
        // Replacing the same hash URL can stay in this document, even after the session expires.
        if (target.href === location.href) location.reload();
        else location.replace(target.href);
    }
    const api = createApi(base, returnToLogin);
    const route = () => application?.route(readRoute());
    function dispose() {
        disposed = true;
        controller.abort();
        application?.dispose();
        unmount();
        window.removeEventListener('hashchange', route);
        window.removeEventListener('pagehide', hide);
        window.removeEventListener('pageshow', restore);
    }
    function hide(event: PageTransitionEvent) {
        dispose();
        // A cached document is disposed too, but must still react when the browser restores it.
        if (event.persisted) window.addEventListener('pageshow', restore, { once: true });
    }
    window.addEventListener('pagehide', hide, { once: true });
    // A restored bfcache page has disposed subscriptions; obtain a fresh authenticated document.
    function restore(event: PageTransitionEvent) {
        if (event.persisted) location.reload();
    }
    window.addEventListener('pageshow', restore);

    async function start() {
        const status = document.createElement('p');
        status.setAttribute('role', 'status');
        status.textContent = locale.common('bootstrapLoading');
        root.replaceChildren(status);
        try {
            const metadata = await api.context(controller.signal);
            if (disposed || leaving) return;
            if (!metadata.user) {
                returnToLogin();
                return;
            }
            application = createTerminal(
                api,
                preferences,
                createIconLoader(base, returnToLogin, async () => {
                    await application?.refresh();
                }),
                metadata.capabilities
            );
            const context = {
                ...theme,
                user: metadata.user,
                modVersion: metadata.modVersion,
                updateNotice: createUpdateNotice(
                    base,
                    api,
                    metadata.user.isAdmin && metadata.isOutdated ? metadata.availableUpdate : null
                ),
                async logout() {
                    await api.logout();
                    returnToLogin();
                }
            };
            function renderTheme() {
                unmount();
                unmount = mountTheme(root, definition, application!, context, (error) => {
                    console.error('Theme rendering failed', error);
                    const message = document.createElement('p');
                    message.role = 'alert';
                    message.textContent = locale.common('themeFailed');
                    const retry = document.createElement('button');
                    retry.type = 'button';
                    retry.textContent = locale.common('retry');
                    retry.onclick = renderTheme;
                    root.replaceChildren(message, retry);
                });
            }
            renderTheme();
            window.addEventListener('hashchange', route);
            route();
            application.refresh();
        } catch {
            if (disposed || leaving) return;
            application?.dispose();
            unmount();
            status.setAttribute('role', 'alert');
            status.textContent = locale.common('bootstrapError');
            const retry = document.createElement('button');
            retry.type = 'button';
            retry.textContent = locale.common('retry');
            retry.addEventListener('click', start, { once: true });
            root.replaceChildren(status, retry);
        }
    }
    void start();
    return dispose;
}
