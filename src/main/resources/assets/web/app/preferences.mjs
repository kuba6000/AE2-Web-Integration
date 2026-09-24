import { createSettings } from './storage.mjs';

/**
 * @typedef {object} Preferences
 * @property {string} language
 * @property {'name' | 'quantity' | 'id'} sort
 * @property {'all' | 'stored' | 'craftable'} filter
 * @property {boolean} autoRefresh
 */

/** Small browser preferences, isolated from other installations on the same origin.
 * @param {URL} base
 */
export function createPreferences(base) {
    const settings = createSettings(base, 'ui');
    /** @type {Preferences} */
    const defaults = {
        language: navigator.language.toLowerCase().startsWith('pl') ? 'pl' : 'en',
        sort: 'name',
        filter: 'all',
        autoRefresh: true
    };
    const allowed = {
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
        if (name === 'language') {
            if (typeof value !== 'string') return false;
            try {
                return Intl.getCanonicalLocales(value).length === 1;
            } catch {
                return false;
            }
        }
        const choices = /** @type {readonly unknown[]} */ (allowed[/** @type {keyof typeof allowed} */ (name)]);
        return choices?.includes(value) ?? false;
    }
    const values = { ...defaults };
    /** @template {keyof Preferences} K @param {K} name */
    function restore(name) {
        const value = settings.get(name);
        if (isValid(name, value)) values[name] = value;
    }
    for (const name of /** @type {(keyof Preferences)[]} */ (Object.keys(defaults))) restore(name);
    return {
        values,
        /** @template {keyof Preferences} K @param {K} name @param {Preferences[K]} value */
        set(name, value) {
            if (!isValid(name, value)) return;
            values[name] = value;
            settings.set(name, value);
        }
    };
}
