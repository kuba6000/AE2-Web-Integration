import type { Api, ApiFailure } from './api.js';
import type { IconMetadata, ResourceDescription } from './api-types.js';

export type TreeKind =
    'RESOURCE' | 'RECIPE' | 'INGREDIENT' | 'UNPLACED' | 'UNLINKED_MISSING' | 'REQUEST' | 'TASK' | 'CONTRIBUTION';
/** TRACE records requests and their sources; AGGREGATE contains plan totals and declared recipes. */
export type TreeMode = 'TRACE' | 'AGGREGATE';
export type TreeContribution = 'STORAGE' | 'EMITTED' | 'MISSING' | 'REUSED' | 'UNKNOWN';
export type TreeOutput = { resourceId: number; quantity: string; primary: boolean | null };
export type TreeRecipe = {
    id: number;
    inputs: { multiplier: string | null; candidates: { resourceId: number; quantity: string | null }[] }[];
    outputs: TreeOutput[];
};
export type TreeChild = {
    nodeId: number;
    relation:
        | 'DECLARED_OUTPUT'
        | 'INGREDIENT'
        | 'DECLARED_ALTERNATIVE'
        | 'UNPLACED'
        | 'MISSING_RESOURCE'
        | 'CONTRIBUTION'
        | 'CRAFT'
        | 'CHILD_REQUEST';
    quantity: string | null;
    primary: boolean | null;
    missing: boolean;
};
export type TreeNode = {
    id: number;
    kind: TreeKind;
    resourceId: number | null;
    recipeId: number | null;
    crafts: string | null;
    multiplier: string | null;
    complete: boolean | null;
    candidates: number | null;
    contribution: TreeContribution | null;
    children: TreeChild[];
};
export type TreeQuery = { icons: boolean; captureId?: string };
export type TreeResource = ResourceDescription & { missingAmount: string | null };
export type TreeDocument = {
    state: 'CALCULATING' | 'PREPARING' | 'UNAVAILABLE' | 'READY' | 'TOO_LARGE';
    mode: TreeMode | null;
    captureId: string | null;
    root: { resourceId: number; quantity: string; simulated: boolean } | null;
    roots: number[];
    resources: (TreeResource & { id: number })[];
    recipes: TreeRecipe[];
    nodes: TreeNode[];
    partial: boolean;
    limits: string[];
    missingComplete: boolean;
    missingResourceCount: number;
};
export type TreeRow = {
    key: string;
    node: TreeNode;
    depth: number;
    reference: boolean;
    quantity: string | null;
    relation: TreeChild['relation'] | null;
    /** The single-candidate ingredient group represented directly by this row. */
    group?: TreeNode;
};
export type TreeState = {
    captureId: string | null;
    visible: boolean;
    status: TreeDocument['state'] | 'IDLE' | 'ERROR';
    mode: TreeMode | null;
    root: TreeDocument['root'];
    nodes: Record<string, TreeNode>;
    resources: Record<number, TreeResource>;
    recipes: Record<number, TreeRecipe>;
    icons: IconMetadata | null;
    expanded: Record<string, boolean>;
    rows: TreeRow[];
    partial: boolean;
    limits: string[];
    missingOnly: boolean;
    missingComplete: boolean;
    missingResourceCount: number;
    loading: string | null;
    error: string | null;
    bounded: boolean;
    canRevealMissing: boolean;
    expanding: boolean;
    expandedComplete: boolean;
    expandedNodes: number;
    windowStart: number;
    totalRows: number;
    revision: number;
};

const keyOf = (node: TreeNode) => `${node.kind}:${node.id}`;
const expandable = (node: TreeNode) => node.kind !== 'CONTRIBUTION';
const VISIBLE_LIMIT = 300;
const REVEAL_STEPS = 12;
const EXPANSION_CHUNK = 256;

