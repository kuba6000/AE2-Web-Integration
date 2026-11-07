import type { IconAnimation, IconMetadata, IconReference, LiveDelta, TerminalView } from './api-types.js';
import type { EventHandlers } from './api.js';

export type LiveStreamState = { status: 'connecting'; lastEventAt: null } | { status: 'live'; lastEventAt: number };

/** Accepted delta content. `rows` is present only when keyed or unkeyed rows changed. */
export type LiveChange<Row> = {
    rows: { all: Row[]; keyed: ReadonlyMap<string, Row> } | null;
    icons: IconMetadata | null;
};

/** Maps the delta-local icon page of a value to the page of the owner's merged icon metadata. */
export type IconRemap = <Value extends { icon: IconReference | null }>(value: Value) => Value;

/**
 * The owner keeps the rows; the stream only keeps them current. Rows are keyed by `itemKey` and an update
 * replaces its row's fields with its own.
 */
export type LiveStreamHost<Row, Delta> = {
    /** Whether live updates are wanted at all. */
    enabled: () => boolean;
    rows: () => Row[];
    icons: () => IconMetadata | null;
    /** Receives every accepted newer delta before its rows, for sections beyond the keyed list. */
    section?: (delta: Delta, remap: IconRemap) => void;
    /** Mirrors the connection state; `null` once the stream is stopped. */
    status: (next: LiveStreamState | null) => void;
    apply: (change: LiveChange<Row>) => void;
    /** An open connection failed; `confirmed` tells whether it had ever delivered an event. */
    failed?: (confirmed: boolean) => void;
    changed: () => void;
    /** The stream is stopped and the owner must take a fresh snapshot. */
    resync: () => void;
};

const reconnectDelayMillis = 2000;

/** One live subscription of a keyed list: events, icon pages, revision checks and keyed apply. */
export function createLiveStream<
    Row extends { itemKey: string | null; icon: IconReference | null },
    Update extends { itemKey: string },
    Delta extends LiveDelta<Row, Update>
>(host: LiveStreamHost<Row, Delta>) {
    let view: TerminalView | undefined;
    let subscription: string | undefined;
    let close: (() => void) | undefined;
    let reconnect: ReturnType<typeof setTimeout> | undefined;
    let connection = 0;
    const sequenceKeys = new WeakMap<IconAnimation, string>();
    function sequenceKey(animation: IconAnimation) {
        let key = sequenceKeys.get(animation);
        if (!key) {
            key = `${animation.page}:${animation.frames.map((frame) => `${frame.x},${frame.y},${frame.durationMillis}`).join(';')}`;
            sequenceKeys.set(animation, key);
        }
        return key;
    }

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
                let icons: IconMetadata | null = null;
                let remap: IconRemap = (value) => ({ ...value, icon: null });
                if (delta.icons) {
                    const pages = [...(previous?.pages ?? [])];
                    const indices = (delta.icons?.pages ?? []).map((page) => {
                        let index = pages.findIndex((existing) => existing.digest === page.digest);
                        if (index < 0) {
                            index = pages.length;
                            pages.push(page);
                        }
                        return index;
                    });
                    const animations = delta.icons.animations?.length
                        ? [...(previous?.animations ?? [])]
                        : (previous?.animations ?? []);
                    const sequenceIndices = new Map(
                        delta.icons.animations?.length
                            ? animations.map((animation, index) => [sequenceKey(animation), index])
                            : []
                    );
                    const animationIndices = (delta.icons?.animations ?? []).map((animation) => {
                        const mapped = { ...animation, page: indices[animation.page] };
                        const key = sequenceKey(mapped);
                        let index = sequenceIndices.get(key);
                        if (index === undefined) {
                            index = animations.length;
                            animations.push(mapped);
                            sequenceIndices.set(key, index);
                        }
                        return index;
                    });
                    remap = (value) => ({
                        ...value,
                        icon:
                            value.icon && delta.icons
                                ? {
                                      ...value.icon,
                                      page: indices[value.icon.page],
                                      ...(value.icon.animation != null
                                          ? { animation: animationIndices[value.icon.animation] ?? null }
                                          : {})
                                  }
                                : null
                    });
                    icons = { ...delta.icons, pages, animations };
                }
                host.section?.(delta, remap);
                let rows: LiveChange<Row>['rows'] = null;
                if (delta.definitions.length || delta.updates.length || delta.removed.length || delta.unkeyed) {
                    const existing = host.rows();
                    const keyed = new Map<string, Row>();
                    for (const row of existing) {
                        if (row.itemKey) keyed.set(row.itemKey, row);
                    }
                    for (const removed of delta.removed) keyed.delete(removed);
                    for (const row of delta.definitions) {
                        if (row.itemKey) keyed.set(row.itemKey, remap(row));
                    }
                    for (const update of delta.updates) {
                        const row = keyed.get(update.itemKey);
                        if (!row) {
                            resync();
                            return;
                        }
                        keyed.set(update.itemKey, { ...row, ...update });
                    }
                    const unkeyed = delta.unkeyed?.map(remap) ?? existing.filter((row) => !row.itemKey);
                    rows = { all: [...keyed.values(), ...unkeyed], keyed };
                }
                host.apply({ rows, icons });
                view = delta.view;
                host.changed();
            },
            reset() {
                if (current()) resync();
            },
            error() {
                if (!current()) return;
                host.failed?.(confirmed);
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
