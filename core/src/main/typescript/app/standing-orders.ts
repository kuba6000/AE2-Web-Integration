import type { Api, ApiFailure } from './api.js';
import type {
    IconMetadata,
    ResourceDescription,
    ResourceResponse,
    StandingOrder,
    StandingOrderDefinition,
    StandingOrderRejection,
    StandingOrdersListing
} from './api-types.js';
import type { Notify } from './notifications.js';
import { standingOrdersHref, type Route } from './router.js';
import {
    applyChange,
    buildChanges,
    buildDefinition,
    draftFromDefinition,
    isFlat,
    keepStockedDraft,
    placeRejection,
    type DraftChange,
    type FieldErrors,
    type OrderDraft
} from './standing-order-model.js';

export type StandingOrderEditor = {
    /** The order being edited; null while creating a new one. */
    order: string | null;
    /** `missing` means the network has no such order (any more). */
    status: 'loading' | 'ready' | 'missing';
    draft: OrderDraft;
    /** What the draft is compared with; null for a new order and for repairing an unreadable one. */
    baseline: OrderDraft | null;
    /** The order's stored settings, for showing rules the editor cannot change. */
    definition: StandingOrderDefinition | null;
    /** Why the stored order cannot be read; the editor then repairs it instead of editing it. */
    invalid: StandingOrderRejection | null;
    errors: FieldErrors;
    /** A message key for a failure that belongs to no single field. */
    formError: string | null;
    saving: boolean;
};
export type EditorCpu = { key: string; name: string; eligible: boolean };
export type StandingOrdersState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    error: string | null;
    serverEnabled: boolean;
    gridEnabled: boolean;
    canEdit: boolean;
    maxOrders: number;
    orders: StandingOrder[];
    /** Descriptions of the resources the orders and the editor refer to. */
    resources: Record<string, ResourceDescription>;
    icons: IconMetadata | null;
    /** The network's stock sampling interval, which is how often orders are checked; null while unknown. */
    checkIntervalSeconds: number | null;
    /** The network's crafting CPUs, read while an editor is open. */
    cpus: EditorCpu[];
    /** An order change is in flight; the orders stay as they were until it is answered. */
    pending: boolean;
    editor: StandingOrderEditor | null;
};

/** A change takes the engine about a tick to evaluate; one follow-up read shows its first status. */
const settleMillis = 1500;
const unconfirmed = ['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT', 'INTERNAL_ERROR'];
const lostAccess = ['UNAUTHORIZED', 'NO_PERMISSIONS', 'GRID_NOT_FOUND'];
/** Refusals that mean the list on screen is out of date, so it is read again. */
const staleList = ['STANDING_ORDER_NOT_FOUND', 'STANDING_ORDER_NOT_RUNNABLE'];

/** The message for a failed standing order request; unknown failures keep their own wire status as the key. */
export function standingOrderErrorKey(status: string) {
    if (unconfirmed.includes(status)) return 'actionUnconfirmed';
    if (status === 'TOO_MANY_REQUESTS') return 'standingOrderRateLimited';
    if (status === 'ITEM_NOT_FOUND') return 'standingOrderResourceUnknown';
    if (status === 'NO_PERMISSIONS') return 'standingOrderNoPermission';
    return status;
}

