export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

/** JSON settings scoped to one deployment and consumer. */
export function createSettings(base: URL, scope: string) {
    const storageKey = `ae2web:${base.pathname}:${scope}`;
    let saved: Record<string, JsonValue> = {};
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
            const value = JSON.parse(raw ?? '{}') as JsonValue;
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
        get(key: string, fallback: JsonValue = null): JsonValue {
            const values = read();
            return Object.hasOwn(values, key) ? values[key] : fallback;
        },
        /** Returns whether the value was persisted. */
        set(key: string, value: JsonValue): boolean {
            saved = { ...read(), [key]: value };
            return write();
        },
        remove(key: string) {
            read();
            if (!Object.hasOwn(saved, key)) return;
            delete saved[key];
            write();
        }
    };
}
