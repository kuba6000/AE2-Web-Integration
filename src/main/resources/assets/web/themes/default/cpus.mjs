/**
 * @typedef {ReturnType<typeof import('../../app/terminal.mjs').createTerminal>} Terminal
 * @typedef {Terminal['state']} TerminalState
 * @typedef {import('../../app/i18n.mjs').Translator} Locale
 * @typedef {import('../../app/api-types.mjs').CpuItem} CpuItem
 * @typedef {'name' | 'active' | 'pending' | 'stored' | 'shareInCraftingTime'} ResourceSort
 */

import { cpuHref } from '../../app/router.mjs';
import { renderMinecraftText } from './minecraft-text.mjs';
import { plainMinecraftText } from '../../app/minecraft-text.mjs';
import { terminalIcons, craftingHammer, craftingQueue } from './icons/ae2/terminal.mjs';
import { slotQuantity } from './resource-quantity.mjs';

/** @template {keyof HTMLElementTagNameMap} Tag
 * @param {Tag} tag @param {string} [text] @param {string} [className]
 * @returns {HTMLElementTagNameMap[Tag]} */
function element(tag, text = '', className = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    node.className = className;
    return node;
}

/** Presentation only: CPU requests, mutations and refresh lifetime belong to the application.
 * @param {HTMLElement} root @param {Terminal} application
 * @param {{workspace: HTMLElement}} options */
