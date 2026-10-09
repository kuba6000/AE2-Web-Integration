import { registryId } from './resource-metadata.js';
import type { CpuOutcome } from '../../app/cpus.js';
import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { CpuResource } from '../../app/api-types.js';
import type { Translator as Locale } from '../../app/i18n.js';
import type { createThemeContext } from '../../app/theme-context.js';
type Terminal = ReturnType<typeof createTerminal>;
type ResourceSort = 'name' | 'active' | 'pending' | 'stored' | 'shareInCraftingTime';

import { cpuHref } from '../../app/router.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { terminalIcons, craftingHammer, craftingQueue, craftingPriorityIcon } from './icons/pixel/terminal.js';
import { slotQuantity } from './resource-quantity.js';
import { createSlotGrid } from './slot-grid.js';
import { cancelIcon, pauseIcon, playIcon } from './icons/hackernoon/playback.js';
import { cpuChipIcon, cpuSearchIcon } from './icons/pixel/cpu-controls.js';

function element<Tag extends keyof HTMLElementTagNameMap>(
    tag: Tag,
    text = '',
    className = ''
): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    node.className = className;
    return node;
}

/* Presentation only: CPU requests, mutations and refresh lifetime belong to the application. */

export function createCpuView(
    root: HTMLElement,
    application: Terminal,
    { workspace, settings }: { workspace: HTMLElement; settings: ReturnType<typeof createThemeContext>['settings'] }
) {
    const lifetime = new AbortController();
    const view = element('section', '', 'cpu-view');
    view.hidden = true;
    const overview = element('div', '', 'cpu-overview');
    const overviewHeader = element('div', '', 'terminal-heading');
    const title = element('h2');
    overviewHeader.append(title);
    const overviewBody = element('div', '', 'terminal-body');
    const listStatus = element('p', '', 'cpu-list-status');
    listStatus.role = 'status';
    const listScroll = element('div', '', 'terminal-scroll');
    listScroll.role = 'region';
    listScroll.tabIndex = 0;
    const list = element('ul', '', 'cpu-list');
    listScroll.append(list);
    const overviewIcons = application.icons.observe(listScroll, paintResourceIcon);
    overviewBody.append(listStatus, listScroll);
    overview.append(overviewHeader, overviewBody);
    const overviewSize = new ResizeObserver(() => {
        const bounds = list.getBoundingClientRect();
        if (bounds.width === 0) return;
        overview.style.setProperty('--grid-inset', `${bounds.left - overview.getBoundingClientRect().left}px`);
    });
    overviewSize.observe(listScroll);
    const terminal = element('section', '', 'cpu-terminal');
    const header = element('div', '', 'terminal-heading');
    const heading = element('h2');
    const searchLabel = element('label', '', 'search');
    const searchName = element('span', '', 'sr-only');
    const search = element('input');
    search.type = 'search';
    searchLabel.append(searchName, search);
    header.append(heading, searchLabel);
    const status = element('p', '', 'cpu-status');
    status.role = 'status';
    const body = element('div', '', 'terminal-body');
    const scroll = element('div', '', 'terminal-scroll');
    scroll.role = 'region';
    scroll.tabIndex = 0;
    const grid = element('ul', '', 'resource-grid cpu-items');
    const icons = application.icons.observe(scroll, paintResourceIcon);
    const slots = createSlotGrid(grid, scroll, terminal);
    const empty = element('p', '', 'cpu-empty');
    scroll.append(grid);
    body.append(empty, scroll);
    terminal.append(header, body);
    view.append(overview, terminal);
    root.append(view);
    const tools = element('div', '', 'cpu-tools terminal-tools');
    const filterGroup = element('div');
    filterGroup.role = 'group';
    const sortGroup = element('div');
    sortGroup.role = 'group';
    tools.append(filterGroup, sortGroup);
    workspace.insertBefore(tools, root);
    const panel = element('aside');
    panel.id = 'cpu-panel';
    const selectorTitle = element('h3');
    const selectorControls = element('div', '', 'cpu-selector-controls');
    const selectorButtons = element('div', '', 'cpu-selector-buttons');
    selectorButtons.role = 'group';
    const selectorSortButton = element('button', '', 'tool-button');
    selectorSortButton.type = 'button';
    const selectorDirection = element('button', '', 'tool-button');
    selectorDirection.type = 'button';
    const selectorSearchToggle = element('button', '', 'tool-button');
    selectorSearchToggle.type = 'button';
    selectorSearchToggle.innerHTML = cpuSearchIcon;
    selectorButtons.append(selectorSortButton, selectorDirection, selectorSearchToggle);
    const selectorSearchLabel = element('label', '', 'cpu-selector-search');
    const selectorSearchName = element('span', '', 'sr-only');
    const selectorSearch = element('input');
    selectorSearch.type = 'search';
    selectorSearch.id = 'cpu-selector-search-input';
    selectorSearchToggle.setAttribute('aria-controls', selectorSearch.id);
    selectorSearchLabel.append(selectorSearchName, selectorSearch);
    selectorControls.append(selectorButtons, selectorSearchLabel);
    const selectorScroll = element('div', '', 'cpu-selector-scroll inset-frame');
    selectorScroll.role = 'region';
    selectorScroll.tabIndex = 0;
    const selectorList = element('ul', '', 'cpu-selector-list');
    const selectorStatus = element('p', '', 'cpu-selector-status');
    selectorStatus.role = 'status';
    selectorScroll.append(selectorStatus, selectorList);
    const selectorIcons = application.icons.observe(selectorScroll, paintResourceIcon);
    const panelState = element('span', '', 'sr-only');
    panelState.role = 'status';
    const work = element('section', '', 'cpu-detail-work');
    work.role = 'region';
    work.tabIndex = 0;
    const output = element('p', '', 'cpu-detail-output');
    const outputIcon = createResourceIcon();
    const timing = element('p', '', 'cpu-detail-note');
    const tracking = element('p', '', 'cpu-detail-note');
    work.append(panelState, output, timing, tracking);
    const actions = element('div');
    const footer = element('div', '', 'cpu-footer');
    footer.append(work, actions, status);
    terminal.append(footer);
    panel.append(selectorTitle, selectorControls, selectorScroll);
    workspace.append(panel);
    const outputIcons = application.icons.observe(footer, paintResourceIcon);
    const tooltip = element('div', '', 'tooltip');
    tooltip.id = 'cpu-resource-tooltip';
    tooltip.role = 'tooltip';
    tooltip.tabIndex = -1;
    tooltip.hidden = true;
    workspace.append(tooltip);
    type CpuRow = ReturnType<typeof createOverviewRow>;

    let rows: Map<string, CpuRow> = new Map();
    let selectorRows = new Map<string, ReturnType<typeof createSelectorRow>>();
    let selectorGrid = '';
    let selectedCpu = '';
    let selectorSearchOpen = false;
    const selectorCriteria = [
        { value: 'name', label: 'name', icon: terminalIcons.name },
        { value: 'capacity', label: 'cpuCapacityLabel', icon: terminalIcons.stored },
        { value: 'coprocessors', label: 'cpuCoprocessorsLabel', icon: cpuChipIcon },
        { value: 'state', label: 'cpuBusyState', icon: craftingHammer }
    ] as const;
    const savedSelectorSort = settings.get('cpuSelectorSort');
    let selectorSort =
        selectorCriteria.find((criterion) => criterion.value === savedSelectorSort) || selectorCriteria[0];
    let selectorDescending = settings.get('cpuSelectorSortOrder') === 'descending';

    let itemRows: Map<string, ReturnType<typeof createResourceRow>> = new Map();
    let hideStored = false;
    let activeFirst = false;

    let sort: ResourceSort = 'name';

    let locale: Locale;

    let state: TerminalState['cpus'];
    let selectedRoute = '';
    let updatingRows = false;

    let selectedTooltip: { row: ReturnType<typeof createResourceRow>; x: number; y: number; pointer: boolean } | null =
        null;

    let toolTooltip: HTMLElement | null = null;
    let cpuTooltip: { target: HTMLElement; key: string; pointer: boolean; x: number; y: number } | null = null;

    function hideTooltip() {
        selectedTooltip?.row.button.removeAttribute('aria-describedby');
        cpuTooltip?.target.removeAttribute('aria-describedby');
        selectedTooltip = null;
        cpuTooltip = null;
        toolTooltip = null;
        tooltip.hidden = true;
        tooltip.classList.remove('cpu-info-tooltip');
        tooltip.scrollTop = 0;
    }

    function positionTooltip(x: number, y: number) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
    }
    function refreshTooltip() {
        if (!selectedTooltip) return;
        const { row, x, y, pointer } = selectedTooltip;
        if (!row.button.isConnected || (pointer && !row.button.contains(document.elementFromPoint(x, y)))) {
            hideTooltip();
            return;
        }
        const item = row.item;
        const name = element('strong');
        name.append(renderMinecraftText(item.displayName));
        const values = Object.entries({ cpuActive: item.active, cpuPending: item.pending, cpuStored: item.stored })
            .filter(([, quantity]) => quantity > 0)
            .map(([key, quantity]) => [key, locale.number(quantity)]);
        if (state.detail?.hasTrackingInfo)
            values.push(
                ['cpuTimeSpent', locale.duration(item.timeSpentCrafting)],
                ['cpuCraftedTotal', locale.number(item.craftedTotal)],
                ['cpuRate', `${locale.number(item.craftsPerSec)}/s`],
                ['cpuElapsedShare', `${locale.number(item.shareInCraftingTimeCombined * 100)}%`],
                ['cpuProcessingShare', `${locale.number(item.shareInCraftingTime * 100)}%`]
            );
        tooltip.replaceChildren(
            name,
            ...(registryId(item) ? [element('code', registryId(item))] : []),
            ...values.map(([key, value]) => element('span', `${locale.common(key)}: ${value}`))
        );
        tooltip.hidden = false;
        const box = row.button.getBoundingClientRect();
        positionTooltip(pointer ? x : box.left, pointer ? y : box.bottom);
    }

    function showResourceTooltip(row: ReturnType<typeof createResourceRow>, x: number, y: number, pointer: boolean) {
        hideTooltip();
        selectedTooltip = { row, x, y, pointer };
        row.button.setAttribute('aria-describedby', tooltip.id);
        refreshTooltip();
    }

    function showToolTooltip(button: HTMLElement) {
        hideTooltip();
        toolTooltip = button;
        tooltip.replaceChildren(
            ...[button.getAttribute('aria-label'), button.getAttribute('aria-description')]
                .filter((text): text is string => !!text)
                .map((text) => element('span', text))
        );
        tooltip.hidden = false;
        const box = button.getBoundingClientRect();
        positionTooltip(box.right, box.top);
    }

    for (const button of [selectorSortButton, selectorDirection, selectorSearchToggle]) {
        button.addEventListener('pointerenter', () => showToolTooltip(button));
        button.addEventListener('focus', () => showToolTooltip(button));
        for (const event of ['pointerleave', 'blur'])
            button.addEventListener(event, () => {
                if (toolTooltip === button) hideTooltip();
            });
    }
    selectorSortButton.addEventListener('click', () => {
        selectorSort = selectorCriteria[(selectorCriteria.indexOf(selectorSort) + 1) % selectorCriteria.length];
        settings.set('cpuSelectorSort', selectorSort.value);
        selectorScroll.scrollTop = 0;
        renderSelector();
    });
    selectorDirection.addEventListener('click', () => {
        selectorDescending = !selectorDescending;
        settings.set('cpuSelectorSortOrder', selectorDescending ? 'descending' : 'ascending');
        selectorScroll.scrollTop = 0;
        renderSelector();
    });
    function setSelectorSearch(open: boolean) {
        selectorSearchOpen = open;
        if (!open) selectorSearch.value = '';
        selectorScroll.scrollTop = 0;
        hideTooltip();
        renderSelector();
        if (open) selectorSearch.focus();
    }
    selectorSearchToggle.addEventListener('click', () => setSelectorSearch(!selectorSearchOpen));
    selectorSearch.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            event.stopPropagation();
            setSelectorSearch(false);
            selectorSearchToggle.focus();
            hideTooltip();
        }
    });

    function refreshCpuTooltip() {
        if (!cpuTooltip) return;
        const { target, key, pointer, x, y } = cpuTooltip;
        const cpu = state.cpus.find((entry) => entry.key === key);
        if (
            !cpu ||
            !target.isConnected ||
            target.hidden ||
            (pointer &&
                document.activeElement !== tooltip &&
                !tooltip.matches(':hover') &&
                !target.contains(document.elementFromPoint(x, y)))
        ) {
            hideTooltip();
            return;
        }
        const current = key === selectedCpu && state.detail && !state.error ? state.detail : cpu;
        const t = locale.common;
        const name = element('strong');
        name.append(renderMinecraftText(plainMinecraftText(cpu.name).trim() ? cpu.name : cpu.key));
        const values = [
            element('span', t(current.isBusy ? (current.isPaused ? 'cpuPausedState' : 'cpuBusy') : 'cpuIdle')),
            element('span', `${t('cpuCapacityLabel')}: ${locale.bytes(cpu.availableStorage)}`),
            element(
                'span',
                `${t('cpuUsedStorageLabel')}: ${cpu.usedStorage >= 0 ? locale.bytes(cpu.usedStorage) : t('cpuValueUnavailable')}`
            ),
            element('span', `${t('cpuCoprocessorsLabel')}: ${locale.number(cpu.coProcessors)}`)
        ];
        if (current.isBusy) {
            const product = element('span');
            if (current.finalOutput)
                product.append(
                    renderMinecraftText(current.finalOutput.displayName),
                    ` × ${locale.number(current.finalOutput.quantity)}`
                );
            else product.textContent = t('cpuOutputUnknown');
            values.push(product);
            if (current.hasTrackingInfo) {
                if ('timeElapsed' in current)
                    values.push(element('span', `${t('cpuElapsedLabel')}: ${locale.duration(current.timeElapsed)}`));
                values.push(element('span', `${t('historyStartedLabel')}: ${locale.dateTime(current.timeStarted)}`));
            } else values.push(element('span', t('cpuTrackingUnavailable')));
        }
        const readingPosition = tooltip.scrollTop;
        tooltip.replaceChildren(name, element('code', cpu.key), ...values);
        tooltip.classList.add('cpu-info-tooltip');
        tooltip.hidden = false;
        tooltip.scrollTop = readingPosition;
        const box = target.getBoundingClientRect();
        positionTooltip(pointer ? x : box.left, pointer ? y : box.bottom);
    }

    function bindCpuTooltip(target: HTMLElement, key: () => string) {
        const show = (pointer = false, x = 0, y = 0) => {
            hideTooltip();
            cpuTooltip = { target, key: key(), pointer, x, y };
            target.setAttribute('aria-describedby', tooltip.id);
            refreshCpuTooltip();
        };
        target.addEventListener('pointerenter', (event) => {
            if (event.pointerType !== 'touch') show(true, event.clientX, event.clientY);
        });
        target.addEventListener('pointermove', (event) => {
            if (cpuTooltip?.target === target && cpuTooltip.pointer) {
                cpuTooltip.x = event.clientX;
                cpuTooltip.y = event.clientY;
                positionTooltip(event.clientX, event.clientY);
            }
        });
        target.addEventListener('focus', () => {
            if (!updatingRows) show();
        });
        target.addEventListener('click', () => show());
        target.addEventListener('pointerleave', (event) => {
            if (
                cpuTooltip?.target === target &&
                event.relatedTarget !== tooltip &&
                !(event.relatedTarget instanceof Node && tooltip.contains(event.relatedTarget)) &&
                document.activeElement !== tooltip
            )
                hideTooltip();
        });
        target.addEventListener('blur', (event) => {
            if (!updatingRows && cpuTooltip?.target === target && event.relatedTarget !== tooltip) hideTooltip();
        });
        target.addEventListener('keydown', (event) => {
            if (
                event.key === 'Tab' &&
                !event.shiftKey &&
                cpuTooltip?.target === target &&
                tooltip.scrollHeight > tooltip.clientHeight
            ) {
                event.preventDefault();
                cpuTooltip.pointer = false;
                tooltip.focus({ preventScroll: true });
            }
        });
    }
    bindCpuTooltip(work, () => selectedCpu);
    tooltip.addEventListener('blur', (event) => {
        if (cpuTooltip && event.relatedTarget !== cpuTooltip.target) hideTooltip();
    });
    tooltip.addEventListener('pointerleave', (event) => {
        if (
            cpuTooltip &&
            event.relatedTarget !== cpuTooltip.target &&
            !(event.relatedTarget instanceof Node && cpuTooltip.target.contains(event.relatedTarget)) &&
            document.activeElement !== tooltip
        )
            hideTooltip();
    });
    tooltip.addEventListener('keydown', (event) => {
        if (event.key === 'Tab' && event.shiftKey && cpuTooltip) {
            event.preventDefault();
            cpuTooltip.target.focus({ preventScroll: true });
        }
    });

    function createResourceRow(item: CpuResource) {
        const li = element('li');
        const button = element('button', '', 'item cpu-item slot-frame');
        button.type = 'button';
        const name = element('strong');
        const amounts = element('span', '', 'cpu-item-amounts');
        const counts = ['cpuActive', 'cpuPending', 'cpuStored'].map((key) => {
            const amount = element('span', '', 'cpu-amount');
            const label = element('span');
            const quantity = element('span', '', 'quantity');
            amount.append(label, quantity);
            amounts.append(amount);
            return { key, amount, label, quantity };
        });
        const share = element('span', '', 'cpu-amount');
        const shareLabel = element('span');
        const shareValue = element('span', '', 'quantity');
        share.append(shareLabel, shareValue);
        amounts.append(share);
        button.append(createResourceIcon(), name, amounts);
        li.append(button);
        const row = { item, li, button, name, counts, share, shareLabel, shareValue };
        button.addEventListener('pointerenter', (event) => {
            if (event.pointerType !== 'touch') showResourceTooltip(row, event.clientX, event.clientY, true);
        });
        button.addEventListener('pointermove', (event) => {
            if (selectedTooltip?.row === row && selectedTooltip.pointer) {
                selectedTooltip.x = event.clientX;
                selectedTooltip.y = event.clientY;
                positionTooltip(event.clientX, event.clientY);
            }
        });
        button.addEventListener('pointerleave', () => {
            if (selectedTooltip?.row === row && selectedTooltip.pointer) hideTooltip();
        });
        const showFocused = () => {
            const box = button.getBoundingClientRect();
            showResourceTooltip(row, box.left, box.bottom, false);
        };
        button.addEventListener('focus', () => {
            if (!updatingRows) showFocused();
        });
        button.addEventListener('click', showFocused);
        button.addEventListener('blur', () => {
            if (!updatingRows && selectedTooltip?.row === row) hideTooltip();
        });
        return row;
    }

    const controls: Array<{
        button: HTMLButtonElement;
        value: ResourceSort | 'hideStored' | 'activeFirst';
        key: string;
    }> = [];

    function addControl(
        value: ResourceSort | 'hideStored' | 'activeFirst',
        key: string,
        group: 'filter' | 'sort',
        icon: string
    ) {
        const button = element('button', '', 'tool-button');
        button.type = 'button';
        button.innerHTML = icon;
        button.addEventListener('click', () => {
            if (value === 'hideStored') hideStored = !hideStored;
            else if (value === 'activeFirst') activeFirst = !activeFirst;
            else sort = value;
            scroll.scrollTop = 0;
            hideTooltip();
            renderResources();
        });
        button.addEventListener('pointerenter', () => showToolTooltip(button));
        button.addEventListener('focus', () => showToolTooltip(button));
        button.addEventListener('pointerleave', () => {
            if (toolTooltip === button) hideTooltip();
        });
        button.addEventListener('blur', () => {
            if (toolTooltip === button) hideTooltip();
        });
        (group === 'filter' ? filterGroup : sortGroup).append(button);
        controls.push({ button, value, key });
    }
    addControl('hideStored', 'cpuHideStored', 'filter', terminalIcons.stored);
    addControl('activeFirst', 'cpuActiveFirst', 'sort', craftingPriorityIcon);
    addControl('name', 'name', 'sort', terminalIcons.name);
    addControl('active', 'cpuSortActive', 'sort', craftingHammer);
    addControl('pending', 'cpuSortPending', 'sort', craftingQueue);
    addControl('stored', 'cpuSortStored', 'sort', terminalIcons.quantity);
    addControl('shareInCraftingTime', 'cpuProcessingShare', 'sort', '<span aria-hidden="true">%</span>');

    function craftingPriority(item: CpuResource) {
        return item.active > 0 ? 0 : item.pending > 0 ? 1 : 2;
    }

    function renderResources() {
        const hasTracking = !!state.detail?.hasTrackingInfo && !state.error;
        grid.classList.toggle('cpu-items-tracked', hasTracking);
        if (!hasTracking && sort === 'shareInCraftingTime') sort = 'name';
        filterGroup.setAttribute('aria-label', locale.common('cpuShow'));
        sortGroup.setAttribute('aria-label', locale.common('sort'));
        for (const control of controls) {
            control.button.hidden = control.value === 'shareInCraftingTime' && !hasTracking;
            if (control.button.hidden && toolTooltip === control.button) hideTooltip();
            control.button.setAttribute(
                'aria-label',
                control.value === 'hideStored' || control.value === 'activeFirst'
                    ? locale.common(control.key)
                    : `${locale.common('sort')}: ${locale.common(control.key)}`
            );
            control.button.setAttribute(
                'aria-pressed',
                String(
                    control.value === 'hideStored'
                        ? hideStored
                        : control.value === 'activeFirst'
                          ? activeFirst
                          : control.value === sort
                )
            );
        }
        const terms = search.value.trim().toLocaleLowerCase(document.documentElement.lang).split(/\s+/);
        const language = document.documentElement.lang;
        const focused = [...itemRows.values()].find((row) => row.button === document.activeElement);

        const occurrences: Map<string, number> = new Map();
        // Unsupported identities retain repeated rows by occurrence; never merge by registry ID.
        const items = (state.detail?.isBusy && !state.error ? state.detail.items || [] : []).map((item) => {
            const identity = item.itemKey || JSON.stringify([registryId(item), item.displayName]);
            const occurrence = occurrences.get(identity) || 0;
            occurrences.set(identity, occurrence + 1);
            return { item, key: `${identity}:${occurrence}`, name: plainMinecraftText(item.displayName) };
        });
        const visible = items
            .filter(({ item, name }) => {
                const text = `${name} ${registryId(item)}`.toLocaleLowerCase(language);
                const mod = (item.registryNamespace ?? '').toLocaleLowerCase(language);
                return (
                    (!hideStored || item.active > 0 || item.pending > 0) &&
                    terms.every((term) => (term.startsWith('@') ? mod.includes(term.slice(1)) : text.includes(term)))
                );
            })
            .sort((a, b) => {
                return (
                    (activeFirst ? craftingPriority(a.item) - craftingPriority(b.item) : 0) ||
                    (sort === 'name' ? 0 : b.item[sort] - a.item[sort]) ||
                    a.name.localeCompare(b.name, language)
                );
            });

        const current: Map<string, ReturnType<typeof createResourceRow>> = new Map();
        updatingRows = true;
        for (const [index, { item, key }] of visible.entries()) {
            const row = itemRows.get(key) || createResourceRow(item);
            row.item = item;
            row.name.replaceChildren(renderMinecraftText(item.displayName));
            row.button.dataset.crafting = item.active > 0 ? 'active' : item.pending > 0 ? 'pending' : 'stored';
            const quantities = [item.active, item.pending, item.stored];
            row.counts.forEach((count, i) => {
                count.amount.hidden = quantities[i] <= 0;
                count.label.textContent = locale.common(count.key);
                count.quantity.textContent = slotQuantity(quantities[i], locale);
            });
            row.share.hidden = !hasTracking;
            const share = hasTracking ? `${locale.number(Math.round(item.shareInCraftingTime * 1000) / 10)}%` : '';
            row.shareLabel.textContent = locale.common('cpuTimeShare');
            row.shareValue.textContent = share;
            row.button.setAttribute(
                'aria-label',
                [
                    plainMinecraftText(item.displayName),
                    ...row.counts.flatMap((count, i) =>
                        quantities[i] > 0 ? [`${locale.common(count.key)}: ${locale.number(quantities[i])}`] : []
                    ),
                    ...(hasTracking ? [`${locale.common('cpuProcessingShare')}: ${share}`] : [])
                ].join(' · ')
            );
            if (grid.children[index] !== row.li) grid.insertBefore(row.li, grid.children[index] || null);
            current.set(key, row);
        }
        for (const [key, row] of itemRows) if (!current.has(key)) row.li.remove();
        itemRows = current;
        slots.update(itemRows.size);
        icons.update(
            [...itemRows.values()].map((row) => ({ element: row.button, icon: row.item.icon })),
            state.icons
        );
        if (focused?.button.isConnected && document.activeElement !== focused.button)
            focused.button.focus({ preventScroll: true });
        updatingRows = false;
        empty.textContent =
            state.detail?.isBusy && !state.error && !items.length ? locale.common('cpuNoResources') : '';
        empty.hidden = !empty.textContent;
        refreshTooltip();
    }
    search.addEventListener('input', () => {
        scroll.scrollTop = 0;
        hideTooltip();
        renderResources();
    });
    scroll.addEventListener('scroll', hideTooltip);
    document.addEventListener(
        'keydown',
        (event) => {
            if (event.key === 'Escape') {
                if (document.activeElement === tooltip) cpuTooltip?.target.focus({ preventScroll: true });
                hideTooltip();
            }
        },
        { signal: lifetime.signal }
    );
    document.addEventListener(
        'pointerdown',
        (event) => {
            if (selectedTooltip && event.target instanceof Node && !selectedTooltip.row.button.contains(event.target))
                hideTooltip();
            if (
                cpuTooltip &&
                event.target instanceof Node &&
                !cpuTooltip.target.contains(event.target) &&
                !tooltip.contains(event.target)
            )
                hideTooltip();
        },
        { signal: lifetime.signal }
    );
    window.addEventListener('resize', hideTooltip, { signal: lifetime.signal });

    const outcomeText = (outcome: CpuOutcome | undefined) =>
        outcome?.uncertain
            ? locale.common('cpuMutationUncertain')
            : outcome?.notice
              ? outcome.notice === 'cpuCancelled' && state.status !== 'ready'
                  ? ''
                  : locale.common(outcome.notice)
              : '';

    function renderActions(
        target: HTMLDivElement,
        key: string,
        cpu: TerminalState['cpus']['detail'] | TerminalState['cpus']['cpus'][number] | null,
        overviewIdentity = ''
    ) {
        target.className = 'cpu-actions';
        if (!target.firstChild) {
            const pause = element('button');
            pause.type = 'button';
            const cancel = element('button', '', 'cpu-cancel');
            cancel.type = 'button';
            target.append(pause, cancel);
        }
        const [pause, cancel] = [...target.children] as HTMLButtonElement[];
        const outcome = state.outcomes[key];
        const disabled = !!outcome?.mutation || !!outcome?.uncertain || state.status !== 'ready';
        pause.hidden = !cpu?.isBusy || !cpu.supportsPause;
        const pauseLabel = locale.common(cpu?.isPaused ? 'resumeCpuWork' : 'pauseCpuWork');
        const cancelLabel = locale.common('cancelCpuWork');
        pause.innerHTML = cpu?.isPaused ? playIcon : pauseIcon;
        pause.append(element('span', locale.common(cpu?.isPaused ? 'resumeCpuShort' : 'pauseCpuShort')));
        pause.disabled = disabled;
        pause.onclick = () => application.cpus.pause(key, !cpu?.isPaused);
        cancel.hidden = !cpu?.isBusy;
        cancel.innerHTML = cancelIcon;
        cancel.append(element('span', locale.common('cancelCpuShort')));
        cancel.disabled = disabled;
        cancel.onclick = () => {
            if (window.confirm(locale.common('confirmCancelCpu', { cpu: overviewIdentity || key })))
                application.cpus.cancel(key);
        };
        for (const [button, description] of [
            [pause, pauseLabel],
            [cancel, cancelLabel]
        ] as const) {
            if (overviewIdentity) button.setAttribute('aria-label', `${description} · ${overviewIdentity}`);
            else button.setAttribute('aria-label', description);
        }
    }

    function renderCpuState(target: HTMLElement, busy: boolean, paused: boolean) {
        target.innerHTML = busy && paused ? pauseIcon : '';
        target.append(element('span', locale.common(busy ? (paused ? 'cpuPausedState' : 'cpuBusy') : 'cpuIdle')));
    }

    function createOverviewRow() {
        const li = element('li', '', 'inset-frame cpu-card');
        const header = element('div', '', 'cpu-card-heading');
        const heading = element('h3');
        const link = element('a', '', 'cpu-card-link');
        const badge = element('span', '', 'cpu-card-state');
        header.append(heading, badge);
        const work = element('div', '', 'cpu-card-output');
        const outputLabel = element('p', '', 'cpu-card-label');
        const output = element('p', '', 'cpu-card-output-value');
        const icon = createResourceIcon();
        work.append(outputLabel, output);
        const metrics = element('dl', '', 'cpu-card-metrics');
        const fields = ['cpuCapacityLabel', 'cpuUsedStorageLabel', 'cpuCoprocessorsLabel'].map((label) => {
            const group = element('div');
            const term = element('dt');
            const value = element('dd');
            group.append(term, value);
            metrics.append(group);
            return { label, term, value };
        });
        const identity = element('details', '', 'cpu-card-identity');
        const identityLabel = element('summary');
        const key = element('code');
        identity.append(identityLabel, key);
        const actions = element('div');
        const notice = element('p', '', 'cpu-card-notice');
        notice.role = 'status';
        link.append(header, work, metrics);
        li.append(link, identity, actions, notice);
        return { li, link, heading, badge, outputLabel, output, icon, fields, identityLabel, key, actions, notice };
    }

    function renderOverviewRow(entry: CpuRow, cpu: TerminalState['cpus']['cpus'][number], gridKey: string) {
        const t = locale.common;
        const displayName = plainMinecraftText(cpu.name).trim() ? cpu.name : t('cpuUnnamed');
        const identity = `${plainMinecraftText(displayName)} · ${cpu.key}`;
        entry.link.href = cpuHref(gridKey, cpu.key);
        entry.heading.replaceChildren(renderMinecraftText(displayName));
        entry.link.setAttribute('aria-label', identity);
        entry.link.title = cpu.key;
        entry.li.dataset.state = cpu.isBusy ? (cpu.isPaused ? 'paused' : 'busy') : 'idle';
        renderCpuState(entry.badge, cpu.isBusy, cpu.isPaused);
        entry.outputLabel.textContent = t('cpuOutput');
        entry.output.replaceChildren();
        if (cpu.isBusy && cpu.finalOutput) {
            const name = element('strong');
            name.append(renderMinecraftText(cpu.finalOutput.displayName));
            entry.output.append(
                entry.icon,
                name,
                element('span', `× ${locale.number(cpu.finalOutput.quantity)}`, 'cpu-card-quantity')
            );
        } else {
            entry.output.textContent = t(cpu.isBusy ? 'cpuOutputUnknown' : 'cpuIdleMessage');
        }
        const values = [
            locale.bytes(cpu.availableStorage),
            cpu.usedStorage >= 0 ? locale.bytes(cpu.usedStorage) : t('cpuValueUnavailable'),
            locale.number(cpu.coProcessors)
        ];
        entry.fields.forEach((field, index) => {
            field.term.textContent = t(field.label);
            field.value.textContent = values[index];
        });
        entry.identityLabel.textContent = t('cpuIdentifier');
        entry.key.textContent = cpu.key;
        renderActions(entry.actions, cpu.key, cpu, identity);
        entry.notice.textContent = outcomeText(state.outcomes[cpu.key]);
        entry.notice.hidden = !entry.notice.textContent;
    }

    function createSelectorRow(key: string) {
        const li = element('li');
        const link = element('a', '', 'cpu-selector-link');
        const name = element('span', '', 'cpu-selector-name');
        const badge = element('span', '', 'cpu-card-state');
        const icon = createResourceIcon();
        link.append(icon, name, badge);
        bindCpuTooltip(link, () => key);
        li.append(link);
        return { li, link, name, badge, icon };
    }

    function renderSelector() {
        const t = locale.common;
        selectorButtons.setAttribute('aria-label', t('cpuSelector'));
        selectorSearchLabel.hidden = !selectorSearchOpen;
        selectorSearchToggle.setAttribute('aria-expanded', String(selectorSearchOpen));
        selectorSearchToggle.setAttribute('aria-label', t(selectorSearchOpen ? 'closeCpuSearch' : 'searchCpus'));
        if (selectorSearchOpen) selectorSearchToggle.setAttribute('aria-description', t('clearCpuSearch'));
        else selectorSearchToggle.removeAttribute('aria-description');
        selectorSortButton.innerHTML = selectorSort.icon;
        selectorSortButton.setAttribute('aria-label', `${t('cpuSelectorSort')}: ${t(selectorSort.label)}`);
        selectorSortButton.setAttribute(
            'aria-description',
            locale.t('nextState', {
                state: t(selectorCriteria[(selectorCriteria.indexOf(selectorSort) + 1) % selectorCriteria.length].label)
            })
        );
        selectorDirection.innerHTML = terminalIcons[selectorDescending ? 'descending' : 'ascending'];
        selectorDirection.setAttribute(
            'aria-label',
            `${t('cpuSelectorDirection')}: ${t(selectorDescending ? 'descending' : 'ascending')}`
        );
        if (selectorSort.value === 'state')
            selectorDirection.setAttribute('aria-description', t(selectorDescending ? 'cpuBusyFirst' : 'cpuIdleFirst'));
        else selectorDirection.removeAttribute('aria-description');
        if (toolTooltip && selectorButtons.contains(toolTooltip)) showToolTooltip(toolTooltip);
        const terms = selectorSearch.value.trim().toLocaleLowerCase(document.documentElement.lang).split(/\s+/);
        const visible = state.cpus
            .filter((cpu) => {
                const text = `${plainMinecraftText(cpu.name)} ${cpu.key}`.toLocaleLowerCase(
                    document.documentElement.lang
                );
                return terms.every((term) => text.includes(term));
            })
            .sort((a, b) => {
                const name = (plainMinecraftText(a.name).trim() || a.key).localeCompare(
                    plainMinecraftText(b.name).trim() || b.key,
                    document.documentElement.lang
                );
                const primary =
                    selectorSort.value === 'capacity'
                        ? a.availableStorage - b.availableStorage
                        : selectorSort.value === 'coprocessors'
                          ? a.coProcessors - b.coProcessors
                          : selectorSort.value === 'state'
                            ? Number(a.isBusy) - Number(b.isBusy)
                            : name;
                return (
                    (selectorDescending ? -primary : primary) || name || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0)
                );
            });
        const current = new Map<string, ReturnType<typeof createSelectorRow>>();
        const scrollTop = selectorScroll.scrollTop;
        const focused = [...selectorRows.values()].find((row) => row.link === document.activeElement);
        updatingRows = true;
        for (const [index, cpu] of visible.entries()) {
            const row = selectorRows.get(cpu.key) || createSelectorRow(cpu.key);
            const displayName = plainMinecraftText(cpu.name).trim() ? cpu.name : cpu.key;
            row.link.href = cpuHref(selectorGrid, cpu.key);
            row.link.setAttribute(
                'aria-label',
                `${plainMinecraftText(displayName)} · ${cpu.key} · ${t(cpu.isBusy ? (cpu.isPaused ? 'cpuPausedState' : 'cpuBusy') : 'cpuIdle')} · ${locale.bytes(cpu.availableStorage)}`
            );
            if (cpu.key === selectedCpu) row.link.setAttribute('aria-current', 'page');
            else row.link.removeAttribute('aria-current');
            row.name.replaceChildren(renderMinecraftText(displayName));
            row.icon.hidden = !cpu.isBusy || !cpu.finalOutput;
            renderCpuState(row.badge, cpu.isBusy, cpu.isPaused);
            row.badge.append(element('span', `· ${locale.bytes(cpu.availableStorage)}`));
            if (selectorList.children[index] !== row.li)
                selectorList.insertBefore(row.li, selectorList.children[index] || null);
            current.set(cpu.key, row);
        }
        for (const [key, row] of selectorRows) if (!current.has(key)) row.li.remove();
        selectorRows = current;
        if (focused?.link.isConnected && document.activeElement !== focused.link)
            focused.link.focus({ preventScroll: true });
        updatingRows = false;
        selectorStatus.textContent = visible.length
            ? ''
            : state.cpus.length
              ? t('noMatchingCpus')
              : state.status === 'loading'
                ? t('loading')
                : t('noCpus');
        selectorStatus.hidden = !selectorStatus.textContent;
        selectorScroll.scrollTop = scrollTop;
        selectorIcons.update(
            visible
                .filter((cpu) => cpu.isBusy && cpu.finalOutput)
                .map((cpu) => ({ element: current.get(cpu.key)!.link, icon: cpu.icon })),
            state.overviewIcons
        );
    }
    selectorSearch.addEventListener('input', () => {
        hideTooltip();
        selectorScroll.scrollTop = 0;
        renderSelector();
    });
    selectorScroll.addEventListener('scroll', hideTooltip);

    return {
        render(route: TerminalState['route'], nextState: TerminalState['cpus'], nextLocale: Locale) {
            state = nextState;
            locale = nextLocale;
            const selected = route.view === 'cpus' && !!route.cpuKey;
            const nextGrid = route.view === 'cpus' ? route.gridKey : '';
            if (selectorGrid !== nextGrid) {
                selectorGrid = nextGrid;
                selectorSearchOpen = false;
                selectorSearch.value = '';
                selectorScroll.scrollTop = 0;
            }
            selectedCpu = selected ? route.cpuKey || '' : '';
            view.hidden = route.view !== 'cpus';
            overview.hidden = selected;
            terminal.hidden = !selected;
            tools.hidden = !selected;
            panel.hidden = !selected;
            const routeKey = selected ? `${route.gridKey}/${route.cpuKey}` : '';
            if (routeKey !== selectedRoute) {
                selectedRoute = routeKey;
                hideTooltip();
                for (const row of itemRows.values()) row.li.remove();
                itemRows.clear();
                search.value = '';
                scroll.scrollTop = 0;
            }
            if (!selected) {
                icons.update([], null);
                outputIcons.update([], null);
                selectorIcons.update([], null);
                selectorList.replaceChildren();
                selectorRows.clear();
            }
            if (route.view !== 'cpus' || selected) overviewIcons.update([], null);
            if (route.view !== 'cpus') return;
            const t = locale.common;
            selectorTitle.textContent = t('cpus');
            selectorSearchName.textContent = t('searchCpus');
            selectorSearch.placeholder = t('searchCpus');
            selectorScroll.setAttribute('aria-label', t('cpuSelector'));
            work.setAttribute('aria-label', t('cpuCurrentJob'));
            title.textContent = t('cpus');
            listScroll.setAttribute('aria-label', t('cpus'));
            listStatus.textContent = state.error
                ? t(state.error)
                : state.cpus.length
                  ? ''
                  : state.status === 'loading'
                    ? t('loading')
                    : t('noCpus');
            listStatus.hidden = !listStatus.textContent;
            if (!selected) {
                const current: Map<string, CpuRow> = new Map();
                for (const cpu of state.cpus) {
                    const entry = rows.get(cpu.key) || createOverviewRow();
                    renderOverviewRow(entry, cpu, route.gridKey);
                    if (!entry.li.parentNode) list.append(entry.li);
                    current.set(cpu.key, entry);
                }
                for (const [key, entry] of rows) if (!current.has(key)) entry.li.remove();
                rows = current;
                overviewIcons.update(
                    state.cpus
                        .filter((cpu) => cpu.isBusy && cpu.finalOutput)
                        .map((cpu) => ({ element: current.get(cpu.key)!.output, icon: cpu.icon })),
                    state.overviewIcons
                );
                return;
            }
            // Hidden list rows must not retain private data after a failed selected-CPU read.
            list.replaceChildren();
            rows.clear();
            renderSelector();
            const cpu = state.error ? undefined : state.cpus.find((entry) => entry.key === route.cpuKey);
            const detail = state.error ? null : state.detail;
            const selectedOutcome = state.outcomes[route.cpuKey || ''];
            heading.replaceChildren(renderMinecraftText(cpu?.name || route.cpuKey || ''));
            heading.title = plainMinecraftText(cpu?.name || route.cpuKey || '');
            searchName.textContent = t('searchCpuResources');
            search.placeholder = t('searchHint');
            search.setAttribute('aria-description', t('searchHelp'));
            scroll.setAttribute('aria-label', t('cpuResources'));
            grid.setAttribute('aria-label', t('cpuResources'));
            status.textContent = selectedOutcome?.uncertain
                ? outcomeText(selectedOutcome)
                : state.error
                  ? t(state.error)
                  : '';
            const selectedNotice = selectedOutcome?.notice ? outcomeText(selectedOutcome) : '';
            if (selectedNotice && selectedOutcome?.notice !== state.error) status.textContent += ` ${selectedNotice}`;
            if (selectedOutcome?.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
            status.hidden = !status.textContent;
            panel.setAttribute('aria-label', t('cpus'));
            panelState.hidden = !detail;
            if (detail) renderCpuState(panelState, detail.isBusy, detail.isPaused);
            else panelState.replaceChildren();
            work.hidden = !detail;
            output.replaceChildren();
            if (detail?.isBusy) {
                if (detail.finalOutput) {
                    const product = element('span', '', 'cpu-job-name');
                    product.append(renderMinecraftText(detail.finalOutput.displayName));
                    output.append(
                        outputIcon,
                        product,
                        element('span', `× ${locale.number(detail.finalOutput.quantity)}`, 'cpu-detail-quantity')
                    );
                } else output.textContent = t('cpuOutputUnknown');
            } else if (detail) output.textContent = t('cpuIdleMessage');
            timing.textContent =
                detail?.isBusy && detail.hasTrackingInfo
                    ? `${t('cpuElapsedLabel')}: ${locale.duration(detail.timeElapsed)}`
                    : '';
            timing.hidden = !timing.textContent;
            tracking.textContent = detail?.isBusy && !detail.hasTrackingInfo ? t('cpuTrackingUnavailable') : '';
            tracking.hidden = !tracking.textContent;
            outputIcons.update(
                detail?.isBusy && detail.finalOutput ? [{ element: output, icon: detail.icon }] : [],
                state.icons
            );
            renderActions(actions, route.cpuKey || '', detail);
            renderResources();
            refreshCpuTooltip();
        },
        dispose() {
            lifetime.abort();
            overviewSize.disconnect();
            overviewIcons.dispose();
            selectorIcons.dispose();
            outputIcons.dispose();
            icons.dispose();
            slots.dispose();
            hideTooltip();
            view.remove();
            tools.remove();
            panel.remove();
            tooltip.remove();
        }
    };
}
