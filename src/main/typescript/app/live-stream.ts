import type { IconMetadata, IconReference, LiveDelta, TerminalView } from './api-types.js';
import type { EventHandlers } from './api.js';

export type LiveStreamState = { status: 'connecting'; lastEventAt: null } | { status: 'live'; lastEventAt: number };

/** Accepted delta content. `rows` is present only when keyed or unkeyed rows changed. */
export type LiveChange<Row> = {
    rows: { all: Row[]; keyed: ReadonlyMap<string, Row> } | null;
    icons: IconMetadata | null;
};

/** The owner keeps the rows and decides how an update changes one; the stream only keeps them current. */
export type LiveStreamHost<Row, Update, Delta> = {
    /** Whether live updates are wanted at all. */
    enabled: () => boolean;
    rows: () => Row[];
    icons: () => IconMetadata | null;
    rowKey: (row: Row) => string | null;
    updateKey: (update: Update) => string;
    merge: (row: Row, update: Update) => Row;
    /** Receives every accepted newer delta before its rows, for sections beyond the keyed list. */
    section?: (delta: Delta) => void;
    /** Mirrors the connection state; `null` once the stream is stopped. */
    status: (next: LiveStreamState | null) => void;
    apply: (change: LiveChange<Row>) => void;
    /** An open connection failed; `confirmed` tells whether it had ever delivered an event. */
    failed: (confirmed: boolean) => void;
    changed: () => void;
    /** The stream is stopped and the owner must take a fresh snapshot. */
    resync: () => void;
};

const reconnectDelayMillis = 2000;

/** One live subscription of a keyed list: events, icon pages, revision checks and keyed apply. */
export function createLiveStream<
    Row extends { icon: IconReference | null },
    Update,
    Delta extends LiveDelta<Row, Update>
>(host: LiveStreamHost<Row, Update, Delta>) {
    let view: TerminalView | undefined;
    let subscription: string | undefined;
    let close: (() => void) | undefined;
    let reconnect: ReturnType<typeof setTimeout> | undefined;
    let connection = 0;

    function stop() {
        connection++;
        host.status(null);
        close?.();
        close = undefined;
        clearTimeout(reconnect);
        reconnect = undefined;
        view = undefined;
        subscription = undefined;
    }
    function resync() {
        stop();
        host.resync();
    }
    /** Opens the stream of a snapshot's subscription while `scope` still holds for the snapshot's owner. */
    function start(
        snapshot: { view?: TerminalView; subscription?: string },
        open: (subscription: string, handlers: EventHandlers<Delta>) => () => void,
        scope: () => boolean
    ) {
        view = snapshot.view;
        subscription = snapshot.subscription;
        if (!view || !subscription || !host.enabled() || !scope()) return;
        const id = ++connection;
        close?.();
        host.status({ status: 'connecting', lastEventAt: null });
        let confirmed = false;
        const current = () => scope() && id === connection && host.enabled();
        close = open(subscription, {
            ready() {
                if (!current() || !view) return;
                confirmed = true;
                host.status({ status: 'live', lastEventAt: Date.now() });
                host.changed();
            },
            delta(delta) {
                if (!current() || !view) return;
                if (delta.view.generation !== view.generation) {
                    resync();
                    return;
                }
                confirmed = true;
                host.status({ status: 'live', lastEventAt: Date.now() });
                if (BigInt(delta.view.revision) <= BigInt(view.revision)) {
                    host.changed();
                    return;
                }
                const previous = host.icons();
                if (previous && delta.icons && previous.packId !== delta.icons.packId) {
                    resync();
                    return;
                }
                const pages = [...(previous?.pages ?? [])];
                const indices = (delta.icons?.pages ?? []).map((page) => {
                    let index = pages.findIndex((existing) => existing.digest === page.digest);
                    if (index < 0) {
                        index = pages.length;
                        pages.push(page);
                    }
                    return index;
                });
                const remap = (row: Row): Row => ({
                    ...row,
                    icon: row.icon && delta.icons ? { ...row.icon, page: indices[row.icon.page] } : null
                });
                host.section?.(delta);
                let rows: LiveChange<Row>['rows'] = null;
                if (delta.definitions.length || delta.updates.length || delta.removed.length || delta.unkeyed) {
                    const existing = host.rows();
                    const keyed = new Map<string, Row>();
                    for (const row of existing) {
                        const key = host.rowKey(row);
                        if (key) keyed.set(key, row);
                    }
                    for (const removed of delta.removed) keyed.delete(removed);
                    for (const row of delta.definitions) {
                        const key = host.rowKey(row);
                        if (key) keyed.set(key, remap(row));
                    }
                    for (const update of delta.updates) {
                        const key = host.updateKey(update);
                        const row = keyed.get(key);
                        if (!row) {
                            resync();
                            return;
                        }
                        keyed.set(key, host.merge(row, update));
                    }
                    const unkeyed = delta.unkeyed?.map(remap) ?? existing.filter((row) => !host.rowKey(row));
                    rows = { all: [...keyed.values(), ...unkeyed], keyed };
                }
                host.apply({ rows, icons: delta.icons ? { ...delta.icons, pages } : null });
                view = delta.view;
                host.changed();
            },
            reset() {
                if (current()) resync();
            },
            error() {
                if (!current()) return;
                host.failed(confirmed);
                stop();
                host.status({ status: 'connecting', lastEventAt: null });
                host.changed();
                reconnect = setTimeout(() => {
                    reconnect = undefined;
                    if (scope()) host.resync();
                }, reconnectDelayMillis);
            }
        });
    }
    return {
        start,
        stop,
        /** The latest snapshot came with a subscription to follow. */
        get active() {
            return !!view && !!subscription;
        },
        /** A reconnect attempt is pending after a connection failure. */
        get reconnecting() {
            return reconnect !== undefined;
        }
    };
}
