import type { Grid } from '../../app/api-types.js';

export function networkLabel(grid: Grid) {
    return grid.name || grid.key;
}
