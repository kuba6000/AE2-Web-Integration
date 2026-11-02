import type { Api, ApiFailure } from './api.js';
import type { IconAnimation, IconMetadata, ResourceDescription } from './api-types.js';

export type TreeKind =
    'RESOURCE' | 'RECIPE' | 'INGREDIENT' | 'UNPLACED' | 'UNLINKED_MISSING' | 'REQUEST' | 'TASK' | 'CONTRIBUTION';
/** TRACE: the native job recorded every request and what satisfied it. AGGREGATE: recipes summed per pattern, plan totals only. */
export type TreeMode = 'TRACE' | 'AGGREGATE';
export type TreeContribution = 'STORAGE' | 'EMITTED' | 'MISSING' | 'REUSED' | 'UNKNOWN';
/** An output a recipe declares, per craft; primary is false for a byproduct and null when not retained. */
export type TreeOutput = { resourceId: number; quantity: string; primary: boolean | null };
/** An input a TRACE task declares, per craft. */
export type TreeInput = { resourceId: number; quantity: string };
export type TreeNode = {
    kind: TreeKind;
    id: number;
    crafts: string | null;
    multiplier: string | null;
    complete: boolean | null;
    /** Declared candidate count of an ingredient group, whatever the missing-only filter leaves in its page. */
    candidates: number | null;
    /** Declared outputs of a recipe or TRACE task. */
    outputs: TreeOutput[] | null;
    /** Declared inputs of a TRACE task. */
    inputs: TreeInput[] | null;
    /** The requested resource of a TRACE request or contribution. */
    resourceId: number | null;
    /** What satisfied the request, for a TRACE contribution leaf. */
    contribution: TreeContribution | null;
};
export type TreeChild = {
    relation:
        | 'DECLARED_OUTPUT'
        | 'INGREDIENT'
        | 'DECLARED_ALTERNATIVE'
        | 'UNPLACED'
        | 'MISSING_RESOURCE'
        | 'CONTRIBUTION'
        | 'CRAFT'
        | 'CHILD_REQUEST';
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
    missingOnly: boolean;
};
export type TreeBatchQuery = {
    /** Comma-separated KIND:id[:offset] start nodes. */
    nodes: string;
    rows: number;
    icons: boolean;
    captureId?: string;
    missingOnly: boolean;
    /** Nodes already held completely, as KIND:ranges groups separated by |. */
    loaded?: string;
};
export type TreeResource = ResourceDescription & { missingAmount: string | null };
export type TreePage = {
    captureId: string | null;
    state: 'CALCULATING' | 'PREPARING' | 'UNAVAILABLE' | 'READY';
    mode: TreeMode | null;
    root: { resourceId: number; quantity: string; simulated: boolean } | null;
    node: TreeNode | null;
    children: TreeChild[];
    resources: (TreeResource & { id: number })[];
    nextOffset: number | null;
    partial: boolean;
    limits: string[];
    unplacedCount: number;
    unlinkedMissingCount: number;
    missingOnly: boolean;
    missingComplete: boolean;
    missingResourceCount: number;
};
export type TreeBatchPage = {
    node: TreeNode;
    offset: number;
    children: TreeChild[];
    nextOffset: number | null;
};
export type TreeBatch = Omit<TreePage, 'node' | 'children' | 'nextOffset'> & { pages: TreeBatchPage[] | null };
export type TreeRow = {
    key: string;
    node: TreeNode;
    depth: number;
    reference: boolean;
    quantity: string | null;
    relation: TreeChild['relation'] | null;
    /** The single-candidate ingredient group this resource stands in for; the group has no row of its own. */
    group?: TreeNode;
};
export type TreeState = {
    captureId: string | null;
    visible: boolean;
    status: TreePage['state'] | 'IDLE' | 'ERROR';
    mode: TreeMode | null;
    root: TreePage['root'];
    nodes: Record<string, TreeNode>;
    resources: Record<number, TreeResource>;
    icons: IconMetadata | null;
    expanded: Record<string, boolean>;
    pages: Record<string, { children: TreeChild[]; nextOffset: number | null }>;
    rows: TreeRow[];
    partial: boolean;
    limits: string[];
    unplacedCount: number;
    unlinkedMissingCount: number;
    missingOnly: boolean;
    missingComplete: boolean;
    missingResourceCount: number;
    loading: string | null;
    error: string | null;
    bounded: boolean;
    cacheBounded: boolean;
    canRevealMissing: boolean;
    expanding: boolean;
    expandedComplete: boolean;
    expandedNodes: number;
    windowStart: number;
    totalRows: number;
    revision: number;
};
const keyOf = (node: TreeNode) => `${node.kind}:${node.id}`;
const syntheticNode: TreeNode = {
    kind: 'UNPLACED',
    id: 0,
    crafts: null,
    multiplier: null,
    complete: null,
    candidates: null,
    outputs: null,
    inputs: null,
    resourceId: null,
    contribution: null
};
/** A contribution answers a request outright, so it has no children to load. */
const expandable = (node: Pick<TreeNode, 'kind'>) => node.kind !== 'CONTRIBUTION';
const VISIBLE_LIMIT = 300;
const CACHE_LIMIT = 3000;
// One batched response carries at most this many child rows; the server accepts up to twice as many.
const BATCH_ROWS = 500;
const BATCH_NODES = 32;
// The loaded hint travels in the query string, so it is capped to stay far below common URL limits.
const LOADED_RANGES = 250;
const REVEAL_STEPS = 12;