export function createCpuView(root, application, { workspace }) {
    const lifetime = new AbortController();
    const view = element('section', '', 'cpu-view');
    view.hidden = true;
    const overview = element('div', '', 'cpu-overview');
    const title = element('h2');
    const listStatus = element('p');
    listStatus.role = 'status';
    const list = element('ul', '', 'cpu-list');
    const resources = element('a');
    overview.append(title, listStatus, list, resources);
    const terminal = element('section', '', 'cpu-terminal');
    const header = element('div', '', 'terminal-heading');
    const heading = element('h2');
    const back = element('a', '', 'cpu-back');
    header.append(heading, back);
    const searchLabel = element('label', '', 'search');
    const searchName = element('span', '', 'sr-only');
    const search = element('input');
    search.type = 'search';
    searchLabel.append(searchName, search);
    const status = element('p', '', 'cpu-status');
    status.role = 'status';
    const body = element('div', '', 'terminal-body');
    const scroll = element('div', '', 'terminal-scroll');
    scroll.role = 'region';
    scroll.tabIndex = 0;
    const grid = element('ul', '', 'resource-grid cpu-items');
    const empty = element('p', '', 'cpu-empty');
    scroll.append(grid);
    body.append(scroll);
    terminal.append(header, searchLabel, status, empty, body);
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
    const panelTitle = element('h3');
    const panelName = element('h4');
    const summary = element('div', '', 'cpu-summary');
    const output = element('p');
    const timing = element('p');
    const actions = element('div');
    panel.append(panelTitle, panelName, summary, output, timing, actions);
    workspace.append(panel);
    const tooltip = element('div', '', 'tooltip');
    tooltip.id = 'cpu-resource-tooltip';
    tooltip.role = 'tooltip';
    tooltip.hidden = true;
    workspace.append(tooltip);
    /** @typedef {{li: HTMLLIElement, link: HTMLAnchorElement, summary: HTMLParagraphElement, output: HTMLParagraphElement, actions: HTMLDivElement, notice: HTMLParagraphElement}} CpuRow */
    /** @type {Map<string, CpuRow>} */
    let rows = new Map();
    /** @type {Map<string, ReturnType<typeof createResourceRow>>} */
    let itemRows = new Map();
    let hideStored = false;
    let activeFirst = false;
    /** @type {ResourceSort} */
    let sort = 'name';
    /** @type {Locale} */
    let locale;
    /** @type {TerminalState['cpus']} */
    let state;
    let selectedRoute = '';
    let updatingRows = false;
    /** @type {{row: ReturnType<typeof createResourceRow>, x: number, y: number, pointer: boolean} | null} */
    let selectedTooltip = null;
    /** @type {HTMLButtonElement | null} */
    let toolTooltip = null;

    function hideTooltip() {
        selectedTooltip?.row.button.removeAttribute('aria-describedby');
        selectedTooltip = null;
        toolTooltip = null;
        tooltip.hidden = true;
    }
    /** @param {number} x @param {number} y */
    function positionTooltip(x, y) {
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
        name.append(renderMinecraftText(item.itemname));
        const values = Object.entries({ cpuActive: item.active, cpuPending: item.pending, cpuStored: item.stored })
            .filter(([, quantity]) => quantity > 0)
            .map(([key, quantity]) => [key, locale.number(quantity)]);
        if (state.detail?.hasTrackingInfo)
            values.push(
                ['cpuTimeSpent', locale.common('seconds', { count: item.timeSpentCrafting / 1000 })],
                ['cpuCraftedTotal', locale.number(item.craftedTotal)],
                ['cpuRate', `${locale.number(item.craftsPerSec)}/s`],
                ['cpuElapsedShare', `${locale.number(item.shareInCraftingTimeCombined * 100)}%`],
                ['cpuProcessingShare', `${locale.number(item.shareInCraftingTime * 100)}%`]
            );
        tooltip.replaceChildren(
            name,
            element('code', item.itemid),
            ...values.map(([key, value]) => element('span', `${locale.common(key)}: ${value}`))
        );
        tooltip.hidden = false;
        const box = row.button.getBoundingClientRect();
        positionTooltip(pointer ? x : box.left, pointer ? y : box.bottom);
    }
    /** @param {ReturnType<typeof createResourceRow>} row @param {number} x @param {number} y @param {boolean} pointer */
    function showResourceTooltip(row, x, y, pointer) {
        hideTooltip();
        selectedTooltip = { row, x, y, pointer };
        row.button.setAttribute('aria-describedby', tooltip.id);
        refreshTooltip();
    }
    /** @param {HTMLButtonElement} button */
    function showToolTooltip(button) {
        hideTooltip();
        toolTooltip = button;
        tooltip.textContent = button.getAttribute('aria-label');
        tooltip.hidden = false;
        const box = button.getBoundingClientRect();
        positionTooltip(box.right, box.top);
    }
    /** @param {CpuItem} item */
    function createResourceRow(item) {
        const li = element('li');
        const button = element('button', '', 'item cpu-item');
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
        button.append(name, amounts);
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
    /** @type {Array<{button: HTMLButtonElement, value: ResourceSort | 'hideStored' | 'activeFirst', key: string}>} */
    const controls = [];
    /** @param {ResourceSort | 'hideStored' | 'activeFirst'} value @param {string} key @param {'filter' | 'sort'} group @param {string} icon */
    function addControl(value, key, group, icon) {
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
    addControl('activeFirst', 'cpuActiveFirst', 'sort', terminalIcons.craftable);
    addControl('name', 'name', 'sort', terminalIcons.name);
    addControl('active', 'cpuSortActive', 'sort', craftingHammer);
    addControl('pending', 'cpuSortPending', 'sort', craftingQueue);
    addControl('stored', 'cpuSortStored', 'sort', terminalIcons.quantity);
    addControl('shareInCraftingTime', 'cpuProcessingShare', 'sort', '<span aria-hidden="true">%</span>');

    /** @param {CpuItem} item */
    function craftingPriority(item) {
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
        /** @type {Map<string, number>} */
        const occurrences = new Map();
        // The CPU API does not expose variant keys. Retain repeated rows by occurrence; never merge by registry ID.
        const items = (state.detail?.isBusy && !state.error ? state.detail.items || [] : []).map((item) => {
            const identity = JSON.stringify([item.itemid, item.itemname]);
            const occurrence = occurrences.get(identity) || 0;
            occurrences.set(identity, occurrence + 1);
            return { item, key: `${identity}:${occurrence}`, name: plainMinecraftText(item.itemname) };
        });
        const visible = items
            .filter(({ item, name }) => {
                const text = `${name} ${item.itemid}`.toLocaleLowerCase(language);
                const mod = item.itemid.split(':')[0].toLocaleLowerCase(language);
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
        /** @type {Map<string, ReturnType<typeof createResourceRow>>} */
        const current = new Map();
        updatingRows = true;
        for (const [index, { item, key }] of visible.entries()) {
            const row = itemRows.get(key) || createResourceRow(item);
            row.item = item;
            row.name.replaceChildren(renderMinecraftText(item.itemname));
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
                    plainMinecraftText(item.itemname),
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
            if (event.key === 'Escape') hideTooltip();
        },
        { signal: lifetime.signal }
    );
    document.addEventListener(
        'pointerdown',
        (event) => {
            if (selectedTooltip && event.target instanceof Node && !selectedTooltip.row.button.contains(event.target))
                hideTooltip();
        },
        { signal: lifetime.signal }
    );
    window.addEventListener('resize', hideTooltip, { signal: lifetime.signal });
    const slotBackground = new ResizeObserver(([entry]) => {
        if (!entry || entry.contentRect.width === 0) return;
        const columns = getComputedStyle(grid).gridTemplateColumns.split(' ').length;
        grid.style.setProperty('--slot-width', `${entry.contentRect.width / columns}px`);
        terminal.style.setProperty(
            '--grid-inset',
            `${grid.getBoundingClientRect().left - terminal.getBoundingClientRect().left}px`
        );
    });
    slotBackground.observe(grid);

    /** @param {import('../../app/cpus.mjs').CpuOutcome | undefined} outcome */
    const outcomeText = (outcome) =>
        outcome?.uncertain
            ? locale.common('cpuMutationUncertain')
            : outcome?.mutation
              ? locale.common(outcome.mutation === 'cancel' ? 'cpuCancelling' : 'cpuUpdating')
              : outcome?.notice
                ? locale.common(outcome.notice)
                : '';
    /** @param {HTMLDivElement} target @param {string} key @param {TerminalState['cpus']['detail'] | TerminalState['cpus']['cpus'][number] | null} cpu @param {string} [label] */
    function renderActions(target, key, cpu, label = '') {
        target.className = 'cpu-actions';
        if (!target.firstChild) {
            const pause = element('button');
            pause.type = 'button';
            const cancel = element('button');
            cancel.type = 'button';
            target.append(pause, cancel);
        }
        const [pause, cancel] = /** @type {HTMLButtonElement[]} */ ([...target.children]);
        const outcome = state.outcomes[key];
        const disabled = !!outcome?.mutation || !!outcome?.uncertain || state.status !== 'ready';
        pause.hidden = !cpu?.isBusy || !cpu.supportsPause;
        pause.textContent = locale.common(cpu?.isPaused ? 'resumeCpuWork' : 'pauseCpuWork');
        pause.disabled = disabled;
        pause.onclick = () => application.cpus.pause(key, !cpu?.isPaused);
        cancel.hidden = !cpu?.isBusy;
        cancel.textContent = locale.common('cancelCpuWork');
        cancel.disabled = disabled;
        cancel.onclick = () => {
            if (window.confirm(locale.common('confirmCancelCpu', { cpu: label || key }))) application.cpus.cancel(key);
        };
        for (const button of [pause, cancel]) {
            if (label) button.setAttribute('aria-label', `${button.textContent} · ${label}`);
            else button.removeAttribute('aria-label');
        }
    }
    /** @param {TerminalState['cpus']['cpus'][number]} cpu */
    function summaryText(cpu) {
        const t = locale.common;
        return `${t(cpu.isBusy ? (cpu.isPaused ? 'cpuPaused' : 'cpuBusy') : 'cpuIdle')} · ${t('cpuCapacity', { count: cpu.availableStorage })} · ${t('coprocessors', { count: cpu.coProcessors })} · ${cpu.usedStorage >= 0 ? t('cpuUsedStorage', { count: cpu.usedStorage }) : t('cpuStorageUnknown')}`;
    }
    /** @param {HTMLParagraphElement} target @param {TerminalState['cpus']['detail'] | TerminalState['cpus']['cpus'][number]} cpu */
    function renderOutput(target, cpu) {
        target.replaceChildren();
        if (!cpu?.isBusy) return;
        if (cpu.finalOutput)
            target.append(
                `${locale.common('cpuOutput')}: `,
                renderMinecraftText(cpu.finalOutput.itemname),
                ` × ${locale.number(cpu.finalOutput.quantity)}`
            );
        else target.append(locale.common('cpuOutputUnknown'));
    }
    return {
        /** @param {TerminalState['route']} route @param {TerminalState['cpus']} nextState @param {Locale} nextLocale */
        render(route, nextState, nextLocale) {
            state = nextState;
            locale = nextLocale;
            const selected = route.view === 'cpus' && !!route.cpuKey;
            view.hidden = route.view !== 'cpus';
            view.classList.toggle('cpu-detail-active', selected);
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
            if (route.view !== 'cpus') return;
            const t = locale.common;
            title.textContent = t('cpus');
            resources.textContent = t('backResources');
            resources.href = `#/grids/${encodeURIComponent(route.gridKey)}/items`;
            listStatus.textContent = state.error
                ? t(state.error)
                : state.status === 'loading'
                  ? t('loading')
                  : t(state.cpus.length ? 'selectCpuWork' : 'noCpus');
            if (!selected) {
                /** @type {Map<string, CpuRow>} */
                const current = new Map();
                for (const cpu of state.cpus) {
                    const entry = rows.get(cpu.key) || {
                        li: element('li'),
                        link: element('a'),
                        summary: element('p'),
                        output: element('p'),
                        actions: element('div'),
                        notice: element('p')
                    };
                    if (!entry.link.parentNode) {
                        entry.li.append(entry.link, entry.summary, entry.output, entry.actions, entry.notice);
                        entry.notice.role = 'status';
                    }
                    entry.link.href = cpuHref(route.gridKey, cpu.key);
                    entry.link.replaceChildren(renderMinecraftText(cpu.name), ` · ${cpu.key}`);
                    entry.summary.textContent = summaryText(cpu);
                    renderActions(entry.actions, cpu.key, cpu, `${plainMinecraftText(cpu.name)} · ${cpu.key}`);
                    entry.notice.textContent = outcomeText(state.outcomes[cpu.key]);
                    renderOutput(entry.output, cpu);
                    if (!entry.li.parentNode) list.append(entry.li);
                    current.set(cpu.key, entry);
                }
                for (const [key, entry] of rows) if (!current.has(key)) entry.li.remove();
                rows = current;
                return;
            }
            // Hidden list rows must not retain private data after a failed selected-CPU read.
            list.replaceChildren();
            rows.clear();
            const cpu = state.error ? undefined : state.cpus.find((entry) => entry.key === route.cpuKey);
            const detail = state.error ? null : state.detail;
            const selectedOutcome = state.outcomes[route.cpuKey || ''];
            heading.replaceChildren(renderMinecraftText(cpu?.name || route.cpuKey || ''));
            back.textContent = t('backCpus');
            back.href = cpuHref(route.gridKey);
            searchName.textContent = t('searchCpuResources');
            search.placeholder = t('searchHint');
            search.setAttribute('aria-description', t('searchHelp'));
            scroll.setAttribute('aria-label', t('cpuResources'));
            grid.setAttribute('aria-label', t('cpuResources'));
            status.textContent =
                selectedOutcome?.uncertain || selectedOutcome?.mutation
                    ? outcomeText(selectedOutcome)
                    : state.error
                      ? t(state.error)
                      : state.status === 'loading'
                        ? t('loading')
                        : detail
                          ? t(detail.isBusy ? (detail.isPaused ? 'cpuPaused' : 'cpuWorking') : 'cpuIdleMessage')
                          : '';
            if (selectedOutcome?.notice && selectedOutcome.notice !== state.error)
                status.textContent += ` ${t(selectedOutcome.notice)}`;
            if (selectedOutcome?.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
            status.hidden = !status.textContent;
            panel.setAttribute('aria-label', t('cpuDetails'));
            panelTitle.textContent = t('cpuDetails');
            panelName.replaceChildren(renderMinecraftText(cpu?.name || route.cpuKey || ''));
            summary.textContent = cpu ? summaryText(cpu) : '';
            output.replaceChildren();
            if (detail) renderOutput(output, detail);
            timing.textContent = detail?.isBusy
                ? detail.hasTrackingInfo
                    ? `${t('cpuStarted', { time: locale.dateTime(detail.timeStarted) })} · ${t('cpuElapsed', { count: detail.timeElapsed / 1000 })}`
                    : t('cpuTrackingUnavailable')
                : '';
            renderActions(actions, route.cpuKey || '', detail);
            renderResources();
        },
        dispose() {
            lifetime.abort();
            slotBackground.disconnect();
            hideTooltip();
            view.remove();
            tools.remove();
            panel.remove();
            tooltip.remove();
        }
    };
}
