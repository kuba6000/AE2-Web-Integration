import type { CpuDetail, CpuHeader, CpuResource } from './api-types.js';

/** A resource exactly as the server sent it, with the local time that value arrived. */
export type CpuMeasured = { resource: CpuResource; receivedAt: number };

/**
 * The detail a theme displays at `now`. The server sends measurements as of the moment it built them; a running
 * measurement continues from the local time it arrived, so no traffic is needed for clock progress. Timing ratios
 * are derived here from those values, preserving input order for theme-owned sorting. Without a clock to extend, pass the time the data arrived.
 */
export function deriveCpuDetail(
    header: CpuHeader,
    headerAt: number,
    rows: readonly CpuMeasured[] | null,
    now: number
): CpuDetail {
    const since = (at: number) => Math.max(0, now - at);
    const timeElapsed = header.timeElapsed + (header.isBusy && header.hasTrackingInfo ? since(headerAt) : 0);
    if (!rows) return { ...header, timeElapsed, items: null };
    const spent = rows.map(
        ({ resource, receivedAt }) => resource.timeSpentCrafting + (resource.crafting ? since(receivedAt) : 0)
    );
    const total = spent.reduce((sum, value) => sum + value, 0);
    const items = rows.map(({ resource }, index) =>
        header.hasTrackingInfo
            ? {
                  ...resource,
                  timeSpentCrafting: spent[index],
                  shareInCraftingTime: total === 0 ? 1 : spent[index] / total,
                  shareInCraftingTimeCombined: timeElapsed > 0 ? Math.min(spent[index] / timeElapsed, 1) : 0,
                  craftsPerSec: spent[index] > 0 ? resource.craftedTotal / (spent[index] / 1000) : 0
              }
            : resource
    );
    return { ...header, timeElapsed, items };
}
