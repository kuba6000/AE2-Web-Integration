import type { CraftingState } from './crafting.js';
import type { CpuState } from './cpus.js';
import type { HistoryState } from './history.js';
import type { HomeState } from './home.js';
import type { SettingsState } from './settings.js';
import type {
    StoredResource,
    Grid,
    IconMetadata,
    ApplicationContext,
    GridSettings,
    AvailableUpdate
} from './api-types.js';
import type { Preferences } from './preferences.js';
import type { Route } from './router.js';
import type { IconSprite, IconTarget } from './icons.js';
import type { JsonValue } from './storage.js';
import type { Dictionaries, Translator } from './i18n.js';
import type * as resourceHelpers from './resource-helpers.js';

/** Shared data is borrowed. Themes derive their own arrays instead of mutating application state. */
export type ReadonlyData<T> = T extends object ? { readonly [K in keyof T]: ReadonlyData<T[K]> } : T;
export type ThemeState = ReadonlyData<{
    capabilities: ApplicationContext['capabilities'];
    route: Route;
    grids: Grid[];
    gridStatus: 'loading' | 'ready' | 'error';
    gridError: string | null;
    items: StoredResource[];
    itemIcons: IconMetadata | null;
    iconPack: { available: boolean | null; status: 'loading' | 'ready' | 'error' };
    itemStatus: 'idle' | 'loading' | 'ready' | 'error';
    itemError: string | null;
    refreshing: boolean;
    refreshingView: boolean;
    updatedAt: number | null;
    search: string;
    selected: StoredResource | null;
    selectedGridKey: string | null;
    preferences: Preferences;
    crafting: CraftingState;
    cpus: CpuState;
    home: HomeState;
    history: HistoryState;
    settings: SettingsState;
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
    readonly state: ThemeState;
    /** Invoked immediately, then on data changes. Renderer failures terminate this theme mount. */
    subscribe(listener: (state: ThemeState) => void): () => void;
    refresh(options?: { reloadDetail?: boolean }): Promise<void>;
    search(value: string): void;
    select(resource: ReadonlyData<StoredResource> | null): void;
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
    readonly cpus: { cancel(key: string): Promise<void>; pause(key: string, paused: boolean): Promise<void> };
    readonly settings: {
        edit<K extends keyof GridSettings>(field: K, value: GridSettings[K]): void;
        save(): Promise<void>;
    };
    readonly icons: {
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
