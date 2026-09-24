/** @typedef {null | boolean | number | string | JsonValue[] | {[key: string]: JsonValue}} JsonValue */

/** JSON settings scoped to one deployment and consumer.
 * @param {URL} base
 * @param {string} scope
 */
export function createSettings(base, scope) {
    const storageKey = `ae2web:${base.pathname}:${scope}`;
    /** @type {Record<string, JsonValue>} */
    let saved = {};
    let available = true;

    function read() {
        if (!available) return saved;
        let raw;
        try {
            raw = localStorage.getItem(storageKey);
        } catch {
            available = false;
            return saved;
        }
        try {
            const value = /** @type {JsonValue} */ (JSON.parse(raw ?? '{}'));
            saved = value !== null && typeof value === 'object' && !Array.isArray(value) ? value : {};
        } catch {
            saved = {};
        }
        return saved;
    }

    function write() {
        if (!available) return false;
        try {
            localStorage.setItem(storageKey, JSON.stringify(saved));
            return true;
        } catch {
            // Retain session edits even when reads still work but writes are blocked.
            available = false;
            return false;
        }
    }

    return {
        /** @param {string} key @param {JsonValue} [fallback] @returns {JsonValue} */
        get(key, fallback = null) {
            const values = read();
            return Object.hasOwn(values, key) ? values[key] : fallback;
        },
        /** @param {string} key @param {JsonValue} value @returns {boolean} Whether the value was persisted. */
        set(key, value) {
            saved = { ...read(), [key]: value };
            return write();
        },
        /** @param {string} key */
        remove(key) {
            read();
            if (!Object.hasOwn(saved, key)) return;
            delete saved[key];
            write();
        }
    };
}
