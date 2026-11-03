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
    cycle: boolean;
    referenceTarget: string | null;
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
    rootKind: TreeKind | null;
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
    canRevealMissing: boolean;
    expanding: boolean;
    expandedComplete: boolean;
    expandedNodes: number;
    revision: number;
};

const expandable = (node: TreeNode) => node.kind !== 'CONTRIBUTION';
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
        rootKind: null,
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
        canRevealMissing: false,
        expanding: false,
        expandedComplete: false,
        expandedNodes: 0,
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
    type PendingRow = Omit<TreeRow, 'reference' | 'cycle' | 'referenceTarget'>;
    type Visit = { row: PendingRow } | { exit: number[] };
    function rootKey(index: number) {
        return `root:${index}`;
    }
    function rootVisits(): Visit[] {
        const visible = new Set(visibleRoots());
        const stack: Visit[] = [];
        for (let index = roots.length - 1; index >= 0; index--) {
            const node = byId[roots[index]];
            if (!node || !visible.has(node.id)) continue;
            stack.push({
                row: {
                    key: rootKey(index),
                    node,
                    depth: 0,
                    quantity: index === 0 ? (state.root?.quantity ?? null) : null,
                    relation:
                        node.kind === 'UNPLACED'
                            ? 'UNPLACED'
                            : node.kind === 'UNLINKED_MISSING'
                              ? 'MISSING_RESOURCE'
                              : null
                }
            });
        }
        return stack;
    }
    function visit(stack: Visit[], ancestors: Set<number>, seen: Map<number, string>, row: PendingRow) {
        const cycle =
            ancestors.has(row.node.id) ||
            (row.group !== undefined && (ancestors.has(row.group.id) || row.group.id === row.node.id));
        const target = seen.get(row.node.id) ?? (row.group ? seen.get(row.group.id) : undefined);
        const reference = cycle || target !== undefined;
        if (!reference) {
            const ids = row.group ? [row.group.id, row.node.id] : [row.node.id];
            for (const id of ids) {
                ancestors.add(id);
                seen.set(id, row.key);
            }
            stack.push({ exit: ids });
        }
        return { reference, cycle, referenceTarget: target ?? null };
    }
    function descend(stack: Visit[], row: PendingRow, ancestors: Set<number>, seen: Map<number, string>) {
        const links = row.node.children;
        for (let index = links.length - 1; index >= 0; index--) {
            const child = links[index];
            if (missingOnly && !child.missing) continue;
            const node = byId[child.nodeId];
            if (!node) continue;
            const only = ancestors.has(node.id) || seen.has(node.id) ? null : singleCandidate(node);
            const shown = only ? byId[only.nodeId] : node;
            if (!shown) continue;
            const edge = only ?? child;
            stack.push({
                row: {
                    key: `edge:${row.node.id}:${index}`,
                    node: shown,
                    depth: row.depth + 1,
                    quantity: edge.quantity,
                    relation: edge.relation,
                    ...(only ? { group: node } : {})
                }
            });
        }
    }
    function publish() {
        const rows: TreeRow[] = [];
        const nodes: Record<string, TreeNode> = {};
        const stack = rootVisits();
        const ancestors = new Set<number>();
        const seen = new Map<number, string>();
        while (stack.length) {
            const item = stack.pop()!;
            if ('exit' in item) {
                for (const id of item.exit) ancestors.delete(id);
                continue;
            }
            const row = item.row;
            const { reference, cycle, referenceTarget } = visit(stack, ancestors, seen, row);
            rows.push({ ...row, reference, cycle, referenceTarget });
            nodes[row.key] = row.node;
            if (!reference && state.expanded[row.key]) descend(stack, row, ancestors, seen);
        }
        state.rows = rows;
        state.nodes = nodes;
        state.canRevealMissing =
            missingOnly &&
            state.status === 'READY' &&
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
            fullExpansion = null;
            missingExpansion = null;
            fullComplete = false;
            missingExpandedComplete = false;
        }
        state.captureId = document.captureId ?? null;
        state.status = document.state;
        state.mode = document.mode ?? null;
        state.root = document.root ?? null;
        state.rootKind = document.nodes.find((node) => node.id === document.roots[0])?.kind ?? null;
        state.partial = document.partial;
        state.limits = document.limits;
        state.missingComplete = document.missingComplete;
        state.missingResourceCount = document.missingResourceCount;
        state.icons = icons;
        state.resources = Object.fromEntries(document.resources.map((resource) => [resource.id, resource]));
        state.recipes = Object.fromEntries(document.recipes.map((recipe) => [recipe.id, recipe]));
        byId = Object.fromEntries(document.nodes.map((node) => [node.id, node]));
        roots = document.roots;
        if (!sameCapture && roots.length && byId[roots[0]]) {
            const key = rootKey(0);
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
            const row = state.rows.find(
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
        const stack = rootVisits();
        const ancestors = new Set<number>();
        const seen = new Map<number, string>();
        publish();
        try {
            while (stack.length && run === expansionRun && state.visible) {
                for (let count = 0; count < EXPANSION_CHUNK && stack.length;) {
                    const item = stack.pop()!;
                    if ('exit' in item) {
                        for (const id of item.exit) ancestors.delete(id);
                        continue;
                    }
                    const row = item.row;
                    const { reference } = visit(stack, ancestors, seen, row);
                    state.expandedNodes++;
                    count++;
                    if (reference || !expandable(row.node)) continue;
                    state.expanded[row.key] = true;
                    descend(stack, row, ancestors, seen);
                }
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
                if (firstMissingView && roots.length && byId[roots[0]]) state.expanded[rootKey(0)] = true;
            } else {
                missingExpansion = state.expanded;
                missingExpandedComplete = wasComplete;
                state.expanded = fullExpansion ?? {};
                state.expandedComplete = fullComplete;
                fullExpansion = null;
            }
            missingOnly = value;
            state.missingOnly = value;
            publish();
            if (firstMissingView) await revealMissing();
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
            if (!node || !expandable(node) || state.rows.some((row) => row.key === key && row.reference)) return;
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
