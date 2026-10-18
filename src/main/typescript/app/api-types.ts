/**
 * JSON contracts from the core endpoint responses and API models.
 * These declarations describe the wire format; they do not validate responses at runtime.
 */
export type ApplicationContext = {
    capabilities: {
        craftingLightMode?: boolean;
        craftingIgnoreMissing?: boolean;
        craftingPlanSteps?: boolean;
        cpuSelectionMode?: boolean;
    };
    publicMode: boolean;
    modVersion: string | null;
    isOutdated: boolean;
    availableUpdate: AvailableUpdate | null;
    user: { username: string; isAdmin: boolean } | null;
};
export type AvailableUpdate = {
    version: string;
    tag: string;
    releaseUrl: string;
    skipped: boolean;
};
export type ResourceDescription = {
    resourceType: 'ITEM' | 'FLUID' | 'OTHER';
    itemKey: string | null;
    icon: IconReference | null;
    displayName: string;
    registryNamespace: string | null;
    registryPath: string | null;
    componentCount: number;
    damage: number;
};
export type ResourceStack = ResourceDescription & {
    quantity: number;
};
export type IconReference = { page: number; x: number; y: number };
export type IconMetadata = {
    packId: string;
    width: number;
    height: number;
    pages: { digest: string; width: number; height: number }[];
};
export type TerminalView = { generation: string; revision: string };
export type RecentResource = { input: string | null; output: string | null; stockChange: string | null };
export type RecentTracking = {
    flowWindowMillis: number;
    stockWindowMillis: number;
    bucketMillis: number;
    observedAt: number;
    flowObservedAt: number | null;
    stockObservedAt: number | null;
    flowSince: number | null;
    stockSince: number | null;
    flowComplete: boolean;
    stockComplete: boolean;
    resources: Record<string, RecentResource>;
};
export type TerminalDelta = {
    recent?: RecentTracking;
    view: TerminalView;
    updates: { itemKey: string; quantity: number; craftable: boolean }[];
    definitions: StoredResource[];
    removed: string[];
    unkeyed?: StoredResource[] | null;
    icons: IconMetadata | null;
};
export type ResourceResponse<T> = {
    data: T;
    icons: IconMetadata | null;
    view?: TerminalView;
    subscription?: string;
    recent?: RecentTracking;
};
export type StoredResource = ResourceStack & {
    craftable: boolean;
};
export type Position = { dimensionId: string; x: number; y: number; z: number };
export type AccessSource = {
    player: { uuid: string; name: string };
    kind: string;
    position: Position;
    side: string | null;
    reason: string;
};
export type AccessSources = Record<string, AccessSource[]>;
export type Grid = {
    name: string;
    key: string;
    cpuCount: number;
    owner: string;
    isOwned: boolean;
    isTrackingEnabled: boolean;
    accessSources: AccessSources;
};
export type GridSettings = {
    isTracked: boolean;
    isStorageTracked: boolean;
    isFlowTracked: boolean;
    stockSampleIntervalSeconds: number;
    flowFlushIntervalSeconds: number;
    historyRetentionDays: number;
    name: string;
};
export type HistorySetting = 'stockSampleIntervalSeconds' | 'flowFlushIntervalSeconds' | 'historyRetentionDays';
export type SettingRange = { min: number; max: number; defaultValue: number };
export type GridSettingsLimits = Record<HistorySetting, SettingRange> & { historyMaxRowsPerGrid: number };
export type GridSettingsResponse = GridSettings & { limits: GridSettingsLimits };
export type CpuSelectionMode = 'PLAYER_ONLY' | 'AUTOMATION_ONLY' | 'ALL';
export type CpuInfo = {
    acceptsPlayerJobs: boolean;
    selectionMode: CpuSelectionMode | null;
    name: string;
    isBusy: boolean;
    supportsPause: boolean;
    isPaused: boolean;
    finalOutput: ResourceStack | null;
    availableStorage: number;
    usedStorage: number;
    coProcessors: number;
    hasTrackingInfo: boolean;
    timeStarted: number;
};
export type CpuResource = ResourceDescription & {
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
    selectionMode: CpuSelectionMode | null;
    size: number;
    isBusy: boolean;
    supportsPause: boolean;
    isPaused: boolean;
    finalOutput: ResourceStack | null;
    items: CpuResource[] | null;
    hasTrackingInfo: boolean;
    timeStarted: number;
    timeElapsed: number;
};
export type PlanItem = ResourceDescription & {
    stored: number;
    requested: number;
    missing: number;
    steps: number;
    usedPercent: number;
};
export type Plan = {
    finalOutput: ResourceStack;
    options: { lightMode: boolean; ignoreMissing: boolean };
    autoStart: boolean;
    isDone: boolean;
    isSimulating: boolean;
    bytesTotal: number;
    plan: PlanItem[] | null;
};
export type HistoryEntry = {
    timeStarted: number;
    timeDone: number;
    wasCancelled: boolean;
    finalOutput: ResourceStack;
    id: number;
};
export type Timing = { started: number; ended: number };
export type ResourceTiming = ResourceDescription & {
    providers: string[];
    timeSpentOn: number;
    craftedTotal: number;
    shareInCraftingTime: number;
    shareInCraftingTimeCombined: number;
    craftsPerSec: number;
    timings: Timing[];
};
export type ProviderTiming = { name: string; timings: Timing[]; timingsCombined: number; location: Position[] };
export type CraftingHistory = {
    finalOutput: ResourceStack;
    timeStarted: number;
    timeDone: number;
    wasCancelled: boolean;
    items: ResourceTiming[];
    interfaceShare: ProviderTiming[];
};

/** Tracking counters use decimal strings so long values are never rounded by JSON parsing. */
export type ResourceHistoryPoint = {
    flowFrom: number | null;
    observedAt: number;
    generation: string;
    kind: 'STOCK' | 'FLOW' | 'OBSERVATION' | 'FLOW_OBSERVATION' | 'GAP';
    resource: (ResourceDescription & { quantity: string }) | null;
    input: string;
    output: string;
};
export type ResourceHistory = {
    baseline: ResourceHistoryPoint | null;
    points: ResourceHistoryPoint[];
    complete: boolean;
    truncated: boolean;
    enabled: boolean;
};