/** Plan-owned lazy adjacency navigation. Shared/cyclic occurrences remain references. */
export function createCraftingTree(
    api: Pick<Api, 'planTree' | 'planTreeBatch'>,
    changed: () => void,
    iconsEnabled: () => boolean = () => true
) {
    let missingOnly = false;
    const fresh = (): TreeState => ({
        captureId: null,
        visible: false,
        status: 'IDLE',
        mode: null,
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
        unlinkedMissingCount: 0,
        missingOnly,
        missingComplete: false,
        missingResourceCount: 0,
        loading: null,
        error: null,
        bounded: false,
        cacheBounded: false,
        canRevealMissing: false,
        expanding: false,
        expandedComplete: false,
        expandedNodes: 0,
        windowStart: 0,
        totalRows: 0,
        revision: 0
    });
    const state = fresh();
    // Indexes belong to a merged animation table, which swaps with the state it came from.
    const sequenceIndices = new WeakMap<IconAnimation[], Map<string, number>>();
    let target: { grid: string; plan: string | number } | null = null;
    let generation = 0;
    let revision = 0;
    let request: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let rootKey: string | null = null;
    let cachedRows = 0;
    let projection: TreeRow[] = [];
    let revealRequested = false;
    let expansionRun = 0;
    function cancelExpansion() {
        expansionRun++;
        state.expanding = false;
        state.expandedComplete = false;
        revealRequested = false;
    }
    let fullView: { state: TreeState; rootKey: string | null; cachedRows: number } | null = null;
    function stop() {
        cancelExpansion();
        generation++;
        request?.abort();
        request = null;
        clearTimeout(timer);
        state.loading = null;
    }
    /** The only declared candidate of an ingredient group whose page is held completely, else null. */
    function singleCandidate(child: TreeChild) {
        if (child.node.kind !== 'INGREDIENT' || child.node.candidates !== 1) return null;
        const page = state.pages[keyOf(child.node)];
        return page && page.nextOffset === null && page.children.length === 1 ? page.children[0] : null;
    }
    /**
     * Single-candidate groups of a recipe page whose own page is not held yet. Each group page costs exactly one
     * row, so one batch with that row budget reads them all and nothing beyond.
     */
    function unreadGroups(recipeKey: string) {
        return (state.pages[recipeKey]?.children ?? [])
            .filter((child) => child.node.kind === 'INGREDIENT' && child.node.candidates === 1)
            .map((child) => keyOf(child.node))
            .filter((key) => !state.pages[key])
            .map((key) => ({ key, offset: 0 }));
    }
    function publish() {
        const seen = new Set<string>();
        const rows: TreeRow[] = [];
        const stack: Omit<TreeRow, 'reference'>[] = [];
        if (state.unlinkedMissingCount)
            stack.push({
                key: 'UNLINKED_MISSING:0',
                node: { ...syntheticNode, kind: 'UNLINKED_MISSING' },
                depth: 0,
                quantity: null,
                relation: 'MISSING_RESOURCE'
            });
        if (state.unplacedCount && rootKey !== 'UNPLACED:0')
            stack.push({
                key: 'UNPLACED:0',
                node: { ...syntheticNode, kind: 'UNPLACED' },
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
                // A group with one declared candidate adds no choice, so its candidate stands in for the group.
                const only = singleCandidate(child);
                const shown = only ?? child;
                stack.push({
                    key: keyOf(shown.node),
                    node: shown.node,
                    depth: row.depth + 1,
                    quantity: shown.quantity,
                    relation: shown.relation,
                    ...(only ? { group: child.node } : {})
                });
            }
        }
        projection = rows;
        state.windowStart = Math.min(state.windowStart, Math.max(0, rows.length - 1));
        state.rows = rows.slice(state.windowStart, state.windowStart + VISIBLE_LIMIT);
        state.totalRows = rows.length;
        state.cacheBounded = cachedRows >= CACHE_LIMIT;
        state.bounded = rows.length > VISIBLE_LIMIT || state.cacheBounded;
        state.canRevealMissing =
            missingOnly &&
            state.status === 'READY' &&
            !state.cacheBounded &&
            rows.length < VISIBLE_LIMIT &&
            rows.some((row) => !row.reference && expandable(row.node) && !state.expanded[row.key]);
        state.revision = ++revision;
        changed();
    }
    function descriptors(page: Pick<TreePage, 'resources'>, icons: IconMetadata | null) {
        if (icons && state.icons?.packId !== icons.packId) {
            state.icons = { ...icons, pages: [], animations: [] };
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
        // Animation indexes are response-local like pages; equal sequences share one merged entry.
        const sequences = icons?.animations?.map((animation) => {
            const merged = state.icons!.animations!;
            let known = sequenceIndices.get(merged);
            if (!known) sequenceIndices.set(merged, (known = new Map()));
            const sequence = { ...animation, page: indices![animation.page] };
            const key = `${sequence.page}:${sequence.frames.map((frame) => `${frame.x},${frame.y},${frame.durationMillis}`).join(';')}`;
            let index = known.get(key);
            if (index === undefined) {
                index = merged.length;
                merged.push(sequence);
                known.set(key, index);
            }
            return index;
        });
        for (const resource of page.resources)
            state.resources[resource.id] = {
                ...resource,
                icon:
                    resource.icon && indices
                        ? {
                              ...resource.icon,
                              page: indices[resource.icon.page],
                              ...(resource.icon.animation != null
                                  ? { animation: sequences?.[resource.icon.animation] ?? null }
                                  : {})
                          }
                        : null
            };
    }
    function discard() {
        fullView = null;
        Object.assign(state, fresh(), { visible: true });
        rootKey = null;
        cachedRows = 0;
    }
    function applyHeader(page: Omit<TreePage, 'node' | 'children' | 'nextOffset'>, icons: IconMetadata | null) {
        state.captureId = page.captureId ?? null;
        state.status = page.state;
        state.mode = page.mode ?? null;
        state.root = page.root;
        state.partial = page.partial;
        state.limits = page.limits;
        state.unplacedCount = page.unplacedCount;
        state.unlinkedMissingCount = page.unlinkedMissingCount ?? 0;
        state.missingComplete = page.missingComplete ?? false;
        state.missingResourceCount = page.missingResourceCount ?? 0;
        descriptors(page, icons);
    }
    /** Handles a failed read; true when the plan identity changed and the tree has to restart from its root. */
    function failure(error: ApiFailure) {
        if (error.name === 'AbortError') return false;
        if (error.status === 'UPDATE_CHANGED') {
            cancelExpansion();
            discard();
            return true;
        }
        if (['INVALID_ID', 'GRID_NOT_FOUND', 'NO_PERMISSIONS', 'UNAUTHORIZED'].includes(error.status ?? '')) discard();
        state.status = 'ERROR';
        state.error = error.status ?? 'NETWORK_ERROR';
        return false;
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
                    missingOnly,
                    ...(state.captureId ? { captureId: state.captureId } : {})
                },
                request.signal
            );
            if (version !== generation) return;
            const page = response.data;
            if (state.captureId && state.captureId !== page.captureId) {
                cancelExpansion();
                discard();
                if (kind !== 'ROOT') {
                    restart = true;
                    return;
                }
            }
            applyHeader(page, response.icons);
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
                // Read before the recipe's rows are published, so no group card flashes up and is replaced.
                const groups = kind === 'RECIPE' ? unreadGroups(key) : [];
                if (groups.length) {
                    const outcome = await readBatch(groups, groups.length, version);
                    if (outcome === 'stale') return;
                    restart = outcome === 'replaced';
                }
            }
            if (page.state === 'PREPARING' || page.state === 'CALCULATING')
                timer = setTimeout(() => void load('ROOT', 0), 1000);
        } catch (caught) {
            if (version !== generation) return;
            restart = failure(caught as ApiFailure);
        } finally {
            if (version === generation) {
                state.loading = null;
                publish();
                if (restart) await load('ROOT', 0);
                else if (kind === 'ROOT' && state.status === 'READY' && revealRequested) {
                    revealRequested = false;
                    await revealMissing();
                }
            }
        }
    }
    /** Pages the server has already delivered completely; sent so a batch does not read them again. */
    function loadedHint() {
        const loaded = new Map<string, number[]>();
        for (const [key, page] of Object.entries(state.pages)) {
            if (page.nextOffset !== null) continue;
            const [kind, id] = key.split(':');
            if (kind === 'UNPLACED' || kind === 'UNLINKED_MISSING') continue;
            loaded.set(kind, [...(loaded.get(kind) ?? []), Number(id)]);
        }
        let budget = LOADED_RANGES;
        const groups: string[] = [];
        for (const [kind, ids] of loaded) {
            ids.sort((first, second) => first - second);
            const ranges: string[] = [];
            for (let from = 0; from < ids.length && budget > 0; budget--) {
                let to = from;
                while (to + 1 < ids.length && ids[to + 1] === ids[to] + 1) to++;
                ranges.push(to > from ? `${ids[from]}-${ids[to]}` : String(ids[from]));
                from = to + 1;
            }
            if (ranges.length) groups.push(`${kind}:${ranges.join(',')}`);
        }
        return groups.join('|');
    }
    function storePage(entry: TreeBatchPage) {
        const key = keyOf(entry.node);
        const known = state.pages[key];
        // A page already held, or a continuation that does not follow the held children, is not merged again.
        if (entry.offset === 0 ? known : known?.children.length !== entry.offset) return;
        state.nodes[key] = entry.node;
        for (const child of entry.children) state.nodes[keyOf(child.node)] = child.node;
        cachedRows += entry.children.length;
        state.pages[key] = { children: [...(known?.children ?? []), ...entry.children], nextOffset: entry.nextOffset };
    }
    /**
     * Reads the pages of the given nodes and, breadth-first, of the nodes they reference, up to a child-row
     * budget, and merges them. A response for a replaced plan capture discards the tree ("replaced"); one from
     * before a reset or stop is dropped ("stale"). Failures propagate to the caller.
     */
    async function readBatch(
        starts: { key: string; offset: number }[],
        rows: number,
        version: number
    ): Promise<'merged' | 'unready' | 'replaced' | 'stale'> {
        if (!target || !request) return 'stale';
        const loaded = loadedHint();
        const response = await api.planTreeBatch(
            target.grid,
            target.plan,
            {
                nodes: starts.map(({ key, offset }) => (offset ? `${key}:${offset}` : key)).join(','),
                rows: Math.max(1, Math.min(rows, CACHE_LIMIT - cachedRows)),
                icons: iconsEnabled(),
                missingOnly,
                ...(loaded ? { loaded } : {}),
                ...(state.captureId ? { captureId: state.captureId } : {})
            },
            request.signal
        );
        if (version !== generation) return 'stale';
        const page = response.data;
        if (state.captureId && state.captureId !== page.captureId) {
            cancelExpansion();
            discard();
            return 'replaced';
        }
        applyHeader(page, response.icons);
        for (const entry of page.pages ?? []) storePage(entry);
        if (page.state === 'PREPARING' || page.state === 'CALCULATING')
            timer = setTimeout(() => void load('ROOT', 0), 1000);
        return page.state === 'READY' ? 'merged' : 'unready';
    }
    /** Resolves true when the response was merged into the tree. */
    async function loadBatch(starts: { key: string; offset: number }[], rows: number) {
        if (!target || !state.visible || state.loading) return false;
        const version = generation;
        let restart = false;
        let merged = false;
        request = new AbortController();
        state.loading = starts[0].key;
        state.error = null;
        publish();
        try {
            const outcome = await readBatch(starts, rows, version);
            if (outcome === 'stale') return false;
            merged = outcome === 'merged';
            restart = outcome === 'replaced';
        } catch (caught) {
            if (version !== generation) return false;
            restart = failure(caught as ApiFailure);
        } finally {
            if (version === generation) {
                state.loading = null;
                publish();
                if (restart) await load('ROOT', 0);
            }
        }
        return merged;
    }
    async function revealMissing() {
        if (state.expanding) return;
        const version = generation;
        for (
            let count = 0;
            count < REVEAL_STEPS &&
            missingOnly &&
            state.visible &&
            state.status === 'READY' &&
            !state.loading &&
            !state.cacheBounded &&
            state.totalRows < VISIBLE_LIMIT;
            count++
        ) {
            if (version !== generation) return;
            const row = state.rows.find(
                (candidate) => !candidate.reference && expandable(candidate.node) && !state.expanded[candidate.key]
            );
            if (!row) return;
            state.expanded[row.key] = true;
            // The batch also loads the paths below the row, so the next steps usually open cached pages.
            if (!state.pages[row.key]) await loadBatch([{ key: row.key, offset: 0 }], VISIBLE_LIMIT - state.totalRows);
            else publish();
        }
    }
    /**
     * Opens every reachable node in display order, whether or not its children are among the visible rows.
     * Returns the nodes seen and those whose page, or continuation, still has to be fetched.
     */
    function openLoaded() {
        const seen = new Set<string>();
        const pending: { key: string; offset: number }[] = [];
        const stack: string[] = [];
        if (state.unlinkedMissingCount) stack.push('UNLINKED_MISSING:0');
        if (state.unplacedCount && rootKey !== 'UNPLACED:0') stack.push('UNPLACED:0');
        if (rootKey && state.nodes[rootKey]) stack.push(rootKey);
        while (stack.length) {
            const key = stack.pop()!;
            if (seen.has(key)) continue;
            seen.add(key);
            if (state.nodes[key] && !expandable(state.nodes[key])) continue;
            state.expanded[key] = true;
            const page = state.pages[key];
            if (!page || page.nextOffset !== null) pending.push({ key, offset: page?.nextOffset ?? 0 });
            if (page)
                for (let index = page.children.length - 1; index >= 0; index--)
                    stack.push(keyOf(page.children[index].node));
        }
        return { seen, pending };
    }
    async function expandAll() {
        if (!state.visible || state.status !== 'READY' || state.expanding) return;
        stop();
        const run = expansionRun;
        state.expanding = true;
        state.expandedNodes = 0;
        publish();
        try {
            while (run === expansionRun && state.visible && state.status === 'READY') {
                const { seen, pending } = openLoaded();
                state.expandedNodes = seen.size;
                const fetchable = cachedRows < CACHE_LIMIT ? pending.slice(0, BATCH_NODES) : [];
                if (!fetchable.length) {
                    state.expandedComplete = !pending.length;
                    break;
                }
                publish();
                if (!(await loadBatch(fetchable, BATCH_ROWS)) || run !== expansionRun) break;
                await new Promise<void>((resolve) => setTimeout(resolve, 0));
            }
        } finally {
            if (run === expansionRun) {
                state.expanding = false;
                publish();
            }
        }
    }
    return {
        state,
        revealMissing,
        expandAll,
        stopExpansion() {
            stop();
            publish();
        },
        collapseAll() {
            stop();
            state.expanded = {};
            state.windowStart = 0;
            publish();
        },
        async setMissingOnly(value: boolean) {
            if (value === missingOnly) return;
            stop();
            const visible = state.visible;
            const captureId = state.captureId;
            if (value) fullView = { state: { ...state }, rootKey, cachedRows };
            missingOnly = value;
            if (!value && fullView) {
                Object.assign(state, fullView.state, { visible, missingOnly: false });
                rootKey = fullView.rootKey;
                cachedRows = fullView.cachedRows;
                fullView = null;
            } else {
                Object.assign(state, fresh(), { visible });
                rootKey = null;
                cachedRows = 0;
            }
            state.captureId = captureId;
            revealRequested = value;
            if (visible) await load('ROOT', 0);
            else publish();
        },
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
            missingOnly = false;
            fullView = null;
            revealRequested = false;
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
            if (state.nodes[key] && !expandable(state.nodes[key])) return;
            if (state.expanding) stop();
            state.expandedComplete = false;
            if (state.expanded[key]) {
                state.expanded[key] = false;
                publish();
                return;
            }
            if (state.loading && !state.pages[key]) return;
            state.expanded[key] = true;
            const node =
                state.nodes[key] ??
                (key === 'UNPLACED:0'
                    ? { kind: 'UNPLACED' as const, id: 0 }
                    : key === 'UNLINKED_MISSING:0'
                      ? { kind: 'UNLINKED_MISSING' as const, id: 0 }
                      : null);
            const unread = node?.kind === 'RECIPE' ? unreadGroups(key) : [];
            if (node && !state.pages[key]) await load(node.kind, node.id);
            else if (unread.length) await loadBatch(unread, unread.length);
            else publish();
        },
        async more(key: string) {
            if (state.expanding) return;
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
