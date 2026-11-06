import type { TreeKind } from '../../app/crafting-tree.js';
import type { TerminalDisplay } from './web-settings.js';

type LayoutRow = { readonly depth: number; readonly node: { readonly kind: TreeKind } };
export type TreePosition = { x: number; y: number; width: number; height: number; parent: number | null };

/** Preorder leaf columns keep earlier branches fixed as later branches open. */
export function layoutCraftingTree(rows: readonly LayoutRow[], display: TerminalDisplay = 'both') {
    const compact = display === 'icons';
    const widths: Record<TreeKind, number> = {
        RESOURCE: 240,
        RECIPE: 160,
        INGREDIENT: 176,
        UNPLACED: 208,
        UNLINKED_MISSING: 208,
        REQUEST: 240,
        TASK: 160,
        CONTRIBUTION: 240
    };
    const column = compact ? 64 : 240;
    const cardHeight = compact ? 64 : 72;
    const stepX = column + 32;
    const stepY = cardHeight + 40;
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
        const recipe = row.node.kind === 'TASK' || row.node.kind === 'RECIPE';
        const width = compact ? column : widths[row.node.kind] - (display === 'names' && !recipe ? 24 : 0);
        positions.push({ x: 0, y: 16 + level * stepY, width, height: cardHeight, parent });
        ancestors.push({ depth: row.depth, index });
    }
    let nextLeaf = 16;
    let height = cardHeight + 32;
    for (const [index, position] of positions.entries()) {
        height = Math.max(height, position.y + position.height + 16);
        if (children[index].length === 0) {
            position.x = nextLeaf + (column - position.width) / 2;
            nextLeaf += stepX;
        }
    }
    for (let index = positions.length - 1; index >= 0; index--) {
        if (children[index].length) {
            const first = positions[children[index][0]];
            positions[index].x = first.x + (first.width - positions[index].width) / 2;
        }
    }
    return { nodes: positions, width: Math.max(column + 32, nextLeaf - stepX + column + 16), height };
}

/** Shared card artwork/text bounds for both image formats. */
export function treeCardGeometry(node: TreePosition, compact: boolean, resource: boolean, symbol: boolean) {
    const iconSize = 32;
    const iconX = node.x + (compact ? (node.width - iconSize) / 2 : 12);
    const iconY = node.y + (node.height - iconSize) / 2;
    const symbolSize = resource ? 16 : 32;
    const symbolX = resource ? node.x + 4 : iconX;
    const symbolY = resource ? node.y + 5 : node.y + (node.height - symbolSize) / 2;
    const textX = node.x + (resource || symbol ? 56 : 12);
    const textWidth = node.width - (textX - node.x) - 16;
    return { iconSize, iconX, iconY, symbolSize, symbolX, symbolY, textX, textWidth };
}

/** Center the visible title/detail lines as one block; full details remain in the tooltip. */
export function treeCardTextLayout(cardHeight: number, detailCount: number, title = true) {
    const lineCount = Math.min(detailCount, 2, Math.max(0, Math.floor((cardHeight - 36) / 14)));
    const detailOffset = title ? 18 + (lineCount ? 2 : 0) : 0;
    const height = detailOffset + lineCount * 14;
    return { lineCount, height, top: (cardHeight - height) / 2, detailOffset };
}
