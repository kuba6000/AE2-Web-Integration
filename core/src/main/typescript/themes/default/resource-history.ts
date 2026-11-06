import type { ThemeApplication, ThemeSettings, ThemeState, Translator } from '../../app/theme-api.js';
import { createPinActions } from './pin-actions.js';
import { trackingHref } from '../../app/router.js';
import { createTrackingChart } from './tracking-chart.js';
import { renderMinecraftText } from './minecraft-text.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { flowOperationLines } from './flow-operations.js';

export function createResourceHistoryView(
    host: HTMLElement,
    application: ThemeApplication,
    settings: ThemeSettings,
    full = false
) {
    const section = element('section', '', full ? 'tracking-view' : 'resource-history');
    section.hidden = true;
    const heading = element('h2');
    const resourceName = element('h3', '', 'tracking-resource-name');
    const resourceIcon = createResourceIcon();
    const iconHost = element('div', '', 'tracking-resource');
    iconHost.append(resourceIcon, resourceName);
    const resourceKey = element('p');
    resourceKey.hidden = true;
    const toolbar = element('div', '', 'tracking-toolbar');
    toolbar.hidden = !full;
    const rangeLabel = element('label');
    const range = element('select');
    for (const [value, text] of [
        [900000, '15 min'],
        [3600000, '1 h'],
        [86400000, '24 h']
    ] as const) {
        const option = element('option', text);
        option.value = String(value);
        range.append(option);
    }
    range.addEventListener('change', () => void application.resourceHistory.range(Number(range.value)));
    const rangeText = element('span');
    rangeLabel.append(rangeText, range);
    const modeLabel = element('label');
    const modeText = element('span');
    const mode = element('select');
    for (const value of ['pixel', 'smooth']) {
        const option = element('option');
        option.value = value;
        mode.append(option);
    }
    mode.value = settings.get('chartStroke', 'pixel') === 'smooth' ? 'smooth' : 'pixel';
    modeLabel.append(modeText, mode);
    modeLabel.hidden = !full;
    const reset = element('button');
    reset.type = 'button';
    reset.hidden = !full;
    toolbar.append(rangeLabel, modeLabel, reset);
    const status = element('p', '', 'hint');
    status.role = 'status';
    const chartHost = element('div', '', 'tracking-chart');
    chartHost.role = 'img';
    const totals = element('div', '', 'tracking-totals');
    const help = element('p', '', 'hint');
    const link = element('a', '', 'tracking-open');
    const back = element('a', '', 'tracking-back');
    const headingBar = element('div', '', 'terminal-heading');
    headingBar.append(heading, back);
    section.append(headingBar, iconHost, resourceKey, toolbar, status, totals, chartHost, help, link);
    const pins = createPinActions(application);
    if (full) iconHost.after(pins.element);
    host.append(section);
    let displayInterval = 5000;
    const updateHelp = () => {
        if (lastLocale)
            help.textContent = lastLocale.t('trackingChartHelp', { period: lastLocale.duration(displayInterval) });
    };
    const chart = createTrackingChart(chartHost, !full, (interval) => {
        displayInterval = interval;
        updateHelp();
    });
    const icons = application.icons.observe(section, paintResourceIcon);
    reset.addEventListener('click', () => chart.resetZoom());
    let lastState: ThemeState, lastLocale: Translator;
    let lastShowIcons = false;
    mode.addEventListener('change', () => {
        settings.set('chartStroke', mode.value);
        render(lastState, lastLocale, lastShowIcons);
    });
    function render(state: ThemeState, locale: Translator, showIcons = false) {
        lastShowIcons = showIcons;
        lastState = state;
        lastLocale = locale;
        const active = full
            ? state.route.view === 'tracking'
            : state.route.view === 'items' && !!state.selected?.itemKey;
        section.hidden = !active;
        if (!active) return;
        const itemKey = state.route.view === 'tracking' ? state.route.itemKey : state.selected?.itemKey;
        resourceKey.hidden = !full || !itemKey;
        if (full && itemKey)
            resourceKey.replaceChildren(element('span', `${locale.t('resourceKey')}:`), ' ', element('code', itemKey));
        const history = state.resourceHistory;
        if (full) pins.render(state, locale, state.route.gridKey, history.error ? null : (itemKey ?? null));
        const hasStock =
            !!history.baseline?.resource || history.points.some((point) => point.kind === 'STOCK' && point.resource);
        const historicalResource =
            [...history.points].reverse().find((point) => point.resource?.itemKey === itemKey)?.resource ??
            history.baseline?.resource;
        const resource =
            historicalResource ??
            (history.status !== 'error' && state.selected?.itemKey === itemKey ? state.selected : null);
        const iconMetadata = historicalResource ? history.icons : state.itemIcons;
        heading.textContent = full
            ? locale.t('trackingDetails')
            : locale.t('trackingStockTrend', { period: locale.duration(3600000) });
        resourceName.hidden = !full;
        iconHost.hidden = !full;
        resourceIcon.hidden = !showIcons;
        icons.update(full && showIcons && resource ? [{ element: iconHost, icon: resource.icon }] : [], iconMetadata);
        resourceName.replaceChildren(
            ...(resource ? [renderMinecraftText(resource.displayName)] : [document.createTextNode(itemKey ?? '')])
        );
        back.hidden = !full;
        back.textContent = `← ${locale.common('terminal')}`;
        back.href = `#/grids/${encodeURIComponent(state.route.gridKey ?? '')}/items`;
        link.hidden = full;
        link.textContent = locale.t('viewTracking');
        link.href = trackingHref(state.route.gridKey!, itemKey!);
        rangeText.textContent = locale.t('trackingRange');
        range.value = String(history.rangeMillis);
        modeText.textContent = locale.t('trackingStroke');
        mode.options[0].textContent = locale.t('trackingPixel');
        mode.options[1].textContent = locale.t('trackingSmooth');
        reset.textContent = locale.t('trackingResetZoom');
        let message = '';
        if (history.status === 'loading') message = locale.common('loading');
        else if (history.error) message = locale.common(history.error);
        else if (!history.points.length && !history.baseline?.resource) message = locale.common('resourceHistoryEmpty');
        else if (!full && !hasStock && history.status === 'ready') message = locale.t('trackingNoStock');
        if (history.status === 'ready') {
            if (!history.enabled) message += ` ${locale.common('resourceHistoryDisabled')}`;
            if (history.truncated) message += ` ${locale.common('resourceHistoryTruncated')}`;
        }
        status.textContent = message.trim();
        status.hidden = !message;
        chartHost.hidden =
            history.status !== 'ready' ||
            (!history.points.length && !history.baseline?.resource) ||
            (!full && !hasStock);
        chartHost.setAttribute('aria-label', locale.t(full ? 'trackingChart' : 'trackingStockHistory'));
        help.hidden = !full;
        updateHelp();
        totals.replaceChildren();
        totals.hidden = !full;
        if (full && history.status === 'ready') {
            let input = 0n,
                output = 0n,
                inputOperations = 0n,
                outputOperations = 0n;
            const flowObserved = history.points.some(
                (point) =>
                    point.observedAt > history.from &&
                    point.observedAt <= history.until &&
                    (point.kind === 'FLOW' || point.kind === 'FLOW_OBSERVATION')
            );
            for (const point of history.points)
                if (point.kind === 'FLOW' && point.observedAt > history.from && point.observedAt <= history.until) {
                    input += BigInt(point.input);
                    output += BigInt(point.output);
                    inputOperations += BigInt(point.inputOperations);
                    outputOperations += BigInt(point.outputOperations);
                }
            const numbers = new Intl.NumberFormat(state.preferences.language);
            for (const [label, value, color] of [
                [locale.common('resourceHistoryInput'), input, 'tracking-positive'],
                [locale.common('resourceHistoryOutput'), output, 'tracking-negative'],
                [locale.t('trackingNet'), input - output, input >= output ? 'tracking-positive' : 'tracking-negative']
            ] as const) {
                const stat = element('div');
                const count = element('strong', '', color);
                count.append(
                    element('span', flowObserved ? numbers.format(value) : '—', 'tracking-count-full'),
                    element(
                        'span',
                        flowObserved
                            ? new Intl.NumberFormat(state.preferences.language, {
                                  notation: 'compact',
                                  maximumFractionDigits: 1
                              }).format(value)
                            : '—',
                        'tracking-count-short'
                    )
                );
                count.setAttribute('aria-label', flowObserved ? numbers.format(value) : '—');
                stat.append(element('span', label), count);
                totals.append(stat);
            }
            if (flowObserved)
                totals.append(
                    ...flowOperationLines(inputOperations.toString(), outputOperations.toString(), null, locale)
                );
        }
        if (!chartHost.hidden)
            chart.render(history, locale, settings.get('chartStroke', 'pixel') !== 'smooth', itemKey!);
    }
    return {
        render,
        dispose: () => {
            chart.dispose();
            icons.dispose();
        }
    };
}
