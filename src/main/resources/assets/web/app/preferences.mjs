/**
 * @typedef {object} Preferences
 * @property {'en' | 'pl'} language
 * @property {'light' | 'dark' | 'system'} appearance
 * @property {'name' | 'quantity' | 'id'} sort
 * @property {'all' | 'stored' | 'craftable'} filter
 * @property {boolean} autoRefresh
 */

/** Small browser preferences, isolated from other installations on the same origin.
 * @param {URL} base
 */
export function createPreferences(base) {
    const key = `ae2web:${base.pathname}:ui`;
    /** @type {Preferences} */
    const defaults = {
        language: navigator.language.toLowerCase().startsWith('pl') ? 'pl' : 'en',
        appearance: 'system',
        sort: 'name',
        filter: 'all',
        autoRefresh: true
    };
    const allowed = {
        language: ['en', 'pl'],
        appearance: ['light', 'dark', 'system'],
        sort: ['name', 'quantity', 'id'],
        filter: ['all', 'stored', 'craftable']
    };
    /**
     * @template {keyof Preferences} K
     * @param {K} name
     * @param {unknown} value
     * @returns {value is Preferences[K]}
     */
    function isValid(name, value) {
        if (name === 'autoRefresh') return typeof value === 'boolean';
        const choices = /** @type {readonly unknown[]} */ (allowed[/** @type {keyof typeof allowed} */ (name)]);
        return choices?.includes(value) ?? false;
    }
    /** @type {Record<string, unknown>} */
    let saved = {};
    try {
        saved = JSON.parse(localStorage.getItem(key) ?? 'null') || {};
    } catch {
        // Unavailable storage or invalid saved JSON falls back to the defaults.
    }
    const values = { ...defaults };
    /** @template {keyof Preferences} K @param {K} name */
    function restore(name) {
        const value = saved[name];
        if (isValid(name, value)) values[name] = value;
    }
    for (const name of /** @type {(keyof Preferences)[]} */ (Object.keys(defaults))) restore(name);
    return {
        values,
        /** @template {keyof Preferences} K @param {K} name @param {Preferences[K]} value */
        set(name, value) {
            if (!isValid(name, value)) return;
            values[name] = value;
            try {
                localStorage.setItem(key, JSON.stringify(values));
            } catch {
                // Keep the current session's preference when browser storage is unavailable.
            }
        }
    };
}
