import type { ThemeDefinition } from '../../app/theme-api.js';
import { mount } from './view.js';

export const defaultTheme: ThemeDefinition = {
    id: 'default',
    name: 'Default',
    supportedModVersion: '1.2.x',
    views: [
        'home',
        'items',
        'cpus',
        'history',
        'plan',
        'tracking',
        'settings',
        'web-settings',
        'server-settings',
        'about'
    ],
    mount(root, application, context) {
        const style = document.createElement('link');
        style.rel = 'stylesheet';
        style.href = new URL('./style.css', import.meta.url).href;
        context.lifetime.onCleanup(() => style.remove());
        document.head.append(style);
        const chartStyle = document.createElement('link');
        chartStyle.rel = 'stylesheet';
        chartStyle.href = new URL('./uplot.css', import.meta.url).href;
        context.lifetime.onCleanup(() => chartStyle.remove());
        document.head.append(chartStyle);
        mount(root, application, context);
    }
};
