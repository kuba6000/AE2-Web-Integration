import { createSettings } from './storage.mjs';
import { createI18n } from './i18n.mjs';

/** @param {URL} base @param {string} themeId */
export function createThemeContext(base, themeId) {
    return {
        settings: createSettings(base, `theme:${encodeURIComponent(themeId)}`),
        i18n: createI18n()
    };
}
