/** Small browser preferences, isolated from other installations on the same origin. */
export function createPreferences(base) {
    const key = `ae2web:${base.pathname}:ui`;
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
    let saved = {};
    try {
        saved = JSON.parse(localStorage.getItem(key)) || {};
    } catch {
        // Unavailable storage or invalid saved JSON falls back to the defaults.
    }
    const values = { ...defaults };
    for (const [name, choices] of Object.entries(allowed)) {
        if (choices.includes(saved[name])) values[name] = saved[name];
    }
    if (typeof saved.autoRefresh === 'boolean') values.autoRefresh = saved.autoRefresh;
    return {
        values,
        set(name, value) {
            if (name === 'autoRefresh' ? typeof value !== 'boolean' : !allowed[name]?.includes(value)) return;
            values[name] = value;
            try {
                localStorage.setItem(key, JSON.stringify(values));
            } catch {
                // Keep the current session's preference when browser storage is unavailable.
            }
        }
    };
}
