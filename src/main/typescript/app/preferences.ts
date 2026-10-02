import { createSettings } from './storage.js';

export type Preferences = {
    language: string;
    sort: 'name' | 'quantity' | 'id';
    filter: 'all' | 'stored' | 'craftable';
    autoRefresh: boolean;
};

/** Small browser preferences, isolated from other installations on the same origin. */
export function createPreferences(base: URL) {
    const settings = createSettings(base, 'ui');
    const defaults: Preferences = {
        language: navigator.language.toLowerCase().startsWith('pl') ? 'pl' : 'en',
        sort: 'name',
        filter: 'all',
        autoRefresh: true
    };
    const allowed = {
        sort: ['name', 'quantity', 'id'],
        filter: ['all', 'stored', 'craftable']
    };
    function isValid<K extends keyof Preferences>(name: K, value: unknown): value is Preferences[K] {
        if (name === 'autoRefresh') return typeof value === 'boolean';
        if (name === 'language') {
            if (typeof value !== 'string') return false;
            try {
                return Intl.getCanonicalLocales(value).length === 1;
            } catch {
                return false;
            }
        }
        const choices = allowed[name as keyof typeof allowed] as readonly unknown[];
        return choices?.includes(value) ?? false;
    }
    const values = { ...defaults };
    function restore<K extends keyof Preferences>(name: K) {
        const value = settings.get(name);
        if (isValid(name, value)) values[name] = value;
    }
    for (const name of Object.keys(defaults) as (keyof Preferences)[]) restore(name);
    return {
        values,
        set<K extends keyof Preferences>(name: K, value: Preferences[K]) {
            if (!isValid(name, value)) return;
            values[name] = value;
            settings.set(name, value);
        }
    };
}
