/**
 * JSON contracts from the core endpoint responses and API models.
 * These declarations describe the wire format; they do not validate responses at runtime.
 */
export type PlayerIdentity = { uuid: string; name: string };
export type CraftingRequester =
    | { source: 'PLAYER'; player: PlayerIdentity }
    | { source: 'AUTOMATION' | 'WEB'; player: null }
    | { source: 'STANDING_ORDER'; player: null; orderKey: string; orderName: string };
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
export type IconReference = { page: number; x: number; y: number; animation?: number | null };
export type IconAnimation = {
    page: number;
    frames: { x: number; y: number; durationMillis: number }[];
};
export type IconMetadata = {
    packId: string;
    width: number;
    height: number;
    pages: { digest: string; width: number; height: number }[];
    animations?: IconAnimation[] | null;
};
export type TerminalView = { generation: string; revision: string };
export type RecentResource = {
    input: string | null;
    output: string | null;
    inputOperations: string | null;
    outputOperations: string | null;
    stockChange: string | null;
};
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
    nextBefore?: string | null;
    groups?: HomePinGroup[];
};
export type StoredResource = ResourceStack & {
    craftable: boolean;
};
export type Position = { dimensionId: string; x: number; y: number; z: number };
export type AccessSource = {
    player: PlayerIdentity;
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
    trackMachineCrafting: boolean;
    isStorageTracked: boolean;
    isFlowTracked: boolean;
    standingOrdersEnabled: boolean;
    stockSampleIntervalSeconds: number;
    flowFlushIntervalSeconds: number;
    historyRetentionDays: number;
    craftingHistoryRetentionDays: number;
    name: string;
};
export type ResourceHistorySetting = 'stockSampleIntervalSeconds' | 'flowFlushIntervalSeconds' | 'historyRetentionDays';
export type HistorySetting = ResourceHistorySetting | 'craftingHistoryRetentionDays';
export type SettingRange = { min: number; max: number; defaultValue: number };
export type GridSettingsLimits = Record<ResourceHistorySetting, SettingRange> & { historyMaxRowsPerGrid: number };
export type CraftingHistoryLimits = { historyRetentionDays: SettingRange; historyMaxJobsPerGrid: number };
export type GridSettingsResponse = GridSettings & {
    canEditStandingOrders: boolean;
    limits: GridSettingsLimits;
    craftingLimits: CraftingHistoryLimits;
};
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
    requesters: CraftingRequester[];
    nativeRequesterName: string | null;
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
    requesters: CraftingRequester[];
    nativeRequesterName: string | null;
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
    requesters: CraftingRequester[];
    finalOutput: ResourceStack;
    id: string;
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
    requesters: CraftingRequester[];
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
    inputOperations: string;
    outputOperations: string;
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
    homeGroupKey: string | null;
    homeConfig: PinHomeConfig;
};
export type ResourcePinUpdate = {
    terminal?: boolean;
    home?: boolean;
    move?: { placement: PinPlacement; direction: 'earlier' | 'later' };
    homeConfig?: PinHomeConfig;
    homeGroup?: { groupKey?: string };
};
export type HomePinGroup = { groupKey: string; name: string; ordinal: number };
export type HomePinGroupUpdate = { name?: string; move?: 'earlier' | 'later' };

export type StandingOrderStatusName =
    | 'IDLE'
    | 'CALCULATING'
    | 'CRAFTING'
    | 'CRAFTING_LONG'
    | 'COOLDOWN'
    | 'BLOCKED_MISSING'
    | 'SUBMIT_FAILED'
    | 'UNAVAILABLE'
    | 'DISABLED'
    | 'SWITCHED_OFF'
    | 'GRID_UNAVAILABLE'
    | 'WAITING_FOR_STOCK'
    | 'WAITING_FOR_CPU'
    | 'QUEUED';
export type StandingOrderReason =
    | 'WAITING_FIRST_READ'
    | 'READ_FAILED'
    | 'WAITING_FRESH_READ_AFTER_JOB'
    | 'WAITING_READ_FOR_NEW_RESOURCES'
    | 'MISSING_INGREDIENTS'
    | 'CALCULATION_FAILED'
    | 'CALCULATION_TIMED_OUT'
    | 'SUBMIT_REJECTED'
    | 'SUBMIT_ERROR'
    | 'MACHINE_CPU_TOO_SMALL'
    | 'UNEXPECTED_ERROR'
    | 'INVALID_DEFINITION'
    | 'CAPABILITY_UNAVAILABLE'
    | 'UNKNOWN_RESOURCE'
    | 'AMBIGUOUS_RESOURCE'
    | 'NOT_CRAFTABLE'
    | 'EMITTER_BACKED'
    | 'AMOUNT_OVERFLOW'
    | 'NOTHING_TO_ORDER'
    | 'NO_IDLE_CPU'
    | 'SELECTED_CPU_UNAVAILABLE'
    | 'CALCULATION_SLOTS_FULL';
