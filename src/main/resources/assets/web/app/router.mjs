/**
 * @typedef {{view: 'home' | 'missing' | 'web-settings' | 'server-settings' | 'about', gridKey: null}
 * | {view: 'items' | 'settings', gridKey: string}
 * | {view: 'cpus', gridKey: string, cpuKey: string | null}
 * | {view: 'history', gridKey: string, entryId: string | null}
 * | {view: 'plan', gridKey: string, planId: string}} Route
 */

/** @returns {Route} */
export function readRoute() {
    const hash = location.hash.replace(/^#/, '');
    if (!hash || hash === '/' || hash === '/home') return { view: 'home', gridKey: null };
    if (hash === '/web-settings') return { view: 'web-settings', gridKey: null };
    if (hash === '/server-settings') return { view: 'server-settings', gridKey: null };
    if (hash === '/about') return { view: 'about', gridKey: null };
    const settings = /^\/grids\/([^/]+)\/settings$/.exec(hash);
    if (settings) {
        try {
            return { view: 'settings', gridKey: decodeURIComponent(settings[1]) };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const history = /^\/grids\/([^/]+)\/history(?:\/(\d+))?$/.exec(hash);
    if (history) {
        try {
            return { view: 'history', gridKey: decodeURIComponent(history[1]), entryId: history[2] ?? null };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const cpu = /^\/grids\/([^/]+)\/cpus(?:\/([^/]+))?$/.exec(hash);
    if (cpu) {
        try {
            return {
                view: 'cpus',
                gridKey: decodeURIComponent(cpu[1]),
                cpuKey: cpu[2] ? decodeURIComponent(cpu[2]) : null
            };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const plan = /^\/grids\/([^/]+)\/plans\/([1-9]\d*)$/.exec(hash);
    if (plan) {
        try {
            return { view: 'plan', gridKey: decodeURIComponent(plan[1]), planId: plan[2] };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const match = /^\/grids\/([^/]+)\/items$/.exec(hash);
    if (match) {
        try {
            return { view: 'items', gridKey: decodeURIComponent(match[1]) };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    return { view: 'missing', gridKey: null };
}

/** @param {string} gridKey @param {string | number} planId */
export function navigateToPlan(gridKey, planId) {
    location.hash = `/grids/${encodeURIComponent(gridKey)}/plans/${planId}`;
}

/** @param {string | null} gridKey @param {string | null} [cpuKey] */
export function cpuHref(gridKey, cpuKey = null) {
    return `#/grids/${encodeURIComponent(String(gridKey))}/cpus${cpuKey ? '/' + encodeURIComponent(cpuKey) : ''}`;
}

/** @param {string | null} gridKey @param {string | number | null} [entryId] */
export function historyHref(gridKey, entryId = null) {
    return `#/grids/${encodeURIComponent(String(gridKey))}/history${entryId === null ? '' : '/' + entryId}`;
}

/** @param {string | null} key */
export function navigateToGrid(key) {
    location.hash = key ? `/grids/${encodeURIComponent(key)}/items` : '/';
}