/** One plan-owned capture; all navigation and missing-path filtering use its local graph. */
export function createCraftingTree(
    api: Pick<Api, 'planTree'>,
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
        recipes: {},
        icons: null,
        expanded: {},
        rows: [],
        partial: false,
        limits: [],
        missingOnly,
        missingComplete: false,
        missingResourceCount: 0,
        loading: null,
        error: null,
        bounded: false,
        canRevealMissing: false,
        expanding: false,
        expandedComplete: false,
        expandedNodes: 0,
        windowStart: 0,
        totalRows: 0,
        revision: 0
    });
    const state = fresh();
    let target: { grid: string; plan: string | number } | null = null;
    let byId: Record<number, TreeNode> = {};
    let roots: number[] = [];
    let readyCaptureId: string | null = null;
    let fullExpansion: Record<string, boolean> | null = null;
    let missingExpansion: Record<string, boolean> | null = null;
    let fullComplete = false;
    let missingExpandedComplete = false;
    let projection: TreeRow[] = [];
    let generation = 0;
    let revision = 0;
    let expansionRun = 0;
    let request: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;

    function cancelExpansion() {
        expansionRun++;
        if (state.expanding) state.expandedComplete = false;
        state.expanding = false;
    }
    function stop() {
        cancelExpansion();
        generation++;
        request?.abort();
        request = null;
        clearTimeout(timer);
        state.loading = null;
    }
    function discard() {
        const visible = state.visible;
        Object.assign(state, fresh(), { visible });
        byId = {};
        roots = [];
        readyCaptureId = null;
        fullExpansion = null;
        missingExpansion = null;
        fullComplete = false;
        missingExpandedComplete = false;
        projection = [];
    }
    function children(node: TreeNode) {
        return missingOnly ? node.children.filter((child) => child.missing) : node.children;
    }
    function visibleRoots() {
        if (!missingOnly) return [...roots];
        return roots.filter((id) => {
            const node = byId[id];
            if (!node) return false;
            const resource = node.resourceId != null ? state.resources[node.resourceId] : null;
            return (
                (resource?.missingAmount != null && BigInt(resource.missingAmount) > 0n) || children(node).length > 0
            );
        });
    }
    function singleCandidate(node: TreeNode) {
        if (node.kind !== 'INGREDIENT' || node.candidates !== 1) return null;
        const available = children(node);
        return available.length === 1 ? available[0] : null;
    }
    function publish() {
        const seen = new Set<number>();
        const rows: TreeRow[] = [];
        const stack: Omit<TreeRow, 'reference'>[] = [];
        const visible = visibleRoots();
        for (let index = visible.length - 1; index >= 0; index--) {
            const node = byId[visible[index]];
            if (!node) continue;
            stack.push({
                key: keyOf(node),
                node,
                depth: 0,
                quantity: visible[index] === roots[0] ? (state.root?.quantity ?? null) : null,
                relation:
                    node.kind === 'UNPLACED' ? 'UNPLACED' : node.kind === 'UNLINKED_MISSING' ? 'MISSING_RESOURCE' : null
            });
        }
        while (stack.length) {
            const row = stack.pop()!;
            const reference = seen.has(row.node.id);
            rows.push({ ...row, reference });
            if (reference) continue;
            seen.add(row.node.id);
            if (!state.expanded[row.key]) continue;
            const links = children(row.node);
            for (let index = links.length - 1; index >= 0; index--) {
                const child = links[index];
                const node = byId[child.nodeId];
                if (!node) continue;
                const only = singleCandidate(node);
                const shown = only ? byId[only.nodeId] : node;
                if (!shown) continue;
                const edge = only ?? child;
                stack.push({
                    key: keyOf(shown),
                    node: shown,
                    depth: row.depth + 1,
                    quantity: edge.quantity,
                    relation: edge.relation,
                    ...(only ? { group: node } : {})
                });
            }
        }
        projection = rows;
        state.windowStart = Math.min(state.windowStart, Math.max(0, rows.length - 1));
        state.rows = rows.slice(state.windowStart, state.windowStart + VISIBLE_LIMIT);
        state.totalRows = rows.length;
        state.bounded = rows.length > VISIBLE_LIMIT;
        state.canRevealMissing =
            missingOnly &&
            state.status === 'READY' &&
            rows.length < VISIBLE_LIMIT &&
            rows.some(
                (row) =>
                    !row.reference && expandable(row.node) && !state.expanded[row.key] && children(row.node).length > 0
            );
        state.revision = ++revision;
        changed();
    }
    function applyDocument(document: TreeDocument, icons: IconMetadata | null) {
        const sameCapture =
            document.state === 'READY' && readyCaptureId !== null && readyCaptureId === document.captureId;
        readyCaptureId = document.state === 'READY' ? document.captureId : null;
        if (!sameCapture) {
            state.expanded = {};
            state.expandedComplete = false;
            state.windowStart = 0;
            fullExpansion = null;
            missingExpansion = null;
            fullComplete = false;
            missingExpandedComplete = false;
        }
        state.captureId = document.captureId ?? null;
        state.status = document.state;
        state.mode = document.mode ?? null;
        state.root = document.root ?? null;
        state.partial = document.partial;
        state.limits = document.limits;
        state.missingComplete = document.missingComplete;
        state.missingResourceCount = document.missingResourceCount;
        state.icons = icons;
        state.resources = Object.fromEntries(document.resources.map((resource) => [resource.id, resource]));
        state.recipes = Object.fromEntries(document.recipes.map((recipe) => [recipe.id, recipe]));
        byId = Object.fromEntries(document.nodes.map((node) => [node.id, node]));
        state.nodes = Object.fromEntries(document.nodes.map((node) => [keyOf(node), node]));
        roots = document.roots;
        if (!sameCapture && roots.length && byId[roots[0]]) {
            const key = keyOf(byId[roots[0]]);
            state.expanded[key] = true;
            if (missingOnly) fullExpansion = { [key]: true };
        }
    }
    async function load() {
        if (!target || !state.visible || state.loading) return;
        clearTimeout(timer);
        const version = generation;
        const controller = new AbortController();
        request = controller;
        state.loading = 'ROOT';
        state.error = null;
        publish();
        let restart = false;
        let reveal = false;
        try {
            const response = await api.planTree(
                target.grid,
                target.plan,
                {
                    icons: iconsEnabled(),
                    ...(state.captureId ? { captureId: state.captureId } : {})
                },
                controller.signal
            );
            if (version !== generation) return;
            reveal = missingOnly && response.data.state === 'READY' && readyCaptureId !== response.data.captureId;
            applyDocument(response.data, response.icons);
            if (state.status === 'PREPARING' || state.status === 'CALCULATING')
                timer = setTimeout(() => void load(), 1000);
        } catch (caught) {
            if (version !== generation) return;
            const error = caught as ApiFailure;
            if (error.name === 'AbortError') return;
            if (error.status === 'UPDATE_CHANGED') {
                cancelExpansion();
                discard();
                restart = true;
            } else {
                if (['INVALID_ID', 'GRID_NOT_FOUND', 'NO_PERMISSIONS', 'UNAUTHORIZED'].includes(error.status ?? ''))
                    discard();
                state.status = 'ERROR';
                state.error = error.status ?? 'NETWORK_ERROR';
            }
        } finally {
            if (version === generation) {
                request = null;
                state.loading = null;
                publish();
                if (restart) await load();
                else if (reveal) await revealMissing();
            }
        }
    }
    async function revealMissing() {
        if (state.expanding) return;
        for (let count = 0; count < REVEAL_STEPS && state.canRevealMissing; count++) {
            const row = projection.find(
                (candidate) =>
                    !candidate.reference &&
                    expandable(candidate.node) &&
                    !state.expanded[candidate.key] &&
                    children(candidate.node).length > 0
            );
            if (!row) break;
            state.expanded[row.key] = true;
            publish();
        }
    }
    async function expandAll() {
        if (!state.visible || state.status !== 'READY' || state.expanding) return;
        cancelExpansion();
        const run = expansionRun;
        state.expanding = true;
        state.expandedComplete = false;
        state.expandedNodes = 0;
        const seen = new Set<number>();
        const stack = visibleRoots().reverse();
        publish();
        try {
            while (stack.length && run === expansionRun && state.visible) {
                for (let count = 0; count < EXPANSION_CHUNK && stack.length; count++) {
                    const id = stack.pop()!;
                    const node = byId[id];
                    if (!node || seen.has(id)) continue;
                    seen.add(id);
                    if (!expandable(node)) continue;
                    state.expanded[keyOf(node)] = true;
                    const links = children(node);
                    for (let index = links.length - 1; index >= 0; index--) stack.push(links[index].nodeId);
                }
                state.expandedNodes = seen.size;
                publish();
                if (stack.length) await new Promise<void>((resolve) => setTimeout(resolve, 0));
            }
            if (run === expansionRun && !stack.length) state.expandedComplete = true;
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
            cancelExpansion();
            publish();
        },
        collapseAll() {
            cancelExpansion();
            state.expandedComplete = false;
            state.expanded = {};
            state.windowStart = 0;
            publish();
        },
        async setMissingOnly(value: boolean) {
            if (value === missingOnly) return;
            const wasComplete = state.expandedComplete;
            cancelExpansion();
            const firstMissingView = value && missingExpansion === null;
            if (value) {
                fullExpansion = state.expanded;
                fullComplete = wasComplete;
                state.expanded = missingExpansion ?? {};
                state.expandedComplete = missingExpandedComplete;
                if (firstMissingView && roots.length && byId[roots[0]]) state.expanded[keyOf(byId[roots[0]])] = true;
            } else {
                missingExpansion = state.expanded;
                missingExpandedComplete = wasComplete;
                state.expanded = fullExpansion ?? {};
                state.expandedComplete = fullComplete;
                fullExpansion = null;
            }
            missingOnly = value;
            state.missingOnly = value;
            state.windowStart = 0;
            publish();
            if (firstMissingView) await revealMissing();
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
            Object.assign(state, fresh());
            byId = {};
            roots = [];
            readyCaptureId = null;
            fullExpansion = null;
            missingExpansion = null;
            fullComplete = false;
            missingExpandedComplete = false;
            projection = [];
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
            if (reopening || state.status === 'ERROR' || !state.captureId) await load();
            else publish();
        },
        async toggle(key: string) {
            const node = state.nodes[key];
            if (!node || !expandable(node)) return;
            cancelExpansion();
            state.expandedComplete = false;
            state.expanded[key] = !state.expanded[key];
            publish();
        },
        async retry() {
            await load();
        },
        dispose: stop
    };
}
