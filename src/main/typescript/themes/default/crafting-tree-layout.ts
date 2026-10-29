import type { TreeKind } from '../../app/crafting-tree.js';

type LayoutRow = { readonly depth: number; readonly node: { readonly kind: TreeKind } };
export type TreePosition = { x: number; y: number; width: number; height: number; parent: number | null };

/** Lay out only the bounded visible preorder: leaf rows provide space, ancestors share their span. */
export function layoutCraftingTree(rows: readonly LayoutRow[]) {
    const widths: Record<TreeKind, number> = {
        RESOURCE: 240,
        RECIPE: 208,
        INGREDIENT: 176,
        UNPLACED: 208,
        UNLINKED_MISSING: 208
    };
    const positions: TreePosition[] = [];
    const ancestors: { depth: number; index: number }[] = [];
    const children: number[][] = rows.map(() => []);
    const levels: number[] = [];
    for (const [index, row] of rows.entries()) {
        while (ancestors.length && ancestors[ancestors.length - 1].depth >= row.depth) ancestors.pop();
        const ancestor = ancestors[ancestors.length - 1];
        const parent = ancestor?.depth === row.depth - 1 ? ancestor.index : null;
        const level = parent === null ? 0 : levels[parent] + 1;
        levels.push(level);
        if (parent !== null) children[parent].push(index);
        const width = widths[row.node.kind];
        positions.push({ x: 0, y: 0, width, height: 136, parent });
        ancestors.push({ depth: row.depth, index });
    }
    let nextLeaf = 16;
    let height = 152;
    for (const [index, position] of positions.entries()) {
        position.y = 16 + levels[index] * 168;
        height = Math.max(height, position.y + position.height + 16);
        if (children[index].length === 0) {
            position.x = nextLeaf + (240 - position.width) / 2;
            nextLeaf += 272;
        }
    }
    for (let index = positions.length - 1; index >= 0; index--) {
        const descendants = children[index];
        if (descendants.length) {
            const first = positions[descendants[0]];
            const last = positions[descendants[descendants.length - 1]];
            positions[index].x = (first.x + first.width / 2 + last.x + last.width / 2) / 2 - positions[index].width / 2;
        }
    }
    return { nodes: positions, width: Math.max(272, nextLeaf), height };
}
