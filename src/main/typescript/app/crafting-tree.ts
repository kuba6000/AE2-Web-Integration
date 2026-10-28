import type { Api, ApiFailure } from './api.js';
import type { IconMetadata, ResourceDescription } from './api-types.js';

export type TreeKind = 'RESOURCE' | 'RECIPE' | 'INGREDIENT' | 'UNPLACED';
export type TreeNode = {
    kind: TreeKind;
    id: number;
    crafts: string | null;
    multiplier: string | null;
    complete: boolean | null;
};
export type TreeChild = {
    relation: 'DECLARED_OUTPUT' | 'RECIPE_OUTPUT' | 'INGREDIENT' | 'DECLARED_ALTERNATIVE' | 'UNPLACED';
    primary: boolean | null;
    node: TreeNode;
    quantity: string | null;
};
export type TreeQuery = {
    kind: TreeKind | 'ROOT';
    id: number;
    offset: number;
    limit: number;
    icons: boolean;
    captureId?: string;
};
export type TreePage = {
    captureId: string | null;
    state: 'CALCULATING' | 'PREPARING' | 'UNAVAILABLE' | 'READY';
    root: { resourceId: number; quantity: string; simulated: boolean } | null;
    node: TreeNode | null;
    children: TreeChild[];
    resources: (ResourceDescription & { id: number })[];
    nextOffset: number | null;
    partial: boolean;
    limits: string[];
    unplacedCount: number;
};
export type TreeRow = {
    key: string;
    node: TreeNode;
    depth: number;
    reference: boolean;
    quantity: string | null;
    relation: TreeChild['relation'] | null;
    primary?: boolean | null;
};
export type TreeState = {
    captureId: string | null;
    visible: boolean;
    status: TreePage['state'] | 'IDLE' | 'ERROR';
    root: TreePage['root'];
    nodes: Record<string, TreeNode>;
    resources: Record<number, ResourceDescription>;
    icons: IconMetadata | null;
    expanded: Record<string, boolean>;
    pages: Record<string, { children: TreeChild[]; nextOffset: number | null }>;
    rows: TreeRow[];
    partial: boolean;
    limits: string[];
    unplacedCount: number;
    loading: string | null;
    error: string | null;
    bounded: boolean;
    cacheBounded: boolean;
    windowStart: number;
    totalRows: number;
    revision: number;
};
const keyOf = (node: TreeNode) => `${node.kind}:${node.id}`;
const VISIBLE_LIMIT = 300;
const CACHE_LIMIT = 3000;

