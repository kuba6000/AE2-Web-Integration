import type { TreeContribution, TreeRow, TreeState } from '../../app/crafting-tree.js';
import type { ReadonlyData } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';

const contributionLabels: Record<TreeContribution, string> = {
    STORAGE: 'treeFromStorage',
    EMITTED: 'treeEmitted',
    MISSING: 'treeMissingLeaf',
    REUSED: 'treeReused',
    UNKNOWN: 'treeUnknownSource'
};

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
    const resource = row.node.resourceId != null ? state.resources[row.node.resourceId] : null;
    const reference = row.reference ? [t(interactive ? 'treeReference' : 'treeReferenceStatic')] : [];
    const outputs = row.node.outputs?.length
        ? `${t('treeOutputs')}: ${row.node.outputs
              .map((output) => {
                  const name = plainMinecraftText(state.resources[output.resourceId]?.displayName ?? '?');
                  return `${name} ×${number(output.quantity)}${output.primary === false ? ` (${t('treeByproduct')})` : ''}`;
              })
              .join(', ')}`
        : null;
    // Trace nodes mirror the in-game tree: a request states its item and amount, each answer to it is a leaf, a
    // craft is a task card whose children are the requests for its ingredients.
    if (row.node.kind === 'REQUEST')
        return {
            title: resource?.displayName ?? t('treeResource'),
            details: [...reference, ...(row.quantity !== null ? [`${t('quantity')}: ${number(row.quantity)}`] : [])],
            icon: resource?.icon ?? null,
            missing: false,
            reference: row.reference
        };
    if (row.node.kind === 'CONTRIBUTION') {
        const label = t(contributionLabels[row.node.contribution ?? 'UNKNOWN']);
        return {
            title: row.quantity !== null ? `${label} ${number(row.quantity)}` : label,
            details: resource ? [plainMinecraftText(resource.displayName)] : [],
            icon: resource?.icon ?? null,
            missing: row.node.contribution === 'MISSING',
            reference: false
        };
    }
    if (row.node.kind === 'TASK') {
        const inputs = row.node.inputs?.length
            ? `${t('treeInputs')}: ${row.node.inputs
                  .map(
                      (input) =>
                          `${plainMinecraftText(state.resources[input.resourceId]?.displayName ?? '?')} ×${number(input.quantity)}`
                  )
                  .join(', ')}`
            : null;
        const details = [
            ...reference,
            ...(row.quantity !== null ? [`${t('treeCraftAmount')}: ${number(row.quantity)}`] : []),
            ...(row.node.crafts !== null ? [`${t('treeCrafts')}: ${number(row.node.crafts)}`] : []),
            ...(outputs ? [outputs] : []),
            ...(inputs ? [inputs] : []),
            ...(row.node.complete === false ? [t('treePartialNode')] : [])
        ];
        return { title: t('treeRecipe'), details, icon: null, missing: false, reference: row.reference };
    }
    const aggregateResource = row.node.kind === 'RESOURCE' ? state.resources[row.node.id] : null;
    const title =
        aggregateResource?.displayName ??
        t(
            {
                RESOURCE: 'treeResource',
                RECIPE: 'treeRecipe',
                INGREDIENT: 'treeIngredient',
                UNPLACED: 'treeUnplaced',
                UNLINKED_MISSING: 'treeUnlinkedMissing'
            }[row.node.kind]
        );
    const details: string[] = [...reference];
    // A recipe reached from a resource states its own outputs below, which include that resource's amount.
    if (row.relation !== 'DECLARED_OUTPUT') {
        if (row.quantity !== null)
            details.push(
                `${t(row.relation === 'DECLARED_ALTERNATIVE' ? 'treePerTemplate' : 'quantity')}: ${number(row.quantity)}`
            );
        else if (row.relation === 'DECLARED_ALTERNATIVE') details.push(t('treeUnknownQuantity'));
    }
    if (outputs) details.push(outputs);
    if (row.node.crafts !== null) details.push(`${t('treeCrafts')}: ${number(row.node.crafts)}`);
    const multiplier = row.node.multiplier ?? row.group?.multiplier ?? null;
    if (multiplier !== null) details.push(`${t('treeMultiplier')}: ${number(multiplier)}`);
    if (row.node.complete === false) details.push(t('treePartialNode'));
    const missing = aggregateResource?.missingAmount != null && BigInt(aggregateResource.missingAmount) > 0n;
    if (missing) details.unshift(`${t('treeMissingInPlan')}: ${number(aggregateResource.missingAmount!)}`);
    else if (aggregateResource && aggregateResource.missingAmount == null) details.push(t('treeMissingUnknown'));
    return {
        title,
        details,
        icon: aggregateResource?.icon ?? null,
        missing,
        reference: row.reference
    };
}
