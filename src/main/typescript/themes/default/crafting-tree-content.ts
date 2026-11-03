import type { TreeContribution, TreeRow, TreeState } from '../../app/crafting-tree.js';
import type { ReadonlyData } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';

const contributionLabels: Record<TreeContribution, string> = {
    STORAGE: 'treeFromStorage',
    EMITTED: 'treeEmitted',
    MISSING: 'treeMissingLeaf',
    REUSED: 'treeReused',
    UNKNOWN: 'treeUnknownSource'
};

export type TreeSymbol = 'craft' | 'storage' | 'emitted' | 'missing' | 'reused' | 'unknown';

/** Screen and PNG share the same resource, amount, and source presentation. */
export function craftingTreeContent(
    row: ReadonlyData<TreeRow>,
    state: ReadonlyData<TreeState>,
    locale: Translator,
    language: string
) {
    const t = locale.common;
    const number = (value: string) => BigInt(value).toLocaleString(language || 'en');
    const compact = (value: string | null) =>
        value === null
            ? ''
            : new Intl.NumberFormat(language || 'en', { notation: 'compact', maximumFractionDigits: 1 }).format(
                  BigInt(value)
              );
    const resource = row.node.resourceId != null ? state.resources[row.node.resourceId] : null;
    const reference = row.reference ? [t(row.cycle ? 'treeCycle' : 'treeShared')] : [];
    if (row.node.kind === 'TASK' || row.node.kind === 'RECIPE') {
        const details = [
            ...reference,
            row.node.crafts !== null
                ? `${t('treeCrafts')}: ${number(row.node.crafts)}`
                : `${t('treeCrafts')}: ${t('treeUnknownQuantity')}`,
            ...(row.node.complete === false ? [t('treePartialNode')] : [])
        ];
        return {
            title: t('treeRecipe'),
            details,
            icon: null,
            hasResource: false,
            symbol: 'craft' as TreeSymbol,
            count: compact(row.node.crafts),
            missing: false,
            reference: row.reference
        };
    }
    if (row.node.kind === 'CONTRIBUTION') {
        const source = row.node.contribution ?? 'UNKNOWN';
        const label = t(contributionLabels[source]);
        const symbol: TreeSymbol = source.toLowerCase() as TreeSymbol;
        return {
            title: resource?.displayName ?? t('treeResource'),
            details: [...reference, row.quantity !== null ? `${label}: ${number(row.quantity)}` : label],
            icon: resource?.icon ?? null,
            hasResource: !!resource,
            symbol,
            count: compact(row.quantity),
            missing: source === 'MISSING',
            reference: row.reference
        };
    }
    const title =
        resource?.displayName ??
        t(
            {
                RESOURCE: 'treeResource',
                REQUEST: 'treeResource',
                INGREDIENT: 'treeIngredient',
                UNPLACED: 'treeUnplaced',
                UNLINKED_MISSING: 'treeUnlinkedMissing'
            }[row.node.kind]
        );
    const details: string[] = [...reference];
    if (row.quantity !== null)
        details.push(
            `${t(row.relation === 'DECLARED_ALTERNATIVE' ? 'treePerTemplate' : 'quantity')}: ${number(row.quantity)}`
        );
    else if (row.relation === 'DECLARED_ALTERNATIVE') details.push(t('treeUnknownQuantity'));
    const multiplier = row.node.multiplier ?? row.group?.multiplier ?? null;
    if (multiplier !== null) details.push(`${t('treeMultiplier')}: ${number(multiplier)}`);
    if (row.node.complete === false) details.push(t('treePartialNode'));
    const missing =
        row.node.kind === 'RESOURCE' && resource?.missingAmount != null && BigInt(resource.missingAmount) > 0n;
    if (missing) details.unshift(`${t('treeMissingInPlan')}: ${number(resource!.missingAmount!)}`);
    else if (row.node.kind === 'RESOURCE' && resource && resource.missingAmount == null)
        details.push(t('treeMissingUnknown'));
    return {
        title,
        details,
        icon: resource?.icon ?? null,
        hasResource: !!resource,
        symbol: null,
        count: compact(row.quantity),
        missing,
        reference: row.reference
    };
}
