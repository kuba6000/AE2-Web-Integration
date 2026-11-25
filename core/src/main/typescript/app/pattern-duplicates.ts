import type { PatternProvider } from './api-types.js';
import type { ReadonlyData } from './theme-api.js';

type Provider = ReadonlyData<PatternProvider>;
type SlotLocation = { provider: Provider; slot: number };
export type DuplicateOutput = { count: number; locations: SlotLocation[] };
const exampleLimit = 6;

/** Counts physical pattern slots producing each resource, including providers hidden by UI filters. */
export function indexPatternOutputs(providers: readonly Provider[]): Map<string, DuplicateOutput> {
    const first = new Map<string, SlotLocation>();
    const duplicates = new Map<string, DuplicateOutput>();
    const slotOutputs = new Set<string>();
    for (const provider of providers) {
        for (let slot = 0; slot < provider.slots.length; slot++) {
            const pattern = provider.slots[slot].pattern;
            if (!pattern || pattern.kind === 'invalid') continue;
            slotOutputs.clear();
            for (const { itemKey } of pattern.outputs) {
                if (itemKey === null || slotOutputs.has(itemKey)) continue;
                slotOutputs.add(itemKey);
                const duplicate = duplicates.get(itemKey);
                if (duplicate) {
                    duplicate.count++;
                    if (duplicate.locations.length < exampleLimit) duplicate.locations.push({ provider, slot });
                } else {
                    const original = first.get(itemKey);
                    if (original) {
                        duplicates.set(itemKey, { count: 2, locations: [original, { provider, slot }] });
                        first.delete(itemKey);
                    } else first.set(itemKey, { provider, slot });
                }
            }
        }
    }
    return duplicates;
}
