import { createApi } from './api.mjs';
import { createPreferences } from './preferences.mjs';
import { createTerminal } from './terminal.mjs';
import { readRoute } from './router.mjs';
import { mount } from '../themes/default/view.mjs';

const base = new URL('./', location.href);
let application;
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
application = createTerminal(api, createPreferences(base));
const unmount = mount(document.getElementById('app'), application, {
    base,
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
