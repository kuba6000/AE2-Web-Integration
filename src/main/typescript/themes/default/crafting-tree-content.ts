import type { TreeRow, TreeState } from '../../app/crafting-tree.js';
import type { ReadonlyData } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';

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
    if (row.relation === 'RECIPE_OUTPUT')
        details.push(
            t(
                row.primary === true
                    ? 'treePrimaryOutput'
                    : row.primary === false
                      ? 'treeSecondaryOutput'
                      : 'treeUnknownOutput'
            )
        );
    if (row.quantity !== null)
        details.push(
            `${t(row.relation === 'DECLARED_OUTPUT' || row.relation === 'RECIPE_OUTPUT' ? 'treePerCraft' : row.relation === 'DECLARED_ALTERNATIVE' ? 'treePerTemplate' : 'quantity')}: ${number(row.quantity)}`
        );
    else if (['DECLARED_OUTPUT', 'RECIPE_OUTPUT', 'DECLARED_ALTERNATIVE'].includes(row.relation ?? ''))
        details.push(t('treeUnknownQuantity'));
    if (row.node.crafts !== null) details.push(`${t('treeCrafts')}: ${number(row.node.crafts)}`);
    if (row.node.multiplier !== null) details.push(`${t('treeMultiplier')}: ${number(row.node.multiplier)}`);
    if (row.node.complete === false) details.push(t('treePartialNode'));
    const missing = resource?.missingAmount != null && BigInt(resource.missingAmount) > 0n;
    if (missing) details.unshift(`${t('treeMissingInPlan')}: ${number(resource.missingAmount!)}`);
    else if (resource && resource.missingAmount == null) details.push(t('treeMissingUnknown'));
    return { title, details, icon: resource?.icon ?? null, missing, reference: row.reference };
}
