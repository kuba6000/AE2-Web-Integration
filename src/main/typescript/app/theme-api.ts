import type { PinsState } from './pins.js';
import type { TerminalStreamState, TerminalUpdateHint } from './terminal.js';
import type { CraftingState } from './crafting.js';
import type { CpuState } from './cpus.js';
import type { HistoryState } from './history.js';
import type { ResourceHistoryState } from './resource-history.js';
import type { SettingsState } from './settings.js';
import type { ServerSettingsState, createServerSettings } from './server-settings.js';
export type {
    ServerConfiguration,
    ServerSettingsReadOnly,
    ServerSettingsUpdate,
    ServerSecretName,
    ServerSecretChanges,
    ServerSettingsResponse
} from './server-settings.js';
import type {
    StoredResource,
    Grid,
    IconMetadata,
    ApplicationContext,
    GridSettings,
    AvailableUpdate,
    PinPlacement,
    ResourcePinUpdate
} from './api-types.js';
import type { RecentTracking } from './api-types.js';
import type { Preferences } from './preferences.js';
import type { Route } from './router.js';
import type { IconSprite, IconTarget, ResolvedIcons } from './icons.js';
import type { PlanExportRequest } from './plan-export.js';
import type { PlanExportLayout } from './plan-export-layout.js';
import type { JsonValue } from './storage.js';
import type { Dictionaries, Translator } from './i18n.js';
import type * as resourceHelpers from './resource-helpers.js';
import type { Notification } from './notifications.js';
export type { Notification } from './notifications.js';

