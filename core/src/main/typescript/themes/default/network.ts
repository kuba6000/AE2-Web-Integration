import type { Grid } from '../../app/api-types.js';

export function networkLabel(grid: Pick<Grid, 'name' | 'key'>) {
    return grid.name || grid.key;
}
