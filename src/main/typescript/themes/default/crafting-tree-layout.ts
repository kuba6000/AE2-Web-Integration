import type { TreeKind } from '../../app/crafting-tree.js';

type LayoutRow = { readonly depth: number; readonly node: { readonly kind: TreeKind } };
export type TreePosition = { x: number; y: number; width: number; height: number; parent: number | null };

/** Lay out only the bounded visible preorder: leaf rows provide space, ancestors share their span. */
export function layoutCraftingTree(rows: readonly LayoutRow[]) {
    const widths: Record<TreeKind, number> = { RESOURCE: 240, RECIPE: 208, INGREDIENT: 176, UNPLACED: 208 };
    const positions: TreePosition[] = [];
    const ancestors: { depth: number; index: number }[] = [];
    const children: number[][] = rows.map(() => []);
    const levels: number[] = [];
    const columnWidths: number[] = [];
    for (const [index, row] of rows.entries()) {
        while (ancestors.length && ancestors[ancestors.length - 1].depth >= row.depth) ancestors.pop();
        const ancestor = ancestors[ancestors.length - 1];
        const parent = ancestor?.depth === row.depth - 1 ? ancestor.index : null;
        const level = parent === null ? 0 : levels[parent] + 1;
        levels.push(level);
        if (parent !== null) children[parent].push(index);
        const width = widths[row.node.kind];
        columnWidths[level] = Math.max(columnWidths[level] ?? 0, width);
        positions.push({ x: 0, y: 0, width, height: 136, parent });
        ancestors.push({ depth: row.depth, index });
    }
    const columns: number[] = [];
    let width = 16;
    for (const column of columnWidths) {
        columns.push(width);
        width += column + 32;
    }
    let nextLeaf = 16;
    for (const [index, position] of positions.entries()) {
        position.x = columns[levels[index]];
        if (children[index].length === 0) {
            position.y = nextLeaf;
            nextLeaf += position.height + 16;
        }
    }
    for (let index = positions.length - 1; index >= 0; index--) {
        const descendants = children[index];
        if (descendants.length)
            positions[index].y = (positions[descendants[0]].y + positions[descendants[descendants.length - 1]].y) / 2;
    }
    return { nodes: positions, width: Math.max(272, width), height: Math.max(152, nextLeaf) };
}