/** Shared data is borrowed. Themes derive their own arrays instead of mutating application state. */
export type ReadonlyData<T> = T extends object ? { readonly [K in keyof T]: ReadonlyData<T[K]> } : T;
export type ThemeState = ReadonlyData<{
    capabilities: ApplicationContext['capabilities'];
    route: Route;
    grids: Grid[];
    gridStatus: 'loading' | 'ready' | 'error';
    gridError: string | null;
    items: StoredResource[];
    /** Advances only when a full item snapshot is accepted, independently of live deltas. */
    itemSnapshotRevision: number;
    itemIcons: IconMetadata | null;
    iconPack: { available: boolean | null; status: 'loading' | 'ready' | 'error' };
    itemStatus: 'idle' | 'loading' | 'ready' | 'error';
    itemError: string | null;
    refreshing: boolean;
    refreshingView: boolean;
    updatedAt: number | null;
    stream: TerminalStreamState | null;
    search: string;
    selected: StoredResource | null;
    selectedGridKey: string | null;
    preferences: Preferences;
    crafting: CraftingState;
    cpus: CpuState;
    history: HistoryState;
    resourceHistory: ResourceHistoryState;
    recent: RecentTracking | null;
    settings: SettingsState;
    serverSettings: ServerSettingsState;
    pins: PinsState;
}>;
export type ThemeView = Exclude<Route['view'], 'missing'>;
export type ThemeLifetime = {
    readonly signal: AbortSignal;
    /** Register immediately after acquiring a resource; cleanup also runs after a failed mount. */
    onCleanup(cleanup: () => void): void;
};
export type IconView = {
    update(targets: IconTarget[], metadata: ThemeState['itemIcons']): void;
    dispose(): void;
};
export type ThemeApplication = {
    exportPlan(request: PlanExportRequest, signal: AbortSignal): Promise<PlanExportLayout>;
    readonly notifications: {
        subscribe(listener: (items: readonly ReadonlyData<Notification>[]) => void): () => void;
        dismiss(id: number): void;
        pause(id: number, paused: boolean): void;
    };
    readonly state: ThemeState;
    /** Invoked immediately, then with complete state on changes. The optional hint identifies timing-only CPU updates. */
    subscribe(listener: (state: ThemeState, hint?: TerminalUpdateHint) => void): () => void;
    refresh(options?: { reloadDetail?: boolean }): Promise<void>;
    search(value: string): void;
    /** Optionally choose the selected resource's history period without changing the full tracking view's range. */
    select(resource: ReadonlyData<StoredResource> | null, historyRangeMillis?: number): void;
    resourceHistory: { range(millis: number): Promise<void> };
    pins: {
        add(gridKey: string, itemKey: string, placement: PinPlacement): Promise<void>;
        update(pinKey: string, update: ResourcePinUpdate): Promise<void>;
        remove(pinKey: string): Promise<void>;
        refresh(): Promise<void>;
    };
    preference<K extends keyof Preferences>(name: K, value: Preferences[K]): void;
    displayIcons(enabled: boolean): void;
    readonly crafting: {
        search(value: string): void;
        sort(value: Preferences['planSort']): void;
        reverse(): void;
        selectCpu(key: string): void;
        create(
            resource: ReadonlyData<StoredResource> | null,
            quantity: number,
            automatic?: boolean,
            lightMode?: boolean,
            ignoreMissing?: boolean
        ): Promise<void>;
        recalculate(automatic: boolean, ignoreMissing: boolean): Promise<void>;
        submit(): Promise<void>;
        remove(): Promise<void>;
    };
    readonly history: { next(): Promise<void>; previous(): Promise<void> };
    readonly cpus: { cancel(key: string): Promise<void>; pause(key: string, paused: boolean): Promise<void> };
    readonly settings: {
        edit<K extends keyof GridSettings>(field: K, value: GridSettings[K]): void;
        save(): Promise<void>;
    };
    readonly serverSettings: Pick<
        ReturnType<typeof createServerSettings>,
        'edit' | 'editSecret' | 'removeSecret' | 'resetSecret' | 'save' | 'reload'
    >;
    readonly icons: {
        resolve(
            references: readonly import('./api-types.js').IconReference[],
            metadata: ThemeState['itemIcons'],
            signal: AbortSignal
        ): Promise<ResolvedIcons>;
        observe(root: HTMLElement, paint: (target: HTMLElement, sprite: IconSprite | null) => void): IconView;
    };
};
export type ThemeSettings = {
    get(key: string, fallback?: JsonValue): JsonValue;
    set(key: string, value: JsonValue): boolean;
    remove(key: string): void;
};
export type ThemeUpdates = {
    readonly update: ReadonlyData<AvailableUpdate> | null;
    readonly pending: boolean;
    readonly error: string | null;
    readonly visible: boolean;
    dismiss(snooze?: boolean): void;
    skip(): Promise<void>;
    subscribe(listener: () => void): () => void;
};
export type ThemeContext = {
    /** Expose this picker from the theme navigation or settings; no floating host button is added. */
    readonly themes?: { open(): void };
    /** Resolve an asset relative to this theme's package root. */
    readonly assets?: { url(path: string): string };
    readonly user: { readonly username: string; readonly isAdmin: boolean };
    readonly modVersion: string | null;
    readonly settings: ThemeSettings;
    readonly i18n: { register(dictionaries: Dictionaries): void; forLanguage(language: string): Translator };
    readonly updateNotice: ThemeUpdates;
    readonly lifetime: ThemeLifetime;
    readonly helpers: typeof resourceHelpers;
    logout(): Promise<void>;
};
export type ThemeDefinition = {
    readonly id: string;
    readonly name: string;
    readonly supportedModVersion: `${number}.${number}.x`;
    readonly views: readonly ThemeView[];
    mount(root: HTMLElement, application: ThemeApplication, context: ThemeContext): void;
};
export type { Route, Preferences, IconSprite, IconTarget, JsonValue, Dictionaries, Translator };
export type * from './api-types.js';
export type { PlanExportRequest, PlanExportStyle } from './plan-export.js';
export type { PlanExportSettings, PlanExportLayout, PlanExportGrid, PlanExportGeometry } from './plan-export-layout.js';
export type { DrawableIcon, ResolvedIcons } from './icons.js';
