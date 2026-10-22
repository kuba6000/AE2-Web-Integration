import type { ThemeState, PlanExportStyle, PlanExportSettings } from '../../app/theme-api.js';
import { element } from './dom.js';

export function planExportStyle(
    root: HTMLElement,
    settings: PlanExportSettings,
    plan: NonNullable<ThemeState['crafting']['plan']>,
    icons: boolean
): PlanExportStyle {
    const computed = getComputedStyle(root);
    const value = (name: string) => computed.getPropertyValue(name).trim();
    const sourceGrid = document.querySelector('.crafting-plan .resource-grid')!;
    const width = Math.round(Number.parseFloat(getComputedStyle(sourceGrid).gridTemplateColumns));
    const probeRoot = element('div', '', `plan-export-probe ${icons ? 'cpu-icons-enabled' : ''}`);
    probeRoot.dataset.resourceDisplay = settings.name ? 'detailed' : 'icons';
    probeRoot.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none;left:-10000px;top:0';
    const grid = element('ul', '', 'resource-grid cpu-items');
    let maxMetrics = 0;
    for (const row of plan.plan ?? []) {
        if (settings.onlyMissing && row.missing <= 0) continue;
        const count =
            (['missing', 'requested', 'stored', 'steps'] as const).filter((key) => settings[key] && row[key] > 0)
                .length + (!settings.stored && settings.usage && row.stored > 0 && row.usedPercent > 0 ? 1 : 0);
        maxMetrics = Math.max(maxMetrics, count);
    }
    grid.classList.toggle('plan-four-metrics', maxMetrics > 3);
    grid.style.gridTemplateColumns = `${width}px`;
    const li = element('li');
    const probe = element('button', '', 'item cpu-item slot-frame');
    const name = element('strong', 'Name');
    const amounts = element('span', '', 'cpu-item-amounts');
    const metric = element('span', '', 'cpu-amount');
    const label = element('span', 'Stored');
    const quantity = element('span', '12', 'quantity');
    metric.append(label, quantity);
    amounts.append(metric);
    const icon = element('span', '', 'resource-icon');
    probe.append(icon, name, amounts);
    li.append(probe);
    grid.append(li);
    probeRoot.append(grid);
    document.body.append(probeRoot);
    const cell = getComputedStyle(probe),
        metricStyle = getComputedStyle(metric);
    const geometry = { width, height: Number.parseFloat(getComputedStyle(grid).gridAutoRows), headerHeight: 32 };
    const tints = { missing: '', pending: '', stored: '' };
    for (const state of ['missing', 'pending', 'stored'] as const) {
        probe.dataset.crafting = state;
        tints[state] = getComputedStyle(probe).getPropertyValue('--crafting-tint').trim() || 'transparent';
    }
    const textColors: PlanExportStyle['textColors'] = {};
    const colorProbe = element('span');
    probe.append(colorProbe);
    for (const color of [
        'black',
        'dark_blue',
        'dark_green',
        'dark_aqua',
        'dark_red',
        'dark_purple',
        'gold',
        'gray',
        'dark_gray',
        'blue',
        'green',
        'aqua',
        'red',
        'light_purple',
        'yellow',
        'white'
    ] as const) {
        colorProbe.className = `mc-${color}`;
        textColors[color] = getComputedStyle(colorProbe).color;
    }
    const usageColors = ['low', 'medium', 'high', 'critical'].map((level) => {
        colorProbe.className = `storage-use-${level}`;
        return getComputedStyle(colorProbe).color;
    });
    const style: PlanExportStyle = {
        geometry,
        padding: Number.parseFloat(cell.paddingRight),
        contentLeft: Number.parseFloat(cell.paddingLeft),
        borderWidth: Number.parseFloat(cell.borderLeftWidth),
        iconSize: icons ? Number.parseFloat(getComputedStyle(icon).width) : 0,
        iconLeft: icons ? Number.parseFloat(getComputedStyle(icon).left) : 0,
        fontFamily: computed.fontFamily,
        fontSize: Number.parseFloat(getComputedStyle(name).fontSize),
        labelFontSize: Number.parseFloat(metricStyle.fontSize),
        quantityFontSize: Number.parseFloat(getComputedStyle(quantity).fontSize),
        metricLineHeight: Number.parseFloat(metricStyle.lineHeight),
        metricGap: Number.parseFloat(getComputedStyle(amounts).gap),
        foreground: value('--surface-text'),
        background: value('--panel'),
        tile: value('--slot'),
        border: value('--slot-edge-dark'),
        highlight: value('--slot-edge-light'),
        tints,
        textColors,
        usageColors
    };
    probeRoot.remove();
    return style;
}
