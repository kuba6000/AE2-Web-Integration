import type { Grid } from '../../app/api-types.js';
import type { Translator } from '../../app/i18n.js';

export function networkLabel(grid: Grid, locale: Translator) {
    return grid.name || `${grid.owner || locale.common('unknownOwner')} · ${grid.key.slice(0, 8)}`;
}
export function networkOwner(grid: Grid, locale: Translator) {
    return locale.common('gridOwner', { owner: grid.owner || locale.common('unknownOwner') });
}
