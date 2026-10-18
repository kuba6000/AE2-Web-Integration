import { thumbtackIcon } from './icons/hackernoon/thumbtack.js';
import type { ThemeApplication, ThemeSettings, ThemeState, Translator, PinHomeConfig } from '../../app/theme-api.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createTrackingChart } from './tracking-chart.js';
import { trackingHref } from '../../app/router.js';

/** Personal cards consume recorded history supplied by the application, never live inventory. */
export function createHomePins(host: HTMLElement, application: ThemeApplication, settings: ThemeSettings) {
    const list = element('div', '', 'home-pins');
    const status = element('p', '', 'hint');
    status.role = 'status';
    const unavailable = element('ul', '', 'home-unavailable-pins');
    const unavailableRows = new Map<string, HTMLLIElement>();
    host.append(status, list, unavailable);
    const icons = application.icons.observe(list, paintResourceIcon);
    const cards = new Map<string, ReturnType<typeof createCard>>();
    let state: ThemeState, locale: Translator;
    function createCard(key: string) {
        const card = element('article', '', 'inset-frame home-pin-card');
        const identity = element('div', '', 'home-pin-identity tracking-resource');
        const icon = createResourceIcon(),
            name = element('a', '', 'home-pin-name'),
            source = element('a', '', 'home-pin-source');
        const labels = element('div');
        labels.append(name, source);
        const pinMark = element('span', '', 'home-pin-mark');
        pinMark.innerHTML = thumbtackIcon;
        identity.append(icon, labels, pinMark);
        const summary = element('dl', '', 'home-pin-summary');
        const message = element('p', '', 'hint');
        message.role = 'status';
        const period = element('p', '', 'home-pin-period');
        const legend = element('div', '', 'home-pin-legend');
        const chartHost = element('div', '', 'tracking-chart home-pin-chart');
        const toolbar = element('div', '', 'home-pin-actions');
        const earlier = element('button', '←'),
            later = element('button', '→'),
            remove = element('button');
        for (const button of [earlier, later, remove]) button.type = 'button';
        const current = () => state.pins.items.find((pin) => pin.pinKey === key);
        earlier.onclick = () =>
            void application.pins.update(key, { move: { placement: 'home', direction: 'earlier' } });
        later.onclick = () => void application.pins.update(key, { move: { placement: 'home', direction: 'later' } });
        remove.onclick = () => {
            void application.pins.update(key, { home: false });
        };
        const disclosure = element('details', '', 'home-pin-settings'),
            settingsLabel = element('summary'),
            fields = element('fieldset');
        disclosure.append(settingsLabel, fields);
        const inputs = new Map<keyof PinHomeConfig, HTMLInputElement>();
        for (const property of ['showStock', 'showFlow', 'showChart', 'stock', 'input', 'output', 'net'] as const) {
            const label = element('label'),
                input = element('input');
            input.type = 'checkbox';
            input.name = property;
            label.append(input, element('span'));
            fields.append(label);
            inputs.set(property, input);
            input.onchange = () => {
                const pin = current();
                if (pin)
                    void application.pins.update(key, { homeConfig: { ...pin.homeConfig, [property]: input.checked } });
            };
        }
        const rangeLabel = element('label'),
            rangeText = element('span'),
            range = element('select');
        for (const value of [60000, 900000, 3600000, 86400000, 604800000, 2592000000, 31536000000]) {
            const option = element('option');
            option.value = String(value);
            range.append(option);
        }
        rangeLabel.append(rangeText, range);
        fields.append(rangeLabel);
        range.onchange = () => {
            const pin = current();
            if (pin)
                void application.pins.update(key, {
                    homeConfig: { ...pin.homeConfig, rangeMillis: Number(range.value) }
                });
        };
        toolbar.append(earlier, later, remove);
        card.append(identity, summary, period, message, legend, chartHost, toolbar, disclosure);
        let chart: ReturnType<typeof createTrackingChart> | undefined;
        function clearChart() {
            chart?.dispose();
            chart = undefined;
            chartHost.replaceChildren();
        }
        function render(pin: ThemeState['pins']['items'][number], index: number, count: number) {
            const available = pin.available && !!pin.resource && !!pin.gridKey && !!pin.itemKey;
            name.replaceChildren(
                ...(available
                    ? [renderMinecraftText(pin.resource!.displayName)]
                    : [document.createTextNode(locale.common('pinUnavailable'))])
            );
            if (available) name.href = trackingHref(pin.gridKey!, pin.itemKey!);
            else name.removeAttribute('href');
            source.textContent = available ? pin.gridName || pin.gridKey! : '';
            source.hidden = !available;
            if (available) source.href = `#/grids/${encodeURIComponent(pin.gridKey!)}/items`;
            else source.removeAttribute('href');
            earlier.setAttribute('aria-label', locale.common('moveEarlier'));
            later.setAttribute('aria-label', locale.common('moveLater'));
            earlier.disabled = state.pins.pending || index === 0;
            later.disabled = state.pins.pending || index === count - 1;
            remove.textContent = locale.common('unpinHome');
            remove.disabled = state.pins.pending;
            settingsLabel.textContent = locale.common('pinSettings');
            disclosure.hidden = !available;
            fields.disabled = state.pins.pending;
            const labels = {
                showStock: 'pinShowStock',
                showFlow: 'pinShowFlow',
                showChart: 'pinShowChart',
                stock: 'pinSampledStock',
                input: 'resourceHistoryInput',
                output: 'resourceHistoryOutput',
                net: 'trackingNet'
            };
            for (const [property, input] of inputs) {
                if (!state.pins.pending) input.checked = Boolean(pin.homeConfig[property]);
                input.nextElementSibling!.textContent =
                    property === 'net'
                        ? locale.t('trackingNet')
                        : locale.common(labels[property as keyof typeof labels]);
            }
            rangeText.textContent = locale.t('trackingRange');
            if (![...range.options].some((option) => option.value === String(pin.homeConfig.rangeMillis))) {
                const option = element('option');
                option.value = String(pin.homeConfig.rangeMillis);
                range.append(option);
            }
            for (const option of range.options) option.textContent = locale.duration(Number(option.value));
            if (!state.pins.pending) range.value = String(pin.homeConfig.rangeMillis);
            period.textContent = `${locale.t('trackingRange')}: ${locale.duration(pin.homeConfig.rangeMillis)}`;
            period.hidden = !available || (!pin.homeConfig.showFlow && !pin.homeConfig.showChart);
            legend.replaceChildren();
            legend.hidden = true;
            summary.replaceChildren();
            message.textContent = '';
            chartHost.hidden = true;
            const history = available ? state.pins.histories[pin.pinKey] : undefined;
            if (!available) {
                clearChart();
                return;
            }
            const number = new Intl.NumberFormat(state.preferences.language);
            const stat = (label: string, value: string) => summary.append(element('dt', label), element('dd', value));
            if (history?.status === 'ready') {
                const stock = [...(history.baseline ? [history.baseline] : []), ...history.points]
                    .filter(
                        (point) =>
                            point.kind === 'STOCK' &&
                            point.resource?.itemKey === pin.itemKey &&
                            point.observedAt <= history.until
                    )
                    .reduce<ThemeState['resourceHistory']['baseline']>(
                        (last, point) => (!last || point.observedAt > last.observedAt ? point : last),
                        null
                    );
                if (pin.homeConfig.showStock) {
                    stat(
                        locale.common('pinSampledStock'),
                        stock?.resource ? number.format(BigInt(stock.resource.quantity)) : '—'
                    );
                    stat(
                        locale.common('pinSampleTime'),
                        stock ? locale.dateTime(stock.observedAt, history.until) : '—'
                    );
                }
                if (pin.homeConfig.showFlow) {
                    let input = 0n,
                        output = 0n,
                        observed = false;
                    for (const point of history.points)
                        if (point.observedAt > history.from && point.observedAt <= history.until) {
                            if (point.kind === 'FLOW' || point.kind === 'FLOW_OBSERVATION') observed = true;
                            if (point.kind === 'FLOW') {
                                input += BigInt(point.input);
                                output += BigInt(point.output);
                            }
                        }
                    for (const [label, value] of [
                        [locale.common('resourceHistoryInput'), input],
                        [locale.common('resourceHistoryOutput'), output],
                        [locale.t('trackingNet'), input - output]
                    ] as const)
                        stat(label, observed ? number.format(value) : '—');
                }
                const messages = [];
                if (!history.enabled) messages.push(locale.common('resourceHistoryDisabled'));
                if (history.truncated) messages.push(locale.common('resourceHistoryTruncated'));
                message.textContent = messages.join(' · ');
                chartHost.hidden = !pin.homeConfig.showChart || (!history.points.length && !history.baseline);
                if (!chartHost.hidden) {
                    legend.hidden = false;
                    for (const [series, label] of [
                        ['stock', locale.common('stored')],
                        ['input', locale.common('resourceHistoryInput')],
                        ['output', locale.common('resourceHistoryOutput')],
                        ['net', locale.t('trackingNet')]
                    ] as const) {
                        if (!pin.homeConfig[series]) continue;
                        const key = element('span', label);
                        key.dataset.series = series;
                        legend.append(key);
                    }
                    chart ??= createTrackingChart(chartHost, false, () => {}, 220);
                    chart.render(
                        history,
                        locale,
                        settings.get('chartStroke', 'pixel') !== 'smooth',
                        pin.pinKey,
                        pin.homeConfig
                    );
                } else clearChart();
            } else {
                if (pin.homeConfig.showStock) stat(locale.common('pinSampledStock'), '—');
                message.textContent = history?.error
                    ? locale.common(history.error)
                    : history?.status === 'loading'
                      ? locale.common('loading')
                      : '';
                clearChart();
            }
            message.hidden = !message.textContent;
        }
        return {
            card,
            identity,
            render,
            dispose() {
                clearChart();
                card.remove();
            }
        };
    }
    return {
        render(next: ThemeState, translator: Translator) {
            state = next;
            locale = translator;
            const active = state.route.view === 'home';
            const pins = active
                ? state.pins.items.filter((pin) => pin.homeOrder !== null).sort((a, b) => a.homeOrder! - b.homeOrder!)
                : [];
            const keys = new Set(pins.map((pin) => pin.pinKey));
            for (const [key, card] of cards)
                if (!keys.has(key)) {
                    card.dispose();
                    cards.delete(key);
                }
            const targets: Parameters<typeof icons.update>[0] = [];
            pins.forEach((pin, index) => {
                const card = cards.get(pin.pinKey) ?? createCard(pin.pinKey);
                cards.set(pin.pinKey, card);
                card.render(pin, index, pins.length);
                if (list.children[index] !== card.card) {
                    const focus = card.card.contains(document.activeElement)
                        ? (document.activeElement as HTMLElement)
                        : null;
                    list.insertBefore(card.card, list.children[index] ?? null);
                    focus?.focus({ preventScroll: true });
                }
                if (pin.available && pin.resource) targets.push({ element: card.identity, icon: pin.resource.icon });
            });
            icons.update(targets, state.pins.icons);
            const inaccessible = active
                ? state.pins.items.filter((pin) => !pin.available && pin.homeOrder === null)
                : [];
            for (const [key, row] of unavailableRows)
                if (!inaccessible.some((pin) => pin.pinKey === key)) {
                    row.remove();
                    unavailableRows.delete(key);
                }
            for (const pin of inaccessible) {
                let row = unavailableRows.get(pin.pinKey);
                if (!row) {
                    row = element('li');
                    const label = element('span'),
                        button = element('button');
                    button.type = 'button';
                    button.onclick = () => void application.pins.remove(pin.pinKey);
                    row.append(label, button);
                    unavailable.append(row);
                    unavailableRows.set(pin.pinKey, row);
                }
                row.querySelector('span')!.textContent = locale.common('pinUnavailable');
                const button = row.querySelector('button')!;
                button.textContent = locale.common('removePin');
                button.disabled = state.pins.pending;
            }
            unavailable.hidden = !inaccessible.length;
            status.textContent = state.pins.error
                ? locale.common(state.pins.error)
                : state.pins.status === 'loading'
                  ? locale.common('loading')
                  : !pins.length
                    ? locale.common('pinsEmpty')
                    : '';
            status.hidden = !status.textContent || !active;
        },
        dispose() {
            icons.dispose();
            for (const card of cards.values()) card.dispose();
            list.remove();
            status.remove();
            unavailable.remove();
        }
    };
}