/** An ingredient a blocked calculation lacked; `resource` is null when the server cannot name it. */
export type StandingOrderMissingIngredient = { resource: string | null; displayName: string; amount: number };
/**
 * Times are epoch milliseconds and zero means none. `reason` explains the status where the status alone does not;
 * `resource` (AMBIGUOUS_RESOURCE) and `path` (CAPABILITY_UNAVAILABLE) are its parameters. `submitError` is the
 * crafting system's own message, not translated. `missing` lists at most five ingredients, largest first.
 */
export type StandingOrderRuntime = {
    status: StandingOrderStatusName;
    reason: StandingOrderReason | null;
    resource: string | null;
    path: string | null;
    submitError: string | null;
    missing: StandingOrderMissingIngredient[];
    changedAt: number;
    lastSubmittedAmount: number;
    nextCheckAt: number;
    craftingSince: number;
};
/** Members of a listed condition that do not apply to its `type` are null; request bodies omit them instead. */
export type StandingOrderRatioInput = { reference: string; numerator: number; denominator: number };
type ListedRatio = { [K in keyof StandingOrderRatioInput]?: StandingOrderRatioInput[K] | null };
export type StandingOrderCondition = ListedRatio & {
    type:
        | 'stockBelow'
        | 'stockAtLeast'
        | 'stockRatioBelow'
        | 'stockRatioAtLeast'
        | 'idleCpusAtLeast'
        | 'idlePlayerCpusAtLeast'
        | 'all'
        | 'any'
        | 'not';
    resource: string | null;
    amount: number | null;
    count: number | null;
    children: StandingOrderCondition[] | null;
    child: StandingOrderCondition | null;
};
export type StandingOrderAction = {
    resource: string;
    amount: ListedRatio & {
        type: 'fixed' | 'fillTo' | 'fillToRatio';
        batch: number | null;
        target: number | null;
        maxBatch: number | null;
    };
    options: { lightMode: boolean; ignoreMissing: boolean };
    cpu: { type: 'any' | 'machineOnly' | 'specific'; key: string | null };
};
export type StandingOrderDefinition = {
    name: string;
    enabled: boolean;
    cooldownSeconds: number;
    trigger: StandingOrderCondition;
    action: StandingOrderAction;
};
export type StandingOrderConditionInput =
    | ({ type: 'stockRatioBelow' | 'stockRatioAtLeast'; resource: string } & StandingOrderRatioInput)
    | { type: 'stockBelow' | 'stockAtLeast'; resource: string; amount: number }
    | { type: 'idleCpusAtLeast' | 'idlePlayerCpusAtLeast'; count: number }
    | { type: 'all' | 'any'; children: StandingOrderConditionInput[] }
    | { type: 'not'; child: StandingOrderConditionInput };
export type StandingOrderActionInput = {
    resource: string;
    amount:
        | { type: 'fixed'; batch: number }
        | { type: 'fillTo'; target: number; maxBatch: number }
        | ({ type: 'fillToRatio'; maxBatch: number } & StandingOrderRatioInput);
    options: { lightMode: boolean; ignoreMissing: boolean };
    cpu: { type: 'any' | 'machineOnly' } | { type: 'specific'; key: string };
};
export type StandingOrderDefinitionInput = {
    name: string;
    enabled: boolean;
    cooldownSeconds: number;
    trigger: StandingOrderConditionInput;
    action: StandingOrderActionInput;
};
/** A partial edit: omitted members keep their stored value and a present trigger or action replaces that whole part. */
export type StandingOrderChanges = Partial<StandingOrderDefinitionInput>;
/** Why a definition was refused; `path` locates the offending member of the submitted document. */
export type StandingOrderRejection = { reason: string; path: string };
/** Exactly one of `definition` and `invalid` is set. */
export type StandingOrder = {
    orderKey: string;
    definition: StandingOrderDefinition | null;
    invalid: StandingOrderRejection | null;
    status: StandingOrderRuntime | null;
};
export type StandingOrdersListing = {
    serverEnabled: boolean;
    gridEnabled: boolean;
    canEdit: boolean;
    maxOrders: number;
    orders: StandingOrder[];
    resources: Record<string, ResourceDescription>;
};
