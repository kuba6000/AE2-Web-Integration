import type { HistoricalResource } from './api-types.js';

const catalogSorts = ['INPUT', 'OUTPUT', 'STOCK', 'INSERTS', 'EXTRACTS', 'RECENT', 'NAME', 'NAME_DESC'] as const;
const catalogTypes = ['ITEM', 'FLUID', 'OTHER'] as const satisfies readonly HistoricalResource['resourceType'][];

export type TrackingCatalogLocation = {
    search: string;
    after: string | null;
    type: HistoricalResource['resourceType'] | null;
    sort: (typeof catalogSorts)[number];
    minutes: 15 | 60 | 1440;
    until: number | null;
};

export function catalogSortRequiresType(sort: TrackingCatalogLocation['sort']) {
    return sort === 'INPUT' || sort === 'OUTPUT' || sort === 'STOCK';
}

function readCatalog(params: URLSearchParams): TrackingCatalogLocation {
    const requestedType = params.get('type');
    const requestedSort = params.get('sort');
    const sort = catalogSorts.find((value) => value === requestedSort) ?? 'INPUT';
    const defaultType = catalogSortRequiresType(sort) ? 'ITEM' : null;
    const minutes = params.get('minutes');
    const until = Number(params.get('until'));
    return {
        search: params.get('search') ?? '',
        after: params.get('after'),
        type: catalogTypes.find((value) => value === requestedType) ?? defaultType,
        sort,
        minutes: minutes === '15' ? 15 : minutes === '1440' ? 1440 : 60,
        until: Number.isSafeInteger(until) && until > 0 ? until : null
    };
}

export function trackingCatalogParams(options: Partial<TrackingCatalogLocation>) {
    const params = new URLSearchParams();
    if (options.search) params.set('search', options.search);
    if (options.type) params.set('type', options.type);
    if (options.sort) params.set('sort', options.sort);
    if (options.minutes && options.minutes !== 60) params.set('minutes', String(options.minutes));
    if (options.until) params.set('until', String(options.until));
    if (options.after) params.set('after', options.after);
    return params;
}

export type Route =
    | { view: 'home' | 'missing' | 'web-settings' | 'server-settings' | 'about'; gridKey: null }
    | { view: 'items' | 'settings'; gridKey: string }
    | { view: 'tracking'; gridKey: string; itemKey: string; catalog?: TrackingCatalogLocation }
    | ({ view: 'tracking'; gridKey: string; itemKey: null } & TrackingCatalogLocation)
    | { view: 'cpus'; gridKey: string; cpuKey: string | null }
    | { view: 'history'; gridKey: string; entryId: string | null }
    | { view: 'plan'; gridKey: string; planId: string; tree?: boolean };

export function readRoute(): Route {
    const hash = location.hash.replace(/^#/, '');
    if (!hash || hash === '/' || hash === '/home') return { view: 'home', gridKey: null };
    if (hash === '/web-settings') return { view: 'web-settings', gridKey: null };
    if (hash === '/server-settings') return { view: 'server-settings', gridKey: null };
    if (hash === '/about') return { view: 'about', gridKey: null };
    const catalog = /^\/grids\/([^/]+)\/tracking(?:\?([^#]*))?$/.exec(hash);
    if (catalog) {
        try {
            const params = new URLSearchParams(catalog[2]);
            return {
                view: 'tracking',
                gridKey: decodeURIComponent(catalog[1]),
                itemKey: null,
                ...readCatalog(params)
            };
        } catch {
            return { view: 'missing', gridKey: null };
        }
    }
    const tracking = /^\/grids\/([^/]+)\/tracking\/([^/?]+)(?:\?([^#]*))?$/.exec(hash);
    if (tracking) {
        try {
            const params = new URLSearchParams(tracking[3]);
            return {
                view: 'tracking',
                gridKey: decodeURIComponent(tracking[1]),
                itemKey: decodeURIComponent(tracking[2]),
                ...(params.has('catalog') ? { catalog: readCatalog(params) } : {})
            };
        } catch {
            return { view: 'missing', gridKey: null };
        }
    }
    const settings = /^\/grids\/([^/]+)\/settings$/.exec(hash);
    if (settings) {
        try {
            return { view: 'settings', gridKey: decodeURIComponent(settings[1]) };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const history = /^\/grids\/([^/]+)\/history(?:\/(\d+))?$/.exec(hash);
    if (history) {
        try {
            return { view: 'history', gridKey: decodeURIComponent(history[1]), entryId: history[2] ?? null };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const cpu = /^\/grids\/([^/]+)\/cpus(?:\/([^/]+))?$/.exec(hash);
    if (cpu) {
        try {
            return {
                view: 'cpus',
                gridKey: decodeURIComponent(cpu[1]),
                cpuKey: cpu[2] ? decodeURIComponent(cpu[2]) : null
            };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const plan = /^\/grids\/([^/]+)\/plans\/([1-9]\d*)(\/tree)?$/.exec(hash);
    if (plan) {
        try {
            return {
                view: 'plan',
                gridKey: decodeURIComponent(plan[1]),
                planId: plan[2],
                ...(plan[3] ? { tree: true } : {})
            };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    const match = /^\/grids\/([^/]+)\/items$/.exec(hash);
    if (match) {
        try {
            return { view: 'items', gridKey: decodeURIComponent(match[1]) };
        } catch {
            // Malformed URL encoding falls through to the missing-route view.
        }
    }
    return { view: 'missing', gridKey: null };
}

export function navigateToPlan(gridKey: string, planId: string | number, tree = false) {
    location.hash = `/grids/${encodeURIComponent(gridKey)}/plans/${planId}${tree ? '/tree' : ''}`;
}

export function cpuHref(gridKey: string | null, cpuKey: string | null = null) {
    return `#/grids/${encodeURIComponent(String(gridKey))}/cpus${cpuKey ? '/' + encodeURIComponent(cpuKey) : ''}`;
}

export function historyHref(gridKey: string | null, entryId: string | number | null = null) {
    return `#/grids/${encodeURIComponent(String(gridKey))}/history${entryId === null ? '' : '/' + entryId}`;
}

export function navigateToGrid(key: string | null) {
    location.hash = key ? `/grids/${encodeURIComponent(key)}/items` : '/';
}

export function trackingCatalogHref(gridKey: string, options: Partial<TrackingCatalogLocation> = {}) {
    const params = trackingCatalogParams(options);
    return `#/grids/${encodeURIComponent(gridKey)}/tracking${params.size ? '?' + params : ''}`;
}

export function trackingHref(gridKey: string, itemKey: string, catalog?: TrackingCatalogLocation) {
    const href = `#/grids/${encodeURIComponent(gridKey)}/tracking/${encodeURIComponent(itemKey)}`;
    if (!catalog) return href;
    const params = trackingCatalogParams(catalog);
    params.set('catalog', '1');
    return href + '?' + params;
}
