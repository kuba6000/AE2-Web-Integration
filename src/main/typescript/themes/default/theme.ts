import type { ThemeDefinition } from '../../app/theme-api.js';
import { mount } from './view.js';

export const defaultTheme: ThemeDefinition = {
    id: 'default',
    name: 'Default',
    supportedModVersion: '1.2.x',
    views: ['home', 'items', 'cpus', 'history', 'plan', 'settings', 'web-settings', 'server-settings', 'about'],
    mount
};