/** The network's standing orders and the editor draft; the engine's status is read, never computed here. */
export function createStandingOrders(api: Api, changed: () => void, iconsEnabled: () => boolean, notify: Notify) {
    const state: StandingOrdersState = {
        status: 'idle',
        error: null,
        serverEnabled: true,
        gridEnabled: true,
        canEdit: false,
        maxOrders: 0,
        orders: [],
        resources: {},
        icons: null,
        checkIntervalSeconds: null,
        cpus: [],
        pending: false,
        editor: null
    };
    let route: Route = { view: 'home', gridKey: null };
    let generation = 0;
    let request: AbortController | undefined;
    let reading: Promise<void> | undefined;
    let settle: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;
    /** Descriptions of resources chosen in the editor, which a listing does not carry until the order is saved. */
    let chosen: Record<string, ResourceDescription> = {};
    let cpusRead = false;
    const gridKey = () => (route.view === 'standing-orders' ? route.gridKey : null);

    function cancel() {
        generation++;
        request?.abort();
        reading = undefined;
    }
    function clearListing() {
        Object.assign(state, {
            status: route.view === 'standing-orders' ? 'loading' : 'idle',
            error: null,
            orders: [],
            resources: {},
            icons: null,
            checkIntervalSeconds: null,
            cpus: [],
            canEdit: false,
            maxOrders: 0
        });
        chosen = {};
        cpusRead = false;
    }
    function initEditor() {
        const editor = state.editor;
        if (!editor || editor.status !== 'loading' || route.view !== 'standing-orders' || !route.editor) return;
        if (route.editor.order === null) {
            editor.draft = keepStockedDraft(route.editor.itemKey);
            editor.status = 'ready';
            return;
        }
        const wanted = route.editor.order;
        const order = state.orders.find((entry) => entry.orderKey === wanted);
        if (!order) {
            editor.status = 'missing';
            return;
        }
        editor.invalid = order.invalid;
        if (order.definition) {
            editor.definition = order.definition;
            editor.baseline = draftFromDefinition(order.definition);
            editor.draft = draftFromDefinition(order.definition);
        } else editor.draft = keepStockedDraft(null);
        editor.status = 'ready';
    }
    function accept({ data, icons }: ResourceResponse<StandingOrdersListing>) {
        Object.assign(state, {
            status: 'ready',
            error: null,
            serverEnabled: data.serverEnabled,
            gridEnabled: data.gridEnabled,
            canEdit: data.canEdit,
            maxOrders: data.maxOrders,
            orders: data.orders,
            resources: { ...chosen, ...data.resources },
            icons
        });
        initEditor();
        const editor = state.editor;
        // A fresh listing without the edited order means it was deleted meanwhile.
        if (
            editor?.status === 'ready' &&
            editor.order !== null &&
            !data.orders.some((o) => o.orderKey === editor.order)
        )
            editor.status = 'missing';
    }
    function block(error: string | null) {
        cancel();
        clearListing();
        Object.assign(state, { status: 'error', error, pending: false });
        changed();
    }
    function failed(caught: unknown, version: number) {
        const error = caught as ApiFailure;
        if (version !== generation || error.name === 'AbortError') return null;
        return error.status || 'NETWORK_ERROR';
    }
    /** Interval and CPUs only decorate the page, so their failures leave the page as it is. */
    async function details(version: number, signal: AbortSignal, automatic: boolean) {
        const key = gridKey();
        if (!key) return;
        const tasks: Promise<void>[] = [];
        if (!automatic || state.checkIntervalSeconds === null)
            tasks.push(
                api.settings(key, signal).then(
                    (settings) => {
                        if (version === generation) state.checkIntervalSeconds = settings.stockSampleIntervalSeconds;
                    },
                    () => {}
                )
            );
        if (state.editor && !cpusRead)
            tasks.push(
                api.cpus(key, signal).then(
                    ({ data }) => {
                        if (version !== generation) return;
                        cpusRead = true;
                        state.cpus = Object.entries(data).map(([cpu, info]) => ({
                            key: cpu,
                            name: info.name,
                            eligible: info.acceptsPlayerJobs
                        }));
                    },
                    () => {}
                )
            );
        await Promise.all(tasks);
    }
    async function refresh(automatic = false) {
        const key = gridKey();
        if (disposed || !key || state.pending || state.editor?.saving) return;
        if (reading) return reading;
        const version = generation;
        request = new AbortController();
        const signal = request.signal;
        if (state.status !== 'ready') state.status = 'loading';
        changed();
        reading = (async () => {
            try {
                const [result] = await Promise.all([
                    api.standingOrders(key, signal, iconsEnabled()),
                    details(version, signal, automatic)
                ]);
                if (version !== generation) return;
                accept(result);
            } catch (caught) {
                const status = failed(caught, version);
                if (status !== null) block(status);
            } finally {
                if (version === generation) {
                    reading = undefined;
                    changed();
                }
            }
        })();
        return reading;
    }
    /** Runs one change of the orders; the answer replaces the whole list. */
    async function mutate(
        action: (key: string) => Promise<ResourceResponse<StandingOrdersListing> | null>,
        success: { message: string; subject?: string; settle: boolean }
    ) {
        const key = gridKey();
        if (disposed || !key || state.pending || state.status !== 'ready') return null;
        cancel();
        const version = generation;
        state.pending = true;
        changed();
        try {
            const result = await action(key);
            if (disposed || version !== generation) return null;
            if (result) accept(result);
            notify({ kind: 'success', message: success.message, subject: success.subject });
            if (success.settle) {
                clearTimeout(settle);
                settle = setTimeout(() => void refresh(true), settleMillis);
            }
            return { ok: true as const };
        } catch (caught) {
            const status = failed(caught, version);
            if (status === null) return null;
            if (lostAccess.includes(status)) block(status);
            return { ok: false as const, status, data: (caught as ApiFailure).data };
        } finally {
            if (version === generation) {
                state.pending = false;
                changed();
            }
        }
    }
    async function mutateFromList(action: Parameters<typeof mutate>[0], success: Parameters<typeof mutate>[1]) {
        const outcome = await mutate(action, success);
        if (outcome && !outcome.ok && !lostAccess.includes(outcome.status)) {
            notify({ kind: 'error', message: standingOrderErrorKey(outcome.status) });
            if (staleList.includes(outcome.status)) void refresh();
        }
    }
    const subject = (key: string) => state.orders.find((order) => order.orderKey === key)?.definition?.name;
    return {
        state,
        refresh,
        block,
        route(next: Route) {
            cancel();
            const sameNetwork =
                route.view === 'standing-orders' && next.view === 'standing-orders' && route.gridKey === next.gridKey;
            route = next;
            state.pending = false;
            if (!sameNetwork) {
                clearTimeout(settle);
                clearListing();
            }
            state.editor =
                next.view === 'standing-orders' && next.editor
                    ? {
                          order: next.editor.order,
                          status: 'loading',
                          draft: keepStockedDraft(null),
                          baseline: null,
                          definition: null,
                          invalid: null,
                          errors: {},
                          formError: null,
                          saving: false
                      }
                    : null;
            if (!state.editor) {
                state.cpus = [];
                cpusRead = false;
            }
            if (sameNetwork && state.status === 'ready') initEditor();
        },
        invalidateIcons() {
            // Only the read is outdated; a change in flight still has to deliver its answer.
            request?.abort();
            reading = undefined;
            state.icons = null;
        },
        setEnabled: (order: string, enabled: boolean) =>
            mutateFromList((key) => api.updateStandingOrder(key, order, { enabled }, iconsEnabled()), {
                message: enabled ? 'standingOrderEnabled' : 'standingOrderDisabled',
                subject: subject(order),
                settle: enabled
            }),
        run: (order: string) =>
            mutateFromList((key) => api.runStandingOrder(key, order), {
                message: 'standingOrderRunRequested',
                subject: subject(order),
                settle: true
            }),
        remove: (order: string) =>
            mutateFromList((key) => api.deleteStandingOrder(key, order, iconsEnabled()), {
                message: 'standingOrderDeleted',
                subject: subject(order),
                settle: false
            }),
        /** Applies an editor change. A chosen resource is remembered so its name can be shown before saving. */
        edit(change: DraftChange, resource?: ResourceDescription) {
            const editor = state.editor;
            if (!editor || editor.status !== 'ready' || editor.saving) return;
            if (resource?.itemKey) {
                chosen[resource.itemKey] = resource;
                state.resources = { ...state.resources, [resource.itemKey]: resource };
            }
            editor.draft = applyChange(editor.draft, change);
            editor.errors = {};
            editor.formError = null;
            changed();
        },
        async save() {
            const editor = state.editor;
            const key = gridKey();
            if (!editor || !key || editor.status !== 'ready' || editor.saving || state.pending || !state.canEdit)
                return;
            const order = editor.order;
            const refuse = (errors: FieldErrors) => {
                editor.errors = errors;
                editor.formError = null;
                changed();
            };
            let send: ((grid: string) => Promise<ResourceResponse<StandingOrdersListing>>) | undefined;
            if (order === null && isFlat(editor.draft)) {
                const built = buildDefinition(editor.draft);
                if (!built.ok) return refuse(built.errors);
                send = (grid) => api.createStandingOrder(grid, built.value, iconsEnabled());
            } else if (order !== null) {
                const built = editor.baseline
                    ? buildChanges(editor.baseline, editor.draft)
                    : isFlat(editor.draft)
                      ? buildDefinition(editor.draft)
                      : null;
                if (!built) return;
                if (!built.ok) return refuse(built.errors);
                if (editor.baseline && !Object.keys(built.value).length) {
                    location.hash = standingOrdersHref(key);
                    return;
                }
                send = (grid) => api.updateStandingOrder(grid, order, built.value, iconsEnabled());
            }
            if (!send) return;
            editor.errors = {};
            editor.formError = null;
            editor.saving = true;
            const name = editor.draft.name.trim();
            const outcome = await mutate(send, { message: 'standingOrderSaved', subject: name, settle: true });
            if (state.editor !== editor) return;
            editor.saving = false;
            if (!outcome) {
                changed();
                return;
            }
            if (outcome.ok) {
                location.hash = standingOrdersHref(key);
                return;
            }
            const rejection =
                outcome.status === 'INVALID_STANDING_ORDER' ? (outcome.data as StandingOrderRejection | null) : null;
            const placed = rejection && placeRejection(rejection, editor.draft);
            if (placed?.field) editor.errors = { [placed.field]: placed.message };
            else editor.formError = placed?.message ?? standingOrderErrorKey(outcome.status);
            changed();
            if (staleList.includes(outcome.status)) void refresh();
        },
        dispose() {
            disposed = true;
            cancel();
            clearTimeout(settle);
            state.orders = [];
            state.editor = null;
        }
    };
}
