import type { ThemeView } from './theme-api.js';

export type ThemeManifest = {
    readonly id: string;
    readonly name: string;
    readonly version: string;
    readonly supportedModVersion: `${number}.${number}.x`;
    readonly views: readonly ThemeView[];
    readonly entry: string;
    readonly styles: readonly string[];
};

const views: readonly ThemeView[] = [
    'home',
    'web-settings',
    'server-settings',
    'about',
    'items',
    'settings',
    'cpus',
    'history',
    'plan',
    'tracking'
];

export function validateThemePath(name: string): string {
    if (!name || name.length > 240 || !/^[A-Za-z0-9._/-]+$/.test(name)) {
        throw new Error('Theme contains an invalid file path.');
    }
    if (name.split('/').some((part) => !part || part === '.' || part === '..')) {
        throw new Error('Theme contains an invalid file path.');
    }
    return name;
}

/** Validates manifest data without evaluating any package JavaScript or CSS. */
export function validateThemeManifest(value: unknown): ThemeManifest {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        throw new Error('Theme manifest must be an object.');
    }
    const input = value as Record<string, unknown>;
    function text(field: string, max: number): string {
        const content = input[field];
        if (typeof content !== 'string' || !content.trim() || content.length > max) {
            throw new Error(`Invalid theme manifest field: ${field}.`);
        }
        return content;
    }
    const id = text('id', 64);
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(id)) throw new Error('Invalid theme identifier.');
    const name = text('name', 100);
    const version = text('version', 64);
    const supportedModVersion = text('supportedModVersion', 64);
    if (!/^\d+\.\d+\.x$/.test(supportedModVersion)) throw new Error('Invalid supported mod version.');
    if (!Array.isArray(input.views) || !input.views.length || input.views.some((view) => !views.includes(view))) {
        throw new Error('Invalid supported theme views.');
    }
    if (new Set(input.views).size !== input.views.length) throw new Error('Duplicate supported theme view.');
    const entry = validateThemePath(text('entry', 240));
    if (!entry.endsWith('.js')) throw new Error('Theme entry must be a JavaScript file.');
    if (!Array.isArray(input.styles) || input.styles.length > 512) {
        throw new Error('Theme styles must be an array.');
    }
    const styles = input.styles.map((style: unknown) => {
        if (typeof style !== 'string' || !style.endsWith('.css')) throw new Error('Invalid theme stylesheet.');
        return validateThemePath(style);
    });
    if (new Set(styles).size !== styles.length) throw new Error('Duplicate theme stylesheet.');
    return {
        id,
        name,
        version,
        supportedModVersion: supportedModVersion as ThemeManifest['supportedModVersion'],
        views: input.views.slice(),
        entry,
        styles
    };
}
