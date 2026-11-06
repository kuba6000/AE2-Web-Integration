import type { PlanItem } from './api-types.js';
import type { PlanExportRequest } from './plan-export.js';

/** Visible metrics and their order are independent of the image format. */
export function planExportAmounts(
    row: Readonly<PlanItem>,
    request: Pick<PlanExportRequest, 'settings' | 'number' | 'labels'>
) {
    const { settings } = request;
    const amounts: { value: string; label: string; usage?: string }[] = [];
    for (const field of ['missing', 'requested', 'steps', 'stored'] as const) {
        if (!settings[field] || row[field] <= 0) continue;
        amounts.push({
            value: request.number(row[field]),
            label: request.labels[field],
            usage:
                field === 'stored' && settings.usage && row.usedPercent > 0
                    ? ` (${Math.round(row.usedPercent * 100)}%)`
                    : undefined
        });
    }
    if (!settings.stored && settings.usage && row.stored > 0 && row.usedPercent > 0)
        amounts.push({ value: `${Math.round(row.usedPercent * 100)}%`, label: request.labels.usage });
    return amounts;
}
