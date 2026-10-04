import { createApi } from './api.js';
import { createIconLoader } from './icons.js';
import { createPreferences } from './preferences.js';
import { createTerminal } from './terminal.js';
import { readRoute } from './router.js';
import { createThemeContext } from './theme-context.js';
import { mount } from '../themes/default/view.js';

const root = document.getElementById('app') as HTMLElement;
const base = new URL('./', location.href);
const preferences = createPreferences(base);
const theme = createThemeContext(base, 'default');
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
    const target = new URL('?ui=next', base);
    target.hash = location.hash;
    // Replacing the same hash URL can stay in this document, even after the session expires.
    if (target.href === location.href) location.reload();
    else location.replace(target.href);
}
const api = createApi(base, returnToLogin);
const route = () => application?.route(readRoute());
window.addEventListener(
    'pagehide',
    () => {
        disposed = true;
        controller.abort();
        application?.dispose();
        unmount();
        window.removeEventListener('hashchange', route);
    },
    { once: true }
);
// A restored bfcache page has disposed subscriptions; obtain a fresh authenticated document.
window.addEventListener('pageshow', (event) => {
    if (event.persisted) location.reload();
});

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
            })
        );
        unmount = mount(root, application, {
            ...theme,
            base,
            user: metadata.user,
            modVersion: metadata.modVersion,
            async logout() {
                await api.logout();
                returnToLogin();
            }
        });
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
