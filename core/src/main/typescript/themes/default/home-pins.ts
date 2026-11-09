import { createHomePinFlow } from './home-pin-flow.js';
import { thumbtackIcon } from './icons/hackernoon/thumbtack.js';
import type { ThemeApplication, ThemeSettings, ThemeState, Translator, PinHomeConfig } from '../../app/theme-api.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createTrackingChart } from './tracking-chart.js';
import { trackingHref } from '../../app/router.js';
import type { Tooltips } from './tooltips.js';

/** Personal cards consume recorded history supplied by the application, never live inventory. */
export function createHomePins(
    host: HTMLElement,
    application: ThemeApplication,
    settings: ThemeSettings,
    bindTooltip: Tooltips['bind']
) {
    const list = element('div', '', 'home-pin-groups');
    const addGroup = element('form', '', 'home-pin-group-add');
    const groupNameLabel = element('label');
    const groupName = element('input');
    const groupNameText = element('span');
    groupName.type = 'text';
    groupName.maxLength = 128;
    groupName.required = true;
    const addGroupButton = element('button');
    addGroupButton.type = 'submit';
    groupNameLabel.append(groupNameText, groupName);
    addGroup.append(groupNameLabel, addGroupButton);
    const status = element('p', '', 'hint');
    status.role = 'status';
    const unavailable = element('ul', '', 'home-unavailable-pins');
    const unavailableRows = new Map<string, HTMLLIElement>();
    host.append(status, addGroup, list, unavailable);
    const icons = application.icons.observe(list, paintResourceIcon);
    const cards = new Map<string, ReturnType<typeof createCard>>();
    const sections = new Map<string, ReturnType<typeof createGroup>>();
    let state: ThemeState, locale: Translator;
    addGroup.onsubmit = (event) => {
        event.preventDefault();
        const name = groupName.value.trim();
        if (!name) return;
        void application.pins.addGroup(name).then(() => {
            if (!state.pins.error && state.pins.groups.some((group) => group.name === name)) groupName.value = '';
        });
    };
    function createGroup(key: string) {
        const section = element('section', '', 'home-pin-group');
        section.setAttribute('role', 'region');
        if (key) section.dataset.groupKey = key;
        const header = element('div', '', 'home-pin-group-heading');
        const heading = element('h3');
        const actions = element('div', '', 'home-pin-group-actions');
        const earlier = element('button', '←'),
            later = element('button', '→'),
            rename = element('button'),
            remove = element('button');
        for (const button of [earlier, later, rename, remove]) button.type = 'button';
        actions.append(earlier, later, rename, remove);
        header.append(heading, actions);
        const edit = element('form', '', 'home-pin-group-rename');
        edit.hidden = true;
        const label = element('label'),
            nameText = element('span'),
            name = element('input');
        name.type = 'text';
        name.maxLength = 128;
        name.required = true;
        const save = element('button'),
            cancel = element('button');
        save.type = 'submit';
        cancel.type = 'button';
        label.append(nameText, name);
        edit.append(label, save, cancel);
        const content = element('div', '', 'home-pins');
        section.append(header, edit, content);
        earlier.onclick = () => void application.pins.updateGroup(key, { move: 'earlier' });
        later.onclick = () => void application.pins.updateGroup(key, { move: 'later' });
        remove.onclick = () => void application.pins.removeGroup(key);
        rename.onclick = () => {
            name.value = state.pins.groups.find((group) => group.groupKey === key)?.name ?? '';
            edit.hidden = false;
            name.focus();
        };
        cancel.onclick = () => {
            edit.hidden = true;
            rename.focus();
        };
        edit.onsubmit = (event) => {
            event.preventDefault();
            const value = name.value.trim();
            if (!value) return;
            void application.pins.updateGroup(key, { name: value }).then(() => {
                if (
                    !state.pins.error &&
                    state.pins.groups.some((group) => group.groupKey === key && group.name === value)
                ) {
                    edit.hidden = true;
                    rename.focus({ preventScroll: true });
                }
            });
        };
        return {
            section,
            content,
            render(title: string, index: number, count: number) {
                section.setAttribute('aria-label', title);
                heading.textContent = title;
                header.hidden = !key && !count;
                actions.hidden = !key;
                if (!key) return;
                earlier.setAttribute('aria-label', locale.common('moveEarlier'));
                later.setAttribute('aria-label', locale.common('moveLater'));
                rename.textContent = locale.common('renamePinGroup');
                remove.textContent = locale.common('deletePinGroup');
                nameText.textContent = locale.common('pinGroupName');
                save.textContent = locale.common('savePinGroup');
                cancel.textContent = locale.common('cancelPlan');
                earlier.disabled = state.pins.pending || index === 0;
                later.disabled = state.pins.pending || index === count - 1;
                for (const control of [rename, remove, name, save, cancel]) control.disabled = state.pins.pending;
            }
        };
    }
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
        const flowHost = element('div');
        const flow = createHomePinFlow(flowHost, bindTooltip);
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
        const groupLabel = element('label', '', 'home-pin-group-choice'),
            groupText = element('span'),
            groupChoice = element('select');
        groupLabel.append(groupText, groupChoice);
        fields.append(groupLabel);
        groupChoice.onchange = () =>
            void application.pins.update(key, {
                homeGroup: groupChoice.value ? { groupKey: groupChoice.value } : {}
            });
        const inputs = new Map<keyof PinHomeConfig, HTMLInputElement>();
        const groups = new Map<
            'showStock' | 'showRecentFlow' | 'showFlow' | 'showChart',
            { root: HTMLFieldSetElement; controls: HTMLDivElement }
        >();
        function checkbox(property: keyof PinHomeConfig, host: HTMLElement) {
            const label = element('label'),
                input = element('input');
            input.type = 'checkbox';
            input.name = property;
            label.append(input, element('span'));
            host.append(label);
            inputs.set(property, input);
            input.onchange = () => {
                const pin = current();
                if (pin)
                    void application.pins.update(key, { homeConfig: { ...pin.homeConfig, [property]: input.checked } });
            };
        }
        for (const property of ['showStock', 'showRecentFlow', 'showFlow', 'showChart'] as const) {
            const root = element('fieldset', '', 'home-pin-settings-group'),
                legend = element('legend'),
                controls = element('div', '', 'home-pin-section-controls');
            checkbox(property, legend);
            root.append(legend, controls);
            fields.append(root);
            groups.set(property, { root, controls });
        }
        const series = element('div', '', 'home-pin-series');
        for (const property of ['stock', 'input', 'output', 'net'] as const) checkbox(property, series);
        const ranges = new Map<
            'rangeMillis' | 'flowRangeMillis',
            { input: HTMLSelectElement; text: HTMLSpanElement }
        >();
        for (const property of ['flowRangeMillis', 'rangeMillis'] as const) {
            const label = element('label'),
                text = element('span'),
                input = element('select');
            for (const value of [60000, 900000, 3600000, 86400000, 604800000, 2592000000, 31536000000]) {
                const option = element('option');
                option.value = String(value);
                input.append(option);
            }
            label.append(text, input);
            groups.get(property === 'rangeMillis' ? 'showChart' : 'showFlow')!.controls.append(label);
            ranges.set(property, { input, text });
            input.onchange = () => {
                const pin = current();
                if (pin)
                    void application.pins.update(key, {
                        homeConfig: { ...pin.homeConfig, [property]: Number(input.value) }
                    });
            };
        }
        groups.get('showChart')!.controls.append(series);
        toolbar.append(earlier, later, remove);
        card.append(identity, summary, flowHost, period, message, legend, chartHost, toolbar, disclosure);
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
            groupText.textContent = locale.common('pinGroup');
            const choices = [{ groupKey: '', name: locale.common('ungroupedPins') }, ...state.pins.groups];
            if (
                JSON.stringify([...groupChoice.options].map((option) => [option.value, option.textContent])) !==
                JSON.stringify(choices.map((group) => [group.groupKey, group.name]))
            ) {
                groupChoice.replaceChildren(
                    ...choices.map((group) => {
                        const option = element('option', group.name);
                        option.value = group.groupKey;
                        return option;
                    })
                );
            }
            if (!state.pins.pending) groupChoice.value = pin.homeGroupKey ?? '';
            fields.disabled = state.pins.pending;
            const labels = {
                showStock: 'pinShowStock',
                showFlow: 'pinShowFlow',
                showRecentFlow: 'pinShowRecentFlow',
                showChart: 'pinShowChart',
                stock: 'stored',
                input: 'resourceHistoryInput',
                output: 'resourceHistoryOutput',
                net: 'trackingNet'
            };
            for (const [property, input] of inputs) {
                if (!state.pins.pending) input.checked = Boolean(pin.homeConfig[property]);
                const label =
                    property === 'net'
                        ? locale.t('trackingNet')
                        : locale.common(labels[property as keyof typeof labels]);
                input.setAttribute('aria-label', label);
                input.nextElementSibling!.textContent = label;
            }
            const groupLabels = {
                showStock: 'stored',
                showRecentFlow: 'pinRecentFlow',
                showFlow: 'pinHistoricalSection',
                showChart: 'pinChart'
            };
            for (const [property, group] of groups) {
                inputs.get(property)!.nextElementSibling!.textContent = locale.common(groupLabels[property]);
                group.root.setAttribute('aria-label', locale.common(groupLabels[property]));
                group.root.hidden = !available;
                group.controls.hidden = !pin.homeConfig[property];
            }
            for (const [property, { input, text }] of ranges) {
                text.textContent = locale.common(property === 'rangeMillis' ? 'pinChartPeriod' : 'pinFlowPeriod');
                if (![...input.options].some((option) => option.value === String(pin.homeConfig[property]))) {
                    const option = element('option');
                    option.value = String(pin.homeConfig[property]);
                    input.append(option);
                }
                for (const option of input.options) option.textContent = locale.duration(Number(option.value));
                if (!state.pins.pending) input.value = String(pin.homeConfig[property]);
            }
            period.textContent = `${locale.common('pinChartPeriod')}: ${locale.duration(pin.homeConfig.rangeMillis)}`;
            period.hidden = !available || !pin.homeConfig.showChart;
            legend.replaceChildren();
            legend.hidden = true;
            summary.replaceChildren();
            message.textContent = '';
            chartHost.hidden = true;
            const histories = available ? state.pins.histories[pin.pinKey] : undefined;
            const history = histories?.[pin.homeConfig.rangeMillis];
            const flowHistory = histories?.[pin.homeConfig.flowRangeMillis];
            flow.render(pin, flowHistory, locale);
            if (!available) {
                clearChart();
                return;
            }
            const number = new Intl.NumberFormat(state.preferences.language);
            const stat = (label: string, value: string) => summary.append(element('dt', label), element('dd', value));
            if (pin.homeConfig.showStock) {
                let stock: ThemeState['resourceHistory']['baseline'] = null;
                let stockUntil = 0;
                for (const source of new Set([history, flowHistory])) {
                    if (source?.status !== 'ready') continue;
                    for (const point of [source.baseline, ...source.points]) {
                        if (
                            point?.kind !== 'STOCK' ||
                            point.resource?.itemKey !== pin.itemKey ||
                            point.observedAt > source.until
                        )
                            continue;
                        if (!stock || point.observedAt > stock.observedAt) {
                            stock = point;
                            stockUntil = source.until;
                        }
                    }
                }
                stat(
                    locale.common('pinSampledStock'),
                    stock?.resource ? number.format(BigInt(stock.resource.quantity)) : '—'
                );
                stat(locale.common('pinSampleTime'), stock ? locale.dateTime(stock.observedAt, stockUntil) : '—');
            }
            if (pin.homeConfig.showChart && history?.status === 'ready') {
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
            const focused = list.contains(document.activeElement) ? (document.activeElement as HTMLElement) : null;
            const groups = active ? [...state.pins.groups].sort((a, b) => a.ordinal - b.ordinal) : [];
            groupNameText.textContent = locale.common('pinGroupName');
            addGroupButton.textContent = locale.common('addPinGroup');
            groupName.disabled = addGroupButton.disabled = state.pins.pending || state.pins.status !== 'ready';
            addGroup.hidden = !active;
            const buckets = [
                ...groups.map((group) => ({ key: group.groupKey, title: group.name })),
                { key: '', title: locale.common('ungroupedPins') }
            ];
            const groupKeys = new Set(buckets.map((group) => group.key));
            for (const [key, section] of sections)
                if (!groupKeys.has(key)) {
                    section.section.remove();
                    sections.delete(key);
                }
            buckets.forEach((group, index) => {
                const section = sections.get(group.key) ?? createGroup(group.key);
                sections.set(group.key, section);
                section.render(group.title, index, groups.length);
                section.section.hidden =
                    !active || (!group.key && groups.length > 0 && !pins.some((pin) => !pin.homeGroupKey));
                if (list.children[index] !== section.section)
                    list.insertBefore(section.section, list.children[index] ?? null);
            });
            const keys = new Set(pins.map((pin) => pin.pinKey));
            const members = new Map<string, typeof pins>();
            for (const pin of pins) {
                const key = pin.homeGroupKey ?? '';
                const bucket = members.get(key) ?? [];
                bucket.push(pin);
                members.set(key, bucket);
            }
            for (const [key, card] of cards)
                if (!keys.has(key)) {
                    card.dispose();
                    cards.delete(key);
                }
            const targets: Parameters<typeof icons.update>[0] = [];
            for (const bucket of members.values())
                bucket.forEach((pin, index) => {
                    const content = sections.get(pin.homeGroupKey ?? '')?.content ?? sections.get('')!.content;
                    const card = cards.get(pin.pinKey) ?? createCard(pin.pinKey);
                    cards.set(pin.pinKey, card);
                    card.render(pin, index, bucket.length);
                    if (content.children[index] !== card.card) {
                        const focus = card.card.contains(document.activeElement)
                            ? (document.activeElement as HTMLElement)
                            : null;
                        content.insertBefore(card.card, content.children[index] ?? null);
                        focus?.focus({ preventScroll: true });
                    }
                    if (pin.available && pin.resource)
                        targets.push({ element: card.identity, icon: pin.resource.icon });
                });
            if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
            else if (focused && !focused.isConnected) addGroupButton.focus({ preventScroll: true });
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
            addGroup.remove();
            status.remove();
            unavailable.remove();
        }
    };
}