/** Plan-owned lazy adjacency navigation. Shared/cyclic occurrences remain references. */
export function createCraftingTree(
    api: Pick<Api, 'planTree'>,
    changed: () => void,
    iconsEnabled: () => boolean = () => true
) {
    const fresh = (): TreeState => ({
        captureId: null,
        visible: false,
        status: 'IDLE',
        root: null,
        nodes: {},
        resources: {},
        icons: null,
        expanded: {},
        pages: {},
        rows: [],
        partial: false,
        limits: [],
        unplacedCount: 0,
        loading: null,
        error: null,
        bounded: false,
        cacheBounded: false,
        windowStart: 0,
        totalRows: 0,
        revision: 0
    });
    const state = fresh();
    let target: { grid: string; plan: string | number } | null = null;
    let generation = 0;
    let revision = 0;
    let request: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let rootKey: string | null = null;
    let cachedRows = 0;
    let projection: TreeRow[] = [];
    function stop() {
        generation++;
        request?.abort();
        request = null;
        clearTimeout(timer);
        state.loading = null;
    }
    function publish() {
        const seen = new Set<string>();
        const rows: TreeRow[] = [];
        const stack: Omit<TreeRow, 'reference'>[] = [];
        if (state.unplacedCount && rootKey !== 'UNPLACED:0')
            stack.push({
                key: 'UNPLACED:0',
                node: { kind: 'UNPLACED', id: 0, crafts: null, multiplier: null, complete: null },
                depth: 0,
                quantity: null,
                relation: 'UNPLACED'
            });
        if (rootKey && state.nodes[rootKey])
            stack.push({
                key: rootKey,
                node: state.nodes[rootKey],
                depth: 0,
                quantity: state.root?.quantity ?? null,
                relation: null
            });
        while (stack.length) {
            const row = stack.pop()!;
            const reference = seen.has(row.key);
            rows.push({ ...row, reference });
            if (reference) continue;
            seen.add(row.key);
            if (!state.expanded[row.key]) continue;
            const children = state.pages[row.key]?.children ?? [];
            for (let index = children.length - 1; index >= 0; index--) {
                const child = children[index];
                stack.push({
                    key: keyOf(child.node),
                    node: child.node,
                    depth: row.depth + 1,
                    quantity: child.quantity,
                    primary: child.primary,
                    relation: child.relation
                });
            }
        }
        projection = rows;
        state.windowStart = Math.min(state.windowStart, Math.max(0, rows.length - 1));
        state.rows = rows.slice(state.windowStart, state.windowStart + VISIBLE_LIMIT);
        state.totalRows = rows.length;
        state.cacheBounded = cachedRows >= CACHE_LIMIT;
        state.bounded = rows.length > VISIBLE_LIMIT || state.cacheBounded;
        state.revision = ++revision;
        changed();
    }
    function descriptors(page: TreePage, icons: IconMetadata | null) {
        if (icons && state.icons?.packId !== icons.packId) {
            state.icons = { ...icons, pages: [] };
            for (const resource of Object.values(state.resources)) resource.icon = null;
        }
        const indices = icons?.pages.map((atlas) => {
            const pages = state.icons!.pages;
            let index = pages.findIndex((existing) => existing.digest === atlas.digest);
            if (index < 0) {
                index = pages.length;
                pages.push(atlas);
            }
            return index;
        });
        for (const resource of page.resources)
            state.resources[resource.id] = {
                ...resource,
                icon: resource.icon && indices ? { ...resource.icon, page: indices[resource.icon.page] } : null
            };
    }
    async function load(kind: TreeKind | 'ROOT', id: number, offset = 0) {
        if (!target || !state.visible || state.loading) return;
        if (cachedRows >= CACHE_LIMIT && kind !== 'ROOT') {
            publish();
            return;
        }
        const version = generation;
        let restart = false;
        request = new AbortController();
        state.loading = `${kind}:${id}`;
        state.error = null;
        publish();
        try {
            const response = await api.planTree(
                target.grid,
                target.plan,
                {
                    kind,
                    id,
                    offset,
                    limit: kind === 'ROOT' ? 50 : Math.min(50, CACHE_LIMIT - cachedRows),
                    icons: iconsEnabled(),
                    ...(state.captureId ? { captureId: state.captureId } : {})
                },
                request.signal
            );
            if (version !== generation) return;
            const page = response.data;
            if (state.captureId && state.captureId !== page.captureId) {
                Object.assign(state, fresh(), { visible: true });
                rootKey = null;
                cachedRows = 0;
                if (kind !== 'ROOT') {
                    restart = true;
                    return;
                }
            }
            state.captureId = page.captureId ?? null;
            state.status = page.state;
            state.root = page.root;
            state.partial = page.partial;
            state.limits = page.limits;
            state.unplacedCount = page.unplacedCount;
            descriptors(page, response.icons);
            if (page.node) {
                const key = keyOf(page.node);
                state.nodes[key] = page.node;
                for (const child of page.children) state.nodes[keyOf(child.node)] = child.node;
                // Revalidating the same immutable capture must preserve already paged root children.
                if (kind !== 'ROOT' || !state.pages[key]) {
                    const previous = offset ? (state.pages[key]?.children ?? []) : [];
                    cachedRows += page.children.length - (offset ? 0 : (state.pages[key]?.children.length ?? 0));
                    state.pages[key] = { children: [...previous, ...page.children], nextOffset: page.nextOffset };
                }
                if (kind === 'ROOT') {
                    if (rootKey === null) state.expanded[key] = true;
                    rootKey = key;
                }
            }
            if (page.state === 'PREPARING' || page.state === 'CALCULATING')
                timer = setTimeout(() => void load('ROOT', 0), 1000);
        } catch (caught) {
            if (version !== generation) return;
            const error = caught as ApiFailure;
            if (error.name === 'AbortError') return;
            if (error.status === 'UPDATE_CHANGED') {
                Object.assign(state, fresh(), { visible: true });
                rootKey = null;
                cachedRows = 0;
                restart = true;
                return;
            }
            if (['INVALID_ID', 'GRID_NOT_FOUND', 'NO_PERMISSIONS', 'UNAUTHORIZED'].includes(error.status ?? '')) {
                Object.assign(state, fresh(), { visible: true });
                rootKey = null;
                cachedRows = 0;
            }
            state.status = 'ERROR';
            state.error = error.status ?? 'NETWORK_ERROR';
        } finally {
            if (version === generation) {
                state.loading = null;
                publish();
                if (restart) await load('ROOT', 0);
            }
        }
    }
    return {
        state,
        moveWindow(direction: number) {
            state.windowStart = Math.max(
                0,
                Math.min(state.totalRows - 1, state.windowStart + Math.sign(direction) * VISIBLE_LIMIT)
            );
            publish();
        },
        focus(key: string | null) {
            const index =
                key === null
                    ? 0
                    : Math.max(
                          0,
                          projection.findIndex((row) => row.key === key && !row.reference)
                      );
            if (key !== null && index >= state.windowStart && index < state.windowStart + VISIBLE_LIMIT) return;
            state.windowStart = Math.floor(index / VISIBLE_LIMIT) * VISIBLE_LIMIT;
            publish();
        },
        reset(grid: string | null, plan: string | number | null) {
            stop();
            Object.assign(state, fresh());
            rootKey = null;
            cachedRows = 0;
            target = grid !== null && plan !== null ? { grid, plan } : null;
        },
        async show(visible: boolean) {
            const reopening = !state.visible;
            state.visible = visible;
            if (!visible) {
                stop();
                publish();
                return;
            }
            if (reopening || !rootKey || state.status === 'ERROR') await load('ROOT', 0);
            else publish();
        },
        async toggle(key: string) {
            if (state.expanded[key]) {
                state.expanded[key] = false;
                publish();
                return;
            }
            if (state.loading && !state.pages[key]) return;
            state.expanded[key] = true;
            const node = state.nodes[key] ?? (key === 'UNPLACED:0' ? { kind: 'UNPLACED' as const, id: 0 } : null);
            if (node && !state.pages[key]) await load(node.kind, node.id);
            else publish();
        },
        async more(key: string) {
            const node = state.nodes[key];
            const offset = state.pages[key]?.nextOffset;
            if (node && offset != null) await load(node.kind, node.id, offset);
        },
        async retry() {
            if (cachedRows >= CACHE_LIMIT) {
                stop();
                Object.assign(state, fresh(), { visible: true });
                rootKey = null;
                cachedRows = 0;
            }
            await load('ROOT', 0);
        },
        dispose: stop
    };
}
