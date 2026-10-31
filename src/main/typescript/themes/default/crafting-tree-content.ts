import type { TreeRow, TreeState } from '../../app/crafting-tree.js';
import type { ReadonlyData } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';

/** Shared screen/PNG wording; amounts remain exact plan totals or declared recipe quantities. */
export function craftingTreeContent(
    row: ReadonlyData<TreeRow>,
    state: ReadonlyData<TreeState>,
    locale: Translator,
    language: string,
    interactive = true
) {
    const t = locale.common;
    const number = (value: string) => BigInt(value).toLocaleString(language || 'en');
    const resource = row.node.kind === 'RESOURCE' ? state.resources[row.node.id] : null;
    const title =
        resource?.displayName ??
        t(
            {
                RESOURCE: 'treeResource',
                RECIPE: 'treeRecipe',
                INGREDIENT: 'treeIngredient',
                UNPLACED: 'treeUnplaced',
                UNLINKED_MISSING: 'treeUnlinkedMissing'
            }[row.node.kind]
        );
    const details: string[] = [];
    if (row.reference) details.push(t(interactive ? 'treeReference' : 'treeReferenceStatic'));
    // A recipe reached from a resource states its own outputs below, which include that resource's amount.
    if (row.relation !== 'DECLARED_OUTPUT') {
        if (row.quantity !== null)
            details.push(
                `${t(row.relation === 'DECLARED_ALTERNATIVE' ? 'treePerTemplate' : 'quantity')}: ${number(row.quantity)}`
            );
        else if (row.relation === 'DECLARED_ALTERNATIVE') details.push(t('treeUnknownQuantity'));
    }
    if (row.node.outputs?.length)
        details.push(
            `${t('treeOutputs')}: ${row.node.outputs
                .map((output) => {
                    const name = plainMinecraftText(state.resources[output.resourceId]?.displayName ?? '?');
                    return `${name} ×${number(output.quantity)}${output.primary === false ? ` (${t('treeByproduct')})` : ''}`;
                })
                .join(', ')}`
        );
    if (row.node.crafts !== null) details.push(`${t('treeCrafts')}: ${number(row.node.crafts)}`);
    const multiplier = row.node.multiplier ?? row.group?.multiplier ?? null;
    if (multiplier !== null) details.push(`${t('treeMultiplier')}: ${number(multiplier)}`);
    if (row.node.complete === false) details.push(t('treePartialNode'));
    const missing = resource?.missingAmount != null && BigInt(resource.missingAmount) > 0n;
    if (missing) details.unshift(`${t('treeMissingInPlan')}: ${number(resource.missingAmount!)}`);
    else if (resource && resource.missingAmount == null) details.push(t('treeMissingUnknown'));
    return { title, details, icon: resource?.icon ?? null, missing, reference: row.reference };
}
