/**
 * JSON contracts from the core endpoint responses and JSON_* DTOs.
 * These declarations describe the wire format; they do not validate responses at runtime.
 */
export type Bootstrap = {
    publicMode: boolean;
    modVersion: string | null;
    isOutdated: boolean;
    user: { username: string; isAdmin: boolean } | null;
};
export type Stack = { itemid: string; itemname: string; quantity: number; itemKey: string | null };
export type IconReference = { page: number; x: number; y: number };
export type IconMetadata = {
    packId: string;
    width: number;
    height: number;
    pages: { digest: string; width: number; height: number }[];
};
export type ResourceResponse<T> = { data: T; icons: IconMetadata | null };
export type Item = Stack & { craftable: boolean; identityStatus: string | null; icon?: IconReference | null };
export type Position = { dimid: string; x: number; y: number; z: number };
export type AccessSource = {
    player: { uuid: string; name: string };
    kind: string;
    position: Position;
    side: string | null;
    reason: string;
};
export type AccessSources = Record<string, AccessSource[]>;
export type Grid = {
    key: string;
    cpuCount: number;
    owner: string;
    isOwned: boolean;
    isTrackingEnabled: boolean;
    accessSources: AccessSources;
};
export type GridSettings = { isTracked: boolean };
export type CpuInfo = {
    name: string;
    isBusy: boolean;
    supportsPause: boolean;
    isPaused: boolean;
    finalOutput: Stack | null;
    availableStorage: number;
    usedStorage: number;
    coProcessors: number;
    hasTrackingInfo: boolean;
    timeStarted: number;
};
export type CpuItem = {
    itemKey?: string | null;
    icon?: IconReference | null;
    itemid: string;
    itemname: string;
    active: number;
    pending: number;
    stored: number;
    timeSpentCrafting: number;
    craftedTotal: number;
    shareInCraftingTime: number;
    shareInCraftingTimeCombined: number;
    craftsPerSec: number;
};
export type CpuDetail = {
    size: number;
    isBusy: boolean;
    supportsPause: boolean;
    isPaused: boolean;
    finalOutput: Stack | null;
    items: CpuItem[] | null;
    hasTrackingInfo: boolean;
    timeStarted: number;
    timeElapsed: number;
};
export type PlanItem = {
    itemid: string;
    itemname: string;
    stored: number;
    requested: number;
    missing: number;
    steps: number;
    usedPercent: number;
};
export type Plan = { isDone: boolean; isSimulating: boolean; bytesTotal: number; plan: PlanItem[] | null };
export type HistoryEntry = {
    timeStarted: number;
    timeDone: number;
    wasCancelled: boolean;
    finalOutput: Stack;
    id: number;
};
export type Timing = { started: number; ended: number };
export type HistoryItem = {
    itemid: string;
    itemname: string;
    timeSpentOn: number;
    craftedTotal: number;
    shareInCraftingTime: number;
    shareInCraftingTimeCombined: number;
    craftsPerSec: number;
    timings: Timing[];
};
export type ProviderTiming = { name: string; timings: Timing[]; timingsCombined: number; location: Position[] };
export type HistoryDetail = {
    finalOutput: Stack;
    timeStarted: number;
    timeDone: number;
    wasCancelled: boolean;
    items: HistoryItem[];
    interfaceShare: ProviderTiming[];
};
