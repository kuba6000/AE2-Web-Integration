import { createSettings } from './storage.js';
import { createI18n } from './i18n.js';

export function createThemeContext(base: URL, themeId: string) {
    return {
        settings: createSettings(base, `theme:${encodeURIComponent(themeId)}`),
        i18n: createI18n()
    };
}
