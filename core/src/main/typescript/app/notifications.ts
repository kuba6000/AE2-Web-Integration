export type Notification = {
    id: number;
    kind: 'success' | 'error' | 'warning';
    message: string;
    subject?: string;
    quantity?: number;
};
export type Notify = (notification: Omit<Notification, 'id'>) => void;

/** Ephemeral action outcomes survive navigation; themes own their presentation. */
export function createNotifications() {
    type Entry = {
        value: Notification;
        remaining: number;
        started: number;
        paused: boolean;
        timer?: ReturnType<typeof setTimeout>;
    };
    const entries: Entry[] = [];
    const listeners = new Set<(items: readonly Notification[]) => void>();
    let sequence = 0;
    let disposed = false;
    const emit = () => {
        const items = entries.map((entry) => entry.value);
        for (const listener of listeners) listener(items);
    };
    function dismiss(id: number) {
        const index = entries.findIndex((entry) => entry.value.id === id);
        if (index < 0) return;
        clearTimeout(entries[index].timer);
        entries.splice(index, 1);
        emit();
    }
    function stop(entry: Entry) {
        if (entry.timer === undefined) return;
        clearTimeout(entry.timer);
        entry.timer = undefined;
        entry.remaining = Math.max(0, entry.remaining - (Date.now() - entry.started));
    }
    function start(entry: Entry) {
        if (entry.timer !== undefined || entry.paused || document.hidden) return;
        entry.started = Date.now();
        entry.timer = setTimeout(() => dismiss(entry.value.id), entry.remaining);
    }
    function visibility() {
        for (const entry of entries) {
            if (document.hidden) stop(entry);
            else start(entry);
        }
    }
    document.addEventListener('visibilitychange', visibility);
    return {
        publish: ((value) => {
            if (disposed) return;
            if (
                value.kind !== 'success' &&
                entries.some(
                    (entry) =>
                        entry.value.message === value.message &&
                        entry.value.subject === value.subject &&
                        entry.value.quantity === value.quantity &&
                        entry.value.kind === value.kind
                )
            )
                return;
            if (entries.length === 3) dismiss(entries[0].value.id);
            const entry: Entry = {
                value: { ...value, id: ++sequence },
                remaining: value.kind === 'success' ? 4500 : 9000,
                started: 0,
                paused: false
            };
            entries.push(entry);
            start(entry);
            emit();
        }) satisfies Notify,
        dismiss,
        pause(id: number, paused: boolean) {
            const entry = entries.find((item) => item.value.id === id);
            if (!entry || entry.paused === paused) return;
            entry.paused = paused;
            if (paused) stop(entry);
            else start(entry);
        },
        subscribe(listener: (items: readonly Notification[]) => void) {
            listeners.add(listener);
            listener(entries.map((entry) => entry.value));
            return () => listeners.delete(listener);
        },
        dispose() {
            disposed = true;
            for (const entry of entries) clearTimeout(entry.timer);
            entries.length = 0;
            listeners.clear();
            document.removeEventListener('visibilitychange', visibility);
        }
    };
}
