import type { JsonValue } from './storage.js';

export type PlanExportSettings = {
    scale: number;
    icon: boolean;
    name: boolean;
    missing: boolean;
    requested: boolean;
    stored: boolean;
    steps: boolean;
    usage: boolean;
    numbers: 'short' | 'full';
    colors: boolean;
    sort: 'name' | 'quantity' | 'steps';
    direction: 'ascending' | 'descending';
    group: boolean;
    onlyMissing: boolean;
    header: boolean;
    background: 'theme' | 'transparent';
    ratio: string;
};
export const defaultPlanExportSettings: Readonly<PlanExportSettings> = {
    scale: 1,
    icon: true,
    name: true,
    missing: true,
    requested: true,
    stored: true,
    steps: true,
    usage: true,
    numbers: 'short',
    colors: true,
    sort: 'name',
    direction: 'ascending',
    group: true,
    onlyMissing: false,
    header: true,
    background: 'theme',
    ratio: 'square'
};
export type PlanExportGrid = { axis: 'columns' | 'rows'; value: number } | { axis: 'square' | 'ratio'; ratio?: number };
export type PlanExportGeometry = { width: number; height: number; headerHeight: number };
export type PlanExportLayout = {
    columns: number;
    rows: number;
    width: number;
    height: number;
    tileWidth: number;
    tileHeight: number;
    scale: number;
    headerHeight: number;
};

/** Geometry for one image. Explicit row counts retain their empty trailing slots. */
export function planExportLayout(
    count: number,
    settings: PlanExportSettings,
    grid: PlanExportGrid,
    geometry: PlanExportGeometry
): PlanExportLayout | null {
    if (!Number.isSafeInteger(count) || count <= 0) return null;
    const headerHeight = settings.header ? Math.round(geometry.headerHeight * settings.scale) : 0;
    const tileWidth = Math.round(geometry.width * settings.scale);
    const tileHeight = Math.round(geometry.height * settings.scale);
    const size = (columns: number, rows: number): PlanExportLayout => ({
        columns,
        rows,
        tileWidth,
        tileHeight,
        scale: settings.scale,
        headerHeight,
        width: columns * tileWidth,
        height: headerHeight + rows * tileHeight
    });
    if (grid.axis === 'columns' || grid.axis === 'rows') {
        if (!Number.isSafeInteger(grid.value) || grid.value < 1 || grid.value > count) return null;
        return grid.axis === 'columns'
            ? size(grid.value, Math.ceil(count / grid.value))
            : size(Math.ceil(count / grid.value), grid.value);
    }
    const target = grid.axis === 'square' ? 1 : (('ratio' in grid ? grid.ratio : 1) ?? 1);
    let best = size(1, count);
    let distance = Infinity;
    for (let columns = 1; columns <= count; columns++) {
        const candidate = size(columns, Math.ceil(count / columns));
        const next = Math.abs(Math.log(candidate.width / candidate.height / target));
        if (next < distance) {
            best = candidate;
            distance = next;
        }
    }
    return best;
}

/** Settings come from browser storage; reject each malformed value independently. */
export function readPlanExportSettings(get: (key: string) => JsonValue): PlanExportSettings {
    const result = { ...defaultPlanExportSettings };
    const choices: Partial<Record<keyof PlanExportSettings, readonly JsonValue[]>> = {
        scale: [0.5, 1, 1.5, 2, 3, 4],
        numbers: ['short', 'full'],
        sort: ['name', 'quantity', 'steps'],
        direction: ['ascending', 'descending'],
        background: ['theme', 'transparent']
    };
    for (const key of Object.keys(result) as (keyof PlanExportSettings)[]) {
        const value = get(`planExport.${key}`);
        const valid =
            key === 'ratio'
                ? typeof value === 'string' &&
                  (value === 'square' ||
                      (/^\d{1,5}:\d{1,5}$/.test(value) && value.split(':').every((part) => Number(part) > 0)))
                : choices[key]
                  ? choices[key]!.includes(value)
                  : typeof value === 'boolean';
        if (valid) Object.assign(result, { [key]: value });
    }
    return result;
}

/** Conservative allocation budget, followed by an actual browser canvas probe before drawing. */
export function planExportFits(layout: Pick<PlanExportLayout, 'width' | 'height'>): boolean {
    return layout.width <= 16384 && layout.height <= 16384 && layout.width * layout.height <= 67_108_864;
}

export function smallerPlanExportLayout(
    layout: PlanExportLayout,
    geometry: PlanExportGeometry
): PlanExportLayout | null {
    const scale = [4, 3, 2, 1.5, 1, 0.75, 0.5, 0.25].find((value) => value < layout.scale);
    if (!scale) return null;
    const tileWidth = Math.max(1, Math.round(geometry.width * scale));
    const tileHeight = Math.max(1, Math.round(geometry.height * scale));
    const headerHeight = layout.headerHeight ? Math.round(geometry.headerHeight * scale) : 0;
    return {
        ...layout,
        scale,
        tileWidth,
        tileHeight,
        headerHeight,
        width: layout.columns * tileWidth,
        height: headerHeight + layout.rows * tileHeight
    };
}

export function planExportRatio(width: number, height: number): string {
    let best = '1:1',
        distance = Infinity;
    for (let a = 1; a <= 16; a++)
        for (let b = 1; b <= 16; b++) {
            const next = Math.abs(Math.log(width / height / (a / b)));
            if (next < distance) {
                best = `${a}:${b}`;
                distance = next;
            }
        }
    return best;
}
