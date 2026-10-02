import { createApi } from './api.js';
import { createIconLoader } from './icons.js';
import { createPreferences } from './preferences.js';
import { createTerminal } from './terminal.js';
import { readRoute } from './router.js';
import { createThemeContext } from './theme-context.js';
import { mount } from '../themes/default/view.js';

const identity = document.getElementById('ae2-user') as HTMLScriptElement;
const user: { username: string; isAdmin: boolean } = JSON.parse(identity.text);
const version = document.getElementById('ae2-mod-version') as HTMLScriptElement;
const modVersion: string | null = JSON.parse(version.text);
const base = new URL('./', location.href);
let leaving = false;
function returnToLogin() {
    if (leaving) return;
    leaving = true;
    application?.dispose();
    const target = new URL('?ui=next', base);
    target.hash = location.hash;
    // Replacing the same hash URL can stay in this document, even after the session expires.
    if (target.href === location.href) location.reload();
    else location.replace(target.href);
}
const api = createApi(base, returnToLogin);
const application = createTerminal(
    api,
    createPreferences(base),
    createIconLoader(base, returnToLogin, () => application.refresh())
);
const unmount = mount(document.getElementById('app') as HTMLElement, application, {
    ...createThemeContext(base, 'default'),
    base,
    user,
    modVersion,
    async logout() {
        await api.logout();
        returnToLogin();
    }
});
const route = () => application.route(readRoute());
window.addEventListener('hashchange', route);
window.addEventListener(
    'pagehide',
    () => {
        application.dispose();
        unmount();
        window.removeEventListener('hashchange', route);
    },
    { once: true }
);
// A restored bfcache page has disposed subscriptions; obtain a fresh authenticated document.
window.addEventListener('pageshow', (event) => {
    if (event.persisted) location.reload();
});
route();
application.refresh();
