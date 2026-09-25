import { createApi } from './api.mjs';
import { createPreferences } from './preferences.mjs';
import { createTerminal } from './terminal.mjs';
import { readRoute } from './router.mjs';
import { createThemeContext } from './theme-context.mjs';
import { mount } from '../themes/default/view.mjs';

const identity = /** @type {HTMLScriptElement} */ (document.getElementById('ae2-user'));
/** @type {{username: string, isAdmin: boolean}} */
const user = JSON.parse(identity.text);
const version = /** @type {HTMLScriptElement} */ (document.getElementById('ae2-mod-version'));
/** @type {string | null} */
const modVersion = JSON.parse(version.text);
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
const application = createTerminal(api, createPreferences(base));
const unmount = mount(/** @type {HTMLElement} */ (document.getElementById('app')), application, {
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
