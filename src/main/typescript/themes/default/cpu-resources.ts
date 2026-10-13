import type { ThemeState, ThemeApplication, ThemeLifetime } from '../../app/theme-api.js';
import type { CpuResource } from '../../app/api-types.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import { registryId } from '../../app/resource-helpers.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { createSlotGrid } from './slot-grid.js';
import { slotQuantity } from './resource-quantity.js';
import { terminalIcons, craftingHammer, craftingQueue, craftingPriorityIcon } from './icons/pixel/terminal.js';
import type { CpuTooltips } from './cpu-tooltips.js';

type ResourceSort = 'name' | 'active' | 'pending' | 'stored' | 'shareInCraftingTime';

export function createCpuResources(application: ThemeApplication, tips: CpuTooltips, themeLifetime: ThemeLifetime) {
    const terminal = element('section', '', 'cpu-terminal');
    const header = element('div', '', 'terminal-heading');
    const heading = element('h2');
    const searchLabel = element('label', '', 'search');
    const searchName = element('span', '', 'sr-only');
    const search = element('input');
    search.type = 'search';
    searchLabel.append(searchName, search);
    header.append(heading, searchLabel);
    const body = element('div', '', 'terminal-body');
    const scroll = element('div', '', 'terminal-scroll');
    scroll.role = 'region';
    scroll.tabIndex = 0;
    const grid = element('ul', '', 'resource-grid cpu-items');
    const icons = application.icons.observe(scroll, paintResourceIcon);
    const slots = createSlotGrid(grid, scroll, terminal);
    themeLifetime.onCleanup(slots.dispose);
    const empty = element('p', '', 'cpu-empty');
    scroll.append(grid);
    body.append(empty, scroll);
    terminal.append(header, body);
    const tools = element('div', '', 'cpu-tools terminal-tools');
    const filterGroup = element('div');
    filterGroup.role = 'group';
    const sortGroup = element('div');
    sortGroup.role = 'group';
    tools.append(filterGroup, sortGroup);
    let itemRows: Map<string, ReturnType<typeof createResourceRow>> = new Map();
    let hideStored = false;
    let activeFirst = false;

    let sort: ResourceSort = 'name';

    let locale: Translator;

    let state: ThemeState['cpus'];
    let selectedRoute = '';
    let updatingRows = false;

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
        tips.bindResource(row, () => updatingRows);
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
            tips.hide();
            renderResources();
        });
        tips.bindTool(button);
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

    function renderControls(hasTracking: boolean) {
        filterGroup.setAttribute('aria-label', locale.common('cpuShow'));
        sortGroup.setAttribute('aria-label', locale.common('sort'));
        for (const control of controls) {
            control.button.hidden = control.value === 'shareInCraftingTime' && !hasTracking;
            if (control.button.hidden) tips.hideTool(control.button);
            control.button.setAttribute(
                'aria-label',
                control.value === 'hideStored' || control.value === 'activeFirst'
                    ? locale.common(control.key)
                    : `${locale.common('sort')}: ${locale.common(control.key)}`
            );
            let pressed = control.value === sort;
            if (control.value === 'hideStored') pressed = hideStored;
            else if (control.value === 'activeFirst') pressed = activeFirst;
            control.button.setAttribute('aria-pressed', String(pressed));
        }
    }

    function renderResources() {
        const hasTracking = !!state.detail?.hasTrackingInfo && !state.error;
        grid.classList.toggle('cpu-items-tracked', hasTracking);
        if (!hasTracking && sort === 'shareInCraftingTime') sort = 'name';
        renderControls(hasTracking);
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
        tips.refreshResource();
    }
    search.addEventListener('input', () => {
        scroll.scrollTop = 0;
        tips.hide();
        renderResources();
    });
    scroll.addEventListener('scroll', tips.hide);

    return {
        element: terminal,
        tools,
        render(route: ThemeState['route'], next: ThemeState['cpus'], translator: Translator) {
            state = next;
            locale = translator;
            const selected = route.view === 'cpus' && !!route.cpuKey;
            terminal.hidden = !selected;
            tools.hidden = !selected;
            const routeKey = selected ? `${route.gridKey}/${route.cpuKey}` : '';
            if (routeKey !== selectedRoute) {
                selectedRoute = routeKey;
                tips.hide();
                for (const row of itemRows.values()) row.li.remove();
                itemRows.clear();
                search.value = '';
                scroll.scrollTop = 0;
            }

            if (!selected) {
                icons.update([], null);
                return;
            }
            const t = locale.common;
            const cpu = state.error ? undefined : state.cpus.find((entry) => entry.key === route.cpuKey);
            heading.replaceChildren(renderMinecraftText(cpu?.name || route.cpuKey || ''));
            heading.title = plainMinecraftText(cpu?.name || route.cpuKey || '');
            searchName.textContent = t('searchCpuResources');
            search.placeholder = t('searchHint');
            search.setAttribute('aria-description', t('searchHelp'));
            scroll.setAttribute('aria-label', t('cpuResources'));
            grid.setAttribute('aria-label', t('cpuResources'));

            renderResources();
        },
        dispose() {
            icons.dispose();
            slots.dispose();
            terminal.remove();
            tools.remove();
        }
    };
}
