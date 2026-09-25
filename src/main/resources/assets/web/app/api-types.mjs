/**
 * JSON contracts from the core endpoint responses and JSON_* DTOs.
 * These declarations describe the wire format; they do not validate responses at runtime.
 * @typedef {{itemid: string, itemname: string, quantity: number, itemKey: string | null}} Stack
 * @typedef {Stack & {craftable: boolean, identityStatus: string | null}} Item
 * @typedef {{dimid: string, x: number, y: number, z: number}} Position
 * @typedef {{player: {uuid: string, name: string}, kind: string, position: Position, side: string | null, reason: string}} AccessSource
 * @typedef {Record<string, AccessSource[]>} AccessSources
 * @typedef {{key: string, cpuCount: number, owner: string, isOwned: boolean, isTrackingEnabled: boolean, accessSources: AccessSources}} Grid
 * @typedef {{isTracked: boolean}} GridSettings
 * @typedef {{name: string, isBusy: boolean, supportsPause: boolean, isPaused: boolean, finalOutput: Stack | null, availableStorage: number, usedStorage: number, coProcessors: number, hasTrackingInfo: boolean, timeStarted: number}} CpuInfo
 * @typedef {{itemid: string, itemname: string, active: number, pending: number, stored: number, timeSpentCrafting: number, craftedTotal: number, shareInCraftingTime: number, shareInCraftingTimeCombined: number, craftsPerSec: number}} CpuItem
 * @typedef {{size: number, isBusy: boolean, supportsPause: boolean, isPaused: boolean, finalOutput: Stack | null, items: CpuItem[] | null, hasTrackingInfo: boolean, timeStarted: number, timeElapsed: number}} CpuDetail
 * @typedef {{itemid: string, itemname: string, stored: number, requested: number, missing: number, steps: number, usedPercent: number}} PlanItem
 * @typedef {{isDone: boolean, isSimulating: boolean, bytesTotal: number, plan: PlanItem[] | null}} Plan
 * @typedef {{timeStarted: number, timeDone: number, wasCancelled: boolean, finalOutput: Stack, id: number}} HistoryEntry
 * @typedef {{started: number, ended: number}} Timing
 * @typedef {{itemid: string, itemname: string, timeSpentOn: number, craftedTotal: number, shareInCraftingTime: number, shareInCraftingTimeCombined: number, craftsPerSec: number, timings: Timing[]}} HistoryItem
 * @typedef {{name: string, timings: Timing[], timingsCombined: number, location: Position[]}} ProviderTiming
 * @typedef {{finalOutput: Stack, timeStarted: number, timeDone: number, wasCancelled: boolean, items: HistoryItem[], interfaceShare: ProviderTiming[]}} HistoryDetail
 */
export {};
