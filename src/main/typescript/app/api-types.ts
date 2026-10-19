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
/** Changes to one live keyed list; updates carry absolute values for rows that already exist. */
export type LiveDelta<Row, Update> = {
    view: TerminalView;
    updates: Update[];
    definitions: Row[];
    removed: string[];
    unkeyed?: Row[] | null;
    icons: IconMetadata | null;
};
export type StoredResourceUpdate = { itemKey: string; quantity: number; craftable: boolean };
export type TerminalDelta = LiveDelta<StoredResource, StoredResourceUpdate> & { recent?: RecentTracking };
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
    crafting: boolean;
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
/** The CPU state without its resources; a live delta replaces it whole when it changes. */
export type CpuHeader = Omit<CpuDetail, 'items'>;
/** Absolute measurements of one resource; `timeSpentCrafting` is as of the delta and still grows while `crafting`. */
export type CpuResourceUpdate = {
    itemKey: string;
    active: number;
    pending: number;
    stored: number;
    craftedTotal: number;
    timeSpentCrafting: number;
    crafting: boolean;
};
export type CpuDelta = LiveDelta<CpuResource, CpuResourceUpdate> & { cpu?: CpuHeader | null };
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

export type PinPlacement = 'terminal' | 'home';
export type PinHomeConfig = {
    showRecentFlow: boolean;
    flowRangeMillis: number;
    showStock: boolean;
    showFlow: boolean;
    showChart: boolean;
    stock: boolean;
    input: boolean;
    output: boolean;
    net: boolean;
    rangeMillis: number;
};
export type ResourcePin = {
    recent: RecentTracking | null;
    pinKey: string;
    available: boolean;
    gridKey: string | null;
    itemKey: string | null;
    gridName: string | null;
    resource: ResourceDescription | null;
    terminalOrder: number | null;
    homeOrder: number | null;
    homeConfig: PinHomeConfig;
};
export type ResourcePinUpdate = {
    terminal?: boolean;
    home?: boolean;
    move?: { placement: PinPlacement; direction: 'earlier' | 'later' };
    homeConfig?: PinHomeConfig;
};
